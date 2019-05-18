/*
 * Copyright (C) 2019 Square, Inc.
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
@file:JvmName("UtilKt")
package okhttp3.internal

import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Source
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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

fun Socket.connectionName(): String {
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

/** Execute [block], setting the executing thread's name to [name] for the duration. */
inline fun Executor.execute(name: String, crossinline block: () -> Unit) {
  execute {
    threadName(name) {
      block()
    }
  }
}

/** Executes [block] unless this executor has been shutdown, in which case this does nothing. */
inline fun Executor.tryExecute(name: String, crossinline block: () -> Unit) {
  try {
    execute(name, block)
  } catch (_: RejectedExecutionException) {
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
  return headers()["Content-Length"]?.toLongOrDefault(-1L) ?: -1L
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

/** Closes this, ignoring any checked exceptions. Does nothing if this is null. */
fun Closeable?.closeQuietly() {
  try {
    this?.close()
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

/** Closes this, ignoring any checked exceptions. Does nothing if this is null. */
fun Socket?.closeQuietly() {
  try {
    this?.close()
  } catch (e: AssertionError) {
    if (!isAndroidGetsocknameError(e)) throw e
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

/** Closes this, ignoring any checked exceptions. Does nothing if this is null. */
fun ServerSocket?.closeQuietly() {
  try {
    this?.close()
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

/**
 * Returns true if [e] is due to a firmware bug fixed after Android 4.2.2.
 * https://code.google.com/p/android/issues/detail?id=54072
 */
fun isAndroidGetsocknameError(e: AssertionError): Boolean {
  return e.cause != null && e.message?.contains("getsockname failed") == true
}

fun Long.toHexString() = java.lang.Long.toHexString(this)

fun Int.toHexString() = Integer.toHexString(this)
