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
  private var limit: Long = -1L

  /** Type hints scoped to the call stack, manipulated with [pushTypeHint] and [popTypeHint]. */
  private val typeHintStack = mutableListOf<Any?>()

  /**
   * The type hint for the current object. Used to pick adapters based on other fields, such as
   * in extensions which have different types depending on their extension ID.
   */
  var typeHint: Any?
    get() = typeHintStack.lastOrNull()
    set(value) {
      typeHintStack.set(typeHintStack.size - 1, value)
    }

  /** Names leading to the current location in the ASN.1 document. */
  private val path = mutableListOf<String>()

  private var constructed: Boolean = false

  private var peekedHeader: DerHeader? = null

  private val bytesLeft: Long
    get() = if (limit == -1L) -1L else limit - byteCount

  fun hasNext(): Boolean = peekHeader() != null

  /**
   * Returns the next header to process unless this scope is exhausted.
   *
   * This returns null if:
   *
   *  * The stream is exhausted.
   *  * We've read all of the bytes of an object whose length is known.
   *  * We've reached the [DerHeader.TAG_END_OF_CONTENTS] of an object whose length is unknown.
   */
  fun peekHeader(): DerHeader? {
    var result = peekedHeader

    if (result == null) {
      result = readHeader()
      peekedHeader = result
    }

    if (result.isEndOfData) return null

    return result
  }

  /**
   * Consume the next header in the stream and return it. If there is no header to read because we
   * have reached a limit, this returns [END_OF_DATA].
   */
  private fun readHeader(): DerHeader {
    require(peekedHeader == null)

    // We've hit a local limit.
    if (byteCount == limit) return END_OF_DATA

    // We've exhausted the source stream.
    if (limit == -1L && source.exhausted()) return END_OF_DATA

    // Read the tag.
    val tag: Long
    val tagAndClass = source.readByte().toInt() and 0xff
    val tagClass = tagAndClass and 0b1100_0000
    val constructed = (tagAndClass and 0b0010_0000) == 0b0010_0000
    val tag0 = tagAndClass and 0b0001_1111
    if (tag0 == 0b0001_1111) {
      tag = readVariableLengthLong()
    } else {
      tag = tag0.toLong()
    }

    // Read the length.
    val length: Long
    val length0 = source.readByte().toInt() and 0xff
    if (length0 == 0b1000_0000) {
      // Indefinite length.
      length = -1L
    } else if ((length0 and 0b1000_0000) == 0b1000_0000) {
      // Length specified over multiple bytes.
      val lengthBytes = length0 and 0b0111_1111
      var lengthBits = source.readByte().toLong() and 0xff
      for (i in 1 until lengthBytes) {
        lengthBits = lengthBits shl 8
        lengthBits += source.readByte().toInt() and 0xff
      }
      length = lengthBits
    } else {
      // Length is 127 or fewer bytes.
      length = (length0 and 0b0111_1111).toLong()
    }

    // Note that this may be be an encoded "end of data" header.
    return DerHeader(tagClass, tag, constructed, length)
  }

  /**
   * Consume a header and execute [block], which should consume the entire value described by the
   * header. It is an error to not consume a full value in [block].
   */
  internal inline fun <T> read(name: String?, block: (DerHeader) -> T): T {
    if (!hasNext()) throw IOException("expected a value")

    val header = peekedHeader!!
    peekedHeader = null

    val pushedLimit = limit
    val pushedConstructed = constructed

    limit = if (header.length != -1L) byteCount + header.length else -1L
    constructed = header.constructed
    if (name != null) path += name
    try {
      return block(header)
    } finally {
      peekedHeader = null
      limit = pushedLimit
      constructed = pushedConstructed
      if (name != null) path.removeAt(path.size - 1)
    }
  }

  private inline fun readAll(block: (DerHeader) -> Unit) {
    while (hasNext()) {
      read(null, block)
    }
  }

  fun pushTypeHint() {
    typeHintStack.add(null)
  }

  fun popTypeHint() {
    typeHintStack.removeAt(typeHintStack.size - 1)
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
    when (val xy = readVariableLengthLong()) {
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
      result.writeDecimalLong(readVariableLengthLong())
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
      result.writeDecimalLong(readVariableLengthLong())
    }
    return result.readUtf8()
  }

  /** Used for tags and subidentifiers. */
  private fun readVariableLengthLong(): Long {
    // TODO(jwilson): detect overflow.
    var result = 0L
    while (true) {
      val byteN = source.readByte().toLong() and 0xff
      if ((byteN and 0b1000_0000L) == 0b1000_0000L) {
        result = (result + (byteN and 0b0111_1111)) shl 7
      } else {
        return result + byteN
      }
    }
  }

  override fun toString() = path.joinToString(separator = " / ")

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

  companion object {
    /**
     * A synthetic value that indicates there's no more bytes. Values with equivalent data may also
     * show up in ASN.1 streams to also indicate the end of SEQUENCE, SET or other constructed
     * value.
     */
    val END_OF_DATA = DerHeader(
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = DerHeader.TAG_END_OF_CONTENTS,
        constructed = false,
        length = -1L
    )
  }
}
