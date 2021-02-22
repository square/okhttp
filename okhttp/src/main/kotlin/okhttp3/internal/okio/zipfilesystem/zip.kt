/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.okio.zipfilesystem

import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import okio.ExperimentalFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.io.EOFException
import java.io.IOException

internal const val LOCSIG: Long = 0x4034b50
internal const val EXTSIG: Long = 0x8074b50
internal const val CENSIG: Long = 0x2014b50
internal const val ENDSIG: Long = 0x6054b50
internal const val ENDHDR = 22
internal const val EXTSIZ = 8
internal const val CENVER = 6
internal const val CENSIZ = 20
internal const val CENLEN = 24
internal const val CENEXT = 30
internal const val CENATT = 36
internal const val ENDTOT = 10
internal const val ENDSIZ = 12
internal const val ENDCOM = 20

/**
 * General Purpose Bit Flags, Bit 0.
 * If set, indicates that the file is encrypted.
 */
internal const val GPBF_ENCRYPTED_FLAG = 1 shl 0

/**
 * Supported General Purpose Bit Flags Mask.
 * Bit mask of bits not supported.
 * Note: The only bit that we will enforce at this time
 * is the encrypted bit. Although other bits are not supported,
 * we must not enforce them as this could break some legitimate
 * use cases (See http://b/8617715).
 */
internal const val GPBF_UNSUPPORTED_MASK = GPBF_ENCRYPTED_FLAG

/**
 * Open zip file for reading.
 */
internal const val OPEN_READ = 1

/**
 * Delete zip file when closed.
 */
internal const val OPEN_DELETE = 4

/**
 * The maximum supported entry / archive size for standard (non zip64) entries and archives.
 */
internal const val MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE = 0x00000000ffffffffL

/**
 * The header ID of the zip64 extended info header. This value is used to identify
 * zip64 data in the "extra" field in the file headers.
 */
private const val ZIP64_EXTENDED_INFO_HEADER_ID: Short = 0x0001

/**
 * Size (in bytes) of the zip64 end of central directory locator. This will be located
 * immediately before the end of central directory record if a given zipfile is in the
 * zip64 format.
 */
private const val ZIP64_LOCATOR_SIZE = 20

/**
 * The zip64 end of central directory locator signature (4 bytes wide).
 */
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50

/**
 * The zip64 end of central directory record singature (4 bytes wide).
 */
private const val ZIP64_EOCD_RECORD_SIGNATURE = 0x06064b50

/**
 * Constructs a new `ZipFile` allowing access to the given file.
 *
 * UTF-8 is used to decode all comments and entry names in the file.
 *
 * Find the central directory and read the contents.
 *
 * The central directory can be followed by a variable-length comment
 * field, so we have to scan through it backwards.  The comment is at
 * most 64K, plus we have 18 bytes for the end-of-central-dir stuff
 * itself, plus apparently sometimes people throw random junk on the end
 * just for the fun of it.
 *
 * This is all a little wobbly.  If the wrong value ends up in the EOCD
 * area, we're hosed. This appears to be the way that everybody handles
 * it though, so we're in good company if this fails.
 */
@Throws(IOException::class)
@ExperimentalFileSystem
fun open(zipPath: Path, fileSystem: FileSystem): ZipFileSystem {
  // Scan back, looking for the End Of Central Directory field. If the zip file doesn't
  // have an overall comment (unrelated to any per-entry comments), we'll hit the EOCD
  // on the first try.
  // No need to synchronize raf here -- we only do this when we first open the zip file.
  val source = fileSystem.source(zipPath).buffer()
  val cursor = source.cursor()!!
  var scanOffset = cursor.size() - ENDHDR

  if (scanOffset < 0) {
    throw IOException("File too short to be a zip file: " + cursor.size())
  }
  val headerMagic = source.readIntLe()
  if (headerMagic.toLong() == ENDSIG) {
    throw IOException("Empty zip archive not supported")
  }
  if (headerMagic.toLong() != LOCSIG) {
    throw IOException("Not a zip archive")
  }

  var stopOffset = scanOffset - 65536
  if (stopOffset < 0) {
    stopOffset = 0
  }

  val eocdOffset: Long
  while (true) {
    cursor.seek(scanOffset)
    if (source.readIntLe().toLong() == ENDSIG) {
      eocdOffset = scanOffset
      break
    }

    scanOffset--
    if (scanOffset < stopOffset) {
      throw IOException("End Of Central Directory signature not found")
    }
  }

  val zip64EocdRecordOffset = source.readZip64EocdRecordLocator(eocdOffset)

  // Seek back past the eocd signature so that we can continue with our search. Note that we add 4
  // bytes to the offset to skip past the signature.
  cursor.seek(eocdOffset + 4)
  var record = source.readEocdRecord(
    isZip64 = zip64EocdRecordOffset != -1L
  )
  val comment = source.readUtf8(record.commentLength.toLong())

  // We have a zip64 eocd record; use that for getting the information we need.
  if (zip64EocdRecordOffset != -1L) {
    cursor.seek(zip64EocdRecordOffset)
    record = source.readZip64EocdRecord(record.commentLength)
  }

  // Seek to the first CDE and read all entries.
  // We have to do this now (from the constructor) rather than lazily because the
  // public API doesn't allow us to throw IOException except from the constructor
  // or from getInputStream.
  cursor.seek(record.centralDirOffset)
  val entries = mutableMapOf<Path, ZipEntry>()
  for (i in 0 until record.numEntries) {
    val newEntry = source.readEntry(
      isZip64 = zip64EocdRecordOffset != -1L
    )
    if (newEntry.localHeaderRelOffset >= record.centralDirOffset) {
      throw IOException("Local file header offset is after central directory")
    }
    val entryName = newEntry.name
    val path = "/".toPath().div(entryName)
    if (entries.put(path, newEntry) != null) {
      throw IOException("Duplicate path: $entryName")
    }
  }

  return ZipFileSystem(zipPath, fileSystem, entries, comment)
}

@Throws(IOException::class)
private fun BufferedSource.readEocdRecord(isZip64: Boolean): EocdRecord {
  val numEntries: Long
  val centralDirOffset: Long

  if (isZip64) {
    numEntries = -1
    centralDirOffset = -1

    // If we have a zip64 end of central directory record, we skip through the regular
    // end of central directory record and use the information from the zip64 eocd record.
    // We're still forced to read the comment length (below) since it isn't present in the
    // zip64 eocd record.
    skip(16)
  } else {
    // If we don't have a zip64 eocd record, we read values from the "regular" eocd record.
    val diskNumber: Int = readShortLe().toInt() and 0xffff
    val diskWithCentralDir: Int = readShortLe().toInt() and 0xffff
    numEntries = (readShortLe().toInt() and 0xffff).toLong()
    val totalNumEntries: Int = readShortLe().toInt() and 0xffff
    skip(4) // Ignore centralDirSize.
    centralDirOffset = readIntLe().toLong() and 0xffffffffL
    if (numEntries != totalNumEntries.toLong() || diskNumber != 0 || diskWithCentralDir != 0) {
      throw IOException("Spanned archives not supported")
    }
  }

  val commentLength = readShortLe().toInt() and 0xffff
  return EocdRecord(numEntries, centralDirOffset, commentLength)
}

/**
 * On exit, [this@readEntry] will be positioned at the start of the next entry in the Central Directory.
 */
@Throws(IOException::class)
internal fun BufferedSource.readEntry(isZip64: Boolean): ZipEntry {
  val sig = readIntLe()
  if (sig.toLong() != CENSIG) {
    throwZipException("Central Directory Entry", sig)
  }
  skip(4)
  val gpbf = readShortLe().toInt() and 0xffff
  if (gpbf and GPBF_UNSUPPORTED_MASK != 0) {
    throw IOException("Invalid General Purpose Bit Flag: $gpbf")
  }

  val compressionMethod = readShortLe().toInt() and 0xffff
  val time = readShortLe().toInt() and 0xffff
  val modDate = readShortLe().toInt() and 0xffff

  // These are 32-bit values in the file, but 64-bit fields in this object.
  val crc = readIntLe().toLong() and 0xffffffffL
  val compressedSize = readIntLe().toLong() and 0xffffffffL
  val size = readIntLe().toLong() and 0xffffffffL
  val nameLength = readShortLe().toInt() and 0xffff
  val extraLength = readShortLe().toInt() and 0xffff
  val commentByteCount = readShortLe().toInt() and 0xffff

  // This is a 32-bit value in the file, but a 64-bit field in this object.
  skip(8)
  val localHeaderRelOffset = readIntLe().toLong() and 0xffffffffL
  val name = readUtf8(nameLength.toLong())
  for (element in name) {
    if (element == '\u0000') throw IOException("Filename contains NUL byte")
  }

  val extra = readByteString(extraLength.toLong())
  val comment = readUtf8(commentByteCount.toLong())

  val zipEntry = ZipEntry(
    name = name,
    comment = comment,
    crc = crc,
    compressedSize = compressedSize,
    size = size,
    compressionMethod = compressionMethod,
    time = time,
    modDate = modDate,
    extra = extra,
    localHeaderRelOffset = localHeaderRelOffset
  )

  return when {
    isZip64 -> readZip64ExtendedInfo(zipEntry) ?: zipEntry
    else -> zipEntry
  }
}

/**
 * Parses the zip64 end of central directory record locator. The locator
 * must be placed immediately before the end of central directory (eocd) record
 * starting at `eocdOffset`.
 *
 * The position of the file cursor for `raf` after a call to this method
 * is undefined an callers must reposition it after each call to this method.
 */
@Throws(IOException::class)
internal fun BufferedSource.readZip64EocdRecordLocator(eocdOffset: Long): Long {
  // The spec stays curiously silent about whether a zip file with an EOCD record, a zip64 locator
  // and a zip64 eocd record is considered "empty". In our implementation, we parse all records
  // and read the counts from them instead of drawing any size or layout based information.
  if (eocdOffset <= ZIP64_LOCATOR_SIZE) return -1L

  val cursor = cursor()!!
  cursor.seek(eocdOffset - ZIP64_LOCATOR_SIZE)
  if (readIntLe() != ZIP64_LOCATOR_SIGNATURE) return -1L

  val diskWithCentralDir = readIntLe()
  val result = readLongLe()
  val numDisks = readIntLe()
  if (numDisks != 1 || diskWithCentralDir != 0) {
    throw IOException("Spanned archives not supported")
  }
  return result
}

@Throws(IOException::class)
internal fun BufferedSource.readZip64EocdRecord(commentLength: Int): EocdRecord {
  val signature = readIntLe()
  if (signature != ZIP64_EOCD_RECORD_SIGNATURE) {
    throw IOException("Invalid zip64 eocd record offset")
  }

  // The zip64 eocd record specifies its own size as an 8 byte integral type. It is variable
  // length because of the "zip64 extensible data sector" but that field is reserved for
  // pkware's proprietary use. We therefore disregard it altogether and treat the end of
  // central directory structure as fixed length.
  //
  // We also skip "version made by" (2 bytes) and "version needed to extract" (2 bytes)
  // fields. We perform additional validation at the ZipEntry level, where applicable.
  //
  // That's a total of 12 bytes to skip
  skip(12)
  val diskNumber = readIntLe()
  val diskWithCentralDirStart = readIntLe()
  val numEntries = readLongLe()
  val totalNumEntries = readLongLe()
  readLong() // Ignore the size of the central directory
  val centralDirOffset = readLong()
  if (numEntries != totalNumEntries || diskNumber != 0 || diskWithCentralDirStart != 0) {
    throw IOException("spanned archives not supported")
  }

  return EocdRecord(numEntries, centralDirOffset, commentLength)
}

/**
 * Parse the zip64 extended info record from the extras present in [zipEntry].
 *
 * We assume we're parsing a central directory record. A central directory entry is required to be
 * complete.
 */
@Throws(IOException::class)
internal fun readZip64ExtendedInfo(zipEntry: ZipEntry): ZipEntry? {
  var size = zipEntry.size
  var compressedSize = zipEntry.compressedSize
  var localHeaderRelOffset = zipEntry.localHeaderRelOffset
  var extra = zipEntry.extra

  var extendedInfoSize = -1
  var extendedInfoStart = -1

  // If this file contains a zip64 central directory locator, entries might
  // optionally contain a zip64 extended information extra entry.
  if (extra.size > 0) {
    // Extensible data fields are of the form header1+data1 + header2+data2 and so
    // on, where each header consists of a 2 byte header ID followed by a 2 byte size.
    // We need to iterate through the entire list of headers to find the header ID
    // for the zip64 extended information extra field (0x0001).
    val source = Buffer().write(extra)
    extendedInfoSize = source.readZip64ExtendedInfoSize()
    if (extendedInfoSize != -1) {
      extendedInfoStart = (extra.size - source.size).toInt()
      try {
        // The size & compressed size only make sense in the central directory *or* if
        // we know them beforehand. If we don't know them beforehand, they're stored in
        // the data descriptor and should be read from there.
        //
        // Note that the spec says that the local file header "MUST" contain the
        // original and compressed size fields. We don't care too much about that.
        // The spec claims that the order of fields is fixed anyway.
        if (size == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) {
          size = source.readLong()
        }
        if (compressedSize == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) {
          compressedSize = source.readLong()
        }

        // The local header offset is significant only in the central directory. It makes no
        // sense within the local header itself.
        if (localHeaderRelOffset == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) {
          localHeaderRelOffset = source.readLong()
        }
      } catch (e: EOFException) {
        val zipException = IOException("Error parsing extended info")
        zipException.initCause(e)
        throw zipException
      }
    }
  }

  // This entry doesn't contain a zip64 extended information data entry header.
  // We have to check that the compressedSize / size / localHeaderRelOffset values
  // are valid and don't require the presence of the extended header.
  if (extendedInfoSize == -1) {
    if (compressedSize == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE ||
      size == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE ||
      localHeaderRelOffset == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE
    ) {
      throw IOException("File contains no zip64 extended information")
    }
    return null
  }

  // If we're parsed the zip64 extended info header, we remove it from the extras
  // so that applications that set their own extras will see the data they set.

  // This is an unfortunate workaround needed due to a gap in the spec. The spec demands
  // that extras are present in the "extensible" format, which means that each extra field
  // must be prefixed with a header ID and a length. However, earlier versions of the spec
  // made no mention of this, nor did any existing API enforce it. This means users could
  // set "free form" extras without caring very much whether the implementation wanted to
  // extend or add to them.

  // The start of the extended info header.
  val extendedInfoHeaderStart = extendedInfoStart - 4
  // The total size of the extended info, including the header.
  val extendedInfoTotalSize = extendedInfoSize + 4
  val extrasLen = extra.size - extendedInfoTotalSize
  val extrasWithoutZip64 = ByteArray(extrasLen)
  System.arraycopy(extra, 0, extrasWithoutZip64, 0, extendedInfoHeaderStart)
  System.arraycopy(
    extra,
    extendedInfoHeaderStart + extendedInfoTotalSize,
    extrasWithoutZip64,
    extendedInfoHeaderStart,
    extrasLen - extendedInfoHeaderStart
  )
  extra = extrasWithoutZip64.toByteString()

  return ZipEntry(
    name = zipEntry.name,
    comment = zipEntry.comment,
    crc = zipEntry.crc,
    compressedSize = compressedSize,
    size = size,
    compressionMethod = zipEntry.compressionMethod,
    time = zipEntry.time,
    modDate = zipEntry.modDate,
    extra = extra,
    localHeaderRelOffset = localHeaderRelOffset
  )
}

/**
 * Returns the size of the extended info record if `extras` contains a zip64 extended info
 * record, `-1` otherwise. The buffer will be positioned at the start of the extended info
 * record.
 */
private fun Buffer.readZip64ExtendedInfoSize(): Int {
  try {
    while (!exhausted()) {
      val headerId = readShortLe().toInt() and 0xffff
      val length = readShortLe().toInt() and 0xffff
      if (headerId == ZIP64_EXTENDED_INFO_HEADER_ID.toInt()) {
        return when {
          size >= length -> length
          else -> -1
        }
      } else {
        skip(length.toLong())
      }
    }
    return -1
  } catch (_: EOFException) {
    return -1 // Incomplete header or an invalid length.
  }
}

@Throws(IOException::class)
internal fun throwZipException(msg: String, magic: Int) {
  throw IOException(String.format("%s signature not found; was %08x$msg", magic))
}

class EocdRecord(
  val numEntries: Long,
  val centralDirOffset: Long,
  val commentLength: Int
)
