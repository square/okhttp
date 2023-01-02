/*
 * Copyright (C) 2011 Google Inc.
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
package mockwebserver3

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import mockwebserver3.SocketPolicy.DisconnectAtStart
import mockwebserver3.SocketPolicy.DisconnectDuringRequestBody
import mockwebserver3.SocketPolicy.DisconnectDuringResponseBody
import mockwebserver3.SocketPolicy.ShutdownServerAfterResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.TestUtil.assumeNotWindows
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Suppress("deprecation")
@Timeout(30)
@Tag("Slow")
class MockWebServerTest {
  @RegisterExtension
  var platform = PlatformRule()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun defaultMockResponse() {
    val builder = MockResponse.Builder()
    assertThat(headersToList(builder)).containsExactly("Content-Length: 0")
    assertThat(builder.status).isEqualTo("HTTP/1.1 200 OK")
  }

  @Test
  fun setResponseMockReason() {
    val reasons = arrayOf<String?>(
      "Mock Response",
      "Informational",
      "OK",
      "Redirection",
      "Client Error",
      "Server Error",
      "Mock Response"
    )
    for (i in 0..599) {
      val builder = MockResponse.Builder().code(i)
      val expectedReason = reasons[i / 100]
      assertThat(builder.status).isEqualTo("HTTP/1.1 $i $expectedReason")
      assertThat(headersToList(builder)).containsExactly("Content-Length: 0")
    }
  }

  @Test
  fun setStatusControlsWholeStatusLine() {
    val builder = MockResponse.Builder().status("HTTP/1.1 202 That'll do pig")
    assertThat(headersToList(builder)).containsExactly("Content-Length: 0")
    assertThat(builder.status).isEqualTo("HTTP/1.1 202 That'll do pig")
  }

  @Test
  fun setBodyAdjustsHeaders() {
    val builder = MockResponse.Builder().body("ABC")
    assertThat(headersToList(builder)).containsExactly("Content-Length: 3")
    val response = builder.build()
    val body = Buffer()
    response.body!!.writeTo(body)
    assertThat(body.readUtf8()).isEqualTo("ABC")
  }

  @Test
  fun mockResponseAddHeader() {
    val builder = MockResponse.Builder()
      .clearHeaders()
      .addHeader("Cookie: s=square")
      .addHeader("Cookie", "a=android")
    assertThat(headersToList(builder)).containsExactly("Cookie: s=square", "Cookie: a=android")
  }

  @Test
  fun mockResponseSetHeader() {
    val builder = MockResponse.Builder()
      .clearHeaders()
      .addHeader("Cookie: s=square")
      .addHeader("Cookie: a=android")
      .addHeader("Cookies: delicious")
    builder.setHeader("cookie", "r=robot")
    assertThat(headersToList(builder)).containsExactly("Cookies: delicious", "cookie: r=robot")
  }

  @Test
  fun mockResponseSetHeaders() {
    val builder = MockResponse.Builder()
      .clearHeaders()
      .addHeader("Cookie: s=square")
      .addHeader("Cookies: delicious")
    builder.headers(Headers.Builder().add("Cookie", "a=android").build())
    assertThat(headersToList(builder)).containsExactly("Cookie: a=android")
  }

  @Test
  fun regularResponse() {
    server.enqueue(MockResponse.Builder().body("hello world").build())
    val url = server.url("/").toUrl()
    val connection = url.openConnection() as HttpURLConnection
    connection.setRequestProperty("Accept-Language", "en-US")
    val reader = BufferedReader(InputStreamReader(connection.inputStream, UTF_8))
    assertThat(connection.responseCode).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(reader.readLine()).isEqualTo("hello world")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET / HTTP/1.1")
    assertThat(request.headers["Accept-Language"]).isEqualTo("en-US")

    // Server has no more requests.
    assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()
  }

  @Test
  fun redirect() {
    server.enqueue(MockResponse.Builder()
      .code(HttpURLConnection.HTTP_MOVED_TEMP)
      .addHeader("Location: " + server.url("/new-path"))
      .body("This page has moved!")
      .build())
    server.enqueue(MockResponse.Builder()
      .body("This is the new location!")
      .build())
    val connection = server.url("/").toUrl().openConnection()
    val reader = BufferedReader(InputStreamReader(connection!!.getInputStream(), UTF_8))
    assertThat(reader.readLine()).isEqualTo("This is the new location!")
    val first = server.takeRequest()
    assertThat(first.requestLine).isEqualTo("GET / HTTP/1.1")
    val redirect = server.takeRequest()
    assertThat(redirect.requestLine).isEqualTo("GET /new-path HTTP/1.1")
  }

  /**
   * Test that MockWebServer blocks for a call to enqueue() if a request is made before a mock
   * response is ready.
   */
  @Test
  fun dispatchBlocksWaitingForEnqueue() {
    Thread {
      try {
        Thread.sleep(1000)
      } catch (ignored: InterruptedException) {
      }
      server.enqueue(MockResponse.Builder()
        .body("enqueued in the background")
        .build())
    }.start()
    val connection = server.url("/").toUrl().openConnection()
    val reader = BufferedReader(InputStreamReader(connection!!.getInputStream(), UTF_8))
    assertThat(reader.readLine()).isEqualTo("enqueued in the background")
  }

  @Test
  fun nonHexadecimalChunkSize() {
    server.enqueue(MockResponse.Builder()
      .body("G\r\nxxxxxxxxxxxxxxxx\r\n0\r\n\r\n")
      .clearHeaders()
      .addHeader("Transfer-encoding: chunked")
      .build())
    val connection = server.url("/").toUrl().openConnection()
    try {
      connection.getInputStream().read()
      fail<Unit>()
    } catch (expected: IOException) {
      // Expected.
    }
  }

  @Test
  fun responseTimeout() {
    server.enqueue(MockResponse.Builder()
      .body("ABC")
      .clearHeaders()
      .addHeader("Content-Length: 4")
      .build())
    server.enqueue(MockResponse.Builder()
      .body("DEF")
      .build())
    val urlConnection = server.url("/").toUrl().openConnection()
    urlConnection!!.readTimeout = 1000
    val inputStream = urlConnection.getInputStream()
    assertThat(inputStream!!.read()).isEqualTo('A'.code)
    assertThat(inputStream.read()).isEqualTo('B'.code)
    assertThat(inputStream.read()).isEqualTo('C'.code)
    try {
      inputStream.read() // if Content-Length was accurate, this would return -1 immediately
      fail<Unit>()
    } catch (expected: SocketTimeoutException) {
      // Expected.
    }
    val urlConnection2 = server.url("/").toUrl().openConnection()
    val in2 = urlConnection2!!.getInputStream()
    assertThat(in2!!.read()).isEqualTo('D'.code)
    assertThat(in2.read()).isEqualTo('E'.code)
    assertThat(in2.read()).isEqualTo('F'.code)
    assertThat(in2.read()).isEqualTo(-1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Disabled("Not actually failing where expected")
  @Test
  fun disconnectAtStart() {
    server.enqueue(MockResponse.Builder()
      .socketPolicy(DisconnectAtStart)
      .build())
    server.enqueue(MockResponse()) // The jdk's HttpUrlConnection is a bastard.
    server.enqueue(MockResponse())
    try {
      server.url("/a").toUrl().openConnection().getInputStream()
      fail<Unit>()
    } catch (expected: IOException) {
      // Expected.
    }
    server.url("/b").toUrl().openConnection().getInputStream() // Should succeed.
  }

  @Test
  fun clearDispatcherQueue() {
    server.enqueue(MockResponse(body = "A"))
    (server.dispatcher as QueueDispatcher).clear()
    server.enqueue(MockResponse(body = "B"))
    val inputStream = server.url("/a").toUrl().openConnection().getInputStream()
    assertThat(inputStream!!.read()).isEqualTo('B'.code)
  }

  /**
   * Throttle the request body by sleeping 500ms after every 3 bytes. With a 6-byte request, this
   * should yield one sleep for a total delay of 500ms.
   */
  @Test
  fun throttleRequest() {
    assumeNotWindows()
    server.enqueue(MockResponse.Builder()
      .throttleBody(3, 500, TimeUnit.MILLISECONDS)
      .build())
    val startNanos = System.nanoTime()
    val connection = server.url("/").toUrl().openConnection()
    connection.doOutput = true
    connection.getOutputStream().write("ABCDEF".toByteArray(UTF_8))
    val inputStream = connection.getInputStream()
    assertThat(inputStream.read()).isEqualTo(-1)
    val elapsedNanos = System.nanoTime() - startNanos
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
    assertThat(elapsedMillis).isBetween(500L, 1000L)
  }

  /**
   * Throttle the response body by sleeping 500ms after every 3 bytes. With a 6-byte response, this
   * should yield one sleep for a total delay of 500ms.
   */
  @Test
  fun throttleResponse() {
    assumeNotWindows()
    server.enqueue(MockResponse.Builder()
      .body("ABCDEF")
      .throttleBody(3, 500, TimeUnit.MILLISECONDS)
      .build())
    val startNanos = System.nanoTime()
    val connection = server.url("/").toUrl().openConnection()
    val inputStream = connection!!.getInputStream()
    assertThat(inputStream!!.read()).isEqualTo('A'.code)
    assertThat(inputStream.read()).isEqualTo('B'.code)
    assertThat(inputStream.read()).isEqualTo('C'.code)
    assertThat(inputStream.read()).isEqualTo('D'.code)
    assertThat(inputStream.read()).isEqualTo('E'.code)
    assertThat(inputStream.read()).isEqualTo('F'.code)
    assertThat(inputStream.read()).isEqualTo(-1)
    val elapsedNanos = System.nanoTime() - startNanos
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
    assertThat(elapsedMillis).isBetween(500L, 1000L)
  }

  /** Delay the response body by sleeping 1s.  */
  @Test
  fun delayResponse() {
    assumeNotWindows()
    server.enqueue(MockResponse.Builder()
      .body("ABCDEF")
      .bodyDelay(1, TimeUnit.SECONDS)
      .build())
    val startNanos = System.nanoTime()
    val connection = server.url("/").toUrl().openConnection()
    val inputStream = connection!!.getInputStream()
    assertThat(inputStream!!.read()).isEqualTo('A'.code)
    val elapsedNanos = System.nanoTime() - startNanos
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
    assertThat(elapsedMillis).isGreaterThanOrEqualTo(1000L)
    inputStream.close()
  }

  @Test
  fun disconnectRequestHalfway() {
    server.enqueue(MockResponse.Builder()
      .socketPolicy(DisconnectDuringRequestBody)
      .build())
    // Limit the size of the request body that the server holds in memory to an arbitrary
    // 3.5 MBytes so this test can pass on devices with little memory.
    server.bodyLimit = 7 * 512 * 1024
    val connection = server.url("/").toUrl().openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setFixedLengthStreamingMode(1024 * 1024 * 1024) // 1 GB
    connection.connect()
    val out = connection.outputStream
    val data = ByteArray(1024 * 1024)
    var i = 0
    while (i < 1024) {
      try {
        out!!.write(data)
        out.flush()
        if (i == 513) {
          // pause slightly after half way to make result more predictable
          Thread.sleep(100)
        }
      } catch (e: IOException) {
        break
      }
      i++
    }
    // Halfway +/- 0.5%
    assertThat(i.toFloat()).isCloseTo(512f, Offset.offset(5f))
  }

  @Test
  fun disconnectResponseHalfway() {
    server.enqueue(MockResponse.Builder()
      .body("ab")
      .socketPolicy(DisconnectDuringResponseBody)
      .build())
    val connection = server.url("/").toUrl().openConnection()
    assertThat(connection!!.contentLength).isEqualTo(2)
    val inputStream = connection.getInputStream()
    assertThat(inputStream!!.read()).isEqualTo('a'.code)
    try {
      val byteRead = inputStream.read()
      // OpenJDK behavior: end of stream.
      assertThat(byteRead).isEqualTo(-1)
    } catch (e: ProtocolException) {
      // On Android, HttpURLConnection is implemented by OkHttp v2. OkHttp
      // treats an incomplete response body as a ProtocolException.
    }
  }

  private fun headersToList(response: MockResponse.Builder): List<String> {
    val headers = response.build().headers
    return headers.map { (key, value) -> "$key: $value" }.toList()
  }

  @Test
  fun shutdownWithoutStart() {
    val server = MockWebServer()
    server.shutdown()
  }

  @Test
  fun closeViaClosable() {
    val server: Closeable = MockWebServer()
    server.close()
  }

  @Test
  fun shutdownWithoutEnqueue() {
    val server = MockWebServer()
    server.start()
    server.shutdown()
  }

  @Test
  fun portImplicitlyStarts() {
    assertThat(server.port).isGreaterThan(0)
  }

  @Test
  fun hostnameImplicitlyStarts() {
    assertThat(server.hostName).isNotNull
  }

  @Test
  fun toProxyAddressImplicitlyStarts() {
    assertThat(server.toProxyAddress()).isNotNull
  }

  @Test
  fun differentInstancesGetDifferentPorts() {
    val other = MockWebServer()
    assertThat(other.port).isNotEqualTo(server.port)
    other.shutdown()
  }

  @Test
  fun shutdownWhileBlockedDispatching() {
    // Enqueue a request that'll cause MockWebServer to hang on QueueDispatcher.dispatch().
    val connection = server.url("/").toUrl().openConnection() as HttpURLConnection
    connection.readTimeout = 500
    try {
      connection.responseCode
      fail<Unit>()
    } catch (expected: SocketTimeoutException) {
      // Expected.
    }

    // Shutting down the server should unblock the dispatcher.
    server.shutdown()
  }

  @Test
  fun requestUrlReconstructed() {
    server.enqueue(MockResponse.Builder()
      .body("hello world")
      .build())
    val url = server.url("/a/deep/path?key=foo%20bar").toUrl()
    val connection = url.openConnection() as HttpURLConnection
    val inputStream = connection.inputStream
    val reader = BufferedReader(InputStreamReader(inputStream, UTF_8))
    assertThat(connection.responseCode).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(reader.readLine()).isEqualTo("hello world")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo(
      "GET /a/deep/path?key=foo%20bar HTTP/1.1")
    val requestUrl = request.requestUrl
    assertThat(requestUrl!!.scheme).isEqualTo("http")
    assertThat(requestUrl.host).isEqualTo(server.hostName)
    assertThat(requestUrl.port).isEqualTo(server.port)
    assertThat(requestUrl.encodedPath).isEqualTo("/a/deep/path")
    assertThat(requestUrl.queryParameter("key")).isEqualTo("foo bar")
  }

  @Test
  fun shutdownServerAfterRequest() {
    server.enqueue(MockResponse.Builder()
      .socketPolicy(ShutdownServerAfterResponse)
      .build())
    val url = server.url("/").toUrl()
    val connection = url.openConnection() as HttpURLConnection
    assertThat(connection.responseCode).isEqualTo(HttpURLConnection.HTTP_OK)
    val refusedConnection = url.openConnection() as HttpURLConnection
    try {
      refusedConnection.responseCode
      fail<Any?>("Second connection should be refused")
    } catch (e: ConnectException) {
      assertThat(e.message).contains("refused")
    }
  }

  @Test
  fun http100Continue() {
    server.enqueue(MockResponse.Builder()
      .body("response")
      .build())
    val url = server.url("/").toUrl()
    val connection = url.openConnection() as HttpURLConnection
    connection.doOutput = true
    connection.setRequestProperty("Expect", "100-Continue")
    connection.outputStream.write("request".toByteArray(UTF_8))
    val inputStream = connection.inputStream
    val reader = BufferedReader(InputStreamReader(inputStream, UTF_8))
    assertThat(reader.readLine()).isEqualTo("response")
    val request = server.takeRequest()
    assertThat(request.body.readUtf8()).isEqualTo("request")
  }

  @Test
  fun multiple1xxResponses() {
    server.enqueue(MockResponse.Builder()
      .add100Continue()
      .add100Continue()
      .body("response")
      .build())
    val url = server.url("/").toUrl()
    val connection = url.openConnection() as HttpURLConnection
    connection.doOutput = true
    connection.outputStream.write("request".toByteArray(UTF_8))
    val inputStream = connection.inputStream
    val reader = BufferedReader(InputStreamReader(inputStream, UTF_8))
    assertThat(reader.readLine()).isEqualTo("response")
    val request = server.takeRequest()
    assertThat(request.body.readUtf8()).isEqualTo("request")
  }

  @Test
  fun testH2PriorKnowledgeServerFallback() {
    try {
      server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_1_1)
      fail<Unit>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
        "protocols containing h2_prior_knowledge cannot use other protocols: "
          + "[h2_prior_knowledge, http/1.1]")
    }
  }

  @Test
  fun testH2PriorKnowledgeServerDuplicates() {
    try {
      // Treating this use case as user error
      server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.H2_PRIOR_KNOWLEDGE)
      fail<Unit>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
        "protocols containing h2_prior_knowledge cannot use other protocols: "
          + "[h2_prior_knowledge, h2_prior_knowledge]")
    }
  }

  @Test
  fun testMockWebServerH2PriorKnowledgeProtocol() {
    server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
    assertThat(server.protocols.size).isEqualTo(1)
    assertThat(server.protocols[0]).isEqualTo(Protocol.H2_PRIOR_KNOWLEDGE)
  }

  @Test
  fun https() {
    val handshakeCertificates = platform.localhostHandshakeCertificates()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse.Builder()
      .body("abc")
      .build())
    val url = server.url("/")
    val connection = url.toUrl().openConnection() as HttpsURLConnection
    connection.sslSocketFactory = handshakeCertificates.sslSocketFactory()
    connection.hostnameVerifier = RecordingHostnameVerifier()
    assertThat(connection.responseCode).isEqualTo(HttpURLConnection.HTTP_OK)
    val reader = BufferedReader(InputStreamReader(connection.inputStream, UTF_8))
    assertThat(reader.readLine()).isEqualTo("abc")
    val request = server.takeRequest()
    assertThat(request.requestUrl!!.scheme).isEqualTo("https")
    val handshake = request.handshake
    assertThat(handshake!!.tlsVersion).isNotNull
    assertThat(handshake.cipherSuite).isNotNull
    assertThat(handshake.localPrincipal).isNotNull
    assertThat(handshake.localCertificates.size).isEqualTo(1)
    assertThat(handshake.peerPrincipal).isNull()
    assertThat(handshake.peerCertificates.size).isEqualTo(0)
  }

  @Test
  fun httpsWithClientAuth() {
    platform.assumeNotBouncyCastle()
    platform.assumeNotConscrypt()

    val clientCa = HeldCertificate.Builder()
      .certificateAuthority(0)
      .build()
    val serverCa = HeldCertificate.Builder()
      .certificateAuthority(0)
      .build()
    val serverCertificate = HeldCertificate.Builder()
      .signedBy(serverCa)
      .addSubjectAlternativeName(server.hostName)
      .build()
    val serverHandshakeCertificates = HandshakeCertificates.Builder()
      .addTrustedCertificate(clientCa.certificate)
      .heldCertificate(serverCertificate)
      .build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse.Builder()
      .body("abc")
      .build())
    server.requestClientAuth()
    val clientCertificate = HeldCertificate.Builder()
      .signedBy(clientCa)
      .build()
    val clientHandshakeCertificates = HandshakeCertificates.Builder()
      .addTrustedCertificate(serverCa.certificate)
      .heldCertificate(clientCertificate)
      .build()
    val url = server.url("/")
    val connection = url.toUrl().openConnection() as HttpsURLConnection
    connection.sslSocketFactory = clientHandshakeCertificates.sslSocketFactory()
    connection.hostnameVerifier = RecordingHostnameVerifier()
    assertThat(connection.responseCode).isEqualTo(HttpURLConnection.HTTP_OK)
    val reader = BufferedReader(InputStreamReader(connection.inputStream, UTF_8))
    assertThat(reader.readLine()).isEqualTo("abc")
    val request = server.takeRequest()
    assertThat(request.requestUrl!!.scheme).isEqualTo("https")
    val handshake = request.handshake
    assertThat(handshake!!.tlsVersion).isNotNull
    assertThat(handshake.cipherSuite).isNotNull
    assertThat(handshake.localPrincipal).isNotNull
    assertThat(handshake.localCertificates.size).isEqualTo(1)
    assertThat(handshake.peerPrincipal).isNotNull
    assertThat(handshake.peerCertificates.size).isEqualTo(1)
  }

  @Test
  fun proxiedRequestGetsCorrectRequestUrl() {
    server.enqueue(MockResponse.Builder()
      .body("Result")
      .build())
    val proxiedClient = OkHttpClient.Builder()
      .proxy(server.toProxyAddress())
      .readTimeout(Duration.ofMillis(100))
      .build()
    val request = Request.Builder().url("http://android.com/").build()
    proxiedClient.newCall(request).execute().use { response ->
      assertThat(response.body.string()).isEqualTo("Result")
    }
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.requestUrl).isEqualTo("http://android.com/".toHttpUrl())
  }

  @Test
  fun startTwice() {
    val server2 = MockWebServer()
    server2.start()
    server2.start()
    server2.shutdown()
  }

  @Test
  fun shutdownTwice() {
    val server2 = MockWebServer()
    server2.start()
    server2.shutdown()
    try {
      server2.start()
      fail<Unit>()
    } catch (expected: IllegalStateException) {
      // Expected.
    }
    server2.shutdown()
  }
}
