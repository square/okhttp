/*
 * Copyright (C) 2025 Square, Inc.
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
package mockwebserver3.internal

import java.io.Closeable
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.internal.connection.BufferedSocket
import okhttp3.internal.platform.Platform
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.asOkioSocket
import okio.buffer

/**
 * Adapts a [java.net.Socket] to MockWebServer's needs.
 *
 * Note that [asOkioSocket] returns a socket that closes the underlying [java.net.Socket] when both
 * of its component streams are closed. This class takes advantage of that.
 */
internal class MockWebServerSocket(
  val javaNetSocket: Socket,
) : Closeable,
  BufferedSocket {
  private val delegate = javaNetSocket.asOkioSocket()
  private val closedLatch = CountDownLatch(2)

  override val source: BufferedSource =
    object : ForwardingSource(delegate.source) {
      private var closed = false

      override fun close() {
        if (closed) return
        try {
          super.close()
        } finally {
          closedLatch.countDown()
        }
      }
    }.buffer()

  override val sink: BufferedSink =
    object : ForwardingSink(delegate.sink) {
      private var closed = false

      override fun close() {
        if (closed) return
        try {
          super.close()
        } finally {
          closedLatch.countDown()
        }
      }
    }.buffer()

  val localAddress: InetAddress
    get() = javaNetSocket.localAddress

  val localPort: Int
    get() = javaNetSocket.localPort

  val scheme: String
    get() =
      when (javaNetSocket) {
        is SSLSocket -> "https"
        else -> "http"
      }

  val handshake: Handshake?
    get() = (javaNetSocket as? SSLSocket)?.session?.handshake()

  val handshakeServerNames: List<String>
    get() =
      (javaNetSocket as? SSLSocket)
        ?.let { Platform.Companion.get().getHandshakeServerNames(it) }
        ?: listOf()

  fun shutdownInput() {
    javaNetSocket.shutdownInput()
  }

  fun shutdownOutput() {
    javaNetSocket.shutdownOutput()
  }

  /** Sleeps [nanos], throwing if the socket is closed before that period has elapsed. */
  fun sleepWhileOpen(nanos: Long) {
    var ms = nanos / 1_000_000L
    val ns = nanos - (ms * 1_000_000L)

    while (ms > 100) {
      Thread.sleep(100)
      if (javaNetSocket.isClosed) throw InterruptedIOException("socket closed")
      ms -= 100L
    }

    if (ms > 0L || ns > 0) {
      Thread.sleep(ms, ns.toInt())
    }
  }

  override fun cancel() {
    delegate.cancel()
  }

  override fun close() {
    javaNetSocket.close()
  }

  fun awaitClosed() {
    closedLatch.await()
  }
}
