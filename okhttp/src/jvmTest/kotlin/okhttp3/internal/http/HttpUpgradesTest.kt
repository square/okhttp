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
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.RecordingEventListener
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.duplex.MockSocketHandler
import okhttp3.testing.PlatformRule
import okio.ProtocolException
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class HttpUpgradesTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private var listener = RecordingEventListener()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private var client =
    clientTestRule
      .newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()

  fun executeAndCheckUpgrade(request: Request) {
    val socketHandler =
      MockSocketHandler()
        .apply {
          receiveRequest("client says hello\n")
          sendResponse("server says hello\n")
          receiveRequest("client says goodbye\n")
          sendResponse("server says goodbye\n")
          exhaustResponse()
          exhaustRequest()
        }
    server.enqueue(socketHandler.upgradeResponse())

    client
      .newCall(request)
      .execute()
      .use { response ->
        assertThat(response.code).isEqualTo(HTTP_SWITCHING_PROTOCOLS)
        val socket = response.socket!!
        socket.sink.buffer().use { sink ->
          socket.source.buffer().use { source ->
            sink.writeUtf8("client says hello\n")
            sink.flush()

            assertThat(source.readUtf8Line()).isEqualTo("server says hello")

            sink.writeUtf8("client says goodbye\n")
            sink.flush()

            assertThat(source.readUtf8Line()).isEqualTo("server says goodbye")

            assertThat(source.exhausted()).isTrue()
          }
        }
        socketHandler.awaitSuccess()
      }
  }

  @Test
  fun upgrade() {
    executeAndCheckUpgrade(upgradeRequest())
  }

  @Test
  fun upgradeWithRequestBody() {
    executeAndCheckUpgrade(upgradeRequest().newBuilder().post(RequestBody.EMPTY).build())
  }

  @Test
  fun upgradeHttps() {
    enableTls(Protocol.HTTP_1_1)
    upgrade()
  }

  @Test
  fun upgradeRefusedByServer() {
    server.enqueue(MockResponse(body = "normal request"))
    val requestWithUpgrade =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Connection", "upgrade")
        .build()
    client.newCall(requestWithUpgrade).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.socket).isNull()
      assertThat(response.body.string()).isEqualTo("normal request")
    }
    // Confirm there's no RequestBodyStart/RequestBodyEnd on failed upgrades.
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart",
      "ProxySelectEnd",
      "DnsStart",
      "DnsEnd",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "RequestHeadersStart",
      "RequestHeadersEnd",
      "ResponseHeadersStart",
      "ResponseHeadersEnd",
      "FollowUpDecision",
      "ResponseBodyStart",
      "ResponseBodyEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  @Test
  fun upgradeForbiddenOnHttp2() {
    enableTls(Protocol.HTTP_2, Protocol.HTTP_1_1)
    val socketHandler = MockSocketHandler()
    server.enqueue(socketHandler.upgradeResponse())
    val requestWithUpgrade =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Connection", "upgrade")
        .build()
    assertFailsWith<ProtocolException> {
      client.newCall(requestWithUpgrade).execute()
    }
  }

  @Test
  fun upgradesOnReusedConnection() {
    server.enqueue(MockResponse(body = "normal request"))
    client.newCall(Request(server.url("/"))).execute().use { response ->
      assertThat(response.body.string()).isEqualTo("normal request")
    }

    upgrade()

    assertThat(server.takeRequest().connectionIndex).isEqualTo(0)
    assertThat(server.takeRequest().connectionIndex).isEqualTo(0)
  }

  @Test
  fun cannotReuseConnectionAfterUpgrade() {
    upgrade()

    server.enqueue(MockResponse(body = "normal request"))
    client.newCall(Request(server.url("/"))).execute().use { response ->
      assertThat(response.body.string()).isEqualTo("normal request")
    }

    assertThat(server.takeRequest().connectionIndex).isEqualTo(0)
    assertThat(server.takeRequest().connectionIndex).isEqualTo(1)
  }

  @Test
  fun upgradeEventsWithoutRequestBody() {
    upgrade()

    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart",
      "ProxySelectEnd",
      "DnsStart",
      "DnsEnd",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "RequestHeadersStart",
      "RequestHeadersEnd",
      "ResponseHeadersStart",
      "ResponseHeadersEnd",
      "FollowUpDecision",
      "SocketSinkStart",
      "SocketSourceStart",
      "SocketSourceEnd",
      "SocketSinkEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  @Test
  fun upgradeEventsWithRequestBody() {
    upgradeWithRequestBody()

    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart",
      "ProxySelectEnd",
      "DnsStart",
      "DnsEnd",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "RequestHeadersStart",
      "RequestHeadersEnd",
      "ResponseHeadersStart",
      "ResponseHeadersEnd",
      "FollowUpDecision",
      "SocketSinkStart",
      "SocketSourceStart",
      "SocketSourceEnd",
      "SocketSinkEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  @Test
  fun upgradeRequestMustHaveAnEmptyBody() {
    val e =
      assertFailsWith<IllegalArgumentException> {
        Request
          .Builder()
          .url(server.url("/"))
          .header("Connection", "upgrade")
          .post("Hello".toRequestBody())
          .build()
      }
    assertThat(e).hasMessage("expected a null or empty request body with 'Connection: upgrade'")
  }

  private fun enableTls(vararg protocols: Protocol) {
    client =
      client
        .newBuilder()
        .protocols(protocols.toList())
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.protocols = protocols.toList()
  }

  private fun upgradeRequest() =
    Request(
      url = server.url("/"),
      headers =
        headersOf(
          "Connection",
          "upgrade",
        ),
    )

  private fun MockSocketHandler.upgradeResponse() =
    MockResponse
      .Builder()
      .code(HTTP_SWITCHING_PROTOCOLS)
      .addHeader("Connection", "upgrade")
      .socketHandler(this)
      .build()
}
