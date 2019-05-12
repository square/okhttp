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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

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
