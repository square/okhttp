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
import okio.ByteString.Companion.encodeUtf8

/**
 * An [RFC 3492] punycode decoder for converting ASCII to Unicode domain name labels. This is
 * intended for use in Internationalized Domain Names (IDNs).
 *
 * This class contains a Kotlin implementation of the pseudocode specified by RFC 3492. It includes
 * direct translation of the pseudocode presented there.
 *
 * Partner this class with [UTS #46] to implement IDNA2008 [RFC 5890] like most browsers do.
 *
 * [RFC 3492]: https://datatracker.ietf.org/doc/html/rfc3492
 * [RFC 5890]: https://datatracker.ietf.org/doc/html/rfc5890
 * [UTS #46]: https://www.unicode.org/reports/tr46/
 */
object Punycode {
  val PREFIX_STRING = "xn--"
  val PREFIX = PREFIX_STRING.encodeUtf8()

  private const val BASE = 36
  private const val TMIN = 1
  private const val TMAX = 26
  private const val SKEW = 38
  private const val DAMP = 700
  private const val INITIAL_BIAS = 72
  private const val INITIAL_N = 0x80

  /**
   * Returns null if any label is oversized so much that the encoder cannot encode it without
   * integer overflow. This will not return null for labels that fit within the DNS size
   * limits.
   */
  fun encode(string: String): String? {
    var pos = 0
    val limit = string.length
    val result = Buffer()

    while (pos < limit) {
      var dot = string.indexOf('.', startIndex = pos)
      if (dot == -1) dot = limit

      if (!encodeLabel(string, pos, dot, result)) {
        // If we couldn't encode the label, give up.
        return null
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

  private fun encodeLabel(
    string: String,
    pos: Int,
    limit: Int,
    result: Buffer,
  ): Boolean {
    if (!string.requiresEncode(pos, limit)) {
      result.writeUtf8(string, pos, limit)
      return true
    }

    result.write(PREFIX)

    val input = string.codePoints(pos, limit)

    // Copy all the basic code points to the output.
    var b = 0
    for (codePoint in input) {
      if (codePoint < INITIAL_N) {
        result.writeByte(codePoint)
        b++
      }
    }

    // Copy a delimiter if any basic code points were emitted.
    if (b > 0) result.writeByte('-'.code)

    var n = INITIAL_N
    var delta = 0
    var bias = INITIAL_BIAS
    var h = b
    while (h < input.size) {
      val m = input.minBy { if (it >= n) it else Int.MAX_VALUE }

      val increment = (m - n) * (h + 1)
      if (delta > Int.MAX_VALUE - increment) return false // Prevent overflow.
      delta += increment

      n = m

      for (c in input) {
        if (c < n) {
          if (delta == Int.MAX_VALUE) return false // Prevent overflow.
          delta++
        } else if (c == n) {
          var q = delta

          for (k in BASE until Int.MAX_VALUE step BASE) {
            val t =
              when {
                k <= bias -> TMIN
                k >= bias + TMAX -> TMAX
                else -> k - bias
              }
            if (q < t) break
            result.writeByte((t + ((q - t) % (BASE - t))).punycodeDigit)
            q = (q - t) / (BASE - t)
          }

          result.writeByte(q.punycodeDigit)
          bias = adapt(delta, h + 1, h == b)
          delta = 0
          h++
        }
      }
      delta++
      n++
    }

    return true
  }

  /**
   * Converts a punycode-encoded domain name with `.`-separated labels into a human-readable
   * Internationalized Domain Name.
   */
  fun decode(string: String): String? {
    var pos = 0
    val limit = string.length
    val result = Buffer()

    while (pos < limit) {
      var dot = string.indexOf('.', startIndex = pos)
      if (dot == -1) dot = limit

      if (!decodeLabel(string, pos, dot, result)) return null

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
    result: Buffer,
  ): Boolean {
    if (!string.regionMatches(pos, PREFIX_STRING, 0, 4, ignoreCase = true)) {
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

    var n = INITIAL_N
    var i = 0
    var bias = INITIAL_BIAS

    while (pos < limit) {
      val oldi = i
      var w = 1
      for (k in BASE until Int.MAX_VALUE step BASE) {
        if (pos == limit) return false // Malformed.
        val c = string[pos++]
        val digit =
          when (c) {
            in 'a'..'z' -> c - 'a'
            in 'A'..'Z' -> c - 'A'
            in '0'..'9' -> c - '0' + 26
            else -> return false // Malformed.
          }
        val deltaI = digit * w
        if (i > Int.MAX_VALUE - deltaI) return false // Prevent overflow.
        i += deltaI
        val t =
          when {
            k <= bias -> TMIN
            k >= bias + TMAX -> TMAX
            else -> k - bias
          }
        if (digit < t) break
        val scaleW = BASE - t
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
  private fun adapt(
    delta: Int,
    numpoints: Int,
    first: Boolean,
  ): Int {
    var delta =
      when {
        first -> delta / DAMP
        else -> delta / 2
      }
    delta += (delta / numpoints)
    var k = 0
    while (delta > ((BASE - TMIN) * TMAX) / 2) {
      delta /= (BASE - TMIN)
      k += BASE
    }
    return k + (((BASE - TMIN + 1) * delta) / (delta + SKEW))
  }

  private fun String.requiresEncode(
    pos: Int,
    limit: Int,
  ): Boolean {
    for (i in pos until limit) {
      if (this[i].code >= INITIAL_N) return true
    }
    return false
  }

  private fun String.codePoints(
    pos: Int,
    limit: Int,
  ): List<Int> {
    val result = mutableListOf<Int>()
    var i = pos
    while (i < limit) {
      val c = this[i]
      result +=
        when {
          c.isSurrogate() -> {
            val low = (if (i + 1 < limit) this[i + 1] else '\u0000')
            if (c.isLowSurrogate() || !low.isLowSurrogate()) {
              '?'.code
            } else {
              i++
              0x010000 + (c.code and 0x03ff shl 10 or (low.code and 0x03ff))
            }
          }

          else -> c.code
        }
      i++
    }
    return result
  }

  private val Int.punycodeDigit: Int
    get() =
      when {
        this < 26 -> this + 'a'.code
        this < 36 -> (this - 26) + '0'.code
        else -> error("unexpected digit: $this")
      }
}
