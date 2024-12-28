/*
 * Copyright (C) 2017 Square, Inc.
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
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.DisconnectDuringRequestBody
import mockwebserver3.SocketPolicy.DisconnectDuringResponseBody
import mockwebserver3.SocketPolicy.FailHandshake
import okhttp3.CallEvent.CallEnd
import okhttp3.CallEvent.CallFailed
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.ConnectStart
import okhttp3.CallEvent.ConnectionAcquired
import okhttp3.CallEvent.DnsEnd
import okhttp3.CallEvent.DnsStart
import okhttp3.CallEvent.RequestBodyEnd
import okhttp3.CallEvent.RequestBodyStart
import okhttp3.CallEvent.RequestHeadersEnd
import okhttp3.CallEvent.RequestHeadersStart
import okhttp3.CallEvent.ResponseBodyEnd
import okhttp3.CallEvent.ResponseBodyStart
import okhttp3.CallEvent.ResponseFailed
import okhttp3.CallEvent.ResponseHeadersEnd
import okhttp3.CallEvent.ResponseHeadersStart
import okhttp3.CallEvent.SecureConnectEnd
import okhttp3.CallEvent.SecureConnectStart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.connection.RealConnectionPool.Companion.get
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.BufferedSink
import org.hamcrest.BaseMatcher
import org.hamcrest.CoreMatchers
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert
import org.junit.Assume
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Flaky // STDOUT logging enabled for test
@Timeout(30)
@Tag("Slow")
class EventListenerTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private lateinit var server: MockWebServer
  private val listener: RecordingEventListener = RecordingEventListener()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private var client =
    clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()
  private var socksProxy: SocksProxy? = null
  private var cache: Cache? = null

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
    platform.assumeNotOpenJSSE()
    listener.forbidLock(get(client.connectionPool))
    listener.forbidLock(client.dispatcher)
  }

  @AfterEach
  fun tearDown() {
    if (socksProxy != null) {
      socksProxy!!.shutdown()
    }
    if (cache != null) {
      cache!!.delete()
    }
  }

  @Test
  fun successfulCallEventSequence() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun successfulCallEventSequenceForIpAddress() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    val ipAddress = InetAddress.getLoopbackAddress().hostAddress
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/").newBuilder().host(ipAddress!!).build())
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun successfulCallEventSequenceForEnqueue() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val completionLatch = CountDownLatch(1)
    val callback: Callback =
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          completionLatch.countDown()
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
          completionLatch.countDown()
        }
      }
    call.enqueue(callback)
    completionLatch.await()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun failedCallEventSequence() {
    server.enqueue(
      MockResponse.Builder()
        .headersDelay(2, TimeUnit.SECONDS)
        .build(),
    )
    client =
      client.newBuilder()
        .readTimeout(Duration.ofMillis(250))
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isIn("timeout", "Read timed out")
    }
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseFailed", "ConnectionReleased", "CallFailed",
    )
  }

  @Test
  fun failedDribbledCallEventSequence() {
    server.enqueue(
      MockResponse.Builder()
        .body("0123456789")
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .socketPolicy(DisconnectDuringResponseBody)
        .build(),
    )
    client =
      client.newBuilder()
        .protocols(listOf<Protocol>(Protocol.HTTP_1_1))
        .readTimeout(Duration.ofMillis(250))
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertFailsWith<IOException> {
      response.body.string()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("unexpected end of stream")
    }
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseFailed", "ConnectionReleased", "CallFailed",
    )
    val responseFailed = listener.removeUpToEvent<ResponseFailed>()
    assertThat(responseFailed.ioe.message).isEqualTo("unexpected end of stream")
  }

  @Test
  fun canceledCallEventSequence() {
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    call.cancel()
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Canceled")
    }
    assertThat(listener.recordedEventTypes()).containsExactly(
      "Canceled",
      "CallStart",
      "CallFailed",
    )
  }

  @Test
  fun cancelAsyncCall() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
        }
      },
    )
    call.cancel()
    assertThat(listener.recordedEventTypes()).contains("Canceled")
  }

  @Test
  fun multipleCancelsEmitsOnlyOneEvent() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    call.cancel()
    call.cancel()
    assertThat(listener.recordedEventTypes()).containsExactly("Canceled")
  }

  private fun assertSuccessfulEventOrder(responseMatcher: Matcher<Response?>?) {
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.string()
    response.body.close()
    Assume.assumeThat(response, responseMatcher)
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart",
      "ProxySelectEnd",
      "DnsStart",
      "DnsEnd",
      "ConnectStart",
      "SecureConnectStart",
      "SecureConnectEnd",
      "ConnectEnd",
      "ConnectionAcquired",
      "RequestHeadersStart",
      "RequestHeadersEnd",
      "ResponseHeadersStart",
      "ResponseHeadersEnd",
      "ResponseBodyStart",
      "ResponseBodyEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  @Test
  fun secondCallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    client.newCall(
      Request.Builder()
        .url(server.url("/"))
        .build(),
    ).execute().close()
    listener.removeUpToEvent<CallEnd>()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ConnectionAcquired",
      "RequestHeadersStart",
      "RequestHeadersEnd",
      "ResponseHeadersStart",
      "ResponseHeadersEnd",
      "ResponseBodyStart",
      "ResponseBodyEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  private fun assertBytesReadWritten(
    listener: RecordingEventListener,
    requestHeaderLength: Matcher<Long?>?,
    requestBodyBytes: Matcher<Long?>?,
    responseHeaderLength: Matcher<Long?>?,
    responseBodyBytes: Matcher<Long?>?,
  ) {
    if (requestHeaderLength != null) {
      val responseHeadersEnd = listener.removeUpToEvent<RequestHeadersEnd>()
      MatcherAssert.assertThat(
        "request header length",
        responseHeadersEnd.headerLength,
        requestHeaderLength,
      )
    } else {
      assertThat(listener.recordedEventTypes())
        .doesNotContain("RequestHeadersEnd")
    }
    if (requestBodyBytes != null) {
      val responseBodyEnd: RequestBodyEnd = listener.removeUpToEvent<RequestBodyEnd>()
      MatcherAssert.assertThat(
        "request body bytes",
        responseBodyEnd.bytesWritten,
        requestBodyBytes,
      )
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain("RequestBodyEnd")
    }
    if (responseHeaderLength != null) {
      val responseHeadersEnd: ResponseHeadersEnd =
        listener.removeUpToEvent<ResponseHeadersEnd>()
      MatcherAssert.assertThat(
        "response header length",
        responseHeadersEnd.headerLength,
        responseHeaderLength,
      )
    } else {
      assertThat(listener.recordedEventTypes())
        .doesNotContain("ResponseHeadersEnd")
    }
    if (responseBodyBytes != null) {
      val responseBodyEnd: ResponseBodyEnd = listener.removeUpToEvent<ResponseBodyEnd>()
      MatcherAssert.assertThat(
        "response body bytes",
        responseBodyEnd.bytesRead,
        responseBodyBytes,
      )
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain("ResponseBodyEnd")
    }
  }

  private fun greaterThan(value: Long): Matcher<Long?> {
    return object : BaseMatcher<Long?>() {
      override fun describeTo(description: Description?) {
        description!!.appendText("> $value")
      }

      override fun matches(o: Any?): Boolean {
        return (o as Long?)!! > value
      }
    }
  }

  private fun matchesProtocol(protocol: Protocol?): Matcher<Response?> {
    return object : BaseMatcher<Response?>() {
      override fun describeTo(description: Description?) {
        description!!.appendText("is HTTP/2")
      }

      override fun matches(o: Any?): Boolean {
        return (o as Response?)!!.protocol == protocol
      }
    }
  }

  @Test
  fun successfulEmptyH2CallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(MockResponse())
    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2))
    assertBytesReadWritten(
      listener,
      CoreMatchers.any(Long::class.java),
      null,
      greaterThan(0L),
      CoreMatchers.equalTo(0L),
    )
  }

  @Test
  fun successfulEmptyHttpsCallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    assertSuccessfulEventOrder(anyResponse)
    assertBytesReadWritten(
      listener,
      CoreMatchers.any(Long::class.java),
      null,
      greaterThan(0L),
      CoreMatchers.equalTo(3L),
    )
  }

  @Test
  fun successfulChunkedHttpsCallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse.Builder()
        .bodyDelay(100, TimeUnit.MILLISECONDS)
        .chunkedBody("Hello!", 2)
        .build(),
    )
    assertSuccessfulEventOrder(anyResponse)
    assertBytesReadWritten(
      listener,
      CoreMatchers.any(Long::class.java),
      null,
      greaterThan(0L),
      CoreMatchers.equalTo(6L),
    )
  }

  @Test
  fun successfulChunkedH2CallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse.Builder()
        .bodyDelay(100, TimeUnit.MILLISECONDS)
        .chunkedBody("Hello!", 2)
        .build(),
    )
    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2))
    assertBytesReadWritten(
      listener,
      CoreMatchers.any(Long::class.java),
      null,
      CoreMatchers.equalTo(0L),
      greaterThan(6L),
    )
  }

  @Test
  fun successfulDnsLookup() {
    server.enqueue(MockResponse())
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val dnsStart: DnsStart = listener.removeUpToEvent<DnsStart>()
    assertThat(dnsStart.call).isSameAs(call)
    assertThat(dnsStart.domainName).isEqualTo(server.hostName)
    val dnsEnd: DnsEnd = listener.removeUpToEvent<DnsEnd>()
    assertThat(dnsEnd.call).isSameAs(call)
    assertThat(dnsEnd.domainName).isEqualTo(server.hostName)
    assertThat(dnsEnd.inetAddressList.size).isEqualTo(1)
  }

  @Test
  fun noDnsLookupOnPooledConnection() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    // Seed the pool.
    val call1 =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.code).isEqualTo(200)
    response1.body.close()
    listener.clearAllEvents()
    val call2 =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)
    response2.body.close()
    val recordedEvents: List<String> = listener.recordedEventTypes()
    assertThat(recordedEvents).doesNotContain("DnsStart")
    assertThat(recordedEvents).doesNotContain("DnsEnd")
  }

  @Test
  fun multipleDnsLookupsForSingleCall() {
    server.enqueue(
      MockResponse.Builder()
        .code(301)
        .setHeader("Location", "http://www.fakeurl:" + server.port)
        .build(),
    )
    server.enqueue(MockResponse())
    val dns = FakeDns()
    dns["fakeurl"] = client.dns.lookup(server.hostName)
    dns["www.fakeurl"] = client.dns.lookup(server.hostName)
    client =
      client.newBuilder()
        .dns(dns)
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url("http://fakeurl:" + server.port)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    listener.removeUpToEvent<DnsStart>()
    listener.removeUpToEvent<DnsEnd>()
    listener.removeUpToEvent<DnsStart>()
    listener.removeUpToEvent<DnsEnd>()
  }

  @Test
  fun failedDnsLookup() {
    client =
      client.newBuilder()
        .dns(FakeDns())
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url("http://fakeurl/")
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    listener.removeUpToEvent<DnsStart>()
    val callFailed: CallFailed = listener.removeUpToEvent<CallFailed>()
    assertThat(callFailed.call).isSameAs(call)
    assertThat(callFailed.ioe).isInstanceOf<UnknownHostException>()
  }

  @Test
  fun emptyDnsLookup() {
    val emptyDns = Dns { listOf() }
    client =
      client.newBuilder()
        .dns(emptyDns)
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url("http://fakeurl/")
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    listener.removeUpToEvent<DnsStart>()
    val callFailed: CallFailed = listener.removeUpToEvent<CallFailed>()
    assertThat(callFailed.call).isSameAs(call)
    assertThat(callFailed.ioe).isInstanceOf(
      UnknownHostException::class.java,
    )
  }

  @Test
  fun successfulConnect() {
    server.enqueue(MockResponse())
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val address = client.dns.lookup(server.hostName)[0]
    val expectedAddress = InetSocketAddress(address, server.port)
    val connectStart = listener.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY)
    val connectEnd = listener.removeUpToEvent<CallEvent.ConnectEnd>()
    assertThat(connectEnd.call).isSameAs(call)
    assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  @Test
  fun failedConnect() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse.Builder()
        .socketPolicy(FailHandshake)
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    val address = client.dns.lookup(server.hostName)[0]
    val expectedAddress = InetSocketAddress(address, server.port)
    val connectStart = listener.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY)
    val connectFailed = listener.removeUpToEvent<CallEvent.ConnectFailed>()
    assertThat(connectFailed.call).isSameAs(call)
    assertThat(connectFailed.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectFailed.protocol).isNull()
    assertThat(connectFailed.ioe).isNotNull()
  }

  @Test
  fun multipleConnectsForSingleCall() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse.Builder()
        .socketPolicy(FailHandshake)
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client.newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    listener.removeUpToEvent<ConnectStart>()
    listener.removeUpToEvent<CallEvent.ConnectFailed>()
    listener.removeUpToEvent<ConnectStart>()
    listener.removeUpToEvent<CallEvent.ConnectEnd>()
  }

  @Test
  fun successfulHttpProxyConnect() {
    server.enqueue(MockResponse())
    client =
      client.newBuilder()
        .proxy(server.toProxyAddress())
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url("http://www.fakeurl")
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val address = client.dns.lookup(server.hostName)[0]
    val expectedAddress = InetSocketAddress(address, server.port)
    val connectStart: ConnectStart =
      listener.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(
      server.toProxyAddress(),
    )
    val connectEnd = listener.removeUpToEvent<CallEvent.ConnectEnd>()
    assertThat(connectEnd.call).isSameAs(call)
    assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  @Test
  fun successfulSocksProxyConnect() {
    server.enqueue(MockResponse())
    socksProxy = SocksProxy()
    socksProxy!!.play()
    val proxy = socksProxy!!.proxy()
    client =
      client.newBuilder()
        .proxy(proxy)
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url("http://" + SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS + ":" + server.port)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val expectedAddress =
      InetSocketAddress.createUnresolved(
        SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS,
        server.port,
      )
    val connectStart = listener.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(proxy)
    val connectEnd = listener.removeUpToEvent<CallEvent.ConnectEnd>()
    assertThat(connectEnd.call).isSameAs(call)
    assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  @Test
  fun authenticatingTunnelProxyConnect() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse.Builder()
        .inTunnel()
        .code(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close")
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client.newBuilder()
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    listener.removeUpToEvent<ConnectStart>()
    val connectEnd = listener.removeUpToEvent<CallEvent.ConnectEnd>()
    assertThat(connectEnd.protocol).isNull()
    listener.removeUpToEvent<ConnectStart>()
    listener.removeUpToEvent<CallEvent.ConnectEnd>()
  }

  @Test
  fun successfulSecureConnect() {
    enableTlsWithTunnel()
    server.enqueue(MockResponse())
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val secureStart = listener.removeUpToEvent<SecureConnectStart>()
    assertThat(secureStart.call).isSameAs(call)
    val secureEnd = listener.removeUpToEvent<SecureConnectEnd>()
    assertThat(secureEnd.call).isSameAs(call)
    assertThat(secureEnd.handshake).isNotNull()
  }

  @Test
  fun failedSecureConnect() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse.Builder()
        .socketPolicy(FailHandshake)
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    val secureStart = listener.removeUpToEvent<SecureConnectStart>()
    assertThat(secureStart.call).isSameAs(call)
    val callFailed = listener.removeUpToEvent<CallFailed>()
    assertThat(callFailed.call).isSameAs(call)
    assertThat(callFailed.ioe).isNotNull()
  }

  @Test
  fun secureConnectWithTunnel() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse.Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client.newBuilder()
        .proxy(server.toProxyAddress())
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val secureStart = listener.removeUpToEvent<SecureConnectStart>()
    assertThat(secureStart.call).isSameAs(call)
    val secureEnd = listener.removeUpToEvent<SecureConnectEnd>()
    assertThat(secureEnd.call).isSameAs(call)
    assertThat(secureEnd.handshake).isNotNull()
  }

  @Test
  fun multipleSecureConnectsForSingleCall() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse.Builder()
        .socketPolicy(FailHandshake)
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client.newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    listener.removeUpToEvent<SecureConnectStart>()
    listener.removeUpToEvent<CallEvent.ConnectFailed>()
    listener.removeUpToEvent<SecureConnectStart>()
    listener.removeUpToEvent<SecureConnectEnd>()
  }

  @Test
  fun noSecureConnectsOnPooledConnection() {
    enableTlsWithTunnel()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    client =
      client.newBuilder()
        .dns(DoubleInetAddressDns())
        .build()

    // Seed the pool.
    val call1 =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.code).isEqualTo(200)
    response1.body.close()
    listener.clearAllEvents()
    val call2 =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)
    response2.body.close()
    val recordedEvents: List<String> = listener.recordedEventTypes()
    assertThat(recordedEvents).doesNotContain("SecureConnectStart")
    assertThat(recordedEvents).doesNotContain("SecureConnectEnd")
  }

  @Test
  fun successfulConnectionFound() {
    server.enqueue(MockResponse())
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val connectionAcquired = listener.removeUpToEvent<ConnectionAcquired>()
    assertThat(connectionAcquired.call).isSameAs(call)
    assertThat(connectionAcquired.connection).isNotNull()
  }

  @Test
  fun noConnectionFoundOnFollowUp() {
    server.enqueue(
      MockResponse.Builder()
        .code(301)
        .addHeader("Location", "/foo")
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .body("ABC")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    listener.removeUpToEvent<ConnectionAcquired>()
    val remainingEvents = listener.recordedEventTypes()
    assertThat(remainingEvents).doesNotContain("ConnectionAcquired")
  }

  @Test
  fun pooledConnectionFound() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    // Seed the pool.
    val call1 =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.code).isEqualTo(200)
    response1.body.close()
    val connectionAcquired1 = listener.removeUpToEvent<ConnectionAcquired>()
    listener.clearAllEvents()
    val call2 =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)
    response2.body.close()
    val connectionAcquired2 = listener.removeUpToEvent<ConnectionAcquired>()
    assertThat(connectionAcquired2.connection).isSameAs(
      connectionAcquired1.connection,
    )
  }

  @Test
  fun multipleConnectionsFoundForSingleCall() {
    server.enqueue(
      MockResponse.Builder()
        .code(301)
        .addHeader("Location", "/foo")
        .addHeader("Connection", "Close")
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .body("ABC")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    listener.removeUpToEvent<ConnectionAcquired>()
    listener.removeUpToEvent<ConnectionAcquired>()
  }

  @Test
  fun responseBodyFailHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    responseBodyFail(Protocol.HTTP_1_1)
  }

  @Test
  fun responseBodyFailHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    responseBodyFail(Protocol.HTTP_2)
  }

  @Test
  fun responseBodyFailHttp() {
    responseBodyFail(Protocol.HTTP_1_1)
  }

  private fun responseBodyFail(expectedProtocol: Protocol?) {
    // Use a 2 MiB body so the disconnect won't happen until the client has read some data.
    val responseBodySize = 2 * 1024 * 1024 // 2 MiB
    server.enqueue(
      MockResponse.Builder()
        .body(Buffer().write(ByteArray(responseBodySize)))
        .socketPolicy(DisconnectDuringResponseBody)
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    if (expectedProtocol == Protocol.HTTP_2) {
      // soft failure since client may not support depending on Platform
      Assume.assumeThat(response, matchesProtocol(Protocol.HTTP_2))
    }
    assertThat(response.protocol).isEqualTo(expectedProtocol)
    assertFailsWith<IOException> {
      response.body.string()
    }
    val callFailed = listener.removeUpToEvent<CallFailed>()
    assertThat(callFailed.ioe).isNotNull()
  }

  @Test
  fun emptyResponseBody() {
    server.enqueue(
      MockResponse.Builder()
        .body("")
        .bodyDelay(1, TimeUnit.SECONDS)
        .socketPolicy(DisconnectDuringResponseBody)
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun emptyResponseBodyConnectionClose() {
    server.enqueue(
      MockResponse.Builder()
        .addHeader("Connection", "close")
        .body("")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun responseBodyClosedClosedWithoutReadingAllData() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .bodyDelay(1, TimeUnit.SECONDS)
        .socketPolicy(DisconnectDuringResponseBody)
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun requestBodyFailHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    requestBodyFail(Protocol.HTTP_1_1)
  }

  @Test
  fun requestBodyFailHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    requestBodyFail(Protocol.HTTP_2)
  }

  @Test
  fun requestBodyFailHttp() {
    requestBodyFail(null)
  }

  private fun requestBodyFail(expectedProtocol: Protocol?) {
    server.enqueue(
      MockResponse.Builder()
        .socketPolicy(DisconnectDuringRequestBody)
        .build(),
    )
    val request = NonCompletingRequestBody()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .post(request)
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    if (expectedProtocol != null) {
      val connectionAcquired = listener.removeUpToEvent<ConnectionAcquired>()
      assertThat(connectionAcquired.connection.protocol())
        .isEqualTo(expectedProtocol)
    }
    val callFailed = listener.removeUpToEvent<CallFailed>()
    assertThat(callFailed.ioe).isNotNull()
    assertThat(request.ioe).isNotNull()
  }

  private inner class NonCompletingRequestBody : RequestBody() {
    private val chunk: ByteArray? = ByteArray(1024 * 1024)
    var ioe: IOException? = null

    override fun contentType(): MediaType? {
      return "text/plain".toMediaType()
    }

    override fun contentLength(): Long {
      return chunk!!.size * 8L
    }

    override fun writeTo(sink: BufferedSink) {
      try {
        var i = 0
        while (i < contentLength()) {
          sink.write(chunk!!)
          sink.flush()
          Thread.sleep(100)
          i += chunk.size
        }
      } catch (e: IOException) {
        ioe = e
      } catch (e: InterruptedException) {
        throw RuntimeException(e)
      }
    }
  }

  @Test
  fun requestBodyMultipleFailuresReportedOnlyOnce() {
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType() = "text/plain".toMediaType()

        override fun contentLength(): Long {
          return 1024 * 1024 * 256
        }

        override fun writeTo(sink: BufferedSink) {
          var failureCount = 0
          for (i in 0..1023) {
            try {
              sink.write(ByteArray(1024 * 256))
              sink.flush()
            } catch (e: IOException) {
              failureCount++
              if (failureCount == 3) throw e
            }
          }
        }
      }
    server.enqueue(
      MockResponse.Builder()
        .socketPolicy(DisconnectDuringRequestBody)
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .post(requestBody)
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
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
      "RequestBodyStart",
      "RequestFailed",
      "ResponseFailed",
      "ConnectionReleased",
      "CallFailed",
    )
  }

  @Test
  fun requestBodySuccessHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    requestBodySuccess(
      "Hello".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(5L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun requestBodySuccessHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    requestBodySuccess(
      "Hello".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(5L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun requestBodySuccessHttp() {
    requestBodySuccess(
      "Hello".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(5L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun requestBodySuccessStreaming() {
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType() = "text/plain".toMediaType()

        override fun writeTo(sink: BufferedSink) {
          sink.write(ByteArray(8192))
          sink.flush()
        }
      }
    requestBodySuccess(requestBody, CoreMatchers.equalTo(8192L), CoreMatchers.equalTo(19L))
  }

  @Test
  fun requestBodySuccessEmpty() {
    requestBodySuccess(
      "".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(0L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun successfulCallEventSequenceWithListener() {
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    client =
      client.newBuilder()
        .addNetworkInterceptor(
          HttpLoggingInterceptor()
            .setLevel(HttpLoggingInterceptor.Level.BODY),
        )
        .build()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  private fun requestBodySuccess(
    body: RequestBody?,
    requestBodyBytes: Matcher<Long?>?,
    responseHeaderLength: Matcher<Long?>?,
  ) {
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .body("World!")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .post(body!!)
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("World!")
    assertBytesReadWritten(
      listener,
      CoreMatchers.any(Long::class.java),
      requestBodyBytes,
      responseHeaderLength,
      CoreMatchers.equalTo(6L),
    )
  }

  @Test
  fun timeToFirstByteHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    timeToFirstByte()
  }

  @Test
  fun timeToFirstByteHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    timeToFirstByte()
  }

  /**
   * Test to confirm that events are reported at the time they occur and no earlier and no later.
   * This inserts a bunch of synthetic 250 ms delays into both client and server and confirms that
   * the same delays make it back into the events.
   *
   * We've had bugs where we report an event when we request data rather than when the data actually
   * arrives. https://github.com/square/okhttp/issues/5578
   */
  private fun timeToFirstByte() {
    val applicationInterceptorDelay = 250L
    val networkInterceptorDelay = 250L
    val requestBodyDelay = 250L
    val responseHeadersStartDelay = 250L
    val responseBodyStartDelay = 250L
    val responseBodyEndDelay = 250L

    // Warm up the client so the timing part of the test gets a pooled connection.
    server.enqueue(MockResponse())
    val warmUpCall =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    warmUpCall.execute().use { warmUpResponse -> warmUpResponse.body.string() }
    listener.clearAllEvents()

    // Create a client with artificial delays.
    client =
      client.newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            try {
              Thread.sleep(applicationInterceptorDelay)
              return@Interceptor chain.proceed(chain.request())
            } catch (e: InterruptedException) {
              throw InterruptedIOException()
            }
          },
        )
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            try {
              Thread.sleep(networkInterceptorDelay)
              return@Interceptor chain.proceed(chain.request())
            } catch (e: InterruptedException) {
              throw InterruptedIOException()
            }
          },
        )
        .build()

    // Create a request body with artificial delays.
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .post(
            object : RequestBody() {
              override fun contentType(): MediaType? {
                return null
              }

              override fun writeTo(sink: BufferedSink) {
                try {
                  Thread.sleep(requestBodyDelay)
                  sink.writeUtf8("abc")
                } catch (e: InterruptedException) {
                  throw InterruptedIOException()
                }
              }
            },
          )
          .build(),
      )

    // Create a response with artificial delays.
    server.enqueue(
      MockResponse.Builder()
        .headersDelay(responseHeadersStartDelay, TimeUnit.MILLISECONDS)
        .bodyDelay(responseBodyStartDelay, TimeUnit.MILLISECONDS)
        .throttleBody(5, responseBodyEndDelay, TimeUnit.MILLISECONDS)
        .body("fghijk")
        .build(),
    )
    call.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("fghijk")
    }

    // Confirm the events occur when expected.
    listener.takeEvent(CallStart::class.java, 0L)
    listener.takeEvent(ConnectionAcquired::class.java, applicationInterceptorDelay)
    listener.takeEvent(RequestHeadersStart::class.java, networkInterceptorDelay)
    listener.takeEvent(RequestHeadersEnd::class.java, 0L)
    listener.takeEvent(RequestBodyStart::class.java, 0L)
    listener.takeEvent(RequestBodyEnd::class.java, requestBodyDelay)
    listener.takeEvent(ResponseHeadersStart::class.java, responseHeadersStartDelay)
    listener.takeEvent(ResponseHeadersEnd::class.java, 0L)
    listener.takeEvent(ResponseBodyStart::class.java, responseBodyStartDelay)
    listener.takeEvent(ResponseBodyEnd::class.java, responseBodyEndDelay)
    listener.takeEvent(CallEvent.ConnectionReleased::class.java, 0L)
    listener.takeEvent(CallEnd::class.java, 0L)
  }

  private fun enableTlsWithTunnel() {
    client =
      client.newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  @Test
  fun redirectUsingSameConnectionEventSequence() {
    server.enqueue(
      MockResponse.Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .build(),
    )
    server.enqueue(MockResponse())
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    call.execute()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
      "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
      "CallEnd",
    )
  }

  @Test
  fun redirectUsingNewConnectionEventSequence() {
    val otherServer = MockWebServer()
    server.enqueue(
      MockResponse.Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + otherServer.url("/foo"))
        .build(),
    )
    otherServer.enqueue(MockResponse())
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    call.execute()
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
      "ResponseBodyStart",
      "ResponseBodyEnd",
      "ConnectionReleased",
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
      "ResponseBodyStart",
      "ResponseBodyEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  @Test
  fun applicationInterceptorProceedsMultipleTimes() {
    server.enqueue(MockResponse.Builder().body("a").build())
    server.enqueue(MockResponse.Builder().body("b").build())
    client =
      client.newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            chain!!.proceed(chain.request())
              .use { a -> assertThat(a.body.string()).isEqualTo("a") }
            chain.proceed(chain.request())
          },
        )
        .build()
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("b")
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
      "ResponseBodyEnd", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
      "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
      "CallEnd",
    )
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test
  fun applicationInterceptorShortCircuit() {
    client =
      client.newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            Response.Builder()
              .request(chain!!.request())
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body("a".toResponseBody(null))
              .build()
          },
        )
        .build()
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("a")
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "CallEnd")
  }

  /** Response headers start, then the entire request body, then response headers end.  */
  @Test
  fun expectContinueStartsResponseHeadersEarly() {
    server.enqueue(
      MockResponse.Builder()
        .add100Continue()
        .build(),
    )
    val request =
      Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()
    val call = client.newCall(request)
    call.execute()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd", "ConnectStart",
      "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd",
      "ResponseHeadersStart", "RequestBodyStart", "RequestBodyEnd", "ResponseHeadersEnd",
      "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun timeToFirstByteGapBetweenResponseHeaderStartAndEnd() {
    val responseHeadersStartDelay = 250L
    server.enqueue(
      MockResponse.Builder()
        .add100Continue()
        .headersDelay(responseHeadersStartDelay, TimeUnit.MILLISECONDS)
        .build(),
    )
    val request =
      Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()
    val call = client.newCall(request)
    call.execute()
      .use { response -> assertThat(response.body.string()).isEqualTo("") }
    listener.removeUpToEvent<ResponseHeadersStart>()
    listener.takeEvent(RequestBodyStart::class.java, 0L)
    listener.takeEvent(RequestBodyEnd::class.java, 0L)
    listener.takeEvent(ResponseHeadersEnd::class.java, responseHeadersStartDelay)
  }

  @Test
  fun cacheMiss() {
    enableCache()
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart", "CacheMiss",
      "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
      "ConnectStart", "ConnectEnd", "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
      "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun conditionalCache() {
    enableCache()
    server.enqueue(
      MockResponse.Builder()
        .addHeader("ETag", "v1")
        .body("abc")
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    var call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    var response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.close()
    listener.clearAllEvents()
    call = call.clone()
    response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart", "CacheConditionalHit",
      "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd",
      "ResponseBodyStart", "ResponseBodyEnd", "CacheHit", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun conditionalCacheMiss() {
    enableCache()
    server.enqueue(
      MockResponse.Builder()
        .addHeader("ETag: v1")
        .body("abc")
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .code(HttpURLConnection.HTTP_OK)
        .addHeader("ETag: v2")
        .body("abd")
        .build(),
    )
    var call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    var response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.close()
    listener.clearAllEvents()
    call = call.clone()
    response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abd")
    response.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "CallStart", "CacheConditionalHit",
      "ConnectionAcquired", "RequestHeadersStart",
      "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "CacheMiss",
      "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased", "CallEnd",
    )
  }

  @Test
  fun satisfactionFailure() {
    enableCache()
    val call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .cacheControl(CacheControl.FORCE_CACHE)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(504)
    response.close()
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "SatisfactionFailure", "CallEnd")
  }

  @Test
  fun cacheHit() {
    enableCache()
    server.enqueue(
      MockResponse.Builder()
        .body("abc")
        .addHeader("cache-control: public, max-age=300")
        .build(),
    )
    var call =
      client.newCall(
        Request.Builder()
          .url(server.url("/"))
          .build(),
      )
    var response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    listener.clearAllEvents()
    call = call.clone()
    response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "CacheHit", "CallEnd")
  }

  private fun enableCache(): Cache? {
    cache = makeCache()
    client = client.newBuilder().cache(cache).build()
    return cache
  }

  private fun makeCache(): Cache {
    val cacheDir = File.createTempFile("cache-", ".dir")
    cacheDir.delete()
    return Cache(cacheDir, (1024 * 1024).toLong())
  }

  companion object {
    val anyResponse = CoreMatchers.any(Response::class.java)
  }
}
