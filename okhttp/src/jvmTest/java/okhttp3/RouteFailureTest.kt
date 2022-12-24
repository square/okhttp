/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import mockwebserver3.junit5.internal.MockWebServerInstance
import okhttp3.internal.http2.ErrorCode
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class RouteFailureTest {
  private lateinit var socketFactory: SpecificHostSocketFactory
  private lateinit var client: OkHttpClient

  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private lateinit var server1: MockWebServer
  private lateinit var server2: MockWebServer

  private var listener = RecordingEventListener()

  private val handshakeCertificates = localhost()

  val dns = FakeDns()

  val ipv4 = InetAddress.getByName("203.0.113.1")
  val ipv6 = InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:1")

  val refusedStream = MockResponse()
    .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
  val bodyResponse = MockResponse().setBody("body")

  @BeforeEach
  fun setUp(
    server: MockWebServer,
    @MockWebServerInstance("server2") server2: MockWebServer
  ) {
    this.server1 = server
    this.server2 = server2

    socketFactory = SpecificHostSocketFactory(InetSocketAddress(server.hostName, server.port))

    client = clientTestRule.newClientBuilder()
      .dns(dns)
      .socketFactory(socketFactory)
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()
  }

  @Test
  fun http2OneBadHostOneGoodNoRetryOnConnectionFailure() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(false)
      .apply {
        retryOnConnectionFailure = false
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
    assertThat(server2.requestCount).isEqualTo(0)
  }

  @Test
  fun http2OneBadHostOneGoodRetryOnConnectionFailure() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(false)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertBody("body")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    // TODO check if we expect a second request to server1, before attempting server2
    assertThat(server1.requestCount).isEqualTo(2)
    assertThat(server2.requestCount).isEqualTo(1)
  }

  @Test
  fun http2OneBadHostOneGoodNoRetryOnConnectionFailureFastFallback() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(true)
      .apply {
        retryOnConnectionFailure = false
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
    assertThat(server2.requestCount).isEqualTo(0)
  }

  @Test
  fun http2OneBadHostOneGoodRetryOnConnectionFailureFastFallback() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(true)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertBody("body")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    // TODO check if we expect a second request to server1, before attempting server2
    assertThat(server1.requestCount).isEqualTo(2)
    assertThat(server2.requestCount).isEqualTo(1)
  }

  @Test
  fun http2OneBadHostRetryOnConnectionFailure() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)

    dns[server1.hostName] = listOf(ipv6)
    socketFactory[ipv6] = server1.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(false)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
  }

  @Test
  fun http2OneBadHostRetryOnConnectionFailureFastFallback() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)

    dns[server1.hostName] = listOf(ipv6)
    socketFactory[ipv6] = server1.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(true)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
  }

  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client = client.newBuilder()
      .protocols(listOf(protocol, Protocol.HTTP_1_1))
      .build()
    server1.protocols = client.protocols
    server2.protocols = client.protocols
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .hostnameVerifier(RecordingHostnameVerifier())
      .build()
    server1.useHttps(handshakeCertificates.sslSocketFactory())
    server2.useHttps(handshakeCertificates.sslSocketFactory())
  }

  private fun executeSynchronously(request: Request): RecordedResponse {
    val call = client.newCall(request)
    return try {
      val response = call.execute()
      val bodyString = response.body.string()
      RecordedResponse(request, response, null, bodyString, null)
    } catch (e: IOException) {
      RecordedResponse(request, null, null, null, e)
    }
  }
}
