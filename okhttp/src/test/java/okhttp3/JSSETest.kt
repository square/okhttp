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

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.TestUtil.assumeNetwork
import okhttp3.internal.connection
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion
import okhttp3.tls.internal.TlsUtil
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class JSSETest(
  val server: MockWebServer
) {
  @JvmField @RegisterExtension var platform = PlatformRule()
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule()

  private val handshakeCertificates = TlsUtil.localhost()

  var client = clientTestRule.newClient()

  @BeforeEach
  fun setUp() {
    // Default after JDK 14, but we are avoiding tests that assume special setup.
    // System.setProperty("jdk.tls.client.enableSessionTicketExtension", "true")
    // System.setProperty("jdk.tls.server.enableSessionTicketExtension", "true")

    platform.assumeJdk9()
  }

  @Test
  fun testTlsv13Works() {
    // https://docs.oracle.com/en/java/javase/14/security/java-secure-socket-extension-jsse-reference-guide.html
    // TODO test jdk.tls.client.enableSessionTicketExtension
    // TODO check debugging information

    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      if (PlatformVersion.majorVersion > 11) {
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      }
      if (PlatformVersion.majorVersion > 8) {
        assertEquals(Protocol.HTTP_2, response.protocol)
      }

      assertThat(response.connection.socket().javaClass.name).isEqualTo(
        "sun.security.ssl.SSLSocketImpl"
      )
    }
  }

  @Test
  fun testSupportedProtocols() {
    val factory = SSLSocketFactory.getDefault()
    assertThat(factory.javaClass.name).isEqualTo("sun.security.ssl.SSLSocketFactoryImpl")
    val s = factory.createSocket() as SSLSocket

    when {
      PlatformVersion.majorVersion > 11 -> assertThat(s.enabledProtocols.toList()).containsExactly(
        "TLSv1.3", "TLSv1.2"
      )
      // Not much we can guarantee on JDK 11.
      PlatformVersion.majorVersion == 11 -> assertThat(s.enabledProtocols.toList()).contains(
        "TLSv1.2"
      )
      PlatformVersion.majorVersion == 8 -> assertThat(s.enabledProtocols.toList()).contains(
        "TLSv1.2", "TLSv1.1", "TLSv1"
      )
      else -> assertThat(s.enabledProtocols.toList()).containsExactly(
        "TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"
      )
    }
  }

  @Test
  @Disabled
  fun testFacebook() {
    val sessionIds = mutableListOf<String>()

    assumeNetwork()

    client = client.newBuilder()
      .eventListenerFactory(clientTestRule.wrap(object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
          val sslSocket = connection.socket() as SSLSocket

          sessionIds.add(sslSocket.session.id.toByteString().hex())
        }
      }))
      .build()

    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }

    client.connectionPool.evictAll()
    assertEquals(0, client.connectionPool.connectionCount())

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }

    assertEquals(2, sessionIds.size)
    assertNotEquals(sessionIds[0], sessionIds[1])
    assertThat(sessionIds[0]).isNotBlank()
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }
}
