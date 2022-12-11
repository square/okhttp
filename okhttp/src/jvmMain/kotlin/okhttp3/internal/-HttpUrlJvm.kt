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

import java.nio.charset.Charset
import okhttp3.internal.CommonHttpUrl.FORM_ENCODE_SET
import okhttp3.internal.CommonHttpUrl.HEX_DIGITS
import okhttp3.internal.JvmHttpUrl.canonicalizeInternal
import okio.Buffer

object JvmHttpUrl {
  internal const val INVALID_HOST = "Invalid URL host"

  /**
   * Returns the index of the ':' in `input` that is after scheme characters. Returns -1 if
   * `input` does not have a scheme that starts at `pos`.
   */
  internal fun schemeDelimiterOffset(input: String, pos: Int, limit: Int): Int {
    if (limit - pos < 2) return -1

    val c0 = input[pos]
    if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1 // Not a scheme start char.

    characters@ for (i in pos + 1 until limit) {
      return when (input[i]) {
        // Scheme character. Keep going.
        in 'a'..'z', in 'A'..'Z', in '0'..'9', '+', '-', '.' -> continue@characters

        // Scheme prefix!
        ':' -> i

        // Non-scheme character before the first ':'.
        else -> -1
      }
    }

    return -1 // No ':'; doesn't start with a scheme.
  }

  /** Returns the number of '/' and '\' slashes in this, starting at `pos`. */
  internal fun String.slashCount(pos: Int, limit: Int): Int {
    var slashCount = 0
    for (i in pos until limit) {
      val c = this[i]
      if (c == '\\' || c == '/') {
        slashCount++
      } else {
        break
      }
    }
    return slashCount
  }

  /** Finds the first ':' in `input`, skipping characters between square braces "[...]". */
  internal fun portColonOffset(input: String, pos: Int, limit: Int): Int {
    var i = pos
    while (i < limit) {
      when (input[i]) {
        '[' -> {
          while (++i < limit) {
            if (input[i] == ']') break
          }
        }
        ':' -> return i
      }
      i++
    }
    return limit // No colon.
  }

  internal fun parsePort(input: String, pos: Int, limit: Int): Int {
    return try {
      // Canonicalize the port string to skip '\n' etc.
      val portString = input.canonicalizeInternal(pos = pos, limit = limit, encodeSet = "")
      val i = portString.toInt()
      if (i in 1..65535) i else -1
    } catch (_: NumberFormatException) {
      -1 // Invalid port.
    }
  }

  internal fun String.isPercentEncoded(pos: Int, limit: Int): Boolean {
    return pos + 2 < limit &&
      this[pos] == '%' &&
      this[pos + 1].parseHexDigit() != -1 &&
      this[pos + 2].parseHexDigit() != -1
  }

  internal fun Buffer.writeCanonicalized(
    input: String,
    pos: Int,
    limit: Int,
    encodeSet: String,
    alreadyEncoded: Boolean,
    strict: Boolean,
    plusIsSpace: Boolean,
    unicodeAllowed: Boolean,
    charset: Charset?
  ) {
    var encodedCharBuffer: Buffer? = null // Lazily allocated.
    var codePoint: Int
    var i = pos
    while (i < limit) {
      codePoint = input.codePointAt(i)
      if (alreadyEncoded && (codePoint == '\t'.code || codePoint == '\n'.code ||
          codePoint == '\u000c'.code || codePoint == '\r'.code)) {
        // Skip this character.
      } else if (codePoint == ' '.code && encodeSet === FORM_ENCODE_SET) {
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

        if (charset == null || charset == Charsets.UTF_8) {
          encodedCharBuffer.writeUtf8CodePoint(codePoint)
        } else {
          encodedCharBuffer.writeString(input, i, i + Character.charCount(codePoint), charset)
        }

        while (!encodedCharBuffer.exhausted()) {
          val b = encodedCharBuffer.readByte().toInt() and 0xff
          writeByte('%'.code)
          writeByte(HEX_DIGITS[b shr 4 and 0xf].code)
          writeByte(HEX_DIGITS[b and 0xf].code)
        }
      } else {
        // This character doesn't need encoding. Just copy it over.
        writeUtf8CodePoint(codePoint)
      }
      i += Character.charCount(codePoint)
    }
  }

  /**
   * Returns a substring of `input` on the range `[pos..limit)` with the following
   * transformations:
   *
   *  * Tabs, newlines, form feeds and carriage returns are skipped.
   *
   *  * In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
   *
   *  * Characters in `encodeSet` are percent-encoded.
   *
   *  * Control characters and non-ASCII characters are percent-encoded.
   *
   *  * All other characters are copied without transformation.
   *
   * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
   * @param strict true to encode '%' if it is not the prefix of a valid percent encoding.
   * @param plusIsSpace true to encode '+' as "%2B" if it is not already encoded.
   * @param unicodeAllowed true to leave non-ASCII codepoint unencoded.
   * @param charset which charset to use, null equals UTF-8.
   */
  internal fun String.canonicalizeInternal(
    pos: Int = 0,
    limit: Int = length,
    encodeSet: String,
    alreadyEncoded: Boolean = false,
    strict: Boolean = false,
    plusIsSpace: Boolean = false,
    unicodeAllowed: Boolean = false,
    charset: Charset? = null
  ): String {
    var codePoint: Int
    var i = pos
    while (i < limit) {
      codePoint = codePointAt(i)
      if (codePoint < 0x20 ||
        codePoint == 0x7f ||
        codePoint >= 0x80 && !unicodeAllowed ||
        codePoint.toChar() in encodeSet ||
        codePoint == '%'.code &&
        (!alreadyEncoded || strict && !isPercentEncoded(i, limit)) ||
        codePoint == '+'.code && plusIsSpace) {
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
          charset = charset
        )
        return out.readUtf8()
      }
      i += Character.charCount(codePoint)
    }

    // Fast path: no characters in [pos..limit) required encoding.
    return substring(pos, limit)
  }
}

actual object HttpUrlCommon {
  internal actual fun Buffer.writePercentDecoded(
    encoded: String,
    pos: Int,
    limit: Int,
    plusIsSpace: Boolean
  ) {
    var codePoint: Int
    var i = pos
    while (i < limit) {
      codePoint = encoded.codePointAt(i)
      if (codePoint == '%'.code && i + 2 < limit) {
        val d1 = encoded[i + 1].parseHexDigit()
        val d2 = encoded[i + 2].parseHexDigit()
        if (d1 != -1 && d2 != -1) {
          writeByte((d1 shl 4) + d2)
          i += 2
          i += Character.charCount(codePoint)
          continue
        }
      } else if (codePoint == '+'.code && plusIsSpace) {
        writeByte(' '.code)
        i++
        continue
      }
      writeUtf8CodePoint(codePoint)
      i += Character.charCount(codePoint)
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
  ): String = canonicalizeInternal(
    pos = pos,
    limit = limit,
    encodeSet = encodeSet,
    alreadyEncoded = alreadyEncoded,
    strict = strict,
    plusIsSpace = plusIsSpace,
    unicodeAllowed = unicodeAllowed
  )

}
