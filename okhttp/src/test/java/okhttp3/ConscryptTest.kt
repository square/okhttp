/*
 * Copyright (C) 2018 Square, Inc.
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
import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Platform
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.SSLSocket

class ConscryptTest {
  @JvmField @RegisterExtension val platform = PlatformRule.conscrypt()
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule()

  private var client = clientTestRule.newClient()

  private val handshakeCertificates = TlsUtil.localhost()

  private lateinit var server: MockWebServer

  @BeforeEach @Throws(Exception::class) fun setUp(server: MockWebServer) {
    platform.assumeConscrypt()
    this.server = server
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  @Test
  fun testTrustManager() {
    assertThat(Conscrypt.isConscrypt(Platform.get().platformTrustManager())).isTrue()
  }

  @ParameterizedTest(name = "{displayName}({arguments})")
  @ValueSource(strings = ["TLSv1.2", "TLSv1.3"])
  fun testSessionReuse(tlsVersion: String) {
    val sessionIds = mutableListOf<String>()

    enableTls()

    val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.forJavaName(tlsVersion))
      .build()
    client = client.newBuilder().connectionSpecs(listOf(spec)).eventListenerFactory(clientTestRule.wrap(object : EventListener() {
      override fun connectionAcquired(call: Call, connection: Connection) {
        val sslSocket = connection.socket() as SSLSocket

        sessionIds.add(sslSocket.session.id.toByteString().hex())
      }
    })).build()

    server.enqueue(MockResponse().setBody("abc1"))
    server.enqueue(MockResponse().setBody("abc2"))

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    client.connectionPool.evictAll()
    assertEquals(0, client.connectionPool.connectionCount())

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(2, sessionIds.size)
    assertEquals(sessionIds[0], sessionIds[1])
    assertThat(sessionIds[0]).isNotBlank()
  }

  @Test
  @Disabled
  fun testMozilla() {
    assumeNetwork()

    val request = Request.Builder().url("https://mozilla.org/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }
  }

  @Test
  @Disabled
  fun testGoogle() {
    assumeNetwork()

    val request = Request.Builder().url("https://google.com/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      if (it.handshake!!.tlsVersion != TlsVersion.TLS_1_3) {
        System.err.println("Flaky TLSv1.3 with google")
//    assertThat(it.handshake()!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
      }
    }
  }

  @Test
  fun testBuildIfSupported() {
    val actual = ConscryptPlatform.buildIfSupported()
    assertThat(actual).isNotNull
  }

  @Test
  fun testVersion() {
    val version = Conscrypt.version()

    assertTrue(ConscryptPlatform.atLeastVersion(1, 4, 9))
    assertTrue(ConscryptPlatform.atLeastVersion(version.major()))
    assertTrue(ConscryptPlatform.atLeastVersion(version.major(), version.minor()))
    assertTrue(ConscryptPlatform.atLeastVersion(version.major(), version.minor(), version.patch()))
    assertFalse(ConscryptPlatform.atLeastVersion(version.major(), version.minor(), version.patch() + 1))
    assertFalse(ConscryptPlatform.atLeastVersion(version.major(), version.minor() + 1))
    assertFalse(ConscryptPlatform.atLeastVersion(version.major() + 1))
  }
}
