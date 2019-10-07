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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import okio.BufferedSink
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

class CallKotlinTest {
  @JvmField @Rule val platform = PlatformRule()
  @JvmField @Rule val timeout: TestRule = Timeout(30_000, TimeUnit.MILLISECONDS)
  @JvmField @Rule val server = MockWebServer()
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  private var client = clientTestRule.newClient()
  private val handshakeCertificates = localhost()

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
}
