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
package okhttp3.internal

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

/** Execute [block], setting the executing thread's name to [name] for the duration. */
inline fun Executor.execute(name: String, crossinline block: () -> Unit) {
  execute(object : NamedRunnable("%s", name) {
    override fun execute() {
      block()
    }
  })
}

/** Executes [block] unless this executor has been shutdown, in which case this does nothing. */
inline fun Executor.tryExecute(name: String, crossinline block: () -> Unit) {
  try {
    execute(name, block)
  } catch (_: RejectedExecutionException) {
  }
}
