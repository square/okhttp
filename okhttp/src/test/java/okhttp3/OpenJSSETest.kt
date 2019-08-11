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

import okhttp3.Protocol.HTTP_2
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.openjsse.net.ssl.OpenJSSE
import org.openjsse.sun.security.ssl.SSLSocketFactoryImpl
import org.openjsse.sun.security.ssl.SSLSocketImpl
import java.net.InetAddress
import java.security.Security

class OpenJSSETest {
  @JvmField @Rule var platform = PlatformRule()
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()
  @JvmField @Rule val server = MockWebServer()
  lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    platform.assumeJdk8()

    Security.insertProviderAt(OpenJSSE(), 1)
    client = clientTestRule.newClient()
  }

  @After
  fun cleanup() {
    Security.removeProvider("OpenJSSE")
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
      assertEquals(Protocol.HTTP_1_1, response.protocol)

      assertThat(response.exchange?.connection()?.socket()).isInstanceOf(SSLSocketImpl::class.java)
    }
  }

  @Test
  fun testSupportedProtocols() {
    val factory = SSLSocketFactoryImpl()
    val s = factory.createSocket() as SSLSocketImpl

    // A Public API available is available to use in a custom Platform
    s.setHandshakeApplicationProtocolSelector { _, _ -> HTTP_2.toString() }
    assertEquals(listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"), s.enabledProtocols.toList())
  }

  private fun enableTls() {
    // Generate a self-signed cert for the server to serve and the client to trust.
    // can't use localhost with a non OpenJSSE trust manager
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