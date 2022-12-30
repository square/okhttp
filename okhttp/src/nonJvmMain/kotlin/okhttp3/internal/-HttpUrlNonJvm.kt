/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.internal

import okhttp3.internal.CommonHttpUrl.isPercentEncoded
import okhttp3.internal.NonJvmHttpUrl.writeCanonicalized
import okio.Buffer

internal object NonJvmHttpUrl {
  internal fun Buffer.writeCanonicalized(
    input: String,
    pos: Int,
    limit: Int,
    encodeSet: String,
    alreadyEncoded: Boolean,
    strict: Boolean,
    plusIsSpace: Boolean,
    unicodeAllowed: Boolean,
  ) {
    var encodedCharBuffer: Buffer? = null // Lazily allocated.
    var codePoint: Int
    var i = pos
    while (i < limit) {
      codePoint = input[i].code
      if (alreadyEncoded && (codePoint == '\t'.code || codePoint == '\n'.code ||
          codePoint == '\u000c'.code || codePoint == '\r'.code)) {
        // Skip this character.
      } else if (codePoint == ' '.code && encodeSet === CommonHttpUrl.FORM_ENCODE_SET) {
        // Encode ' ' as '+'.
        writeUtf8("+")
      } else if (codePoint == '+'.code && plusIsSpace) {
        // Encode '+' as '%2B' since we permit ' ' to be encoded as either '+' or '%20'.
        writeUtf8(if (alreadyEncoded) "+" else "%2B")
      } else if (codePoint < 0x20 ||
        codePoint == 0x7f ||
        codePoint >= 0x80 && !unicodeAllowed ||
        codePoint.toChar() in encodeSet ||
        codePoint == '%'.code &&
        (!alreadyEncoded || strict && !input.isPercentEncoded(i, limit))) {
        // Percent encode this character.
        if (encodedCharBuffer == null) {
          encodedCharBuffer = Buffer()
        }

        encodedCharBuffer.writeUtf8CodePoint(codePoint)

        while (!encodedCharBuffer.exhausted()) {
          val b = encodedCharBuffer.readByte().toInt() and 0xff
          writeByte('%'.code)
          writeByte(CommonHttpUrl.HEX_DIGITS[b shr 4 and 0xf].code)
          writeByte(CommonHttpUrl.HEX_DIGITS[b and 0xf].code)
        }
      } else {
        // This character doesn't need encoding. Just copy it over.
        writeUtf8CodePoint(codePoint)
      }
      i += 1
    }
  }
}

internal actual object HttpUrlCommon {
  internal actual fun Buffer.writePercentDecoded(
    encoded: String,
    pos: Int,
    limit: Int,
    plusIsSpace: Boolean
  ) {
    var codePoint: Int
    var i = pos
    while (i < limit) {
      codePoint = encoded.get(i).code
      if (codePoint == '%'.code && i + 2 < limit) {
        val d1 = encoded[i + 1].parseHexDigit()
        val d2 = encoded[i + 2].parseHexDigit()
        if (d1 != -1 && d2 != -1) {
          writeByte((d1 shl 4) + d2)
          i += 2
          i += 1
          continue
        }
      } else if (codePoint == '+'.code && plusIsSpace) {
        writeByte(' '.code)
        i++
        continue
      }
      writeUtf8CodePoint(codePoint)
      i += 1
    }
  }

  internal actual fun String.canonicalize(
    pos: Int,
    limit: Int,
    encodeSet: String,
    alreadyEncoded: Boolean,
    strict: Boolean,
    plusIsSpace: Boolean,
    unicodeAllowed: Boolean,
  ): String {
    var codePoint: Int
    var i = pos
    while (i < limit) {
      codePoint = this[i].code
      if (codePoint < 0x20 ||
        codePoint == 0x7f ||
        codePoint >= 0x80 && !unicodeAllowed ||
        codePoint.toChar() in encodeSet ||
        codePoint == '%'.code &&
        (!alreadyEncoded || strict && !isPercentEncoded(i, limit)) ||
        codePoint == '+'.code && plusIsSpace
      ) {
        // Slow path: the character at i requires encoding!
        val out = Buffer()
        out.writeUtf8(this, pos, i)
        out.writeCanonicalized(
          input = this,
          pos = i,
          limit = limit,
          encodeSet = encodeSet,
          alreadyEncoded = alreadyEncoded,
          strict = strict,
          plusIsSpace = plusIsSpace,
          unicodeAllowed = unicodeAllowed,
        )
        return out.readUtf8()
      }
      i += 1
    }

    // Fast path: no characters in [pos..limit) required encoding.
    return substring(pos, limit)
  }
}

