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
package okhttp.android.test

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.security.ProviderInstaller
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.Cache
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.RecordingEventListener
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.internal.asFactory
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http2.Http2
import okhttp3.internal.platform.Android10Platform
import okhttp3.internal.platform.AndroidPlatform
import okhttp3.internal.platform.Platform
import okhttp3.logging.LoggingEventListener
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.internal.TlsUtil.localhost
import okio.ByteString.Companion.toByteString
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.opentest4j.TestAbortedException
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@ExtendWith(MockWebServerExtension::class)
@Tag("Slow")
class OkHttpTest(val server: MockWebServer) {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension public val platform = PlatformRule()

  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension public val clientTestRule = OkHttpClientTestRule().apply {
    logger = Logger.getLogger(OkHttpTest::class.java.name)
  }

  private var client: OkHttpClient = clientTestRule.newClient()

  private val moshi = Moshi.Builder()
      .add(KotlinJsonAdapterFactory())
      .build()

  private val handshakeCertificates = localhost()

  @Test
  fun testPlatform() {
    assertTrue(Platform.isAndroid)

    if (Build.VERSION.SDK_INT >= 29) {
      assertTrue(Platform.get() is Android10Platform)
    } else {
      assertTrue(Platform.get() is AndroidPlatform)
    }
  }

  @Test
  fun testRequest() {
    assumeNetwork()

    val request = Request.Builder().url("https://api.twitter.com/robots.txt").build()

    val clientCertificates = HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .apply {
          if (Build.VERSION.SDK_INT >= 24) {
            addInsecureHost(server.hostName)
          }
        }
        .build()

    client = client.newBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }

    if (Build.VERSION.SDK_INT >= 24) {
      localhostInsecureRequest();
    }
  }

  @Test
  fun testRequestWithSniRequirement() {
    assumeNetwork()

    val request = Request.Builder().url("https://docs.fabric.io/android/changelog.html").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testConscryptRequest() {
    assumeNetwork()

    try {
      Security.insertProviderAt(Conscrypt.newProviderBuilder().build(), 1)

      val request = Request.Builder().url("https://facebook.com/robots.txt").build()

      var socketClass: String? = null

      val clientCertificates = HandshakeCertificates.Builder()
          .addPlatformTrustedCertificates()
          .addInsecureHost(server.hostName)
          .build()

      // Need fresh client to reset sslSocketFactoryOrNull
      client = OkHttpClient.Builder()
          .eventListenerFactory(clientTestRule.wrap(object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
          socketClass = connection.socket().javaClass.name
        }
      }))
          .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
          .build()

      val response = client.newCall(request).execute()

      response.use {
        assertEquals(Protocol.HTTP_2, response.protocol)
        assertEquals(200, response.code)
        // see https://github.com/google/conscrypt/blob/b9463b2f74df42d85c73715a5f19e005dfb7b802/android/src/main/java/org/conscrypt/Platform.java#L613
        when {
            Build.VERSION.SDK_INT >= 24 -> {
              // Conscrypt 2.5+ defaults to SSLEngine-based SSLSocket
              assertEquals("org.conscrypt.Java8EngineSocket", socketClass)
            }
            Build.VERSION.SDK_INT < 22 -> {
              assertEquals("org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter", socketClass)
            }
            else -> {
              assertEquals("org.conscrypt.ConscryptFileDescriptorSocket", socketClass)
            }
        }
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      }

      localhostInsecureRequest();
    } finally {
      Security.removeProvider("Conscrypt")
      client.close()
    }
  }

  @Test
  fun testRequestUsesPlayProvider() {
    assumeNetwork()

    try {
      try {
        ProviderInstaller.installIfNeeded(InstrumentationRegistry.getInstrumentation().targetContext)
      } catch (gpsnae: GooglePlayServicesNotAvailableException) {
        throw TestAbortedException("Google Play Services not available", gpsnae)
      }

      val clientCertificates = HandshakeCertificates.Builder()
          .addPlatformTrustedCertificates()
          .addInsecureHost(server.hostName)
          .build()

      val request = Request.Builder().url("https://facebook.com/robots.txt").build()

      var socketClass: String? = null

      // Need fresh client to reset sslSocketFactoryOrNull
      client = OkHttpClient.Builder()
          .eventListenerFactory(clientTestRule.wrap(object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
          socketClass = connection.socket().javaClass.name
        }
      }))
          .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
          .build()

      val response = client.newCall(request).execute()

      response.use {
        assertEquals(Protocol.HTTP_2, response.protocol)
        assertEquals(200, response.code)
        assertEquals("com.google.android.gms.org.conscrypt.Java8FileDescriptorSocket", socketClass)
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }

      localhostInsecureRequest();
    } finally {
      Security.removeProvider("GmsCore_OpenSSL")
      client.close()
    }
  }

  private fun localhostInsecureRequest() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)

    server.enqueue(MockResponse().setResponseCode(200))

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use {
      assertEquals(200, it.code)
      assertEquals(listOf<Certificate>(), it.handshake?.peerCertificates)
    }
  }

  @Test
  fun testRequestUsesAndroidConscrypt() {
    assumeNetwork()

    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    var socketClass: String? = null

    val clientCertificates = HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates().apply {
          if (Build.VERSION.SDK_INT >= 24) {
            addInsecureHost(server.hostName)
          }
        }
        .build()

    client = client.newBuilder()
        .eventListenerFactory(clientTestRule.wrap(object : EventListener() {
          override fun connectionAcquired(call: Call, connection: Connection) {
            socketClass = connection.socket().javaClass.name
          }
        }))
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(Protocol.HTTP_2, response.protocol)
      if (Build.VERSION.SDK_INT >= 29) {
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      } else {
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }
      assertEquals(200, response.code)
      assertTrue(socketClass?.startsWith("com.android.org.conscrypt.") == true)
    }

    if (Build.VERSION.SDK_INT >= 24) {
      localhostInsecureRequest();
    }
  }

  @Test
  fun testHttpRequestNotBlockedOnLegacyAndroid() {
    assumeTrue(Build.VERSION.SDK_INT < 23)

    val request = Request.Builder().url("http://squareup.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  @Disabled("cleartext required for additional okhttp wide tests")
  fun testHttpRequestBlocked() {
    assumeTrue(Build.VERSION.SDK_INT >= 23)

    val request = Request.Builder().url("http://squareup.com/robots.txt").build()

    try {
      client.newCall(request).execute()
      fail<Any>("expected cleartext blocking")
    } catch (_: java.net.UnknownServiceException) {
    }
  }

  data class HowsMySslResults(
    val unknown_cipher_suite_supported: Boolean,
    val beast_vuln: Boolean,
    val session_ticket_supported: Boolean,
    val tls_compression_supported: Boolean,
    val ephemeral_keys_supported: Boolean,
    val rating: String,
    val tls_version: String,
    val able_to_detect_n_minus_one_splitting: Boolean,
    val insecure_cipher_suites: Map<String, List<String>>,
    val given_cipher_suites: List<String>?
  )

  @Test
  @Disabled
  fun testSSLFeatures() {
    assumeNetwork()

    val request = Request.Builder().url("https://www.howsmyssl.com/a/check").build()

    val response = client.newCall(request).execute()

    val results = response.use {
      moshi.adapter(HowsMySslResults::class.java).fromJson(response.body!!.string())!!
    }

    Platform.get().log("results $results", Platform.WARN)

    assertTrue(results.session_ticket_supported)
    assertEquals("Probably Okay", results.rating)
    // TODO map to expected versions automatically, test ignored for now.  Run manually.
    assertEquals("TLS 1.3", results.tls_version)
    assertEquals(0, results.insecure_cipher_suites.size)

    assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
    assertEquals(Protocol.HTTP_2, response.protocol)
  }

  @Test
  fun testMockWebserverRequest() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals(Protocol.HTTP_2, response.protocol)
      val tlsVersion = response.handshake?.tlsVersion
      assertTrue(tlsVersion == TlsVersion.TLS_1_2 || tlsVersion == TlsVersion.TLS_1_3)
      assertEquals("CN=localhost",
          (response.handshake!!.peerCertificates.first() as X509Certificate).subjectDN.name)
    }
  }

  @Test
  fun testCertificatePinningFailure() {
    enableTls()

    val certificatePinner = CertificatePinner.Builder()
        .add(server.hostName, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()
    client = client.newBuilder().certificatePinner(certificatePinner).build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    try {
      client.newCall(request).execute()
      fail<Any>("")
    } catch (_: SSLPeerUnverifiedException) {
    }
  }

  @Test
  fun testCertificatePinningSuccess() {
    enableTls()

    val certificatePinner = CertificatePinner.Builder()
        .add(server.hostName,
            CertificatePinner.pin(handshakeCertificates.trustManager.acceptedIssuers[0]))
        .build()
    client = client.newBuilder().certificatePinner(certificatePinner).build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testEventListener() {
    val eventListener = RecordingEventListener()

    enableTls()

    client = client.newBuilder().eventListenerFactory(clientTestRule.wrap(eventListener)).build()

    server.enqueue(MockResponse().setBody("abc1"))
    server.enqueue(MockResponse().setBody("abc2"))

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(listOf("CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "SecureConnectStart", "SecureConnectEnd", "ConnectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd"), eventListener.recordedEventTypes())

    eventListener.clearAllEvents()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(listOf("CallStart",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd"), eventListener.recordedEventTypes())
  }

  @Test
  fun testSessionReuse() {
    val sessionIds = mutableListOf<String>()

    enableTls()

    client = client.newBuilder().eventListenerFactory(clientTestRule.wrap(object : EventListener() {
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
  }

  @Test
  fun testDnsOverHttps() {
    assumeNetwork()

    client = client.newBuilder()
        .eventListenerFactory(clientTestRule.wrap(LoggingEventListener.Factory()))
        .build()

    val dohDns = buildCloudflareIp(client)
    val dohEnabledClient =
        client.newBuilder().eventListenerFactory(EventListener.NONE.asFactory()).dns(dohDns).build()

    dohEnabledClient.get("https://www.twitter.com/robots.txt")
    dohEnabledClient.get("https://www.facebook.com/robots.txt")
  }

  @Test
  fun testCustomTrustManager() {
    assumeNetwork()

    val trustManager = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

      override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslContext = Platform.get().newSSLContext().apply {
      init(null, arrayOf(trustManager), null)
    }
    val sslSocketFactory = sslContext.socketFactory

    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    client = client.newBuilder()
        .sslSocketFactory(sslSocketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()

    client.get("https://www.facebook.com/robots.txt")
  }

  @Test
  fun testCustomTrustManagerWithAndroidCheck() {
    assumeNetwork()

    var withHostCalled = false
    var withoutHostCalled = false
    val trustManager = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        withoutHostCalled = true
      }

      @Suppress("unused", "UNUSED_PARAMETER")
      // called by Android via reflection in X509TrustManagerExtensions
      fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, hostname: String): List<X509Certificate> {
        withHostCalled = true
        return chain.toList()
      }

      override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslContext = Platform.get().newSSLContext().apply {
      init(null, arrayOf(trustManager), null)
    }
    val sslSocketFactory = sslContext.socketFactory

    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    client = client.newBuilder()
        .sslSocketFactory(sslSocketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()

    client.get("https://www.facebook.com/robots.txt")

    if (Build.VERSION.SDK_INT < 24) {
      assertFalse(withHostCalled)
      assertTrue(withoutHostCalled)
    } else {
      assertTrue(withHostCalled)
      assertFalse(withoutHostCalled)
    }
  }

  @Test
  fun testUnderscoreRequest() {
    assumeNetwork()

    val request =
        Request.Builder().url("https://example_underscore_123.s3.amazonaws.com/").build()

    try {
      client.newCall(request).execute().close()
      // Hopefully this passes
    } catch (ioe: IOException) {
      // https://github.com/square/okhttp/issues/5840
      when (ioe.cause) {
        is IllegalArgumentException -> {
          assertEquals("Android internal error", ioe.message)
        }
        is CertificateException -> {
          assertTrue(ioe.cause?.cause is IllegalArgumentException)
          assertEquals(true, ioe.cause?.cause?.message?.startsWith("Invalid input to toASCII"))
        }
        else -> throw ioe
      }
    }
  }

  @Test
  @Disabled("breaks conscrypt test")
  fun testBouncyCastleRequest() {
    assumeNetwork()

    try {
      Security.insertProviderAt(BouncyCastleProvider(), 1)
      Security.insertProviderAt(BouncyCastleJsseProvider(), 2)

      var socketClass: String? = null

      val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(null as KeyStore?)
      }.trustManagers.first() as X509TrustManager

      val sslContext = Platform.get().newSSLContext().apply {
        // TODO remove most of this code after https://github.com/bcgit/bc-java/issues/686
        init(null, arrayOf(trustManager), SecureRandom())
      }

      client = client.newBuilder()
          .sslSocketFactory(sslContext.socketFactory, trustManager)
          .eventListenerFactory(clientTestRule.wrap(object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
              socketClass = connection.socket().javaClass.name
            }
          }))
          .build()

      val request = Request.Builder().url("https://facebook.com/robots.txt").build()

      val response = client.newCall(request).execute()

      response.use {
        assertEquals(Protocol.HTTP_2, response.protocol)
        assertEquals(200, response.code)
        assertEquals("org.bouncycastle.jsse.provider.ProvSSLSocketWrap", socketClass)
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }
    } finally {
      Security.removeProvider("BCJSSE")
      Security.removeProvider("BC")
    }
  }

  @Test
  @Disabled("TODO: currently logging okhttp3.internal.concurrent.TaskRunner, okhttp3.internal.http2.Http2")
  fun testLoggingLevels() {
    enableTls()

    val testHandler = object : Handler() {
      val calls = mutableMapOf<String, AtomicInteger>()

      override fun publish(record: LogRecord) {
        calls.getOrPut(record.loggerName) { AtomicInteger(0) }
            .incrementAndGet()
      }

      override fun flush() {
      }

      override fun close() {
      }
    }.apply {
      level = Level.FINEST
    }

    Logger.getLogger("")
        .addHandler(testHandler)
    Logger.getLogger("okhttp3")
        .addHandler(testHandler)
    Logger.getLogger(Http2::class.java.name)
        .addHandler(testHandler)
    Logger.getLogger(TaskRunner::class.java.name)
        .addHandler(testHandler)
    Logger.getLogger(OkHttpClient::class.java.name)
        .addHandler(testHandler)

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val response = client.newCall(request)
        .execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals(Protocol.HTTP_2, response.protocol)
    }

    // Only logs to the test logger above
    // Will fail if "adb shell setprop log.tag.okhttp.Http2 DEBUG" is called
    assertEquals(setOf(OkHttpTest::class.java.name), testHandler.calls.keys)
  }

  fun testCachedRequest() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc").addHeader("cache-control: public, max-age=3"))
    server.enqueue(MockResponse().setBody("abc"))

    val ctxt = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    val cacheSize = 1L * 1024 * 1024 // 1MB
    val cache = Cache(ctxt.cacheDir.resolve("testCache"), cacheSize)

    try {
      client = client.newBuilder()
          .cache(cache)
          .build()

      val request = Request.Builder()
          .url(server.url("/"))
          .build()

      client.newCall(request)
          .execute()
          .use {
            assertEquals(200, it.code)
            assertNull(it.cacheResponse)
            assertNotNull(it.networkResponse)

            assertEquals(3, it.cacheControl.maxAgeSeconds)
            assertTrue(it.cacheControl.isPublic)
          }

      client.newCall(request)
          .execute()
          .use {
            assertEquals(200, it.code)
            assertNotNull(it.cacheResponse)
            assertNull(it.networkResponse)
          }

      assertEquals(1, cache.hitCount())
      assertEquals(1, cache.networkCount())
      assertEquals(2, cache.requestCount())
    } finally {
      cache.delete()
    }
  }

  private fun OkHttpClient.get(url: String) {
    val request = Request.Builder().url(url).build()
    val response = this.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  fun buildCloudflareIp(bootstrapClient: OkHttpClient): DnsOverHttps {
    return DnsOverHttps.Builder().client(bootstrapClient)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .build()
  }

  private fun enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      throw TestAbortedException(uhe.message, uhe)
    }
  }

  fun OkHttpClient.close() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
  }
}
