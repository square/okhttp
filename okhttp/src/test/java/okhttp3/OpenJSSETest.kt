/*
 * Copyright (C) 2019 Square, Inc.
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

import okhttp3.TestUtil.assumeNetwork
import okhttp3.internal.platform.OpenJSSEPlatform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.openjsse.sun.security.ssl.SSLSocketFactoryImpl
import org.openjsse.sun.security.ssl.SSLSocketImpl
import java.net.InetAddress

class OpenJSSETest {
  @JvmField @Rule var platform = PlatformRule()
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()
  @JvmField @Rule val server = MockWebServer()
  var client = clientTestRule.newClient()

  @Before
  fun setUp() {
    platform.assumeOpenJSSE()
  }

  @Test
  fun testTlsv13Works() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      assertEquals(Protocol.HTTP_2, response.protocol)

      assertThat(response.exchange?.connection()?.socket()).isInstanceOf(SSLSocketImpl::class.java)
    }
  }

  @Test
  fun testSupportedProtocols() {
    val factory = SSLSocketFactoryImpl()
    val s = factory.createSocket() as SSLSocketImpl

    assertEquals(listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"), s.enabledProtocols.toList())
  }

  @Test
  @Ignore
  fun testMozilla() {
    assumeNetwork()

    val request = Request.Builder().url("https://mozilla.org/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }
  }

  @Test
  fun testBuildIfSupported() {
    val actual = OpenJSSEPlatform.buildIfSupported()
    assertThat(actual).isNotNull
  }

  private fun enableTls() {
    // Generate a self-signed cert for the server to serve and the client to trust.
    // can't use TlsUtil.localhost with a non OpenJSSE trust manager
    val heldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
        .build()
    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }
}
