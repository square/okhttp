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

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.KeyPair
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.internal.proxy.NullProxySelector
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.PushPromise
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.localhost
import okio.Buffer
import org.junit.Ignore
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Access every declaration that is deprecated with [DeprecationLevel.ERROR]. Although new Kotlin
 * code shouldn't use these, they're necessary for clients migrating from OkHttp 3.x and this test
 * ensures the symbols remain available and with the expected parameter and return types.
 */
@Suppress(
    "DEPRECATION_ERROR",
    "UNUSED_VALUE",
    "UNUSED_VARIABLE",
    "VARIABLE_WITH_REDUNDANT_INITIALIZER"
)
class KotlinDeprecationErrorTest {
  @Test @Disabled
  fun address() {
    val address: Address = newAddress()
    val url: HttpUrl = address.url()
    val dns: Dns = address.dns()
    val socketFactory: SocketFactory = address.socketFactory()
    val proxyAuthenticator: Authenticator = address.proxyAuthenticator()
    val protocols: List<Protocol> = address.protocols()
    val connectionSpecs: List<ConnectionSpec> = address.connectionSpecs()
    val proxySelector: ProxySelector = address.proxySelector()
    val proxy: Proxy? = address.proxy()
    val sslSocketFactory: SSLSocketFactory? = address.sslSocketFactory()
    val hostnameVerifier: HostnameVerifier? = address.hostnameVerifier()
    val certificatePinner: CertificatePinner? = address.certificatePinner()
  }

  @Test @Disabled
  fun cache() {
    val cache = Cache(File("/cache/"), Integer.MAX_VALUE.toLong())
    val directory: File = cache.directory()
  }

  @Test @Disabled
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
    val parse: CacheControl = CacheControl.parse(Headers.of())
  }

  @Test @Disabled
  fun challenge() {
    val challenge = Challenge("", mapOf<String?, String>("" to ""))
    val scheme: String = challenge.scheme()
    val authParams: Map<String?, String> = challenge.authParams()
    val realm: String? = challenge.realm()
    val charset: Charset = challenge.charset()
  }

  @Test @Disabled
  fun cipherSuite() {
    val cipherSuite: CipherSuite = CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    val javaName: String = cipherSuite.javaName()
  }

  @Test @Disabled
  fun connectionSpec() {
    val connectionSpec: ConnectionSpec = ConnectionSpec.RESTRICTED_TLS
    val tlsVersions: List<TlsVersion>? = connectionSpec.tlsVersions()
    val cipherSuites: List<CipherSuite>? = connectionSpec.cipherSuites()
    val supportsTlsExtensions: Boolean = connectionSpec.supportsTlsExtensions()
  }

  @Test @Disabled
  fun cookie() {
    val cookie: Cookie = Cookie.Builder().build()
    val name: String = cookie.name()
    val value: String = cookie.value()
    val persistent: Boolean = cookie.persistent()
    val expiresAt: Long = cookie.expiresAt()
    val hostOnly: Boolean = cookie.hostOnly()
    val domain: String = cookie.domain()
    val path: String = cookie.path()
    val httpOnly: Boolean = cookie.httpOnly()
    val secure: Boolean = cookie.secure()
  }

  @Test @Disabled
  fun formBody() {
    val formBody: FormBody = FormBody.Builder().build()
    val size: Int = formBody.size()
  }

  @Test @Disabled
  fun handshake() {
    val handshake: Handshake =
        Handshake.get((localhost().sslSocketFactory().createSocket() as SSLSocket).session)
    val tlsVersion: TlsVersion = handshake.tlsVersion()
    val cipherSuite: CipherSuite = handshake.cipherSuite()
    val peerCertificates: List<Certificate> = handshake.peerCertificates()
    val peerPrincipal: Principal? = handshake.peerPrincipal()
    val localCertificates: List<Certificate> = handshake.localCertificates()
    val localPrincipal: Principal? = handshake.localPrincipal()
  }

  @Test @Disabled
  fun headers() {
    var headers: Headers = Headers.of("", "")
    headers = Headers.of(mapOf("" to ""))
    val size: Int = headers.size()
  }

  @Test @Disabled
  fun httpLoggingInterceptor() {
    val interceptor = HttpLoggingInterceptor()
    val level = interceptor.getLevel()
  }

  @Test @Disabled
  fun httpUrl() {
    val httpUrl: HttpUrl = HttpUrl.get("")
    val url: URL = httpUrl.url()
    val uri: URI = httpUrl.uri()
    val scheme: String = httpUrl.scheme()
    val encodedUsername: String = httpUrl.encodedUsername()
    val username: String = httpUrl.username()
    val encodedPassword: String = httpUrl.encodedPassword()
    val password: String = httpUrl.password()
    val host: String = httpUrl.host()
    val port: Int = httpUrl.port()
    val pathSize: Int = httpUrl.pathSize()
    val encodedPath: String = httpUrl.encodedPath()
    val encodedPathSegments: List<String> = httpUrl.encodedPathSegments()
    val pathSegments: List<String> = httpUrl.pathSegments()
    val encodedQuery: String? = httpUrl.encodedQuery()
    val query: String? = httpUrl.query()
    val querySize: Int = httpUrl.querySize()
    val queryParameter: String? = httpUrl.queryParameter("")
    val queryParameterNames: Set<String> = httpUrl.queryParameterNames()
    val encodedFragment: String? = httpUrl.encodedFragment()
    val fragment: String? = httpUrl.fragment()
    val getFromUrl: HttpUrl? = HttpUrl.get(URL(""))
    val getFromUri: HttpUrl? = HttpUrl.get(URI(""))
    val parse: HttpUrl? = HttpUrl.parse("")
  }

  @Test @Disabled
  fun handshakeCertificates() {
    val handshakeCertificates = HandshakeCertificates.Builder().build()
    val keyManager: X509KeyManager = handshakeCertificates.keyManager()
    val trustManager: X509TrustManager = handshakeCertificates.trustManager()
  }

  @Test @Disabled
  fun handshakeCertificatesBuilder() {
    var builder: HandshakeCertificates.Builder = HandshakeCertificates.Builder()
    val heldCertificate: HeldCertificate = HeldCertificate.Builder().build()
    builder = builder.heldCertificate(heldCertificate, heldCertificate.certificate())
    builder = builder.addTrustedCertificate(heldCertificate.certificate())
  }

  @Test @Disabled
  fun heldCertificate() {
    val heldCertificate: HeldCertificate = HeldCertificate.Builder().build()
    val certificate: X509Certificate = heldCertificate.certificate()
    val keyPair: KeyPair = heldCertificate.keyPair()
  }

  @Test @Disabled
  fun mediaType() {
    val mediaType: MediaType = MediaType.get("")
    val type: String = mediaType.type()
    val subtype: String = mediaType.subtype()
    val parse: MediaType? = MediaType.parse("")
  }

  @Test @Disabled
  fun mockResponse() {
    val mockResponse = MockResponse()
    var status: String = mockResponse.getStatus()
    var headers: Headers = mockResponse.getHeaders()
    var trailers: Headers = mockResponse.getTrailers()
    var socketPolicy: SocketPolicy = mockResponse.getSocketPolicy()
    var http2ErrorCode: Int = mockResponse.getHttp2ErrorCode()
  }

  @Test @Disabled
  fun mockWebServer() {
    val mockWebServer = MockWebServer()
    var port: Int = mockWebServer.getPort()
    mockWebServer.setServerSocketFactory(ServerSocketFactory.getDefault())
    mockWebServer.setBodyLimit(0L)
    mockWebServer.setProtocolNegotiationEnabled(false)
    mockWebServer.setProtocols(listOf(Protocol.HTTP_1_1))
    var requestCount: Int = mockWebServer.getRequestCount()
  }

  @Test @Disabled
  fun multipartBody() {
    val multipartBody: MultipartBody = MultipartBody.Builder().build()
    val type: MediaType = multipartBody.type()
    val boundary: String = multipartBody.boundary()
    val size: Int = multipartBody.size()
    val parts: List<MultipartBody.Part> = multipartBody.parts()
  }

  @Test @Disabled
  fun multipartBodyPart() {
    val multipartBody: MultipartBody = MultipartBody.Builder().build()
    val part: MultipartBody.Part = multipartBody.part(0)
    val headers: Headers? = part.headers()
    val body: RequestBody = part.body()
  }

  @Test @Disabled
  fun okHttpClient() {
    val client = OkHttpClient()
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
  }

  @Test @Disabled
  fun pushPromise() {
    val pushPromise = PushPromise("", "", Headers.of(), MockResponse())
    val method: String = pushPromise.method()
    val path: String = pushPromise.path()
    val headers: Headers = pushPromise.headers()
    val response: MockResponse = pushPromise.response()
  }

  @Test @Disabled
  fun recordedRequest() {
    val recordedRequest = RecordedRequest("", Headers.of(), listOf(), 0L, Buffer(), 0, Socket())
    var utf8Body: String = recordedRequest.utf8Body
  }

  @Test @Disabled
  fun request() {
    val request: Request = Request.Builder().build()
    val url: HttpUrl = request.url()
    val method: String = request.method()
    val headers: Headers = request.headers()
    val body: RequestBody? = request.body()
    val cacheControl: CacheControl = request.cacheControl()
  }

  @Test @Disabled
  fun response() {
    val response: Response = Response.Builder().build()
    val request: Request = response.request()
    val protocol: Protocol = response.protocol()
    val code: Int = response.code()
    val message: String = response.message()
    val handshake: Handshake? = response.handshake()
    val headers: Headers = response.headers()
    val body: ResponseBody? = response.body()
    val networkResponse: Response? = response.networkResponse()
    val cacheResponse: Response? = response.cacheResponse()
    val priorResponse: Response? = response.priorResponse()
    val cacheControl: CacheControl = response.cacheControl()
    val sentRequestAtMillis: Long = response.sentRequestAtMillis()
    val receivedResponseAtMillis: Long = response.receivedResponseAtMillis()
  }

  @Test @Disabled
  fun route() {
    val route: Route = newRoute()
    val address: Address = route.address()
    val proxy: Proxy = route.proxy()
    val inetSocketAddress: InetSocketAddress = route.socketAddress()
  }

  @Test @Disabled
  fun tlsVersion() {
    val tlsVersion: TlsVersion = TlsVersion.TLS_1_3
    val javaName: String = tlsVersion.javaName()
  }

  private fun newAddress(): Address {
    return Address(
        "",
        0,
        Dns.SYSTEM,
        SocketFactory.getDefault(),
        localhost().sslSocketFactory(),
        newHostnameVerifier(),
        CertificatePinner.DEFAULT,
        Authenticator.NONE,
        Proxy.NO_PROXY,
        listOf(Protocol.HTTP_1_1),
        listOf(ConnectionSpec.MODERN_TLS),
        NullProxySelector
    )
  }

  private fun newHostnameVerifier(): HostnameVerifier {
    return HostnameVerifier { _, _ -> false }
  }

  private fun newRoute(): Route {
    return Route(newAddress(), Proxy.NO_PROXY, InetSocketAddress.createUnresolved("", 0))
  }
}
