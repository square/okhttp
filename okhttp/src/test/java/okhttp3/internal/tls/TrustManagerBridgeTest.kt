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
package okhttp3.internal.tls

import java.net.InetAddress
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.CertificatePinner.Companion.DEFAULT
import okhttp3.ConnectionSpec.Companion.CLEARTEXT
import okhttp3.ConnectionSpec.Companion.MODERN_TLS
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.Response
import okhttp3.TestUtil.assumeNetwork
import okhttp3.internal.platform.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Flaky
class TrustManagerBridgeTest {
  private lateinit var platformTrustManager: X509TrustManager

  val LOCALHOST_DNS = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
      listOf(InetAddress.getByName("localhost"))
  }

  @get:Rule
  val clientTestRule = OkHttpClientTestRule()

  val localhostName = InetAddress.getByName("localhost").canonicalHostName

  // Generate a self-signed cert for the server to serve and the client to trust.
  val heldCertificate = HeldCertificate.Builder()
      .commonName("localhost")
      .addSubjectAlternativeName(localhostName)
      .build()

  private val localhost: HandshakeCertificates =
    HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()

  @get:Rule
  val platform = PlatformRule()

  var client = clientTestRule.newClientBuilder()
      .insecureForHost("localhost")
      .insecureForHost("www.facebook.com")
      .build()

  @get:Rule
  val server = MockWebServer().apply {
    val serverSslContext = Platform.get()
        .newSSLContext()
        .apply {
          init(arrayOf(localhost.keyManager), arrayOf<TrustManager>(client.x509TrustManager!!), null)
        }

    useHttps(serverSslContext.socketFactory)
  }

  val googleUrl = "https://www.google.com/robots.txt"
  val facebookUrl = "https://www.facebook.com/robots.txt"
  val localhostUrl = server.url("/robots.txt").toString()

  val googleCerts: List<X509Certificate> by lazy {
    get(googleUrl).use {
      // TODO investigate async close behaviour
      it.body!!.string()
      it.handshake!!.peerCertificates.map { it as X509Certificate }
    }
  }

  private fun get(url: String): Response =
    client.newCall(
        Request.Builder()
            .url(url)
            .build()
    )
        .execute()

  @Before
  fun setup() {
    platformTrustManager = Platform.get()
        .platformTrustManager()
    server.useHttps(localhost.sslSocketFactory(), false)
  }

  @After
  fun cleanup() {
    client.close()
  }

  @Test fun testBuilderChains() {
    val client1a = OkHttpClient.Builder().build()
    val client1b = client1a.newBuilder().build()
    assertSame(client1a.sslSocketFactory, client1b.sslSocketFactory)
    assertSame(client1a.x509TrustManager, client1b.x509TrustManager)
    assertSame(client1a.certificateChainCleaner, client1b.certificateChainCleaner)
    assertSame(client1a.certificatePinner, client1b.certificatePinner)

    val clientHostOverrides = client1a.newBuilder().insecureForHost("localhost").build()
    assertNotSame(client1a.sslSocketFactory, clientHostOverrides.sslSocketFactory)
    assertNotSame(client1a.x509TrustManager, clientHostOverrides.x509TrustManager)
    assertNotSame(client1a.certificateChainCleaner, clientHostOverrides.certificateChainCleaner)
    // equals of BasicCertificateChainCleaner causes this to match
    assertSame(client1a.certificatePinner, clientHostOverrides.certificatePinner)

    // Plaintext tests
    val plaintextClient = client1a.newBuilder().connectionSpecs(listOf(CLEARTEXT)).build()
    try {
      plaintextClient.sslSocketFactory
      fail()
    } catch (ise: IllegalStateException) {
      // expected
    }
    assertNull(plaintextClient.x509TrustManager)
    assertNull(plaintextClient.certificateChainCleaner)
    assertSame(DEFAULT, plaintextClient.certificatePinner)
    // avoids using INSECURE because it would persist to chained
    assertSame(OkHostnameVerifier, plaintextClient.hostnameVerifier)

    val client2 = plaintextClient.newBuilder().connectionSpecs(listOf(MODERN_TLS)).build()
    assertNotNull(client2.x509TrustManager)
    assertNotNull(client2.certificateChainCleaner)
    assertNotNull(client2.certificatePinner)
    assertNotSame(client1a.sslSocketFactory, client2.sslSocketFactory)
    assertNotSame(client1a.x509TrustManager, client2.x509TrustManager)
    assertNotSame(client1a.certificateChainCleaner, client2.certificateChainCleaner)
    assertSame(DEFAULT, plaintextClient.certificatePinner)
    // avoids using INSECURE because it would persist to chained
    assertSame(OkHostnameVerifier, plaintextClient.hostnameVerifier)
  }

  @Test
  fun testWithoutOverrides() {
    val bridge2 = OkHttpClient.Builder().build().x509TrustManager!!

    assertTrue(bridge2 is X509ExtendedTrustManager)
    assertNotEquals(TrustManagerJvm::class.java, bridge2.javaClass)
  }

  @Test
  fun testWithOverrides() {
    val bridge2 = OkHttpClient.Builder().insecureForHost("localhost").build().x509TrustManager!!

    assertTrue(bridge2 is X509ExtendedTrustManager)
    assertEquals(TrustManagerJvm::class.java, bridge2.javaClass)

    val delegate = (bridge2 as TrustManagerJvm).default

    assertEquals(platformTrustManager.javaClass, delegate.javaClass)
  }

  @Test
  fun testCheckServerTrustedGoogleNoHost() {
    assumeNetwork()

    client.x509TrustManager!!.checkServerTrusted(googleCerts.toTypedArray(), "DHE_DSS")
  }

  @Test
  fun testCheckServerTrustedGoogleNoHostFailing() {
    try {
      client.x509TrustManager!!.checkServerTrusted(arrayOf(heldCertificate.certificate), "DHE_DSS")
      fail("expected to fail without targeted request")
    } catch (_: GeneralSecurityException) {
      // expected
    }
  }

  @Test
  fun testGoogleHttps() {
    assumeNetwork()

    get(googleUrl).use { response ->
      assertEquals(
          "CN=www.google.com, O=Google LLC, L=Mountain View, ST=California, C=US",
          response.subjectNames.firstOrNull()
      )
    }
  }

  @Test
  fun testFacebookInsecureHttps() {
    assumeNetwork()

    get(facebookUrl).use { response ->
      assertEquals(
          "CN=*.facebook.com, O=\"Facebook, Inc.\", L=Menlo Park, ST=California, C=US",
          response.subjectNames.firstOrNull()
      )
    }
  }

  @Test
  fun testFacebookFakedHttps() {
    server.enqueue(MockResponse().setBody("X"))

    client = client.newBuilder().dns(LOCALHOST_DNS).build()

    get("https://www.facebook.com:${server.port}/robots.txt").use { response ->
      assertEquals("X", response.body!!.string())
      // cleaner will avoid serving the wrong certificate
      assertNull(response.subjectNames.firstOrNull())
    }
  }

  @Test
  fun testGoogleFakedHttpsFails() {
    server.enqueue(MockResponse().setBody("X"))

    client = client.newBuilder().dns(LOCALHOST_DNS).build()

    try {
      get("https://www.google.com:${server.port}/robots.txt").close()
      fail()
    } catch (_: SSLPeerUnverifiedException) {
      // expected
    }
  }

  @Test
  fun testLocalhostInsecureHttps() {
    server.enqueue(MockResponse().setBody("X"))

    get(localhostUrl).use { response ->
      assertNull(response.subjectNames.firstOrNull())
    }
  }

  private val Response.subjectNames: List<String>
    get() = handshake!!.peerCertificates.map { (it as X509Certificate).subjectDN.name }

  fun OkHttpClient.close() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
  }
}
