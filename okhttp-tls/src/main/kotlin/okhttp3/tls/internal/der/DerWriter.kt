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
import okio.Buffer
import okio.BufferedSink
import okio.ByteString

internal class DerWriter(sink: BufferedSink) {
  /** A stack of buffers that will be concatenated once we know the length of each. */
  private val stack = mutableListOf(sink)

  /** Type hints scoped to the call stack, manipulated with [pushTypeHint] and [popTypeHint]. */
  private val typeHintStack = mutableListOf<Any?>()

  /**
   * The type hint for the current object. Used to pick adapters based on other fields, such as
   * in extensions which have different types depending on their extension ID.
   */
  var typeHint: Any?
    get() = typeHintStack.lastOrNull()
    set(value) {
      typeHintStack[typeHintStack.size - 1] = value
    }

  /** Names leading to the current location in the ASN.1 document. */
  private val path = mutableListOf<String>()

  /**
   * False unless we made a recursive call to [write] at the current stack frame. The explicit box
   * adapter can clear this to synthesize non-constructed values that are embedded in octet strings.
   */
  var constructed = false

  fun write(name: String, tagClass: Int, tag: Long, block: (BufferedSink) -> Unit) {
    val constructedBit: Int
    val content = Buffer()

    stack.add(content)
    constructed = false // The enclosed object written in block() is not constructed.
    path += name
    try {
      block(content)
      constructedBit = if (constructed) 0b0010_0000 else 0
      constructed = true // The enclosing object is constructed.
    } finally {
      stack.removeAt(stack.size - 1)
      path.removeAt(path.size - 1)
    }

    val sink = sink()

    // Write the tagClass, tag, and constructed bit. This takes 1 byte if tag is less than 31.
    if (tag < 31) {
      val byte0 = tagClass or constructedBit or tag.toInt()
      sink.writeByte(byte0)
    } else {
      val byte0 = tagClass or constructedBit or 0b0001_1111
      sink.writeByte(byte0)
      writeVariableLengthLong(tag)
    }

    // Write the length. This takes 1 byte if length is less than 128.
    val length = content.size
    if (length < 128) {
      sink.writeByte(length.toInt())
    } else {
      // count how many bytes we'll need to express the length.
      val lengthBitCount = 64 - java.lang.Long.numberOfLeadingZeros(length)
      val lengthByteCount = (lengthBitCount + 7) / 8
      sink.writeByte(0b1000_0000 or lengthByteCount)
      for (shift in (lengthByteCount - 1) * 8 downTo 0 step 8) {
        sink.writeByte((length shr shift).toInt())
      }
    }

    // Write the payload.
    sink.writeAll(content)
  }

  /**
   * Execute [block] with a new namespace for type hints. Type hints from the enclosing type are no
   * longer usable by the current type's members.
   */
  fun <T> withTypeHint(block: () -> T): T {
    typeHintStack.add(null)
    try {
      return block()
    } finally {
      typeHintStack.removeAt(typeHintStack.size - 1)
    }
  }

  private fun sink(): BufferedSink = stack[stack.size - 1]

  fun writeBoolean(b: Boolean) {
    sink().writeByte(if (b) -1 else 0)
  }

  fun writeBigInteger(value: BigInteger) {
    sink().write(value.toByteArray())
  }

  fun writeLong(v: Long) {
    val sink = sink()

    val lengthBitCount: Int = if (v < 0L) {
      65 - java.lang.Long.numberOfLeadingZeros(v xor -1L)
    } else {
      65 - java.lang.Long.numberOfLeadingZeros(v)
    }

    val lengthByteCount = (lengthBitCount + 7) / 8
    for (shift in (lengthByteCount - 1) * 8 downTo 0 step 8) {
      sink.writeByte((v shr shift).toInt())
    }
  }

  fun writeBitString(bitString: BitString) {
    val sink = sink()
    sink.writeByte(bitString.unusedBitsCount)
    sink.write(bitString.byteString)
  }

  fun writeOctetString(byteString: ByteString) {
    sink().write(byteString)
  }

  fun writeUtf8(value: String) {
    sink().writeUtf8(value)
  }

  fun writeObjectIdentifier(s: String) {
    val utf8 = Buffer().writeUtf8(s)
    val v1 = utf8.readDecimalLong()
    require(utf8.readByte() == '.'.toByte())
    val v2 = utf8.readDecimalLong()
    writeVariableLengthLong(v1 * 40 + v2)

    while (!utf8.exhausted()) {
      require(utf8.readByte() == '.'.toByte())
      val vN = utf8.readDecimalLong()
      writeVariableLengthLong(vN)
    }
  }

  fun writeRelativeObjectIdentifier(s: String) {
    // Add a leading dot so each subidentifier has a dot prefix.
    val utf8 = Buffer()
        .writeByte('.'.toByte().toInt())
        .writeUtf8(s)

    while (!utf8.exhausted()) {
      require(utf8.readByte() == '.'.toByte())
      val vN = utf8.readDecimalLong()
      writeVariableLengthLong(vN)
    }
  }

  /** Used for tags and subidentifiers. */
  private fun writeVariableLengthLong(v: Long) {
    val sink = sink()
    val bitCount = 64 - java.lang.Long.numberOfLeadingZeros(v)
    val byteCount = (bitCount + 6) / 7
    for (shift in (byteCount - 1) * 7 downTo 0 step 7) {
      val lastBit = if (shift == 0) 0 else 0b1000_0000
      sink.writeByte(((v shr shift) and 0b0111_1111).toInt() or lastBit)
    }
  }

  override fun toString() = path.joinToString(separator = " / ")
}
