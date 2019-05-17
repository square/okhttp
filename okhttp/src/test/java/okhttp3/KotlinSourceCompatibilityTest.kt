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

import okhttp3.internal.http2.Settings
import okhttp3.internal.proxy.NullProxySelector
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.PushPromise
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.localhost
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.Timeout
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.CookieHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Access every type, function, and property from Kotlin to defend against unexpected regressions in
 * source-compatibility.
 *
 * Unlike most tests we're only really interested in whether this test compiles: it's output is not
 * interesting. Do not simplify this code by removing unused declarations or unnecessary types;
 * doing so limits the utility of the test.
 */
@Suppress(
    "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE",
    "DEPRECATION",
    "RedundantExplicitType",
    "RedundantLambdaArrow",
    "UNUSED_ANONYMOUS_PARAMETER",
    "UNUSED_VALUE",
    "UNUSED_VARIABLE",
    "VARIABLE_WITH_REDUNDANT_INITIALIZER"
)
class KotlinSourceCompatibilityTest {
  @Test @Ignore
  fun address() {
    val address: Address = newAddress()
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
  fun authenticator() {
    var authenticator: Authenticator = object : Authenticator {
      override fun authenticate(route: Route?, response: Response): Request? = TODO()
    }
    authenticator = Authenticator { route: Route?, response: Response -> TODO() }
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
  fun call() {
    val call: Call = newCall()
  }

  @Test @Ignore
  fun callback() {
    val callback = object : Callback {
      override fun onFailure(call: Call, e: IOException) = TODO()
      override fun onResponse(call: Call, response: Response) = TODO()
    }
  }

  @Test @Ignore
  fun certificatePinner() {
    val heldCertificate: HeldCertificate = HeldCertificate.Builder().build()
    val certificate: X509Certificate = heldCertificate.certificate()
    val certificatePinner: CertificatePinner = CertificatePinner.Builder().build()
    val certificates: List<Certificate> = listOf()
    certificatePinner.check("", listOf(certificate))
    certificatePinner.check("", certificate, certificate)
    val pin: String = CertificatePinner.pin(certificate)
    val default: CertificatePinner = CertificatePinner.DEFAULT
  }

  @Test @Ignore
  fun certificatePinnerBuilder() {
    val builder: CertificatePinner.Builder = CertificatePinner.Builder()
    builder.add("", "pin1", "pin2")
  }

  @Test @Ignore
  fun challenge() {
    var challenge = Challenge("", mapOf<String?, String>("" to ""))
    challenge = Challenge("", "")
    val scheme: String = challenge.scheme()
    val authParams: Map<String?, String> = challenge.authParams()
    val realm: String? = challenge.realm()
    val charset: Charset = challenge.charset()
    val utf8: Challenge = challenge.withCharset(Charsets.UTF_8)
  }

  @Test @Ignore
  fun cipherSuite() {
    var cipherSuite: CipherSuite = CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    cipherSuite = CipherSuite.forJavaName("")
    val javaName: String = cipherSuite.javaName()
  }

  @Test @Ignore
  fun connection() {
    val connection = object : Connection {
      override fun route(): Route = TODO()
      override fun socket(): Socket = TODO()
      override fun handshake(): Handshake? = TODO()
      override fun protocol(): Protocol = TODO()
    }
  }

  @Test @Ignore
  fun connectionPool() {
    var connectionPool = ConnectionPool()
    connectionPool = ConnectionPool(0, 0L, TimeUnit.SECONDS)
    val idleConnectionCount: Int = connectionPool.idleConnectionCount()
    val connectionCount: Int = connectionPool.connectionCount()
    connectionPool.evictAll()
  }

  @Test @Ignore
  fun connectionSpec() {
    var connectionSpec: ConnectionSpec = ConnectionSpec.RESTRICTED_TLS
    connectionSpec = ConnectionSpec.MODERN_TLS
    connectionSpec = ConnectionSpec.COMPATIBLE_TLS
    connectionSpec = ConnectionSpec.CLEARTEXT
    val tlsVersions: List<TlsVersion>? = connectionSpec.tlsVersions()
    val cipherSuites: List<CipherSuite>? = connectionSpec.cipherSuites()
    val supportsTlsExtensions: Boolean = connectionSpec.supportsTlsExtensions()
    val compatible: Boolean = connectionSpec.isCompatible(
        localhost().sslSocketFactory().createSocket() as SSLSocket)
  }

  @Test @Ignore
  fun connectionSpecBuilder() {
    var builder = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    builder = builder.allEnabledCipherSuites()
    builder = builder.cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
    builder = builder.cipherSuites("", "")
    builder = builder.allEnabledTlsVersions()
    builder = builder.tlsVersions(TlsVersion.TLS_1_3)
    builder = builder.tlsVersions("", "")
    builder = builder.supportsTlsExtensions(false)
    val connectionSpec: ConnectionSpec = builder.build()
  }

  @Test @Ignore
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
    val matches: Boolean = cookie.matches(HttpUrl.get(""))
    val parsedCookie: Cookie? = Cookie.parse(HttpUrl.get(""), "")
    val cookies: List<Cookie> = Cookie.parseAll(HttpUrl.get(""), Headers.of())
  }

  @Test @Ignore
  fun cookieBuilder() {
    var builder: Cookie.Builder = Cookie.Builder()
    builder = builder.name("")
    builder = builder.value("")
    builder = builder.expiresAt(0L)
    builder = builder.domain("")
    builder = builder.hostOnlyDomain("")
    builder = builder.path("")
    builder = builder.secure()
    builder = builder.httpOnly()
    val cookie: Cookie = builder.build()
  }

  @Test @Ignore
  fun cookieJar() {
    val cookieJar = object : CookieJar {
      override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = TODO()
      override fun loadForRequest(url: HttpUrl): List<Cookie> = TODO()
    }
  }

  @Test @Ignore
  fun credentials() {
    val basic: String = Credentials.basic("", "")
  }

  @Test @Ignore
  fun dispatcher() {
    var dispatcher = Dispatcher()
    dispatcher = Dispatcher(Executors.newCachedThreadPool())
    val maxRequests: Int = dispatcher.maxRequests
    dispatcher.maxRequests = 0
    val maxRequestsPerHost: Int = dispatcher.maxRequestsPerHost
    dispatcher.maxRequestsPerHost = 0
    val executorService: ExecutorService = dispatcher.executorService()
    dispatcher.setIdleCallback(object : Runnable {
      override fun run() = TODO()
    })
    dispatcher.setIdleCallback { TODO() }
    val queuedCalls: List<Call> = dispatcher.queuedCalls()
    val runningCalls: List<Call> = dispatcher.runningCalls()
    val queuedCallsCount: Int = dispatcher.queuedCallsCount()
    val runningCallsCount: Int = dispatcher.runningCallsCount()
    dispatcher.cancelAll()
  }

  @Test @Ignore
  fun dispatcherFromMockWebServer() {
    val dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = TODO()
      override fun peek(): MockResponse = TODO()
      override fun shutdown() = TODO()
    }
  }

  @Test @Ignore
  fun dns() {
    var dns: Dns = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> = TODO()
    }
    dns = Dns { it: String -> TODO() }

    val system: Dns = Dns.SYSTEM
  }

  @Test @Ignore
  fun eventListener() {
    val eventListener = object : EventListener() {
      override fun callStart(call: Call) = TODO()
      override fun dnsStart(call: Call, domainName: String) = TODO()
      override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>
      ) = TODO()

      override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy
      ) = TODO()

      override fun secureConnectStart(call: Call) = TODO()
      override fun secureConnectEnd(call: Call, handshake: Handshake?) = TODO()
      override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
      ) = TODO()

      override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
      ) = TODO()

      override fun connectionAcquired(call: Call, connection: Connection) = TODO()
      override fun connectionReleased(call: Call, connection: Connection) = TODO()
      override fun requestHeadersStart(call: Call) = TODO()
      override fun requestHeadersEnd(call: Call, request: Request) = TODO()
      override fun requestBodyStart(call: Call) = TODO()
      override fun requestBodyEnd(call: Call, byteCount: Long) = TODO()
      override fun requestFailed(call: Call, ioe: IOException) = TODO()
      override fun responseHeadersStart(call: Call) = TODO()
      override fun responseHeadersEnd(call: Call, response: Response) = TODO()
      override fun responseBodyStart(call: Call) = TODO()
      override fun responseBodyEnd(call: Call, byteCount: Long) = TODO()
      override fun responseFailed(call: Call, ioe: IOException) = TODO()
      override fun callEnd(call: Call) = TODO()
      override fun callFailed(call: Call, ioe: IOException) = TODO()
    }
    val none: EventListener = EventListener.NONE
  }

  @Test @Ignore
  fun eventListenerBuilder() {
    var builder: EventListener.Factory = object : EventListener.Factory {
      override fun create(call: Call): EventListener = TODO()
    }
    builder = EventListener.Factory { it: Call -> TODO() }
  }

  @Test @Ignore
  fun formBody() {
    val formBody: FormBody = FormBody.Builder().build()
    val size: Int = formBody.size()
    val encodedName: String = formBody.encodedName(0)
    val name: String = formBody.name(0)
    val encodedValue: String = formBody.encodedValue(0)
    val value: String = formBody.value(0)
    val contentType: MediaType? = formBody.contentType()
    val contentLength: Long = formBody.contentLength()
    formBody.writeTo(Buffer())
    val requestBody: RequestBody = formBody
  }

  @Test @Ignore
  fun formBodyBuilder() {
    var builder: FormBody.Builder = FormBody.Builder()
    builder = FormBody.Builder(Charsets.UTF_8)
    builder = builder.add("", "")
    builder = builder.addEncoded("", "")
    val formBody: FormBody = builder.build()
  }

  @Test @Ignore
  fun handshake() {
    var handshake: Handshake =
        Handshake.get((localhost().sslSocketFactory().createSocket() as SSLSocket).session)
    val listOfCertificates: List<Certificate> = listOf()
    handshake = Handshake.get(
        TlsVersion.TLS_1_3,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        listOfCertificates,
        listOfCertificates
    )
    val tlsVersion: TlsVersion = handshake.tlsVersion()
    val cipherSuite: CipherSuite = handshake.cipherSuite()
    val peerCertificates: List<Certificate> = handshake.peerCertificates()
    val peerPrincipal: Principal? = handshake.peerPrincipal()
    val localCertificates: List<Certificate> = handshake.localCertificates()
    val localPrincipal: Principal? = handshake.localPrincipal()
  }

  @Test @Ignore
  fun headers() {
    var headers: Headers = Headers.of("", "")
    headers = Headers.of(mapOf("" to ""))
    val get: String? = headers.get("")
    val date: Date? = headers.getDate("")
    val instant: Instant? = headers.getInstant("")
    val size: Int = headers.size
    val name: String = headers.name(0)
    val value: String = headers.value(0)
    val names: Set<String> = headers.names()
    val values: List<String> = headers.values("")
    val byteCount: Long = headers.byteCount()
    val builder: Headers.Builder = headers.newBuilder()
    val multimap: Map<String, List<String>> = headers.toMultimap()
  }

  @Test @Ignore
  fun headersBuilder() {
    var builder: Headers.Builder = Headers.Builder()
    builder = builder.add("")
    builder = builder.add("", "")
    builder = builder.addUnsafeNonAscii("", "")
    builder = builder.addAll(Headers.of())
    builder = builder.add("", Date(0L))
    builder = builder.add("", Instant.EPOCH)
    builder = builder.set("", "")
    builder = builder.set("", Date(0L))
    builder = builder.set("", Instant.EPOCH)
    builder = builder.removeAll("")
    val get: String? = builder.get("")
    val headers: Headers = builder.build()
  }

  @Test @Ignore
  fun httpLoggingInterceptor() {
    var interceptor: HttpLoggingInterceptor = HttpLoggingInterceptor()
    interceptor = HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT)
    interceptor.redactHeader("")
    interceptor.level = HttpLoggingInterceptor.Level.BASIC
    interceptor.setLevel(HttpLoggingInterceptor.Level.BASIC)
    var level: HttpLoggingInterceptor.Level = interceptor.level
    level = interceptor.getLevel()
    interceptor.intercept(newInterceptorChain())
  }

  @Test @Ignore
  fun httpLoggingInterceptorLevel() {
    val none: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.NONE
    val basic: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BASIC
    val headers: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.HEADERS
    val body: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BODY
  }

  @Test @Ignore
  fun httpLoggingInterceptorLogger() {
    var logger: HttpLoggingInterceptor.Logger = object : HttpLoggingInterceptor.Logger {
      override fun log(message: String) = TODO()
    }
    logger = HttpLoggingInterceptor.Logger { TODO() }
    val default: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
  }

  @Test @Ignore
  fun httpUrl() {
    val httpUrl: HttpUrl = HttpUrl.get("")
    val isHttps: Boolean = httpUrl.isHttps
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
    val queryParameterValues: List<String?> = httpUrl.queryParameterValues("")
    val queryParameterName: String = httpUrl.queryParameterName(0)
    val queryParameterValue: String? = httpUrl.queryParameterValue(0)
    val encodedFragment: String? = httpUrl.encodedFragment()
    val fragment: String? = httpUrl.fragment()
    val redact: String = httpUrl.redact()
    var builder: HttpUrl.Builder = httpUrl.newBuilder()
    var resolveBuilder: HttpUrl.Builder? = httpUrl.newBuilder("")
    val topPrivateDomain: String? = httpUrl.topPrivateDomain()
    val resolve: HttpUrl? = httpUrl.resolve("")
    val getFromUrl: HttpUrl? = HttpUrl.get(URL(""))
    val getFromUri: HttpUrl? = HttpUrl.get(URI(""))
    val parse: HttpUrl? = HttpUrl.parse("")
    val defaultPort: Int = HttpUrl.defaultPort("")
  }

  @Test @Ignore
  fun httpUrlBuilder() {
    var builder: HttpUrl.Builder = HttpUrl.Builder()
    builder = builder.scheme("")
    builder = builder.username("")
    builder = builder.encodedUsername("")
    builder = builder.password("")
    builder = builder.encodedPassword("")
    builder = builder.host("")
    builder = builder.port(0)
    builder = builder.addPathSegment("")
    builder = builder.addPathSegments("")
    builder = builder.addEncodedPathSegment("")
    builder = builder.addEncodedPathSegments("")
    builder = builder.setPathSegment(0, "")
    builder = builder.setEncodedPathSegment(0, "")
    builder = builder.removePathSegment(0)
    builder = builder.encodedPath("")
    builder = builder.query("")
    builder = builder.encodedQuery("")
    builder = builder.addQueryParameter("", "")
    builder = builder.addEncodedQueryParameter("", "")
    builder = builder.setQueryParameter("", "")
    builder = builder.setEncodedQueryParameter("", "")
    builder = builder.removeAllQueryParameters("")
    builder = builder.removeAllEncodedQueryParameters("")
    builder = builder.fragment("")
    builder = builder.encodedFragment("")
    val httpUrl: HttpUrl = builder.build()
  }

  @Test @Ignore
  fun interceptor() {
    var interceptor: Interceptor = object : Interceptor {
      override fun intercept(chain: Interceptor.Chain): Response = TODO()
    }
    interceptor = Interceptor { it: Interceptor.Chain -> TODO() }
  }

  @Test @Ignore
  fun interceptorChain() {
    val chain: Interceptor.Chain = newInterceptorChain()
  }

  @Test @Ignore
  fun handshakeCertificates() {
    val handshakeCertificates = HandshakeCertificates.Builder().build()
    val keyManager: X509KeyManager = handshakeCertificates.keyManager()
    val trustManager: X509TrustManager = handshakeCertificates.trustManager()
    val sslSocketFactory: SSLSocketFactory = handshakeCertificates.sslSocketFactory()
    val sslContext: SSLContext = handshakeCertificates.sslContext()
  }

  @Test @Ignore
  fun handshakeCertificatesBuilder() {
    var builder: HandshakeCertificates.Builder = HandshakeCertificates.Builder()
    val heldCertificate = HeldCertificate.Builder().build()
    builder = builder.heldCertificate(heldCertificate, heldCertificate.certificate())
    builder = builder.addTrustedCertificate(heldCertificate.certificate())
    builder = builder.addPlatformTrustedCertificates()
    val handshakeCertificates: HandshakeCertificates = builder.build()
  }

  @Test @Ignore
  fun heldCertificate() {
    val heldCertificate: HeldCertificate = HeldCertificate.Builder().build()
    val certificate: X509Certificate = heldCertificate.certificate()
    val keyPair: KeyPair = heldCertificate.keyPair()
    val certificatePem: String = heldCertificate.certificatePem()
    val privateKeyPkcs8Pem: String = heldCertificate.privateKeyPkcs8Pem()
    val privateKeyPkcs1Pem: String = heldCertificate.privateKeyPkcs1Pem()
  }

  @Test @Ignore
  fun heldCertificateBuilder() {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("").genKeyPair()
    var builder: HeldCertificate.Builder = HeldCertificate.Builder()
    builder = builder.validityInterval(0L, 0L)
    builder = builder.duration(0L, TimeUnit.SECONDS)
    builder = builder.addSubjectAlternativeName("")
    builder = builder.commonName("")
    builder = builder.organizationalUnit("")
    builder = builder.serialNumber(BigInteger.ZERO)
    builder = builder.serialNumber(0L)
    builder = builder.keyPair(keyPair)
    builder = builder.keyPair(keyPair.public, keyPair.private)
    builder = builder.signedBy(HeldCertificate.Builder().build())
    builder = builder.certificateAuthority(0)
    builder = builder.ecdsa256()
    builder = builder.rsa2048()
    val heldCertificate: HeldCertificate = builder.build()
  }

  @Test @Ignore
  fun javaNetAuthenticator() {
    val authenticator = JavaNetAuthenticator()
    val response = Response.Builder().build()
    var request: Request? = authenticator.authenticate(newRoute(), response)
    request = authenticator.authenticate(null, response)
  }

  @Test @Ignore
  fun javaNetCookieJar() {
    val cookieJar: JavaNetCookieJar = JavaNetCookieJar(newCookieHandler())
    val httpUrl = HttpUrl.get("")
    val loadForRequest: List<Cookie> = cookieJar.loadForRequest(httpUrl)
    cookieJar.saveFromResponse(httpUrl, listOf(Cookie.Builder().build()))
  }

  @Test @Ignore
  fun loggingEventListener() {
    var loggingEventListener: EventListener = LoggingEventListener.Factory().create(newCall())
  }

  @Test @Ignore
  fun loggingEventListenerFactory() {
    var factory: LoggingEventListener.Factory = LoggingEventListener.Factory()
    factory = LoggingEventListener.Factory(HttpLoggingInterceptor.Logger.DEFAULT)
    factory = object : LoggingEventListener.Factory() {
      override fun create(call: Call): EventListener = TODO()
    }
    val eventListener: EventListener = factory.create(newCall())
  }

  @Test @Ignore
  fun mediaType() {
    val mediaType: MediaType = MediaType.get("")
    val defaultCharset: Charset? = mediaType.charset()
    val charset: Charset? = mediaType.charset(Charsets.UTF_8)
    val type: String = mediaType.type()
    val subtype: String = mediaType.subtype()
    val parse: MediaType? = MediaType.parse("")
  }

  @Test @Ignore
  fun mockResponse() {
    var mockResponse: MockResponse = MockResponse()
    var status: String = mockResponse.getStatus()
    status = mockResponse.getStatus()
    mockResponse = mockResponse.setStatus("")
    mockResponse.setStatus("")
    mockResponse = mockResponse.setResponseCode(0)
    var headers: Headers = mockResponse.getHeaders()
    headers = mockResponse.getHeaders()
    var trailers: Headers = mockResponse.getTrailers()
    trailers = mockResponse.getTrailers()
    mockResponse = mockResponse.clearHeaders()
    mockResponse = mockResponse.addHeader("")
    mockResponse = mockResponse.addHeader("", "")
    mockResponse = mockResponse.addHeaderLenient("", Any())
    mockResponse = mockResponse.setHeader("", Any())
    mockResponse = mockResponse.setHeaders(Headers.of())
    mockResponse = mockResponse.setTrailers(Headers.of())
    mockResponse = mockResponse.removeHeader("")
    var body: Buffer? = mockResponse.getBody()
    body = mockResponse.getBody()
    mockResponse = mockResponse.setBody(Buffer())
    mockResponse.setBody(Buffer())
    mockResponse = mockResponse.setChunkedBody(Buffer(), 0)
    mockResponse = mockResponse.setChunkedBody("", 0)
    var socketPolicy: SocketPolicy = mockResponse.getSocketPolicy()
    socketPolicy = mockResponse.getSocketPolicy()
    mockResponse = mockResponse.setSocketPolicy(SocketPolicy.KEEP_OPEN)
    var http2ErrorCode: Int = mockResponse.getHttp2ErrorCode()
    http2ErrorCode = mockResponse.getHttp2ErrorCode()
    mockResponse = mockResponse.setHttp2ErrorCode(0)
    mockResponse.setHttp2ErrorCode(0)
    mockResponse = mockResponse.throttleBody(0L, 0L, TimeUnit.SECONDS)
    var throttleBytesPerPeriod: Long = mockResponse.throttleBytesPerPeriod
    throttleBytesPerPeriod = mockResponse.throttleBytesPerPeriod
    var throttlePeriod: Long = mockResponse.getThrottlePeriod(TimeUnit.SECONDS)
    mockResponse = mockResponse.setBodyDelay(0L, TimeUnit.SECONDS)
    val bodyDelay: Long = mockResponse.getBodyDelay(TimeUnit.SECONDS)
    mockResponse = mockResponse.setHeadersDelay(0L, TimeUnit.SECONDS)
    val headersDelay: Long = mockResponse.getHeadersDelay(TimeUnit.SECONDS)
    mockResponse = mockResponse.withPush(PushPromise("", "", Headers.of(), MockResponse()))
    var pushPromises: List<PushPromise> = mockResponse.pushPromises
    pushPromises = mockResponse.pushPromises
    mockResponse = mockResponse.withSettings(Settings())
    var settings: Settings = mockResponse.settings
    settings = mockResponse.settings
    mockResponse = mockResponse.withWebSocketUpgrade(object : WebSocketListener() {
    })
    var webSocketListener: WebSocketListener? = mockResponse.webSocketListener
    webSocketListener = mockResponse.webSocketListener
  }

  @Test @Ignore
  fun mockWebServer() {
    val mockWebServer: MockWebServer = MockWebServer()
    var port: Int = mockWebServer.getPort()
    port = mockWebServer.getPort()
    var hostName: String = mockWebServer.hostName
    hostName = mockWebServer.hostName
    val toProxyAddress: Proxy = mockWebServer.toProxyAddress()
    mockWebServer.setServerSocketFactory(ServerSocketFactory.getDefault())
    val url: HttpUrl = mockWebServer.url("")
    mockWebServer.setBodyLimit(0L)
    mockWebServer.setProtocolNegotiationEnabled(false)
    mockWebServer.setProtocols(listOf())
    val protocols: List<Protocol> = mockWebServer.protocols()
    mockWebServer.useHttps(SSLSocketFactory.getDefault() as SSLSocketFactory, false)
    mockWebServer.noClientAuth()
    mockWebServer.requestClientAuth()
    mockWebServer.requireClientAuth()
    var request: RecordedRequest? = mockWebServer.takeRequest()
    request = mockWebServer.takeRequest(0L, TimeUnit.SECONDS)
    var requestCount: Int = mockWebServer.getRequestCount()
    requestCount = mockWebServer.getRequestCount()
    mockWebServer.enqueue(MockResponse())
    mockWebServer.start()
    mockWebServer.start(0)
    mockWebServer.start(InetAddress.getLocalHost(), 0)
    mockWebServer.shutdown()
    var dispatcher: okhttp3.mockwebserver.Dispatcher = mockWebServer.dispatcher
    dispatcher = mockWebServer.dispatcher
    mockWebServer.dispatcher = QueueDispatcher()
    mockWebServer.dispatcher = QueueDispatcher()
    mockWebServer.close()
  }

  @Test @Ignore
  fun multipartBody() {
    val multipartBody: MultipartBody = MultipartBody.Builder().build()
    val type: MediaType = multipartBody.type()
    val boundary: String = multipartBody.boundary()
    val size: Int = multipartBody.size()
    val parts: List<MultipartBody.Part> = multipartBody.parts()
    val part: MultipartBody.Part = multipartBody.part(0)
    val contentType: MediaType? = multipartBody.contentType()
    val contentLength: Long = multipartBody.contentLength()
    multipartBody.writeTo(Buffer())
    val mixed: MediaType = MultipartBody.MIXED
    val alternative: MediaType = MultipartBody.ALTERNATIVE
    val digest: MediaType = MultipartBody.DIGEST
    val parallel: MediaType = MultipartBody.PARALLEL
    val form: MediaType = MultipartBody.FORM
  }

  @Test @Ignore
  fun multipartBodyPart() {
    val requestBody: RequestBody = RequestBody.create(null, "")
    var part: MultipartBody.Part = MultipartBody.Part.create(null, requestBody)
    part = MultipartBody.Part.create(Headers.of(), requestBody)
    part = MultipartBody.Part.create(requestBody)
    part = MultipartBody.Part.createFormData("", "")
    part = MultipartBody.Part.createFormData("", "", requestBody)
    part = MultipartBody.Part.createFormData("", null, requestBody)
    val headers: Headers? = part.headers()
    val body: RequestBody = part.body()
  }

  @Test @Ignore
  fun multipartBodyBuilder() {
    val requestBody = RequestBody.create(null, "")
    var builder: MultipartBody.Builder = MultipartBody.Builder()
    builder = MultipartBody.Builder("")
    builder = builder.setType(MediaType.get(""))
    builder = builder.addPart(requestBody)
    builder = builder.addPart(Headers.of(), requestBody)
    builder = builder.addPart(null, requestBody)
    builder = builder.addFormDataPart("", "")
    builder = builder.addFormDataPart("", "", requestBody)
    builder = builder.addFormDataPart("", null, requestBody)
    builder = builder.addPart(MultipartBody.Part.create(requestBody))
    val multipartBody: MultipartBody = builder.build()
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
    builder = builder.hostnameVerifier(OkHostnameVerifier)
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
    builder = builder.addInterceptor { it: Interceptor.Chain -> TODO() }
    val networkInterceptors: List<Interceptor> = builder.networkInterceptors()
    builder = builder.addNetworkInterceptor(object : Interceptor {
      override fun intercept(chain: Interceptor.Chain): Response = TODO()
    })
    builder = builder.addNetworkInterceptor { it: Interceptor.Chain -> TODO() }
    builder = builder.eventListener(EventListener.NONE)
    builder = builder.eventListenerFactory(object : EventListener.Factory {
      override fun create(call: Call): EventListener = TODO()
    })
    builder = builder.eventListenerFactory(LoggingEventListener.Factory { s -> TODO() })
    builder = builder.eventListenerFactory { it: Call -> TODO() }
    val client: OkHttpClient = builder.build()
  }

  @Test @Ignore
  fun testAddInterceptor() {
    val builder = OkHttpClient.Builder()

    val i = HttpLoggingInterceptor()

    builder.interceptors().add(i)
    builder.networkInterceptors().add(i)
  }

  @Test @Ignore
  fun protocol() {
    var protocol: Protocol = Protocol.HTTP_2
    protocol = Protocol.get("")
  }

  @Test @Ignore
  fun pushPromise() {
    val pushPromise: PushPromise = PushPromise("", "", Headers.of(), MockResponse())
    val method: String = pushPromise.method()
    val path: String = pushPromise.path()
    val headers: Headers = pushPromise.headers()
    val response: MockResponse = pushPromise.response()
  }

  @Test @Ignore
  fun queueDispatcher() {
    var queueDispatcher: QueueDispatcher = object : QueueDispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = TODO()
      override fun peek(): MockResponse = TODO()
      override fun enqueueResponse(response: MockResponse) = TODO()
      override fun shutdown() = TODO()
      override fun setFailFast(failFast: Boolean) = TODO()
      override fun setFailFast(failFastResponse: MockResponse?) = TODO()
    }
    queueDispatcher = QueueDispatcher()
    var mockResponse: MockResponse = queueDispatcher.dispatch(
        RecordedRequest("", Headers.of(), listOf(), 0L, Buffer(), 0, Socket()))
    mockResponse = queueDispatcher.peek()
    queueDispatcher.enqueueResponse(MockResponse())
    queueDispatcher.shutdown()
    queueDispatcher.setFailFast(false)
    queueDispatcher.setFailFast(MockResponse())
  }

  @Test @Ignore
  fun recordedRequest() {
    var recordedRequest: RecordedRequest = RecordedRequest(
        "", Headers.of(), listOf(), 0L, Buffer(), 0, Socket())
    recordedRequest = RecordedRequest("", Headers.of(), listOf(), 0L, Buffer(), 0, Socket())
    var requestUrl: HttpUrl? = recordedRequest.requestUrl
    var requestLine: String = recordedRequest.requestLine
    var method: String? = recordedRequest.method
    var path: String? = recordedRequest.path
    var headers: Headers = recordedRequest.headers
    val header: String? = recordedRequest.getHeader("")
    var chunkSizes: List<Int> = recordedRequest.chunkSizes
    var bodySize: Long = recordedRequest.bodySize
    var body: Buffer = recordedRequest.body
    var utf8Body: String = recordedRequest.utf8Body
    var sequenceNumber: Int = recordedRequest.sequenceNumber
    var tlsVersion: TlsVersion? = recordedRequest.tlsVersion
    var handshake: Handshake? = recordedRequest.handshake
    requestUrl = recordedRequest.requestUrl
    requestLine = recordedRequest.requestLine
    method = recordedRequest.method
    path = recordedRequest.path
    headers = recordedRequest.headers
    chunkSizes = recordedRequest.chunkSizes
    bodySize = recordedRequest.bodySize
    body = recordedRequest.body
    utf8Body = recordedRequest.utf8Body
    sequenceNumber = recordedRequest.sequenceNumber
    tlsVersion = recordedRequest.tlsVersion
    handshake = recordedRequest.handshake
  }

  @Test @Ignore
  fun request() {
    val request: Request = Request.Builder().build()
    val isHttps: Boolean = request.isHttps
    val url: HttpUrl = request.url()
    val method: String = request.method()
    val headers: Headers = request.headers()
    val header: String? = request.header("")
    val headersForName: List<String> = request.headers("")
    val body: RequestBody? = request.body()
    var tag: Any? = request.tag()
    tag = request.tag(Any::class.java)
    val builder: Request.Builder = request.newBuilder()
    val cacheControl: CacheControl = request.cacheControl()
  }

  @Test @Ignore
  fun requestBuilder() {
    val requestBody = RequestBody.create(null, "")
    var builder = Request.Builder()
    builder = builder.url(HttpUrl.get(""))
    builder = builder.url("")
    builder = builder.url(URL(""))
    builder = builder.header("", "")
    builder = builder.addHeader("", "")
    builder = builder.removeHeader("")
    builder = builder.headers(Headers.of())
    builder = builder.cacheControl(CacheControl.FORCE_CACHE)
    builder = builder.get()
    builder = builder.head()
    builder = builder.post(requestBody)
    builder = builder.delete(requestBody)
    builder = builder.delete(null)
    builder = builder.put(requestBody)
    builder = builder.patch(requestBody)
    builder = builder.method("", requestBody)
    builder = builder.method("", null)
    builder = builder.tag("")
    builder = builder.tag(null)
    builder = builder.tag(String::class.java, "")
    builder = builder.tag(String::class.java, null)
    val request: Request = builder.build()
  }

  @Test @Ignore
  fun requestBody() {
    var requestBody: RequestBody = object : RequestBody() {
      override fun contentType(): MediaType? = TODO()
      override fun contentLength(): Long = TODO()
      override fun isDuplex(): Boolean = TODO()
      override fun isOneShot(): Boolean = TODO()
      override fun writeTo(sink: BufferedSink) = TODO()
    }
    requestBody = RequestBody.create(null, "")
    requestBody = RequestBody.create(MediaType.parse(""), "")
    requestBody = RequestBody.create(null, ByteString.EMPTY)
    requestBody = RequestBody.create(MediaType.parse(""), ByteString.EMPTY)
    requestBody = RequestBody.create(null, byteArrayOf(0, 1))
    requestBody = RequestBody.create(MediaType.parse(""), byteArrayOf(0, 1))
    requestBody = RequestBody.create(null, byteArrayOf(0, 1), 0, 2)
    requestBody = RequestBody.create(MediaType.parse(""), byteArrayOf(0, 1), 0, 2)
    requestBody = RequestBody.create(null, File(""))
    requestBody = RequestBody.create(MediaType.parse(""), File(""))
  }

  @Test @Ignore
  fun response() {
    val response: Response = Response.Builder().build()
    val request: Request = response.request()
    val protocol: Protocol = response.protocol()
    val code: Int = response.code()
    val successful: Boolean = response.isSuccessful
    val message: String = response.message()
    val handshake: Handshake? = response.handshake()
    val headersForName: List<String> = response.headers("")
    val header: String? = response.header("")
    val headers: Headers = response.headers()
    val trailers: Headers = response.trailers()
    val peekBody: ResponseBody = response.peekBody(0L)
    val body: ResponseBody? = response.body()
    val builder: Response.Builder = response.newBuilder()
    val redirect: Boolean = response.isRedirect
    val networkResponse: Response? = response.networkResponse()
    val cacheResponse: Response? = response.cacheResponse()
    val priorResponse: Response? = response.priorResponse()
    val challenges: List<Challenge> = response.challenges()
    val cacheControl: CacheControl = response.cacheControl()
    val sentRequestAtMillis: Long = response.sentRequestAtMillis()
    val receivedResponseAtMillis: Long = response.receivedResponseAtMillis()
  }

  @Test @Ignore
  fun responseBuilder() {
    var builder: Response.Builder = Response.Builder()
    builder = builder.request(Request.Builder().build())
    builder = builder.protocol(Protocol.HTTP_2)
    builder = builder.code(0)
    builder = builder.message("")
    builder = builder.handshake(Handshake.get(
        TlsVersion.TLS_1_3,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        listOf(),
        listOf())
    )
    builder = builder.handshake(null)
    builder = builder.header("", "")
    builder = builder.addHeader("", "")
    builder = builder.removeHeader("")
    builder = builder.headers(Headers.of())
    builder = builder.body(ResponseBody.create(null, ""))
    builder = builder.body(null)
    builder = builder.networkResponse(Response.Builder().build())
    builder = builder.networkResponse(null)
    builder = builder.cacheResponse(Response.Builder().build())
    builder = builder.cacheResponse(null)
    builder = builder.priorResponse(Response.Builder().build())
    builder = builder.priorResponse(null)
    builder = builder.sentRequestAtMillis(0L)
    builder = builder.receivedResponseAtMillis(0L)
    val response: Response = builder.build()
  }

  @Test @Ignore
  fun responseBody() {
    var responseBody: ResponseBody = object : ResponseBody() {
      override fun contentType(): MediaType? = TODO()
      override fun contentLength(): Long = TODO()
      override fun source(): BufferedSource = TODO()
      override fun close() = TODO()
    }
    val byteStream = responseBody.byteStream()
    val source = responseBody.source()
    val bytes = responseBody.bytes()
    val charStream = responseBody.charStream()
    val string = responseBody.string()
    responseBody.close()
    responseBody = ResponseBody.create(MediaType.get(""), "")
    responseBody = ResponseBody.create(null, "")
    responseBody = ResponseBody.create(MediaType.get(""), ByteString.EMPTY)
    responseBody = ResponseBody.create(null, ByteString.EMPTY)
    responseBody = ResponseBody.create(MediaType.get(""), byteArrayOf(0, 1))
    responseBody = ResponseBody.create(null, byteArrayOf(0, 1))
    responseBody = ResponseBody.create(MediaType.get(""), 0L, Buffer())
    responseBody = ResponseBody.create(null, 0L, Buffer())
  }

  @Test @Ignore
  fun route() {
    val route: Route = newRoute()
    val address: Address = route.address()
    val proxy: Proxy = route.proxy()
    val inetSocketAddress: InetSocketAddress = route.socketAddress()
    val requiresTunnel: Boolean = route.requiresTunnel()
  }

  @Test @Ignore
  fun socketPolicy() {
    val socketPolicy: SocketPolicy = SocketPolicy.KEEP_OPEN
  }

  @Test @Ignore
  fun tlsVersion() {
    var tlsVersion: TlsVersion = TlsVersion.TLS_1_3
    val javaName: String = tlsVersion.javaName()
    tlsVersion = TlsVersion.forJavaName("")
  }

  @Test @Ignore
  fun webSocket() {
    val webSocket = object : WebSocket {
      override fun request(): Request = TODO()
      override fun queueSize(): Long = TODO()
      override fun send(text: String): Boolean = TODO()
      override fun send(bytes: ByteString): Boolean = TODO()
      override fun close(code: Int, reason: String?): Boolean = TODO()
      override fun cancel() = TODO()
    }
  }

  @Test @Ignore
  fun webSocketListener() {
    val webSocketListener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) = TODO()
      override fun onMessage(webSocket: WebSocket, text: String) = TODO()
      override fun onMessage(webSocket: WebSocket, bytes: ByteString) = TODO()
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = TODO()
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = TODO()
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = TODO()
    }
  }

  private fun newAddress(): Address {
    return Address(
        "",
        0,
        Dns.SYSTEM,
        SocketFactory.getDefault(),
        localhost().sslSocketFactory(),
        OkHostnameVerifier,
        CertificatePinner.DEFAULT,
        Authenticator.NONE,
        Proxy.NO_PROXY,
        listOf(Protocol.HTTP_1_1),
        listOf(ConnectionSpec.MODERN_TLS),
        NullProxySelector()
    )
  }

  private fun newCall(): Call {
    return object : Call {
      override fun request(): Request = TODO()
      override fun execute(): Response = TODO()
      override fun enqueue(responseCallback: Callback) = TODO()
      override fun cancel() = TODO()
      override fun isExecuted(): Boolean = TODO()
      override fun isCanceled(): Boolean = TODO()
      override fun timeout(): Timeout = TODO()
      override fun clone(): Call = TODO()
    }
  }

  private fun newCookieHandler(): CookieHandler {
    return object : CookieHandler() {
      override fun put(
        uri: URI?,
        responseHeaders: MutableMap<String, MutableList<String>>?
      ) = TODO()

      override fun get(
        uri: URI?,
        requestHeaders: MutableMap<String, MutableList<String>>?
      ): MutableMap<String, MutableList<String>> = TODO()
    }
  }

  private fun newInterceptorChain(): Interceptor.Chain {
    return object : Interceptor.Chain {
      override fun request(): Request = TODO()
      override fun proceed(request: Request): Response = TODO()
      override fun connection(): Connection? = TODO()
      override fun call(): Call = TODO()
      override fun connectTimeoutMillis(): Int = TODO()
      override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = TODO()
      override fun readTimeoutMillis(): Int = TODO()
      override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = TODO()
      override fun writeTimeoutMillis(): Int = TODO()
      override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = TODO()
    }
  }

  private fun newRoute(): Route {
    return Route(newAddress(), Proxy.NO_PROXY, InetSocketAddress.createUnresolved("", 0))
  }
}