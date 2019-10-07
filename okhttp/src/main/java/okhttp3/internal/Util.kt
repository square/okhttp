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

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.http2.Header
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Options
import okio.Source
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_16BE
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.UTF_32BE
import kotlin.text.Charsets.UTF_32LE

@JvmField
val EMPTY_BYTE_ARRAY = ByteArray(0)
@JvmField
val EMPTY_HEADERS = headersOf()

@JvmField
val EMPTY_RESPONSE = EMPTY_BYTE_ARRAY.toResponseBody()
@JvmField
val EMPTY_REQUEST = EMPTY_BYTE_ARRAY.toRequestBody()

/** Byte order marks. */
private val UNICODE_BOMS = Options.of(
    "efbbbf".decodeHex(), // UTF-8
    "feff".decodeHex(), // UTF-16BE
    "fffe".decodeHex(), // UTF-16LE
    "0000ffff".decodeHex(), // UTF-32BE
    "ffff0000".decodeHex() // UTF-32LE
)

/** GMT and UTC are equivalent for our purposes. */
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
 * Returns an array containing only elements found in this array and also in [other]. The returned
 * elements are in the same order as in this.
 */
fun Array<String>.intersect(
  other: Array<String>,
  comparator: Comparator<in String>
): Array<String> {
  val result = mutableListOf<String>()
  for (a in this) {
    for (b in other) {
      if (comparator.compare(a, b) == 0) {
        result.add(a)
        break
      }
    }
  }
  return result.toTypedArray()
}

/**
 * Returns true if there is an element in this array that is also in [other]. This method terminates
 * if any intersection is found. The sizes of both arguments are assumed to be so small, and the
 * likelihood of an intersection so great, that it is not worth the CPU cost of sorting or the
 * memory cost of hashing.
 */
fun Array<String>.hasIntersection(
  other: Array<String>?,
  comparator: Comparator<in String>
): Boolean {
  if (isEmpty() || other == null || other.isEmpty()) {
    return false
  }
  for (a in this) {
    for (b in other) {
      if (comparator.compare(a, b) == 0) {
        return true
      }
    }
  }
  return false
}

fun HttpUrl.toHostHeader(includeDefaultPort: Boolean = false): String {
  val host = if (":" in host) {
    "[$host]"
  } else {
    host
  }
  return if (includeDefaultPort || port != HttpUrl.defaultPort(scheme)) {
    "$host:$port"
  } else {
    host
  }
}

fun Array<String>.indexOf(value: String, comparator: Comparator<String>): Int =
    indexOfFirst { comparator.compare(it, value) == 0 }

@Suppress("UNCHECKED_CAST")
fun Array<String>.concat(value: String): Array<String> {
  val result = copyOf(size + 1)
  result[result.lastIndex] = value
  return result as Array<String>
}

/**
 * Increments [startIndex] until this string is not ASCII whitespace. Stops at [endIndex].
 */
fun String.indexOfFirstNonAsciiWhitespace(startIndex: Int = 0, endIndex: Int = length): Int {
  for (i in startIndex until endIndex) {
    when (this[i]) {
      '\t', '\n', '\u000C', '\r', ' ' -> Unit
      else -> return i
    }
  }
  return endIndex
}

/**
 * Decrements [endIndex] until `input[endIndex - 1]` is not ASCII whitespace. Stops at [startIndex].
 */
fun String.indexOfLastNonAsciiWhitespace(startIndex: Int = 0, endIndex: Int = length): Int {
  for (i in endIndex - 1 downTo startIndex) {
    when (this[i]) {
      '\t', '\n', '\u000C', '\r', ' ' -> Unit
      else -> return i + 1
    }
  }
  return startIndex
}

/** Equivalent to `string.substring(startIndex, endIndex).trim()`. */
fun String.trimSubstring(startIndex: Int = 0, endIndex: Int = length): String {
  val start = indexOfFirstNonAsciiWhitespace(startIndex, endIndex)
  val end = indexOfLastNonAsciiWhitespace(start, endIndex)
  return substring(start, end)
}

/**
 * Returns the index of the first character in this string that contains a character in
 * [delimiters]. Returns endIndex if there is no such character.
 */
fun String.delimiterOffset(delimiters: String, startIndex: Int = 0, endIndex: Int = length): Int {
  for (i in startIndex until endIndex) {
    if (this[i] in delimiters) return i
  }
  return endIndex
}

/**
 * Returns the index of the first character in this string that is [delimiter]. Returns [endIndex]
 * if there is no such character.
 */
fun String.delimiterOffset(delimiter: Char, startIndex: Int = 0, endIndex: Int = length): Int {
  for (i in startIndex until endIndex) {
    if (this[i] == delimiter) return i
  }
  return endIndex
}

/**
 * Returns the index of the first character in this string that is either a control character (like
 * `\u0000` or `\n`) or a non-ASCII character. Returns -1 if this string has no such characters.
 */
fun String.indexOfControlOrNonAscii(): Int {
  for (i in 0 until length) {
    val c = this[i]
    if (c <= '\u001f' || c >= '\u007f') {
      return i
    }
  }
  return -1
}

/** Returns true if this string is not a host name and might be an IP address. */
fun String.canParseAsIpAddress(): Boolean {
  return VERIFY_AS_IP_ADDRESS.matches(this)
}

/** Returns a [Locale.US] formatted [String]. */
fun format(format: String, vararg args: Any): String {
  return String.format(Locale.US, format, *args)
}

@Throws(IOException::class)
fun BufferedSource.readBomAsCharset(default: Charset): Charset {
  return when (select(UNICODE_BOMS)) {
    0 -> UTF_8
    1 -> UTF_16BE
    2 -> UTF_16LE
    3 -> UTF_32BE
    4 -> UTF_32LE
    -1 -> default
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

fun Char.parseHexDigit(): Int = when (this) {
  in '0'..'9' -> this - '0'
  in 'a'..'f' -> this - 'a' + 10
  in 'A'..'F' -> this - 'A' + 10
  else -> -1
}

fun List<Header>.toHeaders(): Headers {
  val builder = Headers.Builder()
  for ((name, value) in this) {
    builder.addLenient(name.utf8(), value.utf8())
  }
  return builder.build()
}

fun Headers.toHeaderList(): List<Header> = (0 until size).map {
  Header(name(it), value(it))
}

/** Returns true if an HTTP request for this URL and [other] can reuse a connection. */
fun HttpUrl.canReuseConnectionFor(other: HttpUrl): Boolean = host == other.host &&
    port == other.port &&
    scheme == other.scheme

fun EventListener.asFactory() = object : EventListener.Factory {
  override fun create(call: Call): EventListener = this@asFactory
}

infix fun Byte.and(mask: Int): Int = toInt() and mask
infix fun Short.and(mask: Int): Int = toInt() and mask
infix fun Int.and(mask: Long): Long = toLong() and mask

@Throws(IOException::class)
fun BufferedSink.writeMedium(medium: Int) {
  writeByte(medium.ushr(16) and 0xff)
  writeByte(medium.ushr(8) and 0xff)
  writeByte(medium and 0xff)
}

@Throws(IOException::class)
fun BufferedSource.readMedium(): Int {
  return (readByte() and 0xff shl 16
      or (readByte() and 0xff shl 8)
      or (readByte() and 0xff))
}

/**
 * Reads until this is exhausted or the deadline has been reached. This is careful to not extend the
 * deadline if one exists already.
 */
@Throws(IOException::class)
fun Source.skipAll(duration: Int, timeUnit: TimeUnit): Boolean {
  val now = System.nanoTime()
  val originalDuration = if (timeout().hasDeadline()) {
    timeout().deadlineNanoTime() - now
  } else {
    Long.MAX_VALUE
  }
  timeout().deadlineNanoTime(now + minOf(originalDuration, timeUnit.toNanos(duration.toLong())))
  return try {
    val skipBuffer = Buffer()
    while (read(skipBuffer, 8192) != -1L) {
      skipBuffer.clear()
    }
    true // Success! The source has been exhausted.
  } catch (_: InterruptedIOException) {
    false // We ran out of time before exhausting the source.
  } finally {
    if (originalDuration == Long.MAX_VALUE) {
      timeout().clearDeadline()
    } else {
      timeout().deadlineNanoTime(now + originalDuration)
    }
  }
}

/**
 * Attempts to exhaust this, returning true if successful. This is useful when reading a complete
 * source is helpful, such as when doing so completes a cache body or frees a socket connection for
 * reuse.
 */
fun Source.discard(timeout: Int, timeUnit: TimeUnit): Boolean = try {
  this.skipAll(timeout, timeUnit)
} catch (_: IOException) {
  false
}

fun Socket.peerName(): String {
  val address = remoteSocketAddress
  return if (address is InetSocketAddress) address.hostName else address.toString()
}

/** Run [block] until it either throws an [IOException] or completes. */
inline fun ignoreIoExceptions(block: () -> Unit) {
  try {
    block()
  } catch (_: IOException) {
  }
}

inline fun threadName(name: String, block: () -> Unit) {
  val currentThread = Thread.currentThread()
  val oldName = currentThread.name
  currentThread.name = name
  try {
    block()
  } finally {
    currentThread.name = oldName
  }
}

fun Buffer.skipAll(b: Byte): Int {
  var count = 0
  while (!exhausted() && this[0] == b) {
    count++
    readByte()
  }
  return count
}

/**
 * Returns the index of the next non-whitespace character in this. Result is undefined if input
 * contains newline characters.
 */
fun String.indexOfNonWhitespace(startIndex: Int = 0): Int {
  for (i in startIndex until length) {
    val c = this[i]
    if (c != ' ' && c != '\t') {
      return i
    }
  }
  return length
}

/** Returns the Content-Length as reported by the response headers. */
fun Response.headersContentLength(): Long {
  return headers["Content-Length"]?.toLongOrDefault(-1L) ?: -1L
}

fun String.toLongOrDefault(defaultValue: Long): Long {
  return try {
    toLong()
  } catch (_: NumberFormatException) {
    defaultValue
  }
}

/**
 * Returns this as a non-negative integer, or 0 if it is negative, or [Int.MAX_VALUE] if it is too
 * large, or [defaultValue] if it cannot be parsed.
 */
fun String?.toNonNegativeInt(defaultValue: Int): Int {
  try {
    val value = this?.toLong() ?: return defaultValue
    return when {
      value > Int.MAX_VALUE -> Int.MAX_VALUE
      value < 0 -> 0
      else -> value.toInt()
    }
  } catch (_: NumberFormatException) {
    return defaultValue
  }
}

/** Returns an immutable copy of this. */
fun <T> List<T>.toImmutableList(): List<T> {
  return Collections.unmodifiableList(toMutableList())
}

/** Returns an immutable list containing [elements]. */
@SafeVarargs
fun <T> immutableListOf(vararg elements: T): List<T> {
  return Collections.unmodifiableList(Arrays.asList(*elements.clone()))
}

/** Returns an immutable copy of this. */
fun <K, V> Map<K, V>.toImmutableMap(): Map<K, V> {
  return if (isEmpty()) {
    emptyMap()
  } else {
    Collections.unmodifiableMap(LinkedHashMap(this))
  }
}

/** Closes this, ignoring any checked exceptions. */
fun Closeable.closeQuietly() {
  try {
    close()
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

/** Closes this, ignoring any checked exceptions. */
fun Socket.closeQuietly() {
  try {
    close()
  } catch (e: AssertionError) {
    throw e
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

/** Closes this, ignoring any checked exceptions.  */
fun ServerSocket.closeQuietly() {
  try {
    close()
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

fun Long.toHexString(): String = java.lang.Long.toHexString(this)

fun Int.toHexString(): String = Integer.toHexString(this)

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
inline fun Any.wait() = (this as Object).wait()

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
inline fun Any.notify() = (this as Object).notify()

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
inline fun Any.notifyAll() = (this as Object).notifyAll()

fun <T> readFieldOrNull(instance: Any, fieldType: Class<T>, fieldName: String): T? {
  var c: Class<*> = instance.javaClass
  while (c != Any::class.java) {
    try {
      val field = c.getDeclaredField(fieldName)
      field.isAccessible = true
      val value = field.get(instance)
      return if (!fieldType.isInstance(value)) null else fieldType.cast(value)
    } catch (_: NoSuchFieldException) {
    }

    c = c.superclass
  }

  // Didn't find the field we wanted. As a last gasp attempt,
  // try to find the value on a delegate.
  if (fieldName != "delegate") {
    val delegate = readFieldOrNull(instance, Any::class.java, "delegate")
    if (delegate != null) return readFieldOrNull(delegate, fieldType, fieldName)
  }

  return null
}

internal fun <E> MutableList<E>.addIfAbsent(element: E) {
  if (!contains(element)) add(element)
}
