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
@file:JvmName("Util")

package okhttp3.internal

import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.internal.http2.Header
import okhttp3.internal.platform.Platform
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Options
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_16BE
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.charset.StandardCharsets.UTF_8
import java.util.ArrayList
import java.util.Comparator
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import kotlin.text.Charsets.UTF_32BE
import kotlin.text.Charsets.UTF_32LE

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

/** GMT and UTC are equivalent for our purposes.  */
@JvmField
val UTC = TimeZone.getTimeZone("GMT")!!

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
  if (offset or count < 0L || offset > arrayLength || arrayLength - offset < count) {
    throw ArrayIndexOutOfBoundsException()
  }
}

fun threadFactory(
  name: String,
  daemon: Boolean
): ThreadFactory = ThreadFactory { runnable ->
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
  val host = if (":" in url.host) {
    "[${url.host}]"
  } else {
    url.host
  }
  return if (includeDefaultPort || url.port != HttpUrl.defaultPort(url.scheme)) {
    "$host:${url.port}"
  } else {
    host
  }
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
    if (input[i] in delimiters) return i
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
fun verifyAsIpAddress(host: String): Boolean {
  return VERIFY_AS_IP_ADDRESS.matches(host)
}

/** Returns a [Locale.US] formatted [String].  */
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
  check(duration >= 0L) { "$name < 0" }
  check(unit != null) { "unit == null" }
  val millis = unit.toMillis(duration)
  require(millis <= Integer.MAX_VALUE) { "$name too large." }
  require(millis != 0L || duration <= 0L) { "$name too small." }
  return millis.toInt()
}

fun decodeHexDigit(c: Char): Int = when (c) {
  in '0'..'9' -> c - '0'
  in 'a'..'f' -> c - 'a' + 10
  in 'A'..'F' -> c - 'A' + 10
  else -> -1
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
fun sameConnection(a: HttpUrl, b: HttpUrl): Boolean = (a.host == b.host &&
    a.port == b.port &&
    a.scheme == b.scheme)

fun eventListenerFactory(listener: EventListener): EventListener.Factory =
    EventListener.Factory { listener }
