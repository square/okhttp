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
package okhttp3.internal.http

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Cache
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.RecordingCallback
import okhttp3.RecordingEventListener
import okhttp3.Request
import okhttp3.TestLogHandler
import okhttp3.internal.UnreadableResponseBody
import okhttp3.internal.closeQuietly
import okhttp3.internal.duplex.MockSocketHandler
import okhttp3.okio.LoggingFilesystem
import okhttp3.testing.PlatformRule
import okio.Path.Companion.toPath
import okio.Socket
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class HttpUpgradesTest {
  private val fileSystem = FakeFileSystem()

  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  val testLogHandler = TestLogHandler(OkHttpClient::class.java)

  @StartStop
  private val server = MockWebServer()

  @StartStop
  private val server2 = MockWebServer()

  private var listener = RecordingEventListener()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private var client =
    clientTestRule
      .newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()
  private val callback = RecordingCallback()
  private val cache =
    Cache(
      fileSystem = LoggingFilesystem(fileSystem),
      directory = "/cache".toPath(),
      maxSize = Int.MAX_VALUE.toLong(),
    )

  @BeforeEach
  fun setUp() {
  }

  @AfterEach
  @Throws(Exception::class)
  fun tearDown() {
  }

  @Test
  fun upgradeRefusedByServer() {
    server.enqueue(MockResponse(body = "normal request"))
    val requestWithUpgrade =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Connection", "upgrade")
        .header("Upgrade", "tcp")
        .build()
    val response = client.newCall(requestWithUpgrade).execute()
    response.body.string()
    assertThat(response.code).isEqualTo(200)
  }

  @Test
  fun upgradesOnReusedConnection() {
    server.enqueue(MockResponse(body = "normal request"))
    server.enqueue(
      MockResponse
        .Builder()
        .code(HTTP_SWITCHING_PROTOCOLS)
        .headers(
          headersOf(
            "Connection",
            "upgrade",
            "Upgrade",
            "tcp",
            "Content-Type",
            "text/plain; charset=UTF-8",
          ),
        ).socketHandler(MockSocketHandler())
        .build(),
    )
    val request = Request(server.url("/"))
    val requestWithUpgrade =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Connection", "upgrade")
        .header("Upgrade", "tcp")
        .build()
    assertConnectionReused(request, requestWithUpgrade)
  }

  // copied from okhttp3.ConnectionReuseTest.assertConnectionReused
  private fun assertConnectionReused(vararg requests: Request?) {
    for (i in requests.indices) {
      val response = client.newCall(requests[i]!!).execute()
      if (response.code == HTTP_SWITCHING_PROTOCOLS) {
        response.exchange!!.cancel()
      } else {
        response.body.string() // Discard the response body.
      }
      assertThat(server.takeRequest().exchangeIndex).isEqualTo(i)
    }
  }

  @Test
  fun upgradeConnection() {
    val mockStreamHandler =
      MockSocketHandler()
        .receiveRequest("request A\n")
        .sendResponse("response B\n")
        .receiveRequest("request C\n")
        .sendResponse("response D\n")
        .sendResponse("response E\n")
        .receiveRequest("response F\n")
        .exhaustRequest()
        .exhaustResponse()
    server.enqueue(
      MockResponse
        .Builder()
        .code(HTTP_SWITCHING_PROTOCOLS)
        .headers(
          headersOf(
            "Connection",
            "upgrade",
            "Upgrade",
            "tcp",
            "Content-Type",
            "text/plain; charset=UTF-8",
//            "Content-Type", "application/vnd.docker.raw-stream",
          ),
        ).socketHandler(mockStreamHandler)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .header("Connection", "upgrade")
          .header("Upgrade", "tcp")
          // .post(...)
          .build(),
      )

    var socket: Socket?
    val received: BlockingQueue<String?> = LinkedBlockingQueue<String?>()

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(HTTP_SWITCHING_PROTOCOLS)
      assertThat(response.headers("Connection").first()).isEqualTo("upgrade", true)
      assertThat(response.headers("Upgrade").first()).isEqualTo("tcp", true)
      assertThat(response.headers("Content-Type").first().toMediaType()).isEqualTo("text/plain; charset=UTF-8".toMediaType())
//      assertThat(response.headers("Content-Type").first().toMediaType()).isEqualTo("application/vnd.docker.raw-stream".toMediaType())
      assertThat(response.headers("Content-Length")).isEmpty()
      assertThat(response.body).isInstanceOf<UnreadableResponseBody>()

      socket = response.socket
      assertThat(socket).isNotNull()

      val reader = socket!!.source
      val readerThread =
        object : Thread("reader") {
          override fun run() {
            try {
              var line: String?
              while (reader.buffer().readUtf8Line().also { line = it } != null) {
                received.add(line)
              }
            } catch (e: Exception) {
              reader.closeQuietly()
            }
          }
        }
      readerThread.start()

      val writer = socket.sink
      writer.buffer().writeUtf8("request A\n").flush()
      writer.buffer().writeUtf8("request C\n").flush()
      writer.buffer().writeUtf8("response F\n").flush()
    }

    val responses = mutableListOf<String?>()
    responses.add(received.poll(2, TimeUnit.SECONDS))
    responses.add(received.poll(2, TimeUnit.SECONDS))
    responses.add(received.poll(2, TimeUnit.SECONDS))
    assertThat(responses).containsExactly(
      "response B",
      "response D",
      "response E",
    )

    socket?.cancel()
  }
}
