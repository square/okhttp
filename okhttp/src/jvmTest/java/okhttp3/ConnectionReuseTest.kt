/*
 * Copyright (C) 2015 Square, Inc.
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

import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.closeQuietly
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Timeout(30)
@Tag("Slowish")
class ConnectionReuseTest {
  @RegisterExtension
  val platform: PlatformRule = PlatformRule()

  @RegisterExtension
  val clientTestRule: OkHttpClientTestRule = OkHttpClientTestRule()

  private lateinit var server: MockWebServer
  private val handshakeCertificates: HandshakeCertificates = localhost()
  private var client: OkHttpClient = clientTestRule.newClient()

  @BeforeEach fun setUp(server: MockWebServer) {
    this.server = server
  }

  @Test fun connectionsAreReused() {
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    assertConnectionReused(request, request)
  }

  @Test fun connectionsAreReusedForPosts() {
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .post("request body".toRequestBody("text/plain".toMediaType()))
      .build()
    assertConnectionReused(request, request)
  }

  @Test fun connectionsAreReusedWithHttp2() {
    platform.assumeNotBouncyCastle()
    enableHttp2()
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    assertConnectionReused(request, request)
  }

  @Test fun connectionsAreNotReusedWithRequestConnectionClose() {
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    val requestA = Request.Builder()
      .url(server.url("/"))
      .header("Connection", "close")
      .build()
    val requestB = Request.Builder()
      .url(server.url("/"))
      .build()
    assertConnectionNotReused(requestA, requestB)
  }

  @Test fun connectionsAreNotReusedWithResponseConnectionClose() {
    server.enqueue(
      MockResponse()
        .addHeader("Connection", "close")
        .setBody("a")
    )
    server.enqueue(MockResponse().setBody("b"))
    val requestA = Request.Builder()
      .url(server.url("/"))
      .build()
    val requestB = Request.Builder()
      .url(server.url("/"))
      .build()
    assertConnectionNotReused(requestA, requestB)
  }

  @Test fun connectionsAreNotReusedWithUnknownLengthResponseBody() {
    server.enqueue(
      MockResponse()
        .setBody("a")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .clearHeaders()
    )
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    assertConnectionNotReused(request, request)
  }

  @Test fun connectionsAreNotReusedIfPoolIsSizeZero() {
    client = client.newBuilder()
      .connectionPool(ConnectionPool(0, 5, TimeUnit.SECONDS))
      .build()
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    assertConnectionNotReused(request, request)
  }

  @Test fun connectionsReusedWithRedirectEvenIfPoolIsSizeZero() {
    client = client.newBuilder()
      .connectionPool(ConnectionPool(0, 5, TimeUnit.SECONDS))
      .build()
    server.enqueue(
      MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBody("a")
    )
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("b")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test fun connectionsNotReusedWithRedirectIfDiscardingResponseIsSlow() {
    client = client.newBuilder()
      .connectionPool(ConnectionPool(0, 5, TimeUnit.SECONDS))
      .build()
    server.enqueue(
      MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setBody("a")
    )
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val response = client.newCall(request).execute()
    assertThat(response.body!!.string()).isEqualTo("b")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Test fun silentRetryWhenIdempotentRequestFailsOnReusedConnection() {
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val responseA = client.newCall(request).execute()
    assertThat(responseA.body!!.string()).isEqualTo("a")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    val responseB = client.newCall(request).execute()
    assertThat(responseB.body!!.string()).isEqualTo("b")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Test fun http2ConnectionsAreSharedBeforeResponseIsConsumed() {
    platform.assumeNotBouncyCastle()
    enableHttp2()
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val response1 = client.newCall(request).execute()
    val response2 = client.newCall(request).execute()
    response1.body!!.string() // Discard the response body.
    response2.body!!.string() // Discard the response body.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(1)
  }

  @Test fun connectionsAreEvicted() {
    server.enqueue(MockResponse().setBody("a"))
    server.enqueue(MockResponse().setBody("b"))
    client = client.newBuilder()
      .connectionPool(ConnectionPool(5, 250, TimeUnit.MILLISECONDS))
      .build()
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body!!.string()).isEqualTo("a")

    // Give the thread pool a chance to evict.
    Thread.sleep(500)
    val response2 = client.newCall(request).execute()
    assertThat(response2.body!!.string()).isEqualTo("b")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Test fun connectionsAreNotReusedIfSslSocketFactoryChanges() {
    platform.assumeNotBouncyCastle()
    enableHttps()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val response = client.newCall(request).execute()
    response.body!!.close()

    // This client shares a connection pool but has a different SSL socket factory.
    val handshakeCertificates2 = HandshakeCertificates.Builder().build()
    val anotherClient = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates2.sslSocketFactory(), handshakeCertificates2.trustManager
      )
      .build()

    // This client fails to connect because the new SSL socket factory refuses.
    try {
      anotherClient.newCall(request).execute()
      fail<Any?>()
    } catch (expected: SSLException) {
    }
  }

  @Test fun connectionsAreNotReusedIfHostnameVerifierChanges() {
    platform.assumeNotBouncyCastle()
    enableHttps()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val response1 = client.newCall(request).execute()
    response1.body!!.close()

    // This client shares a connection pool but has a different SSL socket factory.
    val anotherClient = client.newBuilder()
      .hostnameVerifier(RecordingHostnameVerifier())
      .build()
    val response2 = anotherClient.newCall(request).execute()
    response2.body!!.close()
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  /**
   * Regression test for an edge case where closing response body in the HTTP engine doesn't release
   * the corresponding stream allocation. This test keeps those response bodies alive and reads
   * them after the redirect has completed. This forces a connection to not be reused where it would
   * be otherwise.
   *
   *
   * This test leaks a response body by not closing it.
   *
   * https://github.com/square/okhttp/issues/2409
   */
  @Test fun connectionsAreNotReusedIfNetworkInterceptorInterferes() {
    val responsesNotClosed: MutableList<Response?> = ArrayList()
    client =
      client.newBuilder()
        // Since this test knowingly leaks a connection, avoid using the default shared connection
        // pool, which should remain clean for subsequent tests.
        .connectionPool(ConnectionPool())
        .addNetworkInterceptor(Interceptor { chain: Interceptor.Chain? ->
          val response = chain!!.proceed(
            chain.request()
          )
          responsesNotClosed.add(response)
          response
            .newBuilder()
            .body("unrelated response body!".toResponseBody(null))
            .build()
        })
        .build()
    server.enqueue(
      MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBody("/a has moved!")
    )
    server.enqueue(
      MockResponse()
        .setBody("/b is here")
    )
    val request = Request.Builder()
      .url(server.url("/"))
      .build()
    val call = client.newCall(request)
    call.execute().use { response ->
      assertThat(
        response.body!!.string()
      ).isEqualTo("unrelated response body!")
    }
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    // No connection reuse.
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    for (response in responsesNotClosed) {
      response!!.closeQuietly()
    }
  }

  private fun enableHttps() {
    enableHttpsAndAlpn(Protocol.HTTP_1_1)
  }

  private fun enableHttp2() {
    platform.assumeHttp2Support()
    enableHttpsAndAlpn(Protocol.HTTP_2, Protocol.HTTP_1_1)
  }

  private fun enableHttpsAndAlpn(vararg protocols: Protocol) {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(),
        handshakeCertificates.trustManager
      )
      .hostnameVerifier(RecordingHostnameVerifier())
      .protocols(protocols.toList())
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    server.protocols = client.protocols
  }

  private fun assertConnectionReused(vararg requests: Request?) {
    for (i in requests.indices) {
      val response = client.newCall(requests[i]!!).execute()
      response.body!!.string() // Discard the response body.
      assertThat(server.takeRequest().sequenceNumber).isEqualTo(i)
    }
  }

  private fun assertConnectionNotReused(vararg requests: Request?) {
    for (request in requests) {
      val response = client.newCall(request!!).execute()
      response.body!!.string() // Discard the response body.
      assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    }
  }
}
