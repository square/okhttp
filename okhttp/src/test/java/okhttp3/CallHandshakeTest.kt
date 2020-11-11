/*
 * Copyright (C) 2020 Square, Inc.
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
import okhttp3.CipherSuite.Companion.TLS_AES_128_GCM_SHA256
import okhttp3.CipherSuite.Companion.TLS_AES_256_GCM_SHA384
import okhttp3.CipherSuite.Companion.TLS_CHACHA20_POLY1305_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
import okhttp3.CipherSuite.Companion.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
import okhttp3.internal.effectiveCipherSuites
import okhttp3.internal.platform.Platform
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import javax.net.ssl.SSLSocket

class CallHandshakeTest {
  private lateinit var client: OkHttpClient
  private lateinit var server: MockWebServer
  val handshakeCertificates = localhost()

  @RegisterExtension
  @JvmField
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  @JvmField
  var platform = PlatformRule()

  private lateinit var handshakeEnabledCipherSuites: List<String>
  private lateinit var defaultEnabledCipherSuites: List<String>
  private lateinit var defaultSupportedCipherSuites: List<String>

  val expectedModernTls12CipherSuites =
    listOf(TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384)
  val expectedModernTls13CipherSuites = listOf(TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384)

  @BeforeEach
  fun setup(server: MockWebServer) {
    this.server = server

    server.enqueue(MockResponse().setResponseCode(200))

    client = clientTestRule.newClientBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
      .hostnameVerifier(RecordingHostnameVerifier())
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)

    defaultEnabledCipherSuites =
      handshakeCertificates.sslSocketFactory().defaultCipherSuites.toList()
    defaultSupportedCipherSuites =
      handshakeCertificates.sslSocketFactory().supportedCipherSuites.toList()
  }

  @Test
  fun testDefaultHandshakeCipherSuiteOrderingTls12Restricted() {
    val client = makeClient(ConnectionSpec.RESTRICTED_TLS, TlsVersion.TLS_1_2)

    val handshake = makeRequest(client)

    assertThat(handshake.cipherSuite).isIn(expectedModernTls12CipherSuites)

    // Probably something like
    // TLS_AES_128_GCM_SHA256
    // TLS_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    assertThat(handshakeEnabledCipherSuites).containsExactlyElementsOf(
      expectedConnectionCipherSuites(client))
  }

  @Test
  fun testDefaultHandshakeCipherSuiteOrderingTls12Modern() {
    val client = makeClient(ConnectionSpec.MODERN_TLS, TlsVersion.TLS_1_2)

    val handshake = makeRequest(client)

    assertThat(handshake.cipherSuite).isIn(expectedModernTls12CipherSuites)

    // Probably something like
    // TLS_AES_128_GCM_SHA256
    // TLS_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // TLS_RSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    // TLS_RSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
    // TLS_RSA_WITH_AES_256_CBC_SHA
    // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
    // TLS_RSA_WITH_AES_128_CBC_SHA
    assertThat(handshakeEnabledCipherSuites).containsExactlyElementsOf(
      expectedConnectionCipherSuites(client))
  }

  @Test
  fun testDefaultHandshakeCipherSuiteOrderingTls13Modern() {
    // Requires modern JVM
    platform.expectFailureOnJdkVersion(8)

    val client = makeClient(ConnectionSpec.MODERN_TLS, TlsVersion.TLS_1_3)

    val handshake = makeRequest(client)

    assertThat(handshake.cipherSuite).isIn(expectedModernTls13CipherSuites)

    // TODO: filter down to TLSv1.3 when only activated.
    // Probably something like
    // TLS_AES_128_GCM_SHA256
    // TLS_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // TLS_RSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    // TLS_RSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
    // TLS_RSA_WITH_AES_256_CBC_SHA
    // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
    // TLS_RSA_WITH_AES_128_CBC_SHA
    assertThat(handshakeEnabledCipherSuites).containsExactlyElementsOf(
      expectedConnectionCipherSuites(client))
  }

  @Test
  fun testHandshakeCipherSuiteOrderingWhenReversed() {
    val client = makeClient(ConnectionSpec.RESTRICTED_TLS, TlsVersion.TLS_1_2,
      defaultEnabledCipherSuites.asReversed())

    val handshake = makeRequest(client)

    // TODO better selection
    assertThat(handshake.cipherSuite).isIn(expectedModernTls12CipherSuites)

    // TODO reversed ciphers
    assertThat(handshakeEnabledCipherSuites).containsExactlyElementsOf(
      expectedConnectionCipherSuites(client))
  }

  @Test
  fun defaultOrderMaintained() {
    val client = makeClient()
    makeRequest(client)

    val socketOrderedByDefaults =
      handshakeEnabledCipherSuites.sortedBy { defaultEnabledCipherSuites.indexOf(it) }
    assertThat(handshakeEnabledCipherSuites).containsExactlyElementsOf(socketOrderedByDefaults)
  }

  @Test
  fun advertisedOrderInRestricted() {
    assertThat(ConnectionSpec.RESTRICTED_TLS.cipherSuites).containsExactly(
      TLS_AES_128_GCM_SHA256,
      TLS_AES_256_GCM_SHA384,
      TLS_CHACHA20_POLY1305_SHA256,
      TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
      TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
    )
  }

  @Test
  fun effectiveOrderInRestrictedJdk11() {
    platform.assumeJdkVersion(11)

    val platform = Platform.get()
    val platformDefaultCipherSuites =
      platform.newSslSocketFactory(platform.platformTrustManager()).defaultCipherSuites
    val cipherSuites =
      ConnectionSpec.RESTRICTED_TLS.effectiveCipherSuites(platformDefaultCipherSuites)
    assertThat(cipherSuites).containsExactlyElementsOf(listOf(
      TLS_AES_128_GCM_SHA256,
      TLS_AES_256_GCM_SHA384,
      TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,

      // Disabled
//      TLS_CHACHA20_POLY1305_SHA256,
//      TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
//      TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
    ).map { it.javaName })
  }

  private fun expectedConnectionCipherSuites(client: OkHttpClient): Set<String> {
    // TODO correct for the client provided order
//    return client.connectionSpecs.first().cipherSuites!!.map { it.javaName }.intersect(defaultEnabledCipherSuites)
    return defaultEnabledCipherSuites.intersect(
      client.connectionSpecs.first().cipherSuites!!.map { it.javaName })
  }

  private fun makeClient(
    connectionSpec: ConnectionSpec? = null,
    tlsVersion: TlsVersion? = null,
    cipherSuites: List<String>? = null
  ): OkHttpClient {
    return this.client.newBuilder()
      .apply {
        if (connectionSpec != null) {
          connectionSpecs(listOf(ConnectionSpec.Builder(connectionSpec)
            .apply {
              if (tlsVersion != null) {
                tlsVersions(tlsVersion)
              }
              if (cipherSuites != null) {
                cipherSuites(*cipherSuites.toTypedArray())
              }
            }
            .build()))
        }
      }
      .addNetworkInterceptor {
        val socket = it.connection()!!.socket() as SSLSocket

        handshakeEnabledCipherSuites = socket.enabledCipherSuites.toList()

        it.proceed(it.request())
      }
      .build()
  }

  private fun makeRequest(client: OkHttpClient): Handshake {
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    return call.execute().use { it.handshake!! }
  }
}