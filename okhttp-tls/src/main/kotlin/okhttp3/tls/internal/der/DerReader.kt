/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls.internal.der

import java.math.BigInteger
import java.net.ProtocolException
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ForwardingSource
import okio.IOException
import okio.Source
import okio.buffer

/**
 * Streaming decoder of data encoded following Abstract Syntax Notation One (ASN.1). There are
 * multiple variants of ASN.1, including:
 *
 *  * DER: Distinguished Encoding Rules. This further constrains ASN.1 for deterministic encoding.
 *  * BER: Basic Encoding Rules.
 *
 * This class was implemented according to the [X.690 spec][[x690]], and under the advice of
 * [Lets Encrypt's ASN.1 and DER][asn1_and_der] guide.
 *
 * [x690]: https://www.itu.int/rec/T-REC-X.690
 * [asn1_and_der]: https://letsencrypt.org/docs/a-warm-welcome-to-asn1-and-der/
 */
internal class DerReader(source: Source) {
  private val countingSource: CountingSource = CountingSource(source)
  private val source: BufferedSource = countingSource.buffer()

  /** Total bytes read thus far. */
  private val byteCount: Long
    get() = countingSource.bytesRead - source.buffer.size

  /** How many bytes to read before [peekHeader] should return false, or -1L for no limit. */
  var limit: Long = -1L

  val peekedTagClass: Int
    get() = peekedHeader?.tagClass ?: error("peekedTagClass only accessible after hasNext")

  val peekedTag: Long
    get() = peekedHeader?.tag ?: error("peekedTag only accessible after hasNext")

  private var constructed: Boolean = false

  private var peekedHeader: DerHeader? = null

  private val bytesLeft: Long
    get() = if (limit == -1L) -1L else limit - byteCount

  fun hasNext(): Boolean {
    if (peekedHeader == null) {
      peekedHeader = peekHeader()
    }
    return peekedHeader != null
  }

  internal inline fun <T> read(block: (DerHeader) -> T): T {
    if (!hasNext()) throw IOException("expected a value")

    val header = peekedHeader!!
    peekedHeader = null

    val pushedLimit = limit
    val pushedConstructed = constructed

    limit = if (header.length != -1L) byteCount + header.length else -1L
    constructed = header.constructed
    try {
      return block(header)
    } finally {
      limit = pushedLimit
      constructed = pushedConstructed
    }
  }

  fun <T> read(derAdapter: DerAdapter<T>): T {
    return read { header ->
      derAdapter.decode(this@DerReader, header)
    }
  }

  private inline fun readAll(block: (DerHeader) -> Unit) {
    while (hasNext()) {
      read(block)
    }
  }

  /**
   * Returns the next header to process unless this scope is exhausted.
   *
   * This returns null if:
   *
   *  * The stream is exhausted.
   *  * We've read all of the bytes of an object whose length is known.
   *  * We've reached the [DerHeader.TAG_END_OF_CONTENTS] of an object whose length is unknown.
   */
  private fun peekHeader(): DerHeader? {
    // We've hit a local limit.
    if (byteCount == limit) return null

    // We've exhausted the source stream.
    if (limit == -1L && source.exhausted()) return null

    // Read the tag.
    val tag: Long
    val tagAndClass = source.readByte().toInt() and 0xff
    val tagClass = tagAndClass and 0b1100_0000
    val constructed = (tagAndClass and 0b0010_0000) == 0b0010_0000
    val tag0 = tagAndClass and 0b0001_1111
    if (tag0 == 0b0001_1111) {
      var tagBits = 0L
      while (true) {
        val tagN = source.readByte().toInt() and 0xff
        tagBits += (tagN and 0b0111_1111)
        if (tagN and 0b1000_0000 == 0b1000_0000) break
        tagBits = tagBits shl 7
      }
      tag = tagBits
    } else {
      tag = tag0.toLong()
    }

    // Read the length.
    val length: Long
    val length0 = source.readByte().toInt() and 0xff
    if (length0 == 0b1000_0000) {
      // Indefinite length.
      length = -1L
    } else if (length0 and 0b1000_0000 == 0b1000_0000) {
      // Length specified over multiple bytes.
      val lengthBytes = length0 and 0b0111_1111
      var lengthBits = source.readByte().toLong() and 0xff
      for (i in 1 until lengthBytes) {
        lengthBits = lengthBits shl 8
        lengthBits += source.readByte()
            .toInt() and 0xff
      }
      length = lengthBits
    } else {
      // Length is 127 or fewer bytes.
      length = (length0 and 0b0111_1111).toLong()
    }

    // This tag indicates the end of the current scope.
    if (tagClass == DerHeader.TAG_CLASS_UNIVERSAL && tag == DerHeader.TAG_END_OF_CONTENTS) return null

    return DerHeader(tagClass, tag, constructed, length)
  }

  fun readBoolean(): Boolean {
    if (bytesLeft != 1L) throw ProtocolException("unexpected length: $bytesLeft")
    return source.readByte().toInt() != 0
  }

  fun readBigInteger(): BigInteger {
    if (bytesLeft == 0L) throw ProtocolException("unexpected length: $bytesLeft")
    val byteArray = source.readByteArray(bytesLeft)
    return BigInteger(byteArray)
  }

  fun readLong(): Long {
    if (bytesLeft !in 1..8) throw ProtocolException("unexpected length: $bytesLeft")

    var result = source.readByte().toLong() // No "and 0xff" because this is a signed value.
    while (byteCount < limit) {
      result = result shl 8
      result += source.readByte().toInt() and 0xff
    }
    return result
  }

  fun readBitString(): BitString {
    val buffer = Buffer()
    val unusedBitCount = readBitString(buffer)
    return BitString(buffer.readByteString(), unusedBitCount)
  }

  private fun readBitString(sink: Buffer): Int {
    if (bytesLeft != -1L) {
      val unusedBitCount = source.readByte().toInt() and 0xff
      source.read(sink, bytesLeft)
      return unusedBitCount
    } else {
      var unusedBitCount = 0
      readAll {
        unusedBitCount = readBitString(sink)
      }
      return unusedBitCount
    }
  }

  fun readOctetString(): ByteString {
    val buffer = Buffer()
    readOctetString(buffer)
    return buffer.readByteString()
  }

  private fun readOctetString(sink: Buffer) {
    if (bytesLeft != -1L && !constructed) {
      source.read(sink, bytesLeft)
    } else {
      readAll {
        readOctetString(sink)
      }
    }
  }

  fun readUtf8String(): String {
    val buffer = Buffer()
    readUtf8String(buffer)
    return buffer.readUtf8()
  }

  private fun readUtf8String(sink: Buffer) {
    if (bytesLeft != -1L && !constructed) {
      source.read(sink, bytesLeft)
    } else {
      readAll {
        readUtf8String(sink)
      }
    }
  }

  fun readObjectIdentifier(): String {
    val result = Buffer()
    val dot = '.'.toByte().toInt()
    when (val xy = readSubidentifier()) {
      in 0L until 40L -> {
        result.writeDecimalLong(0)
        result.writeByte(dot)
        result.writeDecimalLong(xy)
      }
      in 40L until 80L -> {
        result.writeDecimalLong(1)
        result.writeByte(dot)
        result.writeDecimalLong(xy - 40L)
      }
      else -> {
        result.writeDecimalLong(2)
        result.writeByte(dot)
        result.writeDecimalLong(xy - 80L)
      }
    }
    while (byteCount < limit) {
      result.writeByte(dot)
      result.writeDecimalLong(readSubidentifier())
    }
    return result.readUtf8()
  }

  fun readRelativeObjectIdentifier(): String {
    val result = Buffer()
    val dot = '.'.toByte().toInt()
    while (byteCount < limit) {
      if (result.size > 0) {
        result.writeByte(dot)
      }
      result.writeDecimalLong(readSubidentifier())
    }
    return result.readUtf8()
  }

  private fun readSubidentifier(): Long {
    var result = 0L
    while (true) {
      val byteN = source.readByte().toLong() and 0xff
      if (byteN and 0b1000_0000L == 0b1000_0000L) {
        result = (result + (byteN and 0b0111_1111)) shl 7
      } else {
        return result + byteN
      }
    }
  }

  /** A source that keeps track of how many bytes it's consumed. */
  private class CountingSource(source: Source) : ForwardingSource(source) {
    var bytesRead = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
      val result = delegate.read(sink, byteCount)
      if (result == -1L) return -1L
      bytesRead += result
      return result
    }
  }
}
