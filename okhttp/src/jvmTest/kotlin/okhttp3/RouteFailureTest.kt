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

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.ResetStreamAtStart
import mockwebserver3.junit5.internal.MockWebServerInstance
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.internal.http2.ErrorCode
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

  private val handshakeCertificates = platform.localhostHandshakeCertificates()

  val dns = FakeDns()

  val ipv4 = InetAddress.getByName("203.0.113.1")
  val ipv6 = InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:1")

  val refusedStream =
    MockResponse(
      socketPolicy = ResetStreamAtStart(ErrorCode.REFUSED_STREAM.httpCode),
    )
  val bodyResponse = MockResponse(body = "body")

  @BeforeEach
  fun setUp(
    server: MockWebServer,
    @MockWebServerInstance("server2") server2: MockWebServer,
  ) {
    this.server1 = server
    this.server2 = server2

    socketFactory = SpecificHostSocketFactory(InetSocketAddress(server.hostName, server.port))

    client =
      clientTestRule.newClientBuilder()
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

    client =
      client.newBuilder()
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

    assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
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

    client =
      client.newBuilder()
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

    assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "NoNewExchanges",
      "ConnectionReleased",
      "ConnectionClosed",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
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

    client =
      client.newBuilder()
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

    assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
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

    client =
      client.newBuilder()
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

    assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "NoNewExchanges",
      "ConnectionReleased",
      "ConnectionClosed",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @Test
  fun http2OneBadHostRetryOnConnectionFailure() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)

    dns[server1.hostName] = listOf(ipv6)
    socketFactory[ipv6] = server1.inetSocketAddress

    client =
      client.newBuilder()
        .fastFallback(false)
        .apply {
          retryOnConnectionFailure = true
        }
        .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)

    assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @Test
  fun http2OneBadHostRetryOnConnectionFailureFastFallback() {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)

    dns[server1.hostName] = listOf(ipv6)
    socketFactory[ipv6] = server1.inetSocketAddress

    client =
      client.newBuilder()
        .fastFallback(true)
        .apply {
          retryOnConnectionFailure = true
        }
        .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)

    assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun proxyMoveTest(cleanShutdown: Boolean) {
    // Define a single Proxy at myproxy:8008 that will artificially move during the test
    val proxySelector = RecordingProxySelector()
    val socketAddress = InetSocketAddress.createUnresolved("myproxy", 8008)
    proxySelector.proxies.add(Proxy(Proxy.Type.HTTP, socketAddress))

    // Define two host names for the DNS routing of fake proxy servers
    val proxyServer1 = InetAddress.getByAddress("proxyServer1", byteArrayOf(127, 0, 0, 2))
    val proxyServer2 = InetAddress.getByAddress("proxyServer2", byteArrayOf(127, 0, 0, 3))

    println("Proxy Server 1 is ${server1.inetSocketAddress}")
    println("Proxy Server 2 is ${server2.inetSocketAddress}")

    // Since myproxy:8008 won't resolve, redirect with DNS to proxyServer1
    // Then redirect socket connection to server1
    dns["myproxy"] = listOf(proxyServer1)
    socketFactory[proxyServer1] = server1.inetSocketAddress

    client = client.newBuilder().proxySelector(proxySelector).build()

    val request = Request(server1.url("/"))

    server1.enqueue(MockResponse(200))
    server2.enqueue(MockResponse(200))
    server2.enqueue(MockResponse(200))

    println("\n\nRequest to ${server1.inetSocketAddress}")
    executeSynchronously(request)
      .assertSuccessful()
      .assertCode(200)

    println("server1.requestCount ${server1.requestCount}")
    assertThat(server1.requestCount).isEqualTo(1)

    // Shutdown the proxy server
    if (cleanShutdown) {
      server1.shutdown()
    }

    // Now redirect with DNS to proxyServer2
    // Then redirect socket connection to server2
    dns["myproxy"] = listOf(proxyServer2)
    socketFactory[proxyServer2] = server2.inetSocketAddress

    println("\n\nRequest to ${server2.inetSocketAddress}")
    executeSynchronously(request)
      .apply {
        // We may have a single failed request if not clean shutdown
        if (cleanShutdown) {
          assertSuccessful()
          assertCode(200)

          assertThat(server2.requestCount).isEqualTo(1)
        } else {
          this.assertFailure(SocketTimeoutException::class.java)
        }
      }

    println("\n\nRequest to ${server2.inetSocketAddress}")
    executeSynchronously(request)
      .assertSuccessful()
      .assertCode(200)
  }

  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client =
      client.newBuilder()
        .protocols(listOf(protocol, Protocol.HTTP_1_1))
        .build()
    server1.protocols = client.protocols
    server2.protocols = client.protocols
  }

  private fun enableTls() {
    client =
      client.newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
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
