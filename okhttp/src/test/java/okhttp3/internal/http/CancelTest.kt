/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.CallEvent
import okhttp3.CallEvent.CallEnd
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.Canceled
import okhttp3.CallEvent.ConnectEnd
import okhttp3.CallEvent.ConnectStart
import okhttp3.CallEvent.ConnectionAcquired
import okhttp3.CallEvent.ConnectionReleased
import okhttp3.CallEvent.RequestFailed
import okhttp3.CallEvent.ResponseFailed
import okhttp3.DelegatingServerSocketFactory
import okhttp3.DelegatingSocketFactory
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol.HTTP_1_1
import okhttp3.RecordingEventListener
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.SimpleProvider
import okhttp3.internal.http.CancelTest.CancelMode.CANCEL
import okhttp3.internal.http.CancelTest.CancelMode.INTERRUPT
import okhttp3.internal.http.CancelTest.ConnectionType.H2
import okhttp3.internal.http.CancelTest.ConnectionType.HTTP
import okhttp3.internal.http.CancelTest.ConnectionType.HTTPS
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import okio.Buffer
import okio.BufferedSink
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ServerSocketFactory
import javax.net.SocketFactory

@Timeout(30)
@Tag("Slow")
class CancelTest {
  @JvmField @RegisterExtension val platform = PlatformRule()

  lateinit var cancelMode: CancelMode
  lateinit var connectionType: ConnectionType

  private var threadToCancel: Thread? = null

  enum class CancelMode {
    CANCEL,
    INTERRUPT
  }

  enum class ConnectionType {
    H2,
    HTTPS,
    HTTP
  }

  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule()
  val handshakeCertificates = TlsUtil.localhost()

  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient

  val listener = RecordingEventListener()

  fun setUp(mode: Pair<CancelMode, ConnectionType>) {
    this.cancelMode = mode.first
    this.connectionType = mode.second

    if (connectionType == H2) {
      platform.assumeHttp2Support()
    }

    platform.assumeNotBouncyCastle()

    // Sockets on some platforms can have large buffers that mean writes do not block when
    // required. These socket factories explicitly set the buffer sizes on sockets created.
    server = MockWebServer()
    server.serverSocketFactory =
      object : DelegatingServerSocketFactory(ServerSocketFactory.getDefault()) {
        @Throws(IOException::class) override fun configureServerSocket(
          serverSocket: ServerSocket
        ): ServerSocket {
          serverSocket.receiveBufferSize = SOCKET_BUFFER_SIZE
          return serverSocket
        }
      }
    if (connectionType != HTTP) {
      server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    }
    server.start()

    client = clientTestRule.newClientBuilder()
        .socketFactory(object : DelegatingSocketFactory(SocketFactory.getDefault()) {
          @Throws(IOException::class)
          override fun configureSocket(socket: Socket): Socket {
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            return socket
          }
        })
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
        )
        .eventListener(listener)
        .apply {
          if (connectionType == HTTPS) { protocols(listOf(HTTP_1_1)) }
        }
        .build()
    threadToCancel = Thread.currentThread()
  }

  @ParameterizedTest
  @ArgumentsSource(CancelModelParamProvider::class)
  fun cancelWritingRequestBody(mode: Pair<CancelMode, ConnectionType>) {
    setUp(mode)
    server.enqueue(MockResponse())
    val call = client.newCall(
      Request.Builder()
        .url(server.url("/"))
        .post(object : RequestBody() {
          override fun contentType(): MediaType? {
            return null
          }

          @Throws(
            IOException::class
          ) override fun writeTo(sink: BufferedSink) {
            for (i in 0..9) {
              sink.writeByte(0)
              sink.flush()
              sleep(100)
            }
            fail("Expected connection to be closed")
          }
        })
        .build()
    )
    cancelLater(call, 500)
    try {
      call.execute()
      fail("")
    } catch (expected: IOException) {
      assertEquals(cancelMode == INTERRUPT, Thread.interrupted())
    }
  }

  @ParameterizedTest
  @ArgumentsSource(CancelModelParamProvider::class)
  fun cancelReadingResponseBody(mode: Pair<CancelMode, ConnectionType>) {
    setUp(mode)
    val responseBodySize = 8 * 1024 * 1024 // 8 MiB.
    server.enqueue(
      MockResponse()
        .setBody(
          Buffer()
            .write(ByteArray(responseBodySize))
        )
        .throttleBody(64 * 1024, 125, MILLISECONDS)
    ) // 500 Kbps
    val call = client.newCall(
      Request.Builder()
        .url(server.url("/"))
        .build()
    )
    val response = call.execute()
    cancelLater(call, 500)
    val responseBody = response.body!!.byteStream()
    val buffer = ByteArray(1024)
    try {
      while (responseBody.read(buffer) != -1) {
      }
      fail("Expected connection to be closed")
    } catch (expected: IOException) {
      assertEquals(cancelMode == INTERRUPT, Thread.interrupted())
    }
    responseBody.close()
    assertEquals(if (connectionType == H2) 1 else 0, client.connectionPool.connectionCount())
  }

  @ParameterizedTest
  @ArgumentsSource(CancelModelParamProvider::class)
  fun cancelAndFollowup(mode: Pair<CancelMode, ConnectionType>) {
    setUp(mode)
    val responseBodySize = 8 * 1024 * 1024 // 8 MiB.
    server.enqueue(
      MockResponse()
        .setBody(
          Buffer()
            .write(ByteArray(responseBodySize))
        )
        .throttleBody(64 * 1024, 125, MILLISECONDS)
    ) // 500 Kbps
    server.enqueue(MockResponse().apply {
      setResponseCode(200)
      setBody(".")
    })

    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    cancelLater(call, 500)
    val responseBody = response.body!!.byteStream()
    val buffer = ByteArray(1024)
    try {
      while (responseBody.read(buffer) != -1) {
      }
      fail("Expected connection to be closed")
    } catch (expected: IOException) {
      assertEquals(cancelMode == INTERRUPT, Thread.interrupted())
    }
    responseBody.close()
    assertEquals(if (connectionType == H2) 1 else 0, client.connectionPool.connectionCount())

    val events = listener.eventSequence.filter { isConnectionEvent(it) }.map { it.name }
    listener.clearAllEvents()

    assertThat(events).startsWith("CallStart", "ConnectStart", "ConnectEnd", "ConnectionAcquired")
    if (cancelMode == CANCEL) {
      // Flaky https://github.com/square/okhttp/issues/6033
      // assertThat(events).contains("Canceled")
    } else {
      assertThat(events).doesNotContain("Canceled")
    }
    assertThat(events).contains("ResponseFailed")
    assertThat(events).endsWith("ConnectionReleased")

    val call2 = client.newCall(Request.Builder().url(server.url("/")).build())
    call2.execute().use {
      assertEquals(".", it.body!!.string())
    }

    val events2 = listener.eventSequence.filter { isConnectionEvent(it) }.map { it.name }
    val expectedEvents2 = mutableListOf<String>().apply {
      add("CallStart")
      if (connectionType != H2) {
        addAll(listOf("ConnectStart", "ConnectEnd"))
      }
      addAll(listOf("ConnectionAcquired", "ConnectionReleased", "CallEnd"))
    }

    assertThat(events2).isEqualTo(expectedEvents2)
  }

  private fun isConnectionEvent(it: CallEvent?) =
    it is CallStart ||
        it is CallEnd ||
        it is ConnectStart ||
        it is ConnectEnd ||
        it is ConnectionAcquired ||
        it is ConnectionReleased ||
        it is Canceled ||
        it is RequestFailed ||
        it is ResponseFailed

  private fun sleep(delayMillis: Int) {
    try {
      Thread.sleep(delayMillis.toLong())
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun cancelLater(
    call: Call,
    delayMillis: Int
  ) {
    Thread(Runnable {
      sleep(delayMillis)
      if (cancelMode == CANCEL) {
        call.cancel()
      } else {
        threadToCancel!!.interrupt()
      }
    }).apply { start() }
  }

  companion object {
    // The size of the socket buffers in bytes.
    private const val SOCKET_BUFFER_SIZE = 256 * 1024
  }
}

class CancelModelParamProvider: SimpleProvider() {
  override fun arguments() = CancelTest.CancelMode.values().flatMap { c -> CancelTest.ConnectionType.values().map { x -> Pair(
    c, x
  ) } }
}
