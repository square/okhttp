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

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.UTF_16BE
import kotlin.text.Charsets.UTF_16LE
import kotlin.text.Charsets.UTF_32BE
import kotlin.text.Charsets.UTF_32LE
import kotlin.text.Charsets.UTF_8
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.http2.Header
import okio.Buffer
import okio.BufferedSource
import okio.Source

@JvmField
val EMPTY_HEADERS: Headers = headersOf()
@JvmField
val EMPTY_RESPONSE: ResponseBody = EMPTY_BYTE_ARRAY.toResponseBody()
@JvmField
val EMPTY_REQUEST: RequestBody = EMPTY_BYTE_ARRAY.toRequestBody()

/** GMT and UTC are equivalent for our purposes. */
@JvmField
internal val UTC: TimeZone = TimeZone.getTimeZone("GMT")!!

internal fun checkOffsetAndCount(arrayLength: Long, offset: Long, count: Long) {
  if (offset or count < 0L || offset > arrayLength || arrayLength - offset < count) {
    throw ArrayIndexOutOfBoundsException("length=$arrayLength, offset=$offset, count=$offset")
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

internal fun HttpUrl.toHostHeader(includeDefaultPort: Boolean = false): String {
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

/** Returns a [Locale.US] formatted [String]. */
fun format(format: String, vararg args: Any): String {
  return String.format(Locale.US, format, *args)
}

@Throws(IOException::class)
internal fun BufferedSource.readBomAsCharset(default: Charset): Charset {
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

internal fun checkDuration(name: String, duration: Long, unit: TimeUnit?): Int {
  check(duration >= 0L) { "$name < 0" }
  check(unit != null) { "unit == null" }
  val millis = unit.toMillis(duration)
  require(millis <= Integer.MAX_VALUE) { "$name too large." }
  require(millis != 0L || duration <= 0L) { "$name too small." }
  return millis.toInt()
}

internal fun List<Header>.toHeaders(): Headers {
  val builder = Headers.Builder()
  for ((name, value) in this) {
    builder.addLenient(name.utf8(), value.utf8())
  }
  return builder.build()
}

internal fun Headers.toHeaderList(): List<Header> = (0 until size).map {
  Header(name(it), value(it))
}

/** Returns true if an HTTP request for this URL and [other] can reuse a connection. */
internal fun HttpUrl.canReuseConnectionFor(other: HttpUrl): Boolean = host == other.host &&
    port == other.port &&
    scheme == other.scheme

internal fun EventListener.asFactory() = EventListener.Factory { this }

/**
 * Reads until this is exhausted or the deadline has been reached. This is careful to not extend the
 * deadline if one exists already.
 */
@Throws(IOException::class)
internal fun Source.skipAll(duration: Int, timeUnit: TimeUnit): Boolean {
  val nowNs = System.nanoTime()
  val originalDurationNs = if (timeout().hasDeadline()) {
    timeout().deadlineNanoTime() - nowNs
  } else {
    Long.MAX_VALUE
  }
  timeout().deadlineNanoTime(nowNs + minOf(originalDurationNs, timeUnit.toNanos(duration.toLong())))
  return try {
    val skipBuffer = Buffer()
    while (read(skipBuffer, 8192) != -1L) {
      skipBuffer.clear()
    }
    true // Success! The source has been exhausted.
  } catch (_: InterruptedIOException) {
    false // We ran out of time before exhausting the source.
  } finally {
    if (originalDurationNs == Long.MAX_VALUE) {
      timeout().clearDeadline()
    } else {
      timeout().deadlineNanoTime(nowNs + originalDurationNs)
    }
  }
}

/**
 * Attempts to exhaust this, returning true if successful. This is useful when reading a complete
 * source is helpful, such as when doing so completes a cache body or frees a socket connection for
 * reuse.
 */
internal fun Source.discard(timeout: Int, timeUnit: TimeUnit): Boolean = try {
  this.skipAll(timeout, timeUnit)
} catch (_: IOException) {
  false
}

fun Socket.peerName(): String {
  val address = remoteSocketAddress
  return if (address is InetSocketAddress) address.hostName else address.toString()
}

/**
 * Returns true if new reads and writes should be attempted on this.
 *
 * Unfortunately Java's networking APIs don't offer a good health check, so we go on our own by
 * attempting to read with a short timeout. If the fails immediately we know the socket is
 * unhealthy.
 *
 * @param source the source used to read bytes from the socket.
 */
internal fun Socket.isHealthy(source: BufferedSource): Boolean {
  return try {
    val readTimeout = soTimeout
    try {
      soTimeout = 1
      !source.exhausted()
    } finally {
      soTimeout = readTimeout
    }
  } catch (_: SocketTimeoutException) {
    true // Read timed out; socket is good.
  } catch (_: IOException) {
    false // Couldn't read; socket is closed.
  }
}

internal inline fun threadName(name: String, block: () -> Unit) {
  val currentThread = Thread.currentThread()
  val oldName = currentThread.name
  currentThread.name = name
  try {
    block()
  } finally {
    currentThread.name = oldName
  }
}

/** Returns the Content-Length as reported by the response headers. */
internal fun Response.headersContentLength(): Long {
  return headers["Content-Length"]?.toLongOrDefault(-1L) ?: -1L
}

/** Returns an immutable copy of this. */
fun <T> List<T>.toImmutableList(): List<T> {
  return Collections.unmodifiableList(toMutableList())
}

/** Returns an immutable list containing [elements]. */
@SafeVarargs
fun <T> immutableListOf(vararg elements: T): List<T> {
  return Collections.unmodifiableList(listOf(*elements.clone()))
}

/** Returns an immutable copy of this. */
internal fun <K, V> Map<K, V>.toImmutableMap(): Map<K, V> {
  return if (isEmpty()) {
    emptyMap()
  } else {
    Collections.unmodifiableMap(LinkedHashMap(this))
  }
}

/** Closes this, ignoring any checked exceptions. */
internal fun Socket.closeQuietly() {
  try {
    close()
  } catch (e: AssertionError) {
    throw e
  } catch (rethrown: RuntimeException) {
    if (rethrown.message == "bio == null") {
      // Conscrypt in Android 10 and 11 may throw closing an SSLSocket. This is safe to ignore.
      // https://issuetracker.google.com/issues/177450597
      return
    }
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

internal fun Long.toHexString(): String = java.lang.Long.toHexString(this)

internal fun Int.toHexString(): String = Integer.toHexString(this)

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
internal inline fun Any.wait() = (this as Object).wait()

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
internal inline fun Any.notify() = (this as Object).notify()

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")
internal inline fun Any.notifyAll() = (this as Object).notifyAll()

internal fun <T> readFieldOrNull(instance: Any, fieldType: Class<T>, fieldName: String): T? {
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

@JvmField
internal val assertionsEnabled: Boolean = OkHttpClient::class.java.desiredAssertionStatus()

/**
 * Returns the string "OkHttp" unless the library has been shaded for inclusion in another library,
 * or obfuscated with tools like R8 or ProGuard. In such cases it'll return a longer string like
 * "com.example.shaded.okhttp3.OkHttp". In large applications it's possible to have multiple OkHttp
 * instances; this makes it clear which is which.
 */
@JvmField
internal val okHttpName: String =
    OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client")

@Suppress("NOTHING_TO_INLINE")
internal inline fun Any.assertThreadHoldsLock() {
  if (assertionsEnabled && !Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST hold lock on $this")
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Any.assertThreadDoesntHoldLock() {
  if (assertionsEnabled && Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST NOT hold lock on $this")
  }
}
