/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.logging.internal

import java.io.EOFException
import okio.Buffer

/**
 * Reads as much human-readable text as possible from the buffer. Uses code points to detect
 * Unicode control characters commonly used in binary file signatures.
 */
internal fun Buffer.readProbablyUtf8String(builder: StringBuilder, charset: Charset): Long {
  val buffer = clone()
  val totalSize = buffer.size
  var utf8Count = 0L
  try {
    kotlin.run {
      val reuse = ByteArray(16)

      var readCount = 0L
      while (readCount < totalSize) {
        val offset = (readCount - utf8Count).toInt()
        val byteCount = buffer.read(reuse, offset, reuse.size)
        readCount += byteCount

        val s = String(reuse, 0, offset + byteCount, charset)

        val count = s.codePointCount(0, s.length)
        for (i in 0 until count) {
          val codePoint = s.codePointAt(i)
          if (codePoint.isProbablyUtf8CodePoint()) {
            builder.appendCodePoint(codePoint)
            utf8Count += codePoint.getUtf8ByteLength()
            continue
          }
          if (i < count - 1) {
            return@run
          }
        }
      }
    }
  } catch (_: EOFException) {
    // Truncated UTF-8 sequence.
  }
  return utf8Count
}

private fun Int.isProbablyUtf8CodePoint(): Boolean {
  return !(Character.isISOControl(this) && !Character.isWhitespace(this))
}

private fun Int.getUtf8ByteLength(): Int {
  return when {
    this <= 0x7F -> 1  // 1 byte for ASCII characters
    this <= 0x7FF -> 2  // 2 bytes for characters in the range U+0080 to U+07FF
    this <= 0xFFFF -> 3  // 3 bytes for characters in the range U+0800 to U+FFFF
    this <= 0x10FFFF -> 4  // 4 bytes for characters in the range U+10000 to U+10FFFF
    else -> throw IllegalArgumentException("Invalid code point")  // Invalid code point
  }
}