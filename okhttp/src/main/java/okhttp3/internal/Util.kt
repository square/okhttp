/*
 * Copyright (C) 2012 The Android Open Source Project
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

import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.internal.http2.Header
import okhttp3.internal.platform.Platform
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Options
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_16BE
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.charset.StandardCharsets.UTF_8
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/** Junk drawer of utility methods.  */
object Util {
  @JvmField
  val EMPTY_BYTE_ARRAY = ByteArray(0)
  @JvmField
  val EMPTY_HEADERS = Headers.of()

  @JvmField
  val EMPTY_RESPONSE = ResponseBody.create(null, EMPTY_BYTE_ARRAY)
  @JvmField
  val EMPTY_REQUEST = RequestBody.create(null, EMPTY_BYTE_ARRAY)

  /** Byte order marks.  */
  private val UNICODE_BOMS = Options.of(
      "efbbbf".decodeHex(), // UTF-8
      "feff".decodeHex(), // UTF-16BE
      "fffe".decodeHex(), // UTF-16LE
      "0000ffff".decodeHex(), // UTF-32BE
      "ffff0000".decodeHex() // UTF-32LE
  )

  private val UTF_32BE = Charset.forName("UTF-32BE")
  private val UTF_32LE = Charset.forName("UTF-32LE")

  /** GMT and UTC are equivalent for our purposes.  */
  @JvmField val UTC = TimeZone.getTimeZone("GMT")!!

  val NATURAL_ORDER = Comparator<String> { a, b -> a.compareTo(b) }

  /**
   * Quick and dirty pattern to differentiate IP addresses from hostnames. This is an approximation
   * of Android's private InetAddress#isNumeric API.
   *
   * This matches IPv6 addresses as a hex string containing at least one colon, and possibly
   * including dots after the first colon. It matches IPv4 addresses as strings containing only
   * decimal digits and dots. This pattern matches strings like "a:.23" and "54" that are neither IP
   * addresses nor hostnames; they will be verified as IP addresses (which is a more strict
   * verification).
   */
  private val VERIFY_AS_IP_ADDRESS =
      "([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)".toRegex()

  fun checkOffsetAndCount(arrayLength: Long, offset: Long, count: Long) {
    if (offset or count < 0 || offset > arrayLength || arrayLength - offset < count) {
      throw ArrayIndexOutOfBoundsException()
    }
  }

  @JvmStatic fun threadFactory(name: String, daemon: Boolean): ThreadFactory = ThreadFactory { runnable ->
    Thread(runnable, name).apply {
      isDaemon = daemon
    }
  }

  /**
   * Returns an array containing only elements found in [first] and also in [second].
   * The returned elements are in the same order as in [first].
   */
  fun intersect(
    comparator: Comparator<in String>,
    first: Array<String>,
    second: Array<String>
  ): Array<String> {
    val result = ArrayList<String>()
    for (a in first) {
      for (b in second) {
        if (comparator.compare(a, b) == 0) {
          result.add(a)
          break
        }
      }
    }
    return result.toTypedArray()
  }

  /**
   * Returns true if there is an element in [first] that is also in [second]. This
   * method terminates if any intersection is found. The sizes of both arguments are assumed to be
   * so small, and the likelihood of an intersection so great, that it is not worth the CPU cost of
   * sorting or the memory cost of hashing.
   */
  fun nonEmptyIntersection(
    comparator: Comparator<String>,
    first: Array<String>?,
    second: Array<String>?
  ): Boolean {
    if (first == null || second == null || first.isEmpty() || second.isEmpty()) {
      return false
    }
    for (a in first) {
      for (b in second) {
        if (comparator.compare(a, b) == 0) {
          return true
        }
      }
    }
    return false
  }

  fun hostHeader(url: HttpUrl, includeDefaultPort: Boolean): String {
    val host = if (url.host().contains(":"))
      "[${url.host()}]"
    else
      url.host()
    return if (includeDefaultPort || url.port() != HttpUrl.defaultPort(url.scheme()))
      "$host:${url.port()}"
    else
      host
  }

  fun indexOf(comparator: Comparator<String>, array: Array<String>, value: String): Int =
      array.indexOfFirst { comparator.compare(it, value) == 0 }

  @Suppress("UNCHECKED_CAST")
  fun concat(array: Array<String>, value: String): Array<String> {
    val result = array.copyOf(array.size + 1)
    result[result.size - 1] = value
    return result as Array<String>
  }

  /**
   * Increments [pos] until [input] is not ASCII whitespace. Stops at [limit].
   */
  fun skipLeadingAsciiWhitespace(input: String, pos: Int, limit: Int): Int {
    for (i in pos until limit) {
      when (input[i]) {
        '\t', '\n', '\u000C', '\r', ' ' -> Unit
        else -> return i
      }
    }
    return limit
  }

  /**
   * Decrements [limit] until `input[limit - 1]` is not ASCII whitespace. Stops at [pos].
   */
  fun skipTrailingAsciiWhitespace(input: String, pos: Int, limit: Int): Int {
    for (i in limit - 1 downTo pos) {
      when (input[i]) {
        '\t', '\n', '\u000C', '\r', ' ' -> Unit
        else -> return i + 1
      }
    }
    return pos
  }

  /** Equivalent to `string.substring(pos, limit).trim()`.  */
  fun trimSubstring(string: String, pos: Int, limit: Int): String {
    val start = skipLeadingAsciiWhitespace(string, pos, limit)
    val end = skipTrailingAsciiWhitespace(string, start, limit)
    return string.substring(start, end)
  }

  /**
   * Returns the index of the first character in [input] that contains a character in [delimiters].
   * Returns limit if there is no such character.
   */
  fun delimiterOffset(input: String, pos: Int, limit: Int, delimiters: String): Int {
    for (i in pos until limit) {
      if (delimiters.indexOf(input[i]) != -1) return i
    }
    return limit
  }

  /**
   * Returns the index of the first character in [input] that is [delimiter]. Returns
   * limit if there is no such character.
   */
  fun delimiterOffset(input: String, pos: Int, limit: Int, delimiter: Char): Int {
    for (i in pos until limit) {
      if (input[i] == delimiter) return i
    }
    return limit
  }

  /**
   * If [host] is an IP address, this returns the IP address in canonical form.
   *
   * Otherwise this performs IDN ToASCII encoding and canonicalize the result to lowercase. For
   * example this converts `☃.net` to `xn--n3h.net`, and `WwW.GoOgLe.cOm` to
   * `www.google.com`. `null` will be returned if the host cannot be ToASCII encoded or
   * if the result contains unsupported ASCII characters.
   */
  @JvmStatic fun canonicalizeHost(host: String): String? {
    // If the input contains a :, it’s an IPv6 address.
    if (host.contains(":")) {
      // If the input is encased in square braces "[...]", drop 'em.
      val inetAddress = (if (host.startsWith("[") && host.endsWith("]"))
        decodeIpv6(host, 1, host.length - 1)
      else
        decodeIpv6(host, 0, host.length)) ?: return null
      val address = inetAddress.address
      if (address.size == 16) return inet6AddressToAscii(address)
      if (address.size == 4) return inetAddress.hostAddress // An IPv4-mapped IPv6 address.
      throw AssertionError("Invalid IPv6 address: '$host'")
    }

    try {
      val result = IDN.toASCII(host).toLowerCase(Locale.US)
      if (result.isEmpty()) return null

      // Confirm that the IDN ToASCII result doesn't contain any illegal characters.
      return if (containsInvalidHostnameAsciiCodes(result)) {
        null
      } else {
        result // TODO: implement all label limits.
      }
    } catch (_: IllegalArgumentException) {
      return null
    }
  }

  private fun containsInvalidHostnameAsciiCodes(hostnameAscii: String): Boolean {
    for (i in 0 until hostnameAscii.length) {
      val c = hostnameAscii[i]
      // The WHATWG Host parsing rules accepts some character codes which are invalid by
      // definition for OkHttp's host header checks (and the WHATWG Host syntax definition). Here
      // we rule out characters that would cause problems in host headers.
      if (c <= '\u001f' || c >= '\u007f') {
        return true
      }
      // Check for the characters mentioned in the WHATWG Host parsing spec:
      // U+0000, U+0009, U+000A, U+000D, U+0020, "#", "%", "/", ":", "?", "@", "[", "\", and "]"
      // (excluding the characters covered above).
      if (" #%/:?@[\\]".indexOf(c) != -1) {
        return true
      }
    }
    return false
  }

  /**
   * Returns the index of the first character in [input] that is either a control character
   * (like `\u0000` or `\n`) or a non-ASCII character. Returns -1 if [input] has no such
   * characters.
   */
  fun indexOfControlOrNonAscii(input: String): Int {
    var i = 0
    val length = input.length
    while (i < length) {
      val c = input[i]
      if (c <= '\u001f' || c >= '\u007f') {
        return i
      }
      i++
    }
    return -1
  }

  /** Returns true if [host] is not a host name and might be an IP address.  */
  @JvmStatic
  fun verifyAsIpAddress(host: String): Boolean {
    return VERIFY_AS_IP_ADDRESS.matches(host)
  }

  /** Returns a [Locale.US] formatted [String].  */
  @JvmStatic
  fun format(format: String, vararg args: Any): String {
    return String.format(Locale.US, format, *args)
  }

  @Throws(IOException::class)
  fun bomAwareCharset(source: BufferedSource, charset: Charset): Charset {
    return when (source.select(UNICODE_BOMS)) {
      0 -> UTF_8
      1 -> UTF_16BE
      2 -> UTF_16LE
      3 -> UTF_32BE
      4 -> UTF_32LE
      -1 -> charset
      else -> throw AssertionError()
    }
  }

  fun checkDuration(name: String, duration: Long, unit: TimeUnit?): Int {
    check(duration >= 0) { "$name < 0" }
    check(unit != null) { "unit == null" }
    val millis = unit.toMillis(duration)
    require(millis <= Integer.MAX_VALUE) { "$name too large." }
    require(millis != 0L || duration <= 0) { "$name too small." }
    return millis.toInt()
  }

  fun decodeHexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
  }

  /** Decodes an IPv6 address like 1111:2222:3333:4444:5555:6666:7777:8888 or ::1.  */
  private fun decodeIpv6(input: String, pos: Int, limit: Int): InetAddress? {
    val address = ByteArray(16)
    var b = 0
    var compress = -1
    var groupOffset = -1

    var i = pos
    while (i < limit) {
      if (b == address.size) return null // Too many groups.

      // Read a delimiter.
      if (i + 2 <= limit && input.startsWith("::", startIndex = i)) {
        // Compression "::" delimiter, which is anywhere in the input, including its prefix.
        if (compress != -1) return null // Multiple "::" delimiters.
        i += 2
        b += 2
        compress = b
        if (i == limit) break
      } else if (b != 0) {
        // Group separator ":" delimiter.
        if (input.startsWith(":", startIndex = i)) {
          i++
        } else if (input.startsWith(".", startIndex = i)) {
          // If we see a '.', rewind to the beginning of the previous group and parse as IPv4.
          if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) return null
          b += 2 // We rewound two bytes and then added four.
          break
        } else {
          return null // Wrong delimiter.
        }
      }

      // Read a group, one to four hex digits.
      var value = 0
      groupOffset = i
      while (i < limit) {
        val c = input[i]
        val hexDigit = decodeHexDigit(c)
        if (hexDigit == -1) break
        value = (value shl 4) + hexDigit
        i++
      }
      val groupLength = i - groupOffset
      if (groupLength == 0 || groupLength > 4) return null // Group is the wrong size.

      // We've successfully read a group. Assign its value to our byte array.
      address[b++] = (value.ushr(8) and 0xff).toByte()
      address[b++] = (value and 0xff).toByte()
    }

    // All done. If compression happened, we need to move bytes to the right place in the
    // address. Here's a sample:
    //
    //      input: "1111:2222:3333::7777:8888"
    //     before: { 11, 11, 22, 22, 33, 33, 00, 00, 77, 77, 88, 88, 00, 00, 00, 00  }
    //   compress: 6
    //          b: 10
    //      after: { 11, 11, 22, 22, 33, 33, 00, 00, 00, 00, 00, 00, 77, 77, 88, 88 }
    //
    if (b != address.size) {
      if (compress == -1) return null // Address didn't have compression or enough groups.
      System.arraycopy(address, compress, address, address.size - (b - compress), b - compress)
      Arrays.fill(address, compress, compress + (address.size - b), 0.toByte())
    }

    return InetAddress.getByAddress(address)
  }

  /** Decodes an IPv4 address suffix of an IPv6 address, like 1111::5555:6666:192.168.0.1.  */
  private fun decodeIpv4Suffix(
    input: String,
    pos: Int,
    limit: Int,
    address: ByteArray,
    addressOffset: Int
  ): Boolean {
    var b = addressOffset

    var i = pos
    while (i < limit) {
      if (b == address.size) return false // Too many groups.

      // Read a delimiter.
      if (b != addressOffset) {
        if (input[i] != '.') return false // Wrong delimiter.
        i++
      }

      // Read 1 or more decimal digits for a value in 0..255.
      var value = 0
      val groupOffset = i
      while (i < limit) {
        val c = input[i]
        if (c < '0' || c > '9') break
        if (value == 0 && groupOffset != i) return false // Reject unnecessary leading '0's.
        value = value * 10 + c.toInt() - '0'.toInt()
        if (value > 255) return false // Value out of range.
        i++
      }
      val groupLength = i - groupOffset
      if (groupLength == 0) return false // No digits.

      // We've successfully read a byte.
      address[b++] = value.toByte()
    }

    // Check for too few groups. We wanted exactly four.
    return b == addressOffset + 4
  }

  /** Encodes an IPv6 address in canonical form according to RFC 5952.  */
  private fun inet6AddressToAscii(address: ByteArray): String {
    // Go through the address looking for the longest run of 0s. Each group is 2-bytes.
    // A run must be longer than one group (section 4.2.2).
    // If there are multiple equal runs, the first one must be used (section 4.2.3).
    var longestRunOffset = -1
    var longestRunLength = 0
    run {
      var i = 0
      while (i < address.size) {
        val currentRunOffset = i
        while (i < 16 && address[i].toInt() == 0 && address[i + 1].toInt() == 0) {
          i += 2
        }
        val currentRunLength = i - currentRunOffset
        if (currentRunLength > longestRunLength && currentRunLength >= 4) {
          longestRunOffset = currentRunOffset
          longestRunLength = currentRunLength
        }
        i += 2
      }
    }

    // Emit each 2-byte group in hex, separated by ':'. The longest run of zeroes is "::".
    val result = Buffer()
    var i = 0
    while (i < address.size) {
      if (i == longestRunOffset) {
        result.writeByte(':'.toInt())
        i += longestRunLength
        if (i == 16) result.writeByte(':'.toInt())
      } else {
        if (i > 0) result.writeByte(':'.toInt())
        val group = address[i] and 0xff shl 8 or (address[i + 1] and 0xff)
        result.writeHexadecimalUnsignedLong(group.toLong())
        i += 2
      }
    }
    return result.readUtf8()
  }

  fun platformTrustManager(): X509TrustManager = Platform.get().platformTrustManager()

  fun toHeaders(headerBlock: List<Header>): Headers {
    val builder = Headers.Builder()
    for ((name, value) in headerBlock) {
      addHeaderLenient(builder, name.utf8(), value.utf8())
    }
    return builder.build()
  }

  fun toHeaderBlock(headers: Headers): List<Header> = (0 until headers.size).map {
    Header(headers.name(it), headers.value(it))
  }

  /** Returns true if an HTTP request for [a] and [b] can reuse a connection.  */
  fun sameConnection(a: HttpUrl, b: HttpUrl): Boolean = (a.host() == b.host() &&
      a.port() == b.port() &&
      a.scheme() == b.scheme())

  fun eventListenerFactory(listener: EventListener): EventListener.Factory =
      EventListener.Factory { listener }
}
