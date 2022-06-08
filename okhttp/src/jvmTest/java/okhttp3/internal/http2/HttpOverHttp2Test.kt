/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.http2

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.PushPromise
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketPolicy
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Cookie
import okhttp3.Credentials.basic
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.RecordingCookieJar
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.SimpleProvider
import okhttp3.TestLogHandler
import okhttp3.TestUtil.assumeNotWindows
import okhttp3.TestUtil.repeat
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.EMPTY_REQUEST
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.discard
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.internal.TlsUtil.localhost
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

/** Test how HTTP/2 interacts with HTTP features.  */
@Timeout(60)
@Flaky
@Tag("Slow")
class HttpOverHttp2Test {
  class ProtocolParamProvider : SimpleProvider() {
    override fun arguments() = listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_2)
  }

  @TempDir
  lateinit var tempDir: File

  @RegisterExtension
  val platform: PlatformRule = PlatformRule()

  @RegisterExtension
  val clientTestRule = configureClientTestRule()

  @RegisterExtension
  val testLogHandler: TestLogHandler = TestLogHandler(Http2::class.java)

  private lateinit var server: MockWebServer
  private lateinit var protocol: Protocol
  private lateinit var client: OkHttpClient
  private lateinit var cache: Cache
  private lateinit var scheme: String

  private fun configureClientTestRule(): OkHttpClientTestRule {
    val clientTestRule = OkHttpClientTestRule()
    clientTestRule.recordTaskRunner = true
    return clientTestRule
  }

  fun setUp(protocol: Protocol, server: MockWebServer) {
    this.server = server
    this.protocol = protocol
    platform.assumeNotOpenJSSE()
    platform.assumeNotBouncyCastle()
    if (protocol === Protocol.HTTP_2) {
      platform.assumeHttp2Support()
      server.useHttps(handshakeCertificates.sslSocketFactory(), false)
      client = clientTestRule.newClientBuilder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager
        )
        .hostnameVerifier(RecordingHostnameVerifier())
        .build()
      scheme = "https"
    } else {
      server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
      client = clientTestRule.newClientBuilder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .build()
      scheme = "http"
    }
    cache = Cache(tempDir, Int.MAX_VALUE.toLong())
  }

  @AfterEach fun tearDown() {
    java.net.Authenticator.setDefault(null)
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider::class)
  fun get(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
    )
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    assertThat(response.protocol).isEqualTo(protocol)
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme)
    assertThat(request.getHeader(":authority")).isEqualTo("${server.hostName}:${server.port}")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun get204Response(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val responseWithoutBody = MockResponse()
    responseWithoutBody.status = "HTTP/1.1 204"
    responseWithoutBody.removeHeader("Content-Length")
    server.enqueue(responseWithoutBody)
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()

    // Body contains nothing.
    assertThat(response.body.bytes().size).isEqualTo(0)
    assertThat(response.body.contentLength()).isEqualTo(0)

    // Content-Length header doesn't exist in a 204 response.
    assertThat(response.header("content-length")).isNull()
    assertThat(response.code).isEqualTo(204)
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun head(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val mockResponse = MockResponse().setHeader("Content-Length", 5)
    mockResponse.status = "HTTP/1.1 200"
    server.enqueue(mockResponse)
    val call = client.newCall(
      Request.Builder()
        .head()
        .url(server.url("/foo"))
        .build()
    )
    val response = call.execute()

    // Body contains nothing.
    assertThat(response.body.bytes().size).isEqualTo(0)
    assertThat(response.body.contentLength()).isEqualTo(0)

    // Content-Length header stays correctly.
    assertThat(response.header("content-length")).isEqualTo("5")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("HEAD /foo HTTP/1.1")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun emptyResponse(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(MockResponse())
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.byteStream().read()).isEqualTo(-1)
    response.body.close()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noDefaultContentLengthOnStreamingPost(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val postBytes = "FGHIJ".toByteArray()
    server.enqueue(MockResponse().setBody("ABCDE"))
    val call = client.newCall(
      Request(
        url = server.url("/foo"),
        body = object : RequestBody() {
          override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

          override fun writeTo(sink: BufferedSink) {
            sink.write(postBytes)
          }
        },
      )
    )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST /foo HTTP/1.1")
    org.junit.jupiter.api.Assertions.assertArrayEquals(postBytes, request.body.readByteArray())
    assertThat(request.getHeader("Content-Length")).isNull()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun userSuppliedContentLengthHeader(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val postBytes = "FGHIJ".toByteArray()
    server.enqueue(MockResponse().setBody("ABCDE"))
    val call = client.newCall(
      Request(
        url = server.url("/foo"),
        body = object : RequestBody() {
          override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

          override fun contentLength(): Long = postBytes.size.toLong()

          override fun writeTo(sink: BufferedSink) {
            sink.write(postBytes)
          }
        },
      )
    )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST /foo HTTP/1.1")
    org.junit.jupiter.api.Assertions.assertArrayEquals(postBytes, request.body.readByteArray())
    assertThat(request.getHeader("Content-Length")!!.toInt()).isEqualTo(
      postBytes.size.toLong()
    )
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun closeAfterFlush(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    val postBytes = "FGHIJ".toByteArray()
    server.enqueue(MockResponse().setBody("ABCDE"))
    val call = client.newCall(
      Request(
        url = server.url("/foo"),
        body = object : RequestBody() {
          override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

          override fun contentLength(): Long = postBytes.size.toLong()

          override fun writeTo(sink: BufferedSink) {
            sink.write(postBytes) // push bytes into the stream's buffer
            sink.flush() // Http2Connection.writeData subject to write window
            sink.close() // Http2Connection.writeData empty frame
          }
        },
      )
    )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST /foo HTTP/1.1")
    org.junit.jupiter.api.Assertions.assertArrayEquals(postBytes, request.body.readByteArray())
    assertThat(request.getHeader("Content-Length")!!.toInt())
      .isEqualTo(postBytes.size.toLong())
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionReuse(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(MockResponse().setBody("ABCDEF"))
    server.enqueue(MockResponse().setBody("GHIJKL"))
    val call1 = client.newCall(Request(server.url("/r1")))
    val call2 = client.newCall(Request(server.url("/r1")))
    val response1 = call1.execute()
    val response2 = call2.execute()
    assertThat(response1.body.source().readUtf8(3)).isEqualTo("ABC")
    assertThat(response2.body.source().readUtf8(3)).isEqualTo("GHI")
    assertThat(response1.body.source().readUtf8(3)).isEqualTo("DEF")
    assertThat(response2.body.source().readUtf8(3)).isEqualTo("JKL")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    response1.close()
    response2.close()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionWindowUpdateAfterCanceling(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody(Buffer().write(ByteArray(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE + 1)))
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)

    // Cancel the call and discard what we've buffered for the response body. This should free up
    // the connection flow-control window so new requests can proceed.
    call1.cancel()
    assertThat(response1.body.source().discard(1, TimeUnit.SECONDS))
      .overridingErrorMessage("Call should not have completed successfully.")
      .isFalse
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("abc")
  }

  /** Wait for the client to receive `dataLength` DATA frames.  */
  private fun waitForDataFrames(dataLength: Int) {
    val expectedFrameCount = dataLength / 16384
    var dataFrameCount = 0
    while (dataFrameCount < expectedFrameCount) {
      val log = testLogHandler.take()
      if (log == "FINE: << 0x00000003 16384 DATA          ") {
        dataFrameCount++
      }
    }
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionWindowUpdateOnClose(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody(Buffer().write(ByteArray(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE + 1)))
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    // Enqueue an additional response that show if we burnt a good prior response.
    server.enqueue(
      MockResponse()
        .setBody("XXX")
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)

    // Cancel the call and close the response body. This should discard the buffered data and update
    // the connection flow-control window.
    call1.cancel()
    response1.close()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("abc")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun concurrentRequestWithEmptyFlowControlWindow(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody(Buffer().write(ByteArray(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)))
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)
    assertThat(response1.body.contentLength()).isEqualTo(
      Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE.toLong()
    )
    val read = response1.body.source().read(ByteArray(8192))
    assertThat(read).isEqualTo(8192)

    // Make a second call that should transmit the response headers. The response body won't be
    // transmitted until the flow-control window is updated from the first request.
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)

    // Close the response body. This should discard the buffered data and update the connection
    // flow-control window.
    response1.close()
    assertThat(response2.body.string()).isEqualTo("abc")
  }

  /** https://github.com/square/okhttp/issues/373  */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  @Disabled
  fun synchronousRequest(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(MockResponse().setBody("A"))
    server.enqueue(MockResponse().setBody("A"))
    val executor = Executors.newCachedThreadPool()
    val countDownLatch = CountDownLatch(2)
    executor.execute(AsyncRequest("/r1", countDownLatch))
    executor.execute(AsyncRequest("/r2", countDownLatch))
    countDownLatch.await()
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun gzippedResponseBody(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .addHeader("Content-Encoding: gzip")
        .setBody(gzip("ABCABCABC"))
    )
    val call = client.newCall(Request(server.url("/r1")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCABCABC")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun authenticate(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED)
        .addHeader("www-authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.")
    )
    server.enqueue(
      MockResponse()
        .setBody("Successful auth!")
    )
    val credential = basic("username", "password")
    client = client.newBuilder()
      .authenticator(RecordingOkAuthenticator(credential, "Basic"))
      .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("Successful auth!")
    val denied = server.takeRequest()
    assertThat(denied.getHeader("Authorization")).isNull()
    val accepted = server.takeRequest()
    assertThat(accepted.requestLine).isEqualTo("GET / HTTP/1.1")
    assertThat(accepted.getHeader("Authorization")).isEqualTo(credential)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun redirect(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .setBody("This page has moved!")
    )
    server.enqueue(MockResponse().setBody("This is the new location!"))
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("This is the new location!")
    val request1 = server.takeRequest()
    assertThat(request1.path).isEqualTo("/")
    val request2 = server.takeRequest()
    assertThat(request2.path).isEqualTo("/foo")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun readAfterLastByte(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(MockResponse().setBody("ABC"))
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    val inputStream = response.body.byteStream()
    assertThat(inputStream.read()).isEqualTo('A'.code)
    assertThat(inputStream.read()).isEqualTo('B'.code)
    assertThat(inputStream.read()).isEqualTo('C'.code)
    assertThat(inputStream.read()).isEqualTo(-1)
    assertThat(inputStream.read()).isEqualTo(-1)
    inputStream.close()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun readResponseHeaderTimeout(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    server.enqueue(MockResponse().setBody("A"))
    client = client.newBuilder()
      .readTimeout(Duration.ofSeconds(1))
      .build()

    // Make a call expecting a timeout reading the response headers.
    val call1 = client.newCall(Request(server.url("/")))
    try {
      call1.execute()
      fail<Any?>("Should have timed out!")
    } catch (expected: SocketTimeoutException) {
      assertThat(expected.message).isEqualTo("timeout")
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("A")

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  /**
   * Test to ensure we don't  throw a read timeout on responses that are progressing.  For this
   * case, we take a 4KiB body and throttle it to 1KiB/second.  We set the read timeout to two
   * seconds.  If our implementation is acting correctly, it will not throw, as it is progressing.
   */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun readTimeoutMoreGranularThanBodySize(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val body = CharArray(4096) // 4KiB to read.
    Arrays.fill(body, 'y')
    server.enqueue(
      MockResponse().setBody(String(body))
        .throttleBody(1024, 1, TimeUnit.SECONDS) // Slow connection 1KiB/second.
    )
    client = client.newBuilder()
      .readTimeout(Duration.ofSeconds(2))
      .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo(String(body))
  }

  /**
   * Test to ensure we throw a read timeout on responses that are progressing too slowly.  For this
   * case, we take a 2KiB body and throttle it to 1KiB/second.  We set the read timeout to half a
   * second.  If our implementation is acting correctly, it will throw, as a byte doesn't arrive in
   * time.
   */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun readTimeoutOnSlowConnection(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val body = repeat('y', 2048)
    server.enqueue(
      MockResponse()
        .setBody(body)
        .throttleBody(1024, 1, TimeUnit.SECONDS)
    ) // Slow connection 1KiB/second.
    server.enqueue(
      MockResponse()
        .setBody(body)
    )
    client = client.newBuilder()
      .readTimeout(Duration.ofMillis(500)) // Half a second to read something.
      .build()

    // Make a call expecting a timeout reading the response body.
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    try {
      response1.body.string()
      fail<Any?>("Should have timed out!")
    } catch (expected: SocketTimeoutException) {
      assertThat(expected.message).isEqualTo("timeout")
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo(body)

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionTimeout(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody("A")
        .setBodyDelay(1, TimeUnit.SECONDS)
    )
    val client1 = client.newBuilder()
      .readTimeout(Duration.ofSeconds(2))
      .build()
    val call1 = client1
      .newCall(
        Request.Builder()
          .url(server.url("/"))
          .build()
      )
    val client2 = client.newBuilder()
      .readTimeout(Duration.ofMillis(200))
      .build()
    val call2 = client2
      .newCall(
        Request.Builder()
          .url(server.url("/"))
          .build()
      )
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("A")
    try {
      call2.execute()
      fail<Any?>()
    } catch (expected: IOException) {
    }

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun responsesAreCached(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .cache(cache)
      .build()
    server.enqueue(
      MockResponse()
        .addHeader("cache-control: max-age=60")
        .setBody("A")
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("A")
    val call3 = client.newCall(Request(server.url("/")))
    val response3 = call3.execute()
    assertThat(response3.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(3)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(2)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun conditionalCache(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .cache(cache)
      .build()
    server.enqueue(
      MockResponse()
        .addHeader("ETag: v1")
        .setBody("A")
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED)
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun responseCachedWithoutConsumingFullBody(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .cache(cache)
      .build()
    server.enqueue(
      MockResponse()
        .addHeader("cache-control: max-age=60")
        .setBody("ABCD")
    )
    server.enqueue(
      MockResponse()
        .addHeader("cache-control: max-age=60")
        .setBody("EFGH")
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.source().readUtf8(2)).isEqualTo("AB")
    response1.body.close()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.source().readUtf8()).isEqualTo("ABCD")
    response2.body.close()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun sendRequestCookies(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val cookieJar = RecordingCookieJar()
    val requestCookie = Cookie.Builder()
      .name("a")
      .value("b")
      .domain(server.hostName)
      .build()
    cookieJar.enqueueRequestCookies(requestCookie)
    client = client.newBuilder()
      .cookieJar(cookieJar)
      .build()
    server.enqueue(MockResponse())
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    val request = server.takeRequest()
    assertThat(request.getHeader("Cookie")).isEqualTo("a=b")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun receiveResponseCookies(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val cookieJar = RecordingCookieJar()
    client = client.newBuilder()
      .cookieJar(cookieJar)
      .build()
    server.enqueue(
      MockResponse()
        .addHeader("set-cookie: a=b")
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    cookieJar.assertResponseCookies("a=b; path=/")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun cancelWithStreamNotCompleted(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    server.enqueue(
      MockResponse()
        .setBody("def")
    )

    // Disconnect before the stream is created. A connection is still established!
    val call1 = client.newCall(Request(server.url("/")))
    val response = call1.execute()
    call1.cancel()

    // That connection is pooled, and it works.
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("def")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Clean up the connection.
    response.close()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noRecoveryFromOneRefusedStream(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    val call = client.newCall(Request(server.url("/")))
    try {
      call.execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromRefusedStreamWhenAnotherRouteExists(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .dns(DoubleInetAddressDns()) // Two routes!
      .build()
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )

    val request = Request(server.url("/"))
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("abc")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Note that although we have two routes available, we only use one. The retry is permitted
    // because there are routes available, but it chooses the existing connection since it isn't
    // yet considered unhealthy.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noRecoveryWhenRoutesExhausted(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .dns(DoubleInetAddressDns()) // Two routes!
      .build()
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )

    val request = Request(server.url("/"))
    try {
      client.newCall(request).execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0) // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1) // Pooled connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0) // New connection.
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionWithOneRefusedStreamIsPooled(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    val request = Request(server.url("/"))

    // First call fails because it only has one route.
    try {
      client.newCall(request).execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Second call succeeds on the pooled connection.
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("abc")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionWithTwoRefusedStreamsIsNotPooled(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    server.enqueue(
      MockResponse()
        .setBody("def")
    )
    val request = Request(server.url("/"))

    // First call makes a new connection and fails because it is the only route.
    try {
      client.newCall(request).execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0) // New connection.

    // Second call attempts the pooled connection, and it fails. Then it retries a new route which
    // succeeds.
    val response2 = client.newCall(request).execute()
    assertThat(response2.body.string()).isEqualTo("abc")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1) // Pooled connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0) // New connection.

    // Third call reuses the second connection.
    val response3 = client.newCall(request).execute()
    assertThat(response3.body.string()).isEqualTo("def")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1) // New connection.
  }

  /**
   * We had a bug where we'd perform infinite retries of route that fail with connection shutdown
   * errors. The problem was that the logic that decided whether to reuse a route didn't track
   * certain HTTP/2 errors. https://github.com/square/okhttp/issues/5547
   */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noRecoveryFromTwoRefusedStreams(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    val call = client.newCall(Request(server.url("/")))
    try {
      call.execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromOneInternalErrorRequiresNewConnection(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode.INTERNAL_ERROR)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromOneCancelRequiresNewConnection(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode.CANCEL)
  }

  private fun recoverFromOneHttp2ErrorRequiresNewConnection(errorCode: ErrorCode?) {
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(errorCode!!.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    client = client.newBuilder()
      .dns(DoubleInetAddressDns())
      .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("abc")

    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromMultipleRefusedStreamsRequiresNewConnection(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    client = client.newBuilder()
      .dns(DoubleInetAddressDns())
      .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("abc")

    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // Reused connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    // New connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromCancelReusesConnection(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val responseDequeuedLatches = listOf(
      CountDownLatch(1), // No synchronization for the last request, which is not canceled.
      CountDownLatch(0)
    )
    val requestCanceledLatches = listOf(
      CountDownLatch(1),
      CountDownLatch(0)
    )
    val dispatcher = RespondAfterCancelDispatcher(responseDequeuedLatches, requestCanceledLatches)
    dispatcher.enqueueResponse(
      MockResponse()
        .setBodyDelay(10, TimeUnit.SECONDS)
        .setBody("abc")
    )
    dispatcher.enqueueResponse(
      MockResponse()
        .setBody("def")
    )
    server.dispatcher = dispatcher
    client = client.newBuilder()
      .dns(DoubleInetAddressDns())
      .build()
    callAndCancel(0, responseDequeuedLatches[0], requestCanceledLatches[0])

    // Make a second request to ensure the connection is reused.
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("def")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromMultipleCancelReusesConnection(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val responseDequeuedLatches = Arrays.asList(
      CountDownLatch(1),
      CountDownLatch(1), // No synchronization for the last request, which is not canceled.
      CountDownLatch(0)
    )
    val requestCanceledLatches = Arrays.asList(
      CountDownLatch(1),
      CountDownLatch(1),
      CountDownLatch(0)
    )
    val dispatcher = RespondAfterCancelDispatcher(responseDequeuedLatches, requestCanceledLatches)
    dispatcher.enqueueResponse(
      MockResponse()
        .setBodyDelay(10, TimeUnit.SECONDS)
        .setBody("abc")
    )
    dispatcher.enqueueResponse(
      MockResponse()
        .setBodyDelay(10, TimeUnit.SECONDS)
        .setBody("def")
    )
    dispatcher.enqueueResponse(
      MockResponse()
        .setBody("ghi")
    )
    server.dispatcher = dispatcher
    client = client.newBuilder()
      .dns(DoubleInetAddressDns())
      .build()
    callAndCancel(0, responseDequeuedLatches[0], requestCanceledLatches[0])
    callAndCancel(1, responseDequeuedLatches[1], requestCanceledLatches[1])

    // Make a third request to ensure the connection is reused.
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ghi")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  private class RespondAfterCancelDispatcher(
    private val responseDequeuedLatches: List<CountDownLatch>,
    private val requestCanceledLatches: List<CountDownLatch>
  ) : QueueDispatcher() {
    private var responseIndex = 0

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
      // This guarantees a deterministic sequence when handling the canceled request:
      // 1. Server reads request and dequeues first response
      // 2. Client cancels request
      // 3. Server tries to send response on the canceled stream
      // Otherwise, there is no guarantee for the sequence. For example, the server may use the
      // first mocked response to respond to the second request.
      val response = super.dispatch(request)
      responseDequeuedLatches[responseIndex].countDown()
      requestCanceledLatches[responseIndex].await()
      responseIndex++
      return response
    }
  }

  /** Make a call and canceling it as soon as it's accepted by the server.  */
  private fun callAndCancel(
    expectedSequenceNumber: Int, responseDequeuedLatch: CountDownLatch?,
    requestCanceledLatch: CountDownLatch?
  ) {
    val call = client.newCall(Request(server.url("/")))
    val latch = CountDownLatch(1)
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        latch.countDown()
      }

      override fun onResponse(call: Call, response: Response) {
        fail<Any?>()
      }
    })
    assertThat(server.takeRequest().sequenceNumber)
      .isEqualTo(expectedSequenceNumber.toLong())
    responseDequeuedLatch!!.await()
    call.cancel()
    requestCanceledLatch!!.countDown()
    latch.await()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noRecoveryFromRefusedStreamWithRetryDisabled(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.REFUSED_STREAM)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noRecoveryFromInternalErrorWithRetryDisabled(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.INTERNAL_ERROR)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noRecoveryFromCancelWithRetryDisabled(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.CANCEL)
  }

  private fun noRecoveryFromErrorWithRetryDisabled(errorCode: ErrorCode?) {
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(errorCode!!.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("abc")
    )
    client = client.newBuilder()
      .retryOnConnectionFailure(false)
      .build()
    val call = client.newCall(Request(server.url("/")))
    try {
      call.execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.errorCode).isEqualTo(errorCode)
    }
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun recoverFromConnectionNoNewStreamsOnFollowUp(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setResponseCode(401)
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.INTERNAL_ERROR.httpCode)
    )
    server.enqueue(
      MockResponse()
        .setBody("DEF")
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo")
    )
    server.enqueue(
      MockResponse()
        .setBody("ABC")
    )
    val latch = CountDownLatch(1)
    val responses: BlockingQueue<String?> = SynchronousQueue()
    val authenticator = okhttp3.Authenticator { route: Route?, response: Response? ->
      responses.offer(response!!.body.string())
      try {
        latch.await()
      } catch (e: InterruptedException) {
        throw AssertionError()
      }
      response.request
    }
    val blockingAuthClient = client.newBuilder()
      .authenticator(authenticator)
      .build()
    val callback: Callback = object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        fail<Any?>()
      }

      override fun onResponse(call: Call, response: Response) {
        responses.offer(response.body.string())
      }
    }

    // Make the first request waiting until we get our auth challenge.
    val request = Request(server.url("/"))
    blockingAuthClient.newCall(request).enqueue(callback)
    val response1 = responses.take()
    assertThat(response1).isEqualTo("")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Now make the second request which will restrict the first HTTP/2 connection from creating new
    // streams.
    client.newCall(request).enqueue(callback)
    val response2 = responses.take()
    assertThat(response2).isEqualTo("DEF")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Let the first request proceed. It should discard the the held HTTP/2 connection and get a new
    // one.
    latch.countDown()
    val response3 = responses.take()
    assertThat(response3).isEqualTo("ABC")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun nonAsciiResponseHeader(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .addHeaderLenient("Alpha", "α")
        .addHeaderLenient("β", "Beta")
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    response.close()
    assertThat(response.header("Alpha")).isEqualTo("α")
    assertThat(response.header("β")).isEqualTo("Beta")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun serverSendsPushPromise_GET(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val pushPromise = PushPromise(
      "GET", "/foo/bar", headersOf("foo", "bar"),
      MockResponse().setBody("bar").setStatus("HTTP/1.1 200 Sweet")
    )
    server.enqueue(
      MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
        .withPush(pushPromise)
    )
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme)
    assertThat(request.getHeader(":authority")).isEqualTo(
      server.hostName + ":" + server.port
    )
    val pushedRequest = server.takeRequest()
    assertThat(pushedRequest.requestLine).isEqualTo("GET /foo/bar HTTP/1.1")
    assertThat(pushedRequest.getHeader("foo")).isEqualTo("bar")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun serverSendsPushPromise_HEAD(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val pushPromise = PushPromise(
      "HEAD", "/foo/bar", headersOf("foo", "bar"),
      MockResponse().setStatus("HTTP/1.1 204 Sweet")
    )
    server.enqueue(
      MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
        .withPush(pushPromise)
    )
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme)
    assertThat(request.getHeader(":authority")).isEqualTo(
      server.hostName + ":" + server.port
    )
    val pushedRequest = server.takeRequest()
    assertThat(pushedRequest.requestLine).isEqualTo(
      "HEAD /foo/bar HTTP/1.1"
    )
    assertThat(pushedRequest.getHeader("foo")).isEqualTo("bar")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun noDataFramesSentWithNullRequestBody(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody("ABC")
    )
    val call = client.newCall(
      Request.Builder()
        .url(server.url("/"))
        .method("DELETE", null)
        .build()
    )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.protocol).isEqualTo(protocol)
    val logs = testLogHandler.takeAll()
    assertThat(firstFrame(logs, "HEADERS"))
      .overridingErrorMessage("header logged")
      .contains("HEADERS       END_STREAM|END_HEADERS")
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun emptyDataFrameSentWithEmptyBody(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setBody("ABC")
    )
    val call = client.newCall(
      Request.Builder()
        .url(server.url("/"))
        .method("DELETE", EMPTY_REQUEST)
        .build()
    )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.protocol).isEqualTo(protocol)
    val logs = testLogHandler.takeAll()
    assertThat(firstFrame(logs, "HEADERS"))
      .overridingErrorMessage("header logged")
      .contains("HEADERS       END_HEADERS")
    // While MockWebServer waits to read the client's HEADERS frame before sending the response, it
    // doesn't wait to read the client's DATA frame and may send a DATA frame before the client
    // does. So we can't assume the client's empty DATA will be logged first.
    assertThat(countFrames(logs, "FINE: >> 0x00000003     0 DATA          END_STREAM"))
      .isEqualTo(2L)
    assertThat(countFrames(logs, "FINE: >> 0x00000003     3 DATA          "))
      .isEqualTo(1L)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun pingsTransmitted(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    // Ping every 500 ms, starting at 500 ms.
    client = client.newBuilder()
      .pingInterval(Duration.ofMillis(500))
      .build()

    // Delay the response to give 1 ping enough time to be sent and replied to.
    server.enqueue(
      MockResponse()
        .setBodyDelay(750, TimeUnit.MILLISECONDS)
        .setBody("ABC")
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.protocol).isEqualTo(protocol)

    // Confirm a single ping was sent and received, and its reply was sent and received.
    val logs = testLogHandler.takeAll()
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          "))
      .isEqualTo(1L)
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          "))
      .isEqualTo(1L)
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          ACK"))
      .isEqualTo(1L)
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK"))
      .isEqualTo(1L)
  }

  @Flaky @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun missingPongsFailsConnection(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    if (protocol === Protocol.HTTP_2) {
      // https://github.com/square/okhttp/issues/5221
      platform.expectFailureOnJdkVersion(12)
    }

    // Ping every 500 ms, starting at 500 ms.
    client = client.newBuilder()
      .readTimeout(Duration.ofSeconds(10)) // Confirm we fail before the read timeout.
      .pingInterval(Duration.ofMillis(500))
      .build()

    // Set up the server to ignore the socket. It won't respond to pings!
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START)
    )

    // Make a call. It'll fail as soon as our pings detect a problem.
    val call = client.newCall(Request(server.url("/")))
    val executeAtNanos = System.nanoTime()
    try {
      call.execute()
      fail<Any?>()
    } catch (expected: StreamResetException) {
      assertThat(expected.message).isEqualTo(
        "stream was reset: PROTOCOL_ERROR"
      )
    }
    val elapsedUntilFailure = System.nanoTime() - executeAtNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure).toDouble())
      .isCloseTo(1000.0, Offset.offset(250.0))

    // Confirm a single ping was sent but not acknowledged.
    val logs = testLogHandler.takeAll()
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          "))
      .isEqualTo(1L)
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK"))
      .isEqualTo(0L)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun streamTimeoutDegradesConnectionAfterNoPong(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    assumeNotWindows()
    client = client.newBuilder()
      .readTimeout(Duration.ofMillis(500))
      .build()

    // Stalling the socket will cause TWO requests to time out!
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START)
    )

    // The 3rd request should be sent to a fresh connection.
    server.enqueue(
      MockResponse()
        .setBody("fresh connection")
    )

    // The first call times out.
    val call1 = client.newCall(Request(server.url("/")))
    try {
      call1.execute()
      fail<Any?>()
    } catch (expected: SocketTimeoutException) {
    } catch (expected: SSLException) {
    }

    // The second call times out because it uses the same bad connection.
    val call2 = client.newCall(Request(server.url("/")))
    try {
      call2.execute()
      fail<Any?>()
    } catch (expected: SocketTimeoutException) {
    }

    // But after the degraded pong timeout, that connection is abandoned.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS.toLong()))
    val call3 = client.newCall(Request(server.url("/")))
    call3.execute().use { response ->
      assertThat(
        response.body.string()
      ).isEqualTo("fresh connection")
    }
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun oneStreamTimeoutDoesNotBreakConnection(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .readTimeout(Duration.ofMillis(500))
      .build()
    server.enqueue(
      MockResponse()
        .setBodyDelay(1000, TimeUnit.MILLISECONDS)
        .setBody("a")
    )
    server.enqueue(
      MockResponse()
        .setBody("b")
    )
    server.enqueue(
      MockResponse()
        .setBody("c")
    )

    // The first call times out.
    val call1 = client.newCall(Request(server.url("/")))
    try {
      call1.execute().use { response ->
        response.body.string()
        fail<Any?>()
      }
    } catch (expected: SocketTimeoutException) {
    }

    // The second call succeeds.
    val call2 = client.newCall(Request(server.url("/")))
    call2.execute().use { response ->
      assertThat(
        response.body.string()
      ).isEqualTo("b")
    }

    // Calls succeed after the degraded pong timeout because the degraded pong was received.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS.toLong()))
    val call3 = client.newCall(Request(server.url("/")))
    call3.execute().use { response ->
      assertThat(
        response.body.string()
      ).isEqualTo("c")
    }

    // All calls share a connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
  }

  private fun firstFrame(logs: List<String>, type: String): String? {
    for (log in logs) {
      if (type in log) {
        return log
      }
    }
    return null
  }

  private fun countFrames(logs: List<String>, message: String): Int {
    var result = 0
    for (log in logs) {
      if (log == message) {
        result++
      }
    }
    return result
  }

  /**
   * Push a setting that permits up to 2 concurrent streams, then make 3 concurrent requests and
   * confirm that the third concurrent request prepared a new connection.
   */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun settingsLimitsMaxConcurrentStreams(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val settings = Settings()
    settings[Settings.MAX_CONCURRENT_STREAMS] = 2

    // Read & write a full request to confirm settings are accepted.
    server.enqueue(MockResponse().withSettings(settings))
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    server.enqueue(
      MockResponse()
        .setBody("ABC")
    )
    server.enqueue(
      MockResponse()
        .setBody("DEF")
    )
    server.enqueue(
      MockResponse()
        .setBody("GHI")
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    val call3 = client.newCall(Request(server.url("/")))
    val response3 = call3.execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    assertThat(response2.body.string()).isEqualTo("DEF")
    assertThat(response3.body.string()).isEqualTo("GHI")
    // Settings connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // Reuse settings connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    // Reuse settings connection.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(2)
    // New connection!
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionNotReusedAfterShutdown(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("ABC")
    )
    server.enqueue(
      MockResponse()
        .setBody("DEF")
    )
    // Enqueue an additional response that show if we burnt a good prior response.
    server.enqueue(
      MockResponse()
        .setBody("XXX")
    )
    val connections: MutableList<RealConnection?> = ArrayList()
    val localClient = client.newBuilder().eventListener(object : EventListener() {
      override fun connectionAcquired(call: Call, connection: Connection) {
        connections.add(connection as RealConnection)
      }
    }).build()
    val call1 = localClient.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("ABC")

    // Add delays for DISCONNECT_AT_END to propogate
    waitForConnectionShutdown(connections[0])
    val call2 = localClient.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("DEF")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Throws(InterruptedException::class, TimeoutException::class)
  private fun waitForConnectionShutdown(connection: RealConnection?) {
    if (connection!!.isHealthy(false)) {
      Thread.sleep(100L)
    }
    if (connection.isHealthy(false)) {
      Thread.sleep(2000L)
    }
    if (connection.isHealthy(false)) {
      throw TimeoutException("connection didn't shutdown within timeout")
    }
  }

  /**
   * This simulates a race condition where we receive a healthy HTTP/2 connection and just prior to
   * writing our request, we get a GOAWAY frame from the server.
   */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun connectionShutdownAfterHealthCheck(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("ABC")
    )
    server.enqueue(
      MockResponse()
        .setBody("DEF")
    )
    val client2 = client.newBuilder()
      .addNetworkInterceptor(object : Interceptor {
        var executedCall = false
        override fun intercept(chain: Interceptor.Chain): Response {
          if (!executedCall) {
            // At this point, we have a healthy HTTP/2 connection. This call will trigger the
            // server to send a GOAWAY frame, leaving the connection in a shutdown state.
            executedCall = true
            val call = client.newCall(
              Request.Builder()
                .url(server.url("/"))
                .build()
            )
            val response = call.execute()
            assertThat(response.body.string()).isEqualTo("ABC")
            // Wait until the GOAWAY has been processed.
            val connection = chain.connection() as RealConnection?
            while (connection!!.isHealthy(false));
          }
          return chain.proceed(chain.request())
        }
      })
      .build()
    val call = client2.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("DEF")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Flaky @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun responseHeadersAfterGoaway(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .setHeadersDelay(1, TimeUnit.SECONDS)
        .setBody("ABC")
    )
    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("DEF")
    )
    val latch = CountDownLatch(2)
    val errors = ArrayList<IOException?>()
    val bodies: BlockingQueue<String?> = LinkedBlockingQueue()
    val callback: Callback = object : Callback {
      override fun onResponse(call: Call, response: Response) {
        bodies.add(response.body.string())
        latch.countDown()
      }

      override fun onFailure(call: Call, e: IOException) {
        errors.add(e)
        latch.countDown()
      }
    }
    client.newCall(Request.Builder().url(server.url("/")).build()).enqueue(
      callback
    )
    client.newCall(Request.Builder().url(server.url("/")).build()).enqueue(
      callback
    )
    latch.await()
    assertThat(bodies.remove()).isEqualTo("DEF")
    if (errors.isEmpty()) {
      assertThat(bodies.remove()).isEqualTo("ABC")
      assertThat(server.requestCount).isEqualTo(2)
    } else {
      // https://github.com/square/okhttp/issues/4836
      // As documented in SocketPolicy, this is known to be flaky.
      val error = errors[0]
      if (error !is StreamResetException) {
        throw error!!
      }
    }
  }

  /**
   * We don't know if the connection will support HTTP/2 until after we've connected. When multiple
   * connections are requested concurrently OkHttp will pessimistically connect multiple times, then
   * close any unnecessary connections. This test confirms that behavior works as intended.
   *
   * This test uses proxy tunnels to get a hook while a connection is being established.
   */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun concurrentHttp2ConnectionsDeduplicated(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    assumeTrue(protocol === Protocol.HTTP_2)
    server.useHttps(handshakeCertificates.sslSocketFactory(), true)
    val queueDispatcher = QueueDispatcher()
    queueDispatcher.enqueueResponse(
      MockResponse()
        .inTunnel()
    )
    queueDispatcher.enqueueResponse(
      MockResponse()
        .inTunnel()
    )
    queueDispatcher.enqueueResponse(
      MockResponse()
        .setBody("call2 response")
    )
    queueDispatcher.enqueueResponse(
      MockResponse()
        .setBody("call1 response")
    )

    // We use a re-entrant dispatcher to initiate one HTTPS connection while the other is in flight.
    server.dispatcher = object : Dispatcher() {
      var requestCount = 0

      override fun dispatch(request: RecordedRequest): MockResponse {
        val result = queueDispatcher.dispatch(request)
        requestCount++
        if (requestCount == 1) {
          // Before handling call1's CONNECT we do all of call2. This part re-entrant!
          try {
            val call2 = client.newCall(
              Request.Builder()
                .url("https://android.com/call2")
                .build()
            )
            val response2 = call2.execute()
            assertThat(response2.body.string()).isEqualTo("call2 response")
          } catch (e: IOException) {
            throw RuntimeException(e)
          }
        }
        return result
      }

      override fun peek(): MockResponse = queueDispatcher.peek()

      override fun shutdown() {
        queueDispatcher.shutdown()
      }
    }
    client = client.newBuilder()
      .proxy(server.toProxyAddress())
      .build()
    val call1 = client.newCall(Request("https://android.com/call1".toHttpUrl()))
    val response2 = call1.execute()
    assertThat(response2.body.string()).isEqualTo("call1 response")
    val call1Connect = server.takeRequest()
    assertThat(call1Connect.method).isEqualTo("CONNECT")
    assertThat(call1Connect.sequenceNumber).isEqualTo(0)
    val call2Connect = server.takeRequest()
    assertThat(call2Connect.method).isEqualTo("CONNECT")
    assertThat(call2Connect.sequenceNumber).isEqualTo(0)
    val call2Get = server.takeRequest()
    assertThat(call2Get.method).isEqualTo("GET")
    assertThat(call2Get.path).isEqualTo("/call2")
    assertThat(call2Get.sequenceNumber).isEqualTo(0)
    val call1Get = server.takeRequest()
    assertThat(call1Get.method).isEqualTo("GET")
    assertThat(call1Get.path).isEqualTo("/call1")
    assertThat(call1Get.sequenceNumber).isEqualTo(1)
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /** https://github.com/square/okhttp/issues/3103  */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun domainFronting(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    client = client.newBuilder()
      .addNetworkInterceptor(Interceptor { chain: Interceptor.Chain? ->
        val request = chain!!.request().newBuilder()
          .header("Host", "privateobject.com")
          .build()
        chain.proceed(request)
      })
      .build()
    server.enqueue(MockResponse())
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.getHeader(":authority")).isEqualTo("privateobject.com")
  }

  private fun gzip(bytes: String): Buffer {
    val bytesOut = Buffer()
    val sink = GzipSink(bytesOut).buffer()
    sink.writeUtf8(bytes)
    sink.close()
    return bytesOut
  }

  internal inner class AsyncRequest(
    val path: String,
    val countDownLatch: CountDownLatch
  ) : Runnable {
    override fun run() {
      try {
        val call = client.newCall(
          Request.Builder()
            .url(server.url(path))
            .build()
        )
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("A")
        countDownLatch.countDown()
      } catch (e: Exception) {
        throw RuntimeException(e)
      }
    }
  }

  /** https://github.com/square/okhttp/issues/4875  */
  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun shutdownAfterLateCoalescing(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    val latch = CountDownLatch(2)
    val callback: Callback = object : Callback {
      override fun onResponse(call: Call, response: Response) {
        fail<Any?>()
      }

      override fun onFailure(call: Call, e: IOException) {
        latch.countDown()
      }
    }
    client =
      client.newBuilder().eventListenerFactory(clientTestRule.wrap(object : EventListener() {
        var callCount = 0
        override fun connectionAcquired(call: Call, connection: Connection) {
          try {
            if (callCount++ == 1) {
              server.shutdown()
            }
          } catch (e: IOException) {
            fail<Any?>()
          }
        }
      })).build()
    client.newCall(Request.Builder().url(server.url("")).build()).enqueue(
      callback
    )
    client.newCall(Request.Builder().url(server.url("")).build()).enqueue(
      callback
    )
    latch.await()
  }

  @ParameterizedTest @ArgumentsSource(ProtocolParamProvider::class)
  fun cancelWhileWritingRequestBodySendsCancelToServer(
    protocol: Protocol, mockWebServer: MockWebServer
  ) {
    setUp(protocol, mockWebServer)
    server.enqueue(MockResponse())
    val callReference = AtomicReference<Call?>()
    val call = client.newCall(
      Request(
        url = server.url("/"),
        body = object : RequestBody() {
          override fun contentType() = "text/plain; charset=utf-8".toMediaType()

          override fun writeTo(sink: BufferedSink) {
            callReference.get()!!.cancel()
          }
        },
      )
    )
    callReference.set(call)
    try {
      call.execute()
      fail<Any?>()
    } catch (expected: IOException) {
      assertThat(call.isCanceled()).isTrue
    }
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.failure).hasMessage("stream was reset: CANCEL")
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider::class)
  fun http2WithProxy(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .inTunnel()
    )
    server.enqueue(
      MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
    )
    val client = client.newBuilder()
      .proxy(server.toProxyAddress())
      .build()

    val url = server.url("/").resolve("//android.com/foo")!!
    val port = when (url.scheme) {
      "https" -> 443
      "http" -> 80
      else -> error("unexpected scheme")
    }

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    assertThat(response.protocol).isEqualTo(protocol)

    val tunnelRequest = server.takeRequest()
    assertThat(tunnelRequest.requestLine).isEqualTo("CONNECT android.com:$port HTTP/1.1")

    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme)
    assertThat(request.getHeader(":authority")).isEqualTo("android.com")
  }

  /** Respond to a proxy authorization challenge.  */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider::class)
  fun proxyAuthenticateOnConnect(protocol: Protocol, mockWebServer: MockWebServer) {
    setUp(protocol, mockWebServer)
    server.enqueue(
      MockResponse()
        .inTunnel()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
    )
    server.enqueue(
      MockResponse()
        .inTunnel()
    )
    server.enqueue(
      MockResponse()
        .setBody("response body")
    )
    val client = client.newBuilder()
      .proxy(server.toProxyAddress())
      .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
      .build()

    val url = server.url("/").resolve("//android.com/foo")!!
    val port = when (url.scheme) {
      "https" -> 443
      "http" -> 80
      else -> error("unexpected scheme")
    }

    val request = Request(url)
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("response body")

    val connect1 = server.takeRequest()
    assertThat(connect1.requestLine).isEqualTo("CONNECT android.com:$port HTTP/1.1")
    assertThat(connect1.getHeader("Proxy-Authorization")).isNull()

    val connect2 = server.takeRequest()
    assertThat(connect2.requestLine).isEqualTo("CONNECT android.com:$port HTTP/1.1")
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo("password")

    val get = server.takeRequest()
    assertThat(get.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(get.getHeader("Proxy-Authorization")).isNull()
  }

  companion object {
    // Flaky https://github.com/square/okhttp/issues/4632
    // Flaky https://github.com/square/okhttp/issues/4633
    private val handshakeCertificates: HandshakeCertificates = localhost()
  }
}
