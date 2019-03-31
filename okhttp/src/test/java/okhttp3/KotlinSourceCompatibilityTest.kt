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

import okhttp3.internal.proxy.NullProxySelector
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.net.ProxySelector
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory

/**
 * Access every type, function, and property from Kotlin to defend against unexpected regressions in
 * source-compatibility.
 *
 * Unlike most tests we're only really interested in whether this test compiles: it's output is not
 * interesting. Do not simplify this code by removing unused declarations or unnecessary types;
 * doing so limits the utility of the test.
 */
@Suppress("UNUSED_VARIABLE")
class KotlinSourceCompatibilityTest {
  @Test @Ignore
  fun address() {
    val address: Address = Address(
        "",
        0,
        Dns.SYSTEM,
        SocketFactory.getDefault(),
        localhost().sslSocketFactory(),
        OkHostnameVerifier.INSTANCE,
        CertificatePinner.DEFAULT,
        Authenticator.NONE,
        Proxy.NO_PROXY,
        listOf(Protocol.HTTP_1_1),
        listOf(ConnectionSpec.MODERN_TLS),
        NullProxySelector()
    )
    val url: HttpUrl = address.url()
    val dns: Dns = address.dns()
    val socketFactory: SocketFactory = address.socketFactory()
    val proxyAuthenticator: Authenticator = address.proxyAuthenticator()
    val protocols: List<Protocol> = address.protocols()
    val connectionSpecs: List<ConnectionSpec> = address.connectionSpecs()
    val proxySelector: ProxySelector = address.proxySelector()
    val sslSocketFactory: SSLSocketFactory? = address.sslSocketFactory()
    val hostnameVerifier: HostnameVerifier? = address.hostnameVerifier()
    val certificatePinner: CertificatePinner? = address.certificatePinner()
  }

  @Test @Ignore
  fun cache() {
    val cache = Cache(File("/cache/"), Integer.MAX_VALUE.toLong())
    cache.initialize()
    cache.delete()
    cache.evictAll()
    val urls: MutableIterator<String> = cache.urls()
    val writeAbortCount: Int = cache.writeAbortCount()
    val writeSuccessCount: Int = cache.writeSuccessCount()
    val size: Long = cache.size()
    val maxSize: Long = cache.maxSize()
    cache.flush()
    cache.close()
    val directory: File = cache.directory()
    val networkCount: Int = cache.networkCount()
    val hitCount: Int = cache.hitCount()
    val requestCount: Int = cache.requestCount()
  }

  @Test @Ignore
  fun cacheControl() {
    val cacheControl: CacheControl = CacheControl.Builder().build()
    val noCache: Boolean = cacheControl.noCache()
    val noStore: Boolean = cacheControl.noStore()
    val maxAgeSeconds: Int = cacheControl.maxAgeSeconds()
    val sMaxAgeSeconds: Int = cacheControl.sMaxAgeSeconds()
    val mustRevalidate: Boolean = cacheControl.mustRevalidate()
    val maxStaleSeconds: Int = cacheControl.maxStaleSeconds()
    val minFreshSeconds: Int = cacheControl.minFreshSeconds()
    val onlyIfCached: Boolean = cacheControl.onlyIfCached()
    val noTransform: Boolean = cacheControl.noTransform()
    val immutable: Boolean = cacheControl.immutable()
    val forceCache: CacheControl = CacheControl.FORCE_CACHE
    val forceNetwork: CacheControl = CacheControl.FORCE_NETWORK
    val parse: CacheControl = CacheControl.parse(Headers.of())
  }

  @Test @Ignore
  fun cacheControlBuilder() {
    var builder: CacheControl.Builder = CacheControl.Builder()
    builder = builder.noCache()
    builder = builder.noStore()
    builder = builder.maxAge(0, TimeUnit.MILLISECONDS)
    builder = builder.maxStale(0, TimeUnit.MILLISECONDS)
    builder = builder.minFresh(0, TimeUnit.MILLISECONDS)
    builder = builder.onlyIfCached()
    builder = builder.noTransform()
    builder = builder.immutable()
    val cacheControl: CacheControl = builder.build()
  }

  @Test @Ignore
  fun okHttpClient() {
    val client: OkHttpClient = OkHttpClient()
    val dispatcher: Dispatcher = client.dispatcher()
    val proxy: Proxy? = client.proxy()
    val protocols: List<Protocol> = client.protocols()
    val connectionSpecs: List<ConnectionSpec> = client.connectionSpecs()
    val interceptors: List<Interceptor> = client.interceptors()
    val networkInterceptors: List<Interceptor> = client.networkInterceptors()
    val eventListenerFactory: EventListener.Factory = client.eventListenerFactory()
    val proxySelector: ProxySelector = client.proxySelector()
    val cookieJar: CookieJar = client.cookieJar()
    val cache: Cache? = client.cache()
    val socketFactory: SocketFactory = client.socketFactory()
    val sslSocketFactory: SSLSocketFactory = client.sslSocketFactory()
    val hostnameVerifier: HostnameVerifier = client.hostnameVerifier()
    val certificatePinner: CertificatePinner = client.certificatePinner()
    val proxyAuthenticator: Authenticator = client.proxyAuthenticator()
    val authenticator: Authenticator = client.authenticator()
    val connectionPool: ConnectionPool = client.connectionPool()
    val dns: Dns = client.dns()
    val followSslRedirects: Boolean = client.followSslRedirects()
    val followRedirects: Boolean = client.followRedirects()
    val retryOnConnectionFailure: Boolean = client.retryOnConnectionFailure()
    val callTimeoutMillis: Int = client.callTimeoutMillis()
    val connectTimeoutMillis: Int = client.connectTimeoutMillis()
    val readTimeoutMillis: Int = client.readTimeoutMillis()
    val writeTimeoutMillis: Int = client.writeTimeoutMillis()
    val pingIntervalMillis: Int = client.pingIntervalMillis()
    val call: Call = client.newCall(Request.Builder().build())
    val webSocket: WebSocket = client.newWebSocket(
        Request.Builder().build(),
        object : WebSocketListener() {
        })
    val newBuilder: OkHttpClient.Builder = client.newBuilder()
  }

  @Test @Ignore
  fun okHttpClientBuilder() {
    var builder: OkHttpClient.Builder = OkHttpClient.Builder()
    builder = builder.callTimeout(0L, TimeUnit.SECONDS)
    builder = builder.callTimeout(Duration.ofSeconds(0L))
    builder = builder.connectTimeout(0L, TimeUnit.SECONDS)
    builder = builder.connectTimeout(Duration.ofSeconds(0L))
    builder = builder.readTimeout(0L, TimeUnit.SECONDS)
    builder = builder.readTimeout(Duration.ofSeconds(0L))
    builder = builder.writeTimeout(0L, TimeUnit.SECONDS)
    builder = builder.writeTimeout(Duration.ofSeconds(0L))
    builder = builder.pingInterval(0L, TimeUnit.SECONDS)
    builder = builder.pingInterval(Duration.ofSeconds(0L))
    builder = builder.proxy(Proxy.NO_PROXY)
    builder = builder.proxySelector(NullProxySelector())
    builder = builder.cookieJar(CookieJar.NO_COOKIES)
    builder = builder.cache(Cache(File("/cache/"), Integer.MAX_VALUE.toLong()))
    builder = builder.dns(Dns.SYSTEM)
    builder = builder.socketFactory(SocketFactory.getDefault())
    builder = builder.sslSocketFactory(localhost().sslSocketFactory(), localhost().trustManager())
    builder = builder.hostnameVerifier(OkHostnameVerifier.INSTANCE)
    builder = builder.certificatePinner(CertificatePinner.DEFAULT)
    builder = builder.authenticator(Authenticator.NONE)
    builder = builder.proxyAuthenticator(Authenticator.NONE)
    builder = builder.connectionPool(ConnectionPool(0, 0, TimeUnit.SECONDS))
    builder = builder.followSslRedirects(false)
    builder = builder.followRedirects(false)
    builder = builder.retryOnConnectionFailure(false)
    builder = builder.dispatcher(Dispatcher())
    builder = builder.protocols(listOf(Protocol.HTTP_1_1))
    builder = builder.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
    val interceptors: List<Interceptor> = builder.interceptors()
    builder = builder.addInterceptor(object : Interceptor {
      override fun intercept(chain: Interceptor.Chain): Response = TODO()
    })
    val networkInterceptors: List<Interceptor> = builder.networkInterceptors()
    builder = builder.addNetworkInterceptor(object : Interceptor {
      override fun intercept(chain: Interceptor.Chain): Response = TODO()
    })
    builder = builder.eventListener(EventListener.NONE)
    builder = builder.eventListenerFactory(object : EventListener.Factory {
      override fun create(call: Call): EventListener = TODO()
    })
    val client: OkHttpClient = builder.build()
  }
}