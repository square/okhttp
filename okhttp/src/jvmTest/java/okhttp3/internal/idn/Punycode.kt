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
package okhttp3.internal.idn

import okio.Buffer

/**
 * An [RFC 3492] punycode decoder for converting ASCII to Unicode domain name labels. This is
 * intended for use in Internationalized Domain Names (IDNs).
 *
 * This class contains a Kotlin implementation of the pseudocode specified by RFC 3492. It includes
 * direct translation of the pseudocode presented there.
 *
 * Partner this class with [Stringprep] to implement IDNA [RFC 3490].
 *
 * [RFC 3490]: https://datatracker.ietf.org/doc/html/rfc3490
 * [RFC 3492]: https://datatracker.ietf.org/doc/html/rfc3492
 */
object Punycode {
  private const val base = 36
  private const val tmin = 1
  private const val tmax = 26
  private const val skew = 38
  private const val damp = 700
  private const val initial_bias = 72
  private const val initial_n = 0x80

  /**
   * Converts a punycode-encoded domain name with `.`-separated labels into a human-readable
   * Internationalized Domain Name.
   */
  fun decode(string: String): String {
    var pos = 0
    val limit = string.length
    val result = Buffer()

    while (pos < limit) {
      var dot = string.indexOf('.', startIndex = pos)
      if (dot == -1) dot = limit

      if (!decodeLabel(string, pos, dot, result)) {
        // If we couldn't decode the label, emit it without decoding.
        result.writeUtf8(string, pos, dot)
      }

      if (dot < limit) {
        result.writeByte('.'.code)
        pos = dot + 1
      } else {
        break
      }
    }

    return result.readUtf8()
  }

  /**
   * Converts a single label from Punycode to Unicode.
   *
   * @return true if the range of [string] from [pos] to [limit] was valid and decoded successfully.
   *     Otherwise, the decode failed.
   */
  private fun decodeLabel(
    string: String,
    pos: Int,
    limit: Int,
    result: Buffer
  ): Boolean {
    if (!string.regionMatches(pos, "xn--", 0, 4, ignoreCase = true)) {
      result.writeUtf8(string, pos, limit)
      return true
    }

    var pos = pos + 4 // 'xn--'.size.

    // We'd prefer to operate directly on `result` but it doesn't offer insertCodePoint(), only
    // appendCodePoint(). The Punycode algorithm processes code points in increasing code-point
    // order, not in increasing index order.
    val codePoints = mutableListOf<Int>()

    // consume all code points before the last delimiter (if there is one)
    //  and copy them to output, fail on any non-basic code point
    val lastDelimiter = string.lastIndexOf('-', limit)
    if (lastDelimiter >= pos) {
      while (pos < lastDelimiter) {
        when (val codePoint = string[pos++]) {
          in 'a'..'z', in 'A'..'Z', in '0'..'9', '-' -> {
            codePoints += codePoint.code
          }
          else -> return false // Malformed.
        }
      }
      pos++ // Consume '-'.
    }

    var n = initial_n
    var i = 0
    var bias = initial_bias

    while (pos < limit) {
      val oldi = i
      var w = 1
      for (k in base until Int.MAX_VALUE step base) {
        if (pos == limit) return false // Malformed.
        val c = string[pos++]
        val digit = when (c) {
          in 'a'..'z' -> c - 'a'
          in 'A'..'Z' -> c - 'A'
          in '0'..'9' -> c - '0' + 26
          else -> return false // Malformed.
        }
        val deltaI = digit * w
        if (i > Int.MAX_VALUE - deltaI) return false // Prevent overflow.
        i += deltaI
        val t = when {
          k <= bias -> tmin
          k >= bias + tmax -> tmax
          else -> k - bias
        }
        if (digit < t) break
        val scaleW = base - t
        if (w > Int.MAX_VALUE / scaleW) return false // Prevent overflow.
        w *= scaleW
      }
      bias = adapt(i - oldi, codePoints.size + 1, oldi == 0)
      val deltaN = i / (codePoints.size + 1)
      if (n > Int.MAX_VALUE - deltaN) return false // Prevent overflow.
      n += deltaN
      i %= (codePoints.size + 1)

      if (n > 0x10ffff) return false // Not a valid code point.

      codePoints.add(i, n)

      i++
    }

    for (codePoint in codePoints) {
      result.writeUtf8CodePoint(codePoint)
    }

    return true
  }

  /** Returns a new bias. */
  private fun adapt(delta: Int, numpoints: Int, first: Boolean): Int {
    var delta = when {
      first -> delta / damp
      else -> delta / 2
    }
    delta += (delta / numpoints)
    var k = 0
    while (delta > ((base - tmin) * tmax) / 2) {
      delta /= (base - tmin)
      k += base
    }
    return k + (((base - tmin + 1) * delta) / (delta + skew))
  }
}
