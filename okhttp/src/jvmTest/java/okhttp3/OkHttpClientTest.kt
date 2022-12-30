/*
 * Copyright (C) 2014 Square, Inc.
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
import java.net.CookieManager
import java.net.Proxy
import java.net.ProxySelector
import java.net.ResponseCache
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.AbstractList
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.internal.platform.Platform.Companion.get
import okhttp3.internal.proxy.NullProxySelector
import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class OkHttpClientTest {
  @RegisterExtension
  var platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private var server: MockWebServer? = null

  @BeforeEach fun setUp(server: MockWebServer?) {
    this.server = server
  }

  @AfterEach fun tearDown() {
    ProxySelector.setDefault(DEFAULT_PROXY_SELECTOR)
    CookieManager.setDefault(DEFAULT_COOKIE_HANDLER)
    ResponseCache.setDefault(DEFAULT_RESPONSE_CACHE)
  }

  @Test fun durationDefaults() {
    val client = clientTestRule.newClient()
    assertThat(client.callTimeoutMillis).isEqualTo(0)
    assertThat(client.connectTimeoutMillis).isEqualTo(10000)
    assertThat(client.readTimeoutMillis).isEqualTo(10000)
    assertThat(client.writeTimeoutMillis).isEqualTo(10000)
    assertThat(client.pingIntervalMillis).isEqualTo(0)
  }

  @Test fun webSocketDefaults() {
    val client = clientTestRule.newClient()
    assertThat(client.minWebSocketMessageToCompress).isEqualTo(1024)
  }

  @Test fun timeoutValidRange() {
    val builder = OkHttpClient.Builder()
    try {
      builder.callTimeout(Duration.ofNanos(1))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.connectTimeout(Duration.ofNanos(1))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.writeTimeout(Duration.ofNanos(1))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.readTimeout(Duration.ofNanos(1))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.callTimeout(Duration.ofDays(365))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.connectTimeout(Duration.ofDays(365))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.writeTimeout(Duration.ofDays(365))
    } catch (ignored: IllegalArgumentException) {
    }
    try {
      builder.readTimeout(Duration.ofDays(365))
    } catch (ignored: IllegalArgumentException) {
    }
  }

  @Test fun clonedInterceptorsListsAreIndependent() {
    val interceptor = Interceptor { chain: Interceptor.Chain ->
      chain.proceed(chain.request())
    }
    val original = clientTestRule.newClient()
    original.newBuilder()
      .addInterceptor(interceptor)
      .addNetworkInterceptor(interceptor)
      .build()
    assertThat(original.interceptors.size).isEqualTo(0)
    assertThat(original.networkInterceptors.size).isEqualTo(0)
  }

  /**
   * When copying the client, stateful things like the connection pool are shared across all
   * clients.
   */
  @Test fun cloneSharesStatefulInstances() {
    val client = clientTestRule.newClient()

    // Values should be non-null.
    val a = client.newBuilder().build()
    assertThat(a.dispatcher).isNotNull
    assertThat(a.connectionPool).isNotNull
    assertThat(a.sslSocketFactory).isNotNull
    assertThat(a.x509TrustManager).isNotNull

    // Multiple clients share the instances.
    val b = client.newBuilder().build()
    assertThat(b.dispatcher).isSameAs(a.dispatcher)
    assertThat(b.connectionPool).isSameAs(a.connectionPool)
    assertThat(b.sslSocketFactory).isSameAs(a.sslSocketFactory)
    assertThat(b.x509TrustManager).isSameAs(a.x509TrustManager)
  }

  @Test fun setProtocolsRejectsHttp10() {
    val builder = OkHttpClient.Builder()
    try {
      builder.protocols(listOf(Protocol.HTTP_1_0, Protocol.HTTP_1_1))
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun certificatePinnerEquality() {
    val clientA = clientTestRule.newClient()
    val clientB = clientTestRule.newClient()
    assertThat(clientB.certificatePinner).isEqualTo(clientA.certificatePinner)
  }

  @Test fun nullInterceptorInList() {
    val builder = OkHttpClient.Builder()
    builder.interceptors().addAll(listOf(null) as List<Interceptor>)
    try {
      builder.build()
      fail<Any>()
    } catch (expected: IllegalStateException) {
      assertThat(expected.message).isEqualTo("Null interceptor: [null]")
    }
  }

  @Test fun nullNetworkInterceptorInList() {
    val builder = OkHttpClient.Builder()
    builder.networkInterceptors().addAll(listOf(null) as List<Interceptor>)
    try {
      builder.build()
      fail<Any>()
    } catch (expected: IllegalStateException) {
      assertThat(expected.message).isEqualTo("Null network interceptor: [null]")
    }
  }

  @Test fun testH2PriorKnowledgeOkHttpClientConstructionFallback() {
    try {
      OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_1_1))
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
        "protocols containing h2_prior_knowledge cannot use other protocols: "
          + "[h2_prior_knowledge, http/1.1]"
      )
    }
  }

  @Test fun testH2PriorKnowledgeOkHttpClientConstructionDuplicates() {
    try {
      OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.H2_PRIOR_KNOWLEDGE))
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo(
        "protocols containing h2_prior_knowledge cannot use other protocols: "
          + "[h2_prior_knowledge, h2_prior_knowledge]"
      )
    }
  }

  @Test fun testH2PriorKnowledgeOkHttpClientConstructionSuccess() {
    val okHttpClient = OkHttpClient.Builder()
      .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
      .build()
    assertThat(okHttpClient.protocols.size).isEqualTo(1)
    assertThat(okHttpClient.protocols[0]).isEqualTo(Protocol.H2_PRIOR_KNOWLEDGE)
  }

  @Test fun nullDefaultProxySelector() {
    server!!.enqueue(MockResponse(body = "abc"))
    ProxySelector.setDefault(null)
    val client = clientTestRule.newClient()
    val request = Request(server!!.url("/"))
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("abc")
  }

  @Test fun sslSocketFactorySetAsSocketFactory() {
    val builder = OkHttpClient.Builder()
    try {
      builder.socketFactory(SSLSocketFactory.getDefault())
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
    }
  }

  @Test fun noSslSocketFactoryConfigured() {
    val client = OkHttpClient.Builder()
      .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
      .build()
    try {
      client.sslSocketFactory
      fail<Any>()
    } catch (expected: IllegalStateException) {
    }
  }

  @Test fun nullHostileProtocolList() {
    val nullHostileProtocols = object : AbstractList<Protocol?>() {
      override val size: Int = 1

      override fun get(index: Int) = Protocol.HTTP_1_1

      override fun contains(element: Protocol?): Boolean {
        if (element == null) throw NullPointerException()
        return super.contains(element)
      }

      override fun indexOf(element: Protocol?): Int {
        if (element == null) throw NullPointerException()
        return super.indexOf(element)
      }
    } as List<Protocol>
    val client = OkHttpClient.Builder()
      .protocols(nullHostileProtocols)
      .build()
    assertEquals(
      listOf(Protocol.HTTP_1_1), client.protocols
    )
  }

  @Test fun nullProtocolInList() {
    val protocols = mutableListOf(
      Protocol.HTTP_1_1,
      null
    )
    try {
      OkHttpClient.Builder()
        .protocols(protocols as List<Protocol>)
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("protocols must not contain null")
    }
  }

  @Test fun spdy3IsRemovedFromProtocols() {
    val protocols = mutableListOf(
      Protocol.HTTP_1_1,
      Protocol.SPDY_3
    )
    val client = OkHttpClient.Builder()
      .protocols(protocols)
      .build()
    assertThat(client.protocols).containsExactly(Protocol.HTTP_1_1)
  }

  @Test fun testProxyDefaults() {
    var client = OkHttpClient.Builder().build()
    assertThat(client.proxy).isNull()
    assertThat(client.proxySelector)
      .isNotInstanceOf(NullProxySelector::class.java)
    client = OkHttpClient.Builder()
      .proxy(Proxy.NO_PROXY)
      .build()
    assertThat(client.proxy).isSameAs(Proxy.NO_PROXY)
    assertThat(client.proxySelector)
      .isInstanceOf(NullProxySelector::class.java)
    client = OkHttpClient.Builder()
      .proxySelector(FakeProxySelector())
      .build()
    assertThat(client.proxy).isNull()
    assertThat(client.proxySelector)
      .isInstanceOf(FakeProxySelector::class.java)
  }

  @Test fun sharesRouteDatabase() {
    val client = OkHttpClient.Builder()
      .build()
    val proxySelector: ProxySelector = object : ProxySelector() {
      override fun select(uri: URI): List<Proxy> = listOf()

      override fun connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException) {}
    }

    val trustManager = get().platformTrustManager()
    val sslContext = get().newSSLContext()
    sslContext.init(null, null, null)

    // new client, may share all same fields but likely different connection pool
    assertNotSame(
      client.routeDatabase,
      OkHttpClient.Builder()
        .build()
        .routeDatabase
    )

    // same client with no change affecting route db
    assertSame(
      client.routeDatabase,
      client.newBuilder()
        .build()
        .routeDatabase
    )
    assertSame(
      client.routeDatabase,
      client.newBuilder()
        .callTimeout(Duration.ofSeconds(5))
        .build()
        .routeDatabase
    )

    // logically different scope of client for route db
    assertNotSame(client.routeDatabase,
      client.newBuilder()
        .dns { listOf() }
        .build()
        .routeDatabase
    )
    assertNotSame(client.routeDatabase, client.newBuilder()
      .proxyAuthenticator { _: Route?, _: Response? -> null }
      .build()
      .routeDatabase
    )
    assertNotSame(
      client.routeDatabase,
      client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
        .routeDatabase
    )
    assertNotSame(
      client.routeDatabase,
      client.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS))
        .build()
        .routeDatabase
    )
    assertNotSame(
      client.routeDatabase,
      client.newBuilder()
        .proxySelector(proxySelector)
        .build()
        .routeDatabase
    )
    assertNotSame(
      client.routeDatabase,
      client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .build()
        .routeDatabase
    )
    assertNotSame(
      client.routeDatabase,
      client.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()
        .routeDatabase
    )
    assertNotSame(client.routeDatabase,
      client.newBuilder()
        .hostnameVerifier { _: String?, _: SSLSession? -> false }
        .build()
        .routeDatabase
    )
    assertNotSame(
      client.routeDatabase,
      client.newBuilder()
        .certificatePinner(CertificatePinner.Builder().build())
        .build()
        .routeDatabase
    )
  }

  @Test fun minWebSocketMessageToCompressNegative() {
    val builder = OkHttpClient.Builder()
    try {
      builder.minWebSocketMessageToCompress(-1024)
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message)
        .isEqualTo("minWebSocketMessageToCompress must be positive: -1024")
    }
  }

  companion object {
    private val DEFAULT_PROXY_SELECTOR = ProxySelector.getDefault()
    private val DEFAULT_COOKIE_HANDLER = CookieManager.getDefault()
    private val DEFAULT_RESPONSE_CACHE = ResponseCache.getDefault()
  }
}
