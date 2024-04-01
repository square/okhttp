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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameAs
import java.io.IOException
import java.net.Proxy
import java.security.cert.X509Certificate
import java.time.Duration
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.DisconnectAtStart
import mockwebserver3.SocketPolicy.ShutdownOutputAtEnd
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TestUtil.assertSuppressed
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.connection.RealConnection.Companion.IDLE_CONNECTION_HEALTHY_NS
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest

@Timeout(30)
class CallKotlinTest {
  @JvmField @RegisterExtension
  val platform = PlatformRule()

  @JvmField @RegisterExtension
  val clientTestRule =
    OkHttpClientTestRule().apply {
      recordFrames = true
      recordSslDebug = true
    }

  private var client = clientTestRule.newClient()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
  }

  @Test
  fun legalToExecuteTwiceCloning() {
    server.enqueue(MockResponse(body = "abc"))
    server.enqueue(MockResponse(body = "def"))

    val request = Request(server.url("/"))

    val call = client.newCall(request)
    val response1 = call.execute()

    val cloned = call.clone()
    val response2 = cloned.execute()

    assertThat("abc").isEqualTo(response1.body.string())
    assertThat("def").isEqualTo(response2.body.string())
  }

  @Test
  @Flaky
  fun testMockWebserverRequest() {
    enableTls()

    server.enqueue(MockResponse(body = "abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals(
        "CN=localhost",
        (response.handshake!!.peerCertificates.single() as X509Certificate).subjectDN.name,
      )
    }
  }

  private fun enableTls() {
    client =
      client.newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  @RetryingTest(5)
  @Flaky
  fun testHeadAfterPut() {
    class ErringRequestBody : RequestBody() {
      override fun contentType(): MediaType {
        return "application/xml".toMediaType()
      }

      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("<el")
        sink.flush()
        throw IOException("failed to stream the XML")
      }
    }

    class ValidRequestBody : RequestBody() {
      override fun contentType(): MediaType {
        return "application/xml".toMediaType()
      }

      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("<element/>")
        sink.flush()
      }
    }

    server.enqueue(MockResponse(code = 201))
    server.enqueue(MockResponse(code = 204))
    server.enqueue(MockResponse(code = 204))

    val endpointUrl = server.url("/endpoint")

    var request =
      Request.Builder()
        .url(endpointUrl)
        .header("Content-Type", "application/xml")
        .put(ValidRequestBody())
        .build()
    client.newCall(request).execute().use {
      assertEquals(201, it.code)
    }

    request =
      Request.Builder()
        .url(endpointUrl)
        .head()
        .build()
    client.newCall(request).execute().use {
      assertEquals(204, it.code)
    }

    request =
      Request.Builder()
        .url(endpointUrl)
        .header("Content-Type", "application/xml")
        .put(ErringRequestBody())
        .build()
    assertFailsWith<IOException> {
      client.newCall(request).execute()
    }

    request =
      Request.Builder()
        .url(endpointUrl)
        .head()
        .build()

    client.newCall(request).execute().use {
      assertEquals(204, it.code)
    }
  }

  @Test
  fun staleConnectionNotReusedForNonIdempotentRequest() {
    // Capture the connection so that we can later make it stale.
    var connection: RealConnection? = null
    client =
      client.newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain ->
            connection = chain.connection() as RealConnection
            chain.proceed(chain.request())
          },
        )
        .build()

    server.enqueue(
      MockResponse(
        body = "a",
        socketPolicy = ShutdownOutputAtEnd,
      ),
    )
    server.enqueue(MockResponse(body = "b"))

    val requestA = Request(server.url("/"))
    val responseA = client.newCall(requestA).execute()

    assertThat(responseA.body.string()).isEqualTo("a")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Give the socket a chance to become stale.
    connection!!.idleAtNs -= IDLE_CONNECTION_HEALTHY_NS
    Thread.sleep(250)

    val requestB =
      Request(
        url = server.url("/"),
        body = "b".toRequestBody("text/plain".toMediaType()),
      )
    val responseB = client.newCall(requestB).execute()
    assertThat(responseB.body.string()).isEqualTo("b")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  /** Confirm suppressed exceptions that occur while connecting are returned. */
  @Test fun connectExceptionsAreReturnedAsSuppressed() {
    val proxySelector = RecordingProxySelector()
    proxySelector.proxies.add(Proxy(Proxy.Type.HTTP, TestUtil.UNREACHABLE_ADDRESS_IPV4))
    proxySelector.proxies.add(Proxy.NO_PROXY)
    server.shutdown()

    client =
      client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(Duration.ofMillis(100))
        .connectTimeout(Duration.ofMillis(100))
        .build()

    val request = Request(server.url("/"))
    assertFailsWith<IOException> {
      client.newCall(request).execute()
    }.also { expected ->
      expected.assertSuppressed {
        val suppressed = it.single()
        assertThat(suppressed).isInstanceOf(IOException::class.java)
        assertThat(suppressed).isNotSameAs(expected)
      }
    }
  }

  /** Confirm suppressed exceptions that occur after connecting are returned. */
  @Test fun httpExceptionsAreReturnedAsSuppressed() {
    server.enqueue(MockResponse(socketPolicy = DisconnectAtStart))
    server.enqueue(MockResponse(socketPolicy = DisconnectAtStart))

    client =
      client.newBuilder()
        .dns(DoubleInetAddressDns()) // Two routes so we get two failures.
        .build()

    val request = Request(server.url("/"))
    assertFailsWith<IOException> {
      client.newCall(request).execute()
    }.also { expected ->
      expected.assertSuppressed {
        val suppressed = it.single()
        assertThat(suppressed).isInstanceOf(IOException::class.java)
        assertThat(suppressed).isNotSameAs(expected)
      }
    }
  }

  @Test
  fun responseRequestIsLastRedirect() {
    server.enqueue(
      MockResponse(
        code = 302,
        headers = headersOf("Location", "/b"),
      ),
    )
    server.enqueue(MockResponse())

    val request = Request(server.url("/"))
    val call = client.newCall(request)
    val response = call.execute()

    assertThat(response.request.url.encodedPath).isEqualTo("/b")
    assertThat(response.request.headers).isEqualTo(headersOf())
  }
}
