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

import java.io.IOException
import java.net.Proxy
import java.security.cert.X509Certificate
import java.time.Duration
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.connection.RealConnection.Companion.IDLE_CONNECTION_HEALTHY_NS
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import okio.BufferedSink
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail

@Timeout(30)
class CallKotlinTest(
  val server: MockWebServer
) {
  @JvmField @RegisterExtension val platform = PlatformRule()
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = true
    recordSslDebug = true
  }

  private var client = clientTestRule.newClient()
  private val handshakeCertificates = localhost()

  @BeforeEach
  fun setup() {
    platform.assumeNotBouncyCastle()
  }

  @Test
  fun legalToExecuteTwiceCloning() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val call = client.newCall(request)
    val response1 = call.execute()

    val cloned = call.clone()
    val response2 = cloned.execute()

    assertThat("abc").isEqualTo(response1.body!!.string())
    assertThat("def").isEqualTo(response2.body!!.string())
  }

  @Test
  @Flaky
  fun testMockWebserverRequest() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals("CN=localhost",
          (response.handshake!!.peerCertificates.single() as X509Certificate).subjectDN.name)
    }
  }

  private fun enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  @Test
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

    server.enqueue(MockResponse().apply {
      setResponseCode(201)
    })
    server.enqueue(MockResponse().apply {
      setResponseCode(204)
    })
    server.enqueue(MockResponse().apply {
      setResponseCode(204)
    })

    val endpointUrl = server.url("/endpoint")

    var request = Request.Builder()
        .url(endpointUrl)
        .header("Content-Type", "application/xml")
        .put(ValidRequestBody())
        .build()
    // 201
    client.newCall(request).execute()

    request = Request.Builder()
        .url(endpointUrl)
        .head()
        .build()
    // 204
    client.newCall(request).execute()

    request = Request.Builder()
        .url(endpointUrl)
        .header("Content-Type", "application/xml")
        .put(ErringRequestBody())
        .build()
    try {
      client.newCall(request).execute()
      fail("test should always throw exception")
    } catch (_: IOException) {
      // NOTE: expected
    }

    request = Request.Builder()
        .url(endpointUrl)
        .head()
        .build()

    client.newCall(request).execute()

    var recordedRequest = server.takeRequest()
    assertEquals("PUT", recordedRequest.method)

    recordedRequest = server.takeRequest()
    assertEquals("HEAD", recordedRequest.method)

    recordedRequest = server.takeRequest()
    assertThat(recordedRequest.failure).isNotNull()

    recordedRequest = server.takeRequest()
    assertEquals("HEAD", recordedRequest.method)
  }

  @Test
  fun staleConnectionNotReusedForNonIdempotentRequest() {
    // Capture the connection so that we can later make it stale.
    var connection: RealConnection? = null
    client = client.newBuilder()
        .addNetworkInterceptor(Interceptor { chain ->
          connection = chain.connection() as RealConnection
          chain.proceed(chain.request())
        })
        .build()

    server.enqueue(MockResponse().setBody("a")
        .setSocketPolicy(SocketPolicy.SHUTDOWN_OUTPUT_AT_END))
    server.enqueue(MockResponse().setBody("b"))

    val requestA = Request.Builder()
        .url(server.url("/"))
        .build()
    val responseA = client.newCall(requestA).execute()

    assertThat(responseA.body!!.string()).isEqualTo("a")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)

    // Give the socket a chance to become stale.
    connection!!.idleAtNs -= IDLE_CONNECTION_HEALTHY_NS
    Thread.sleep(250)

    val requestB = Request.Builder()
        .url(server.url("/"))
        .post("b".toRequestBody("text/plain".toMediaType()))
        .build()
    val responseB = client.newCall(requestB).execute()
    assertThat(responseB.body!!.string()).isEqualTo("b")
    assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
  }

  @Test fun exceptionsAreReturnedAsSuppressed() {
    val proxySelector = RecordingProxySelector()
    proxySelector.proxies.add(Proxy(Proxy.Type.HTTP, TestUtil.UNREACHABLE_ADDRESS))
    proxySelector.proxies.add(Proxy.NO_PROXY)

    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(Duration.ofMillis(100))
        .connectTimeout(Duration.ofMillis(100))
        .build()

    val request = Request.Builder().url(server.url("/")).build()
    try {
      client.newCall(request).execute()
      fail("")
    } catch (expected: IOException) {
      assertThat(expected.suppressed).hasSize(1)
      val suppressed = expected.suppressed[0]
      assertThat(suppressed).isInstanceOf(IOException::class.java)
      assertThat(suppressed).isNotSameAs(expected)
    }
  }
}
