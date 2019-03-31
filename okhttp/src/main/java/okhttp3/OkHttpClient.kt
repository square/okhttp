/*
 * Copyright (C) 2012 Square, Inc.
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

import okhttp3.Protocol.HTTP_1_1
import okhttp3.Protocol.HTTP_2
import okhttp3.internal.Internal
import okhttp3.internal.Util
import okhttp3.internal.Util.checkDuration
import okhttp3.internal.cache.InternalCache
import okhttp3.internal.connection.Exchange
import okhttp3.internal.platform.Platform
import okhttp3.internal.proxy.NullProxySelector
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.internal.ws.RealWebSocket
import okio.Sink
import okio.Source
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.security.GeneralSecurityException
import java.time.Duration
import java.util.Collections
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.DeprecationLevel.ERROR

/**
 * Factory for [calls][Call], which can be used to send HTTP requests and read their responses.
 *
 * ## OkHttpClients Should Be Shared
 *
 * OkHttp performs best when you create a single `OkHttpClient` instance and reuse it for all of
 * your HTTP calls. This is because each client holds its own connection pool and thread pools.
 * Reusing connections and threads reduces latency and saves memory. Conversely, creating a client
 * for each request wastes resources on idle pools.
 *
 * Use `new OkHttpClient()` to create a shared instance with the default settings:
 *
 * ```
 * // The singleton HTTP client.
 * public final OkHttpClient client = new OkHttpClient();
 * ```
 *
 * Or use `new OkHttpClient.Builder()` to create a shared instance with custom settings:
 *
 * ```
 * // The singleton HTTP client.
 * public final OkHttpClient client = new OkHttpClient.Builder()
 *     .addInterceptor(new HttpLoggingInterceptor())
 *     .cache(new Cache(cacheDir, cacheSize))
 *     .build();
 * ```
 *
 * ## Customize Your Client With newBuilder()
 *
 * You can customize a shared OkHttpClient instance with [newBuilder]. This builds a client that
 * shares the same connection pool, thread pools, and configuration. Use the builder methods to
 * configure the derived client for a specific purpose.
 *
 * This example shows a call with a short 500 millisecond timeout:
 *
 * ```
 * OkHttpClient eagerClient = client.newBuilder()
 *     .readTimeout(500, TimeUnit.MILLISECONDS)
 *     .build();
 * Response response = eagerClient.newCall(request).execute();
 * ```
 *
 * ## Shutdown Isn't Necessary
 *
 * The threads and connections that are held will be released automatically if they remain idle. But
 * if you are writing a application that needs to aggressively release unused resources you may do
 * so.
 *
 * Shutdown the dispatcher's executor service with [shutdown()][ExecutorService.shutdown]. This will
 * also cause future calls to the client to be rejected.
 *
 * ```
 * client.dispatcher().executorService().shutdown();
 * ```
 *
 * Clear the connection pool with [evictAll()][ConnectionPool.evictAll]. Note that the connection
 * pool's daemon thread may not exit immediately.
 *
 * ```
 * client.connectionPool().evictAll();
 * ```
 *
 * If your client has a cache, call [close()][Cache.close]. Note that it is an error to create calls
 * against a cache that is closed, and doing so will cause the call to crash.
 *
 * ```
 * client.cache().close();
 * ```
 *
 * OkHttp also uses daemon threads for HTTP/2 connections. These will exit automatically if they
 * remain idle.
 */
open class OkHttpClient internal constructor(
  builder: Builder
) : Cloneable, Call.Factory, WebSocket.Factory {
  private val dispatcher: Dispatcher = builder.dispatcher
  private val proxy: Proxy? = builder.proxy
  private val protocols: List<Protocol> = builder.protocols
  private val connectionSpecs: List<ConnectionSpec> = builder.connectionSpecs
  private val interceptors: List<Interceptor> =
      Util.immutableList(builder.interceptors)
  private val networkInterceptors: List<Interceptor> =
      Util.immutableList(builder.networkInterceptors)
  private val eventListenerFactory: EventListener.Factory = builder.eventListenerFactory
  private val proxySelector: ProxySelector = builder.proxySelector
  private val cookieJar: CookieJar = builder.cookieJar
  private val cache: Cache? = builder.cache
  private val socketFactory: SocketFactory = builder.socketFactory
  private val sslSocketFactory: SSLSocketFactory?
  private val hostnameVerifier: HostnameVerifier = builder.hostnameVerifier
  private val certificatePinner: CertificatePinner
  private val proxyAuthenticator: Authenticator = builder.proxyAuthenticator
  private val authenticator: Authenticator = builder.authenticator
  private val connectionPool: ConnectionPool = builder.connectionPool
  private val dns: Dns = builder.dns
  private val followSslRedirects: Boolean = builder.followSslRedirects
  private val followRedirects: Boolean = builder.followRedirects
  private val retryOnConnectionFailure: Boolean = builder.retryOnConnectionFailure
  private val callTimeout: Int = builder.callTimeout
  private val connectTimeout: Int = builder.connectTimeout
  private val readTimeout: Int = builder.readTimeout
  private val writeTimeout: Int = builder.writeTimeout
  private val pingInterval: Int = builder.pingInterval
  private val internalCache: InternalCache? = builder.internalCache
  private val certificateChainCleaner: CertificateChainCleaner?

  constructor() : this(Builder())

  init {
    var isTLS = false
    for (spec in connectionSpecs) {
      isTLS = isTLS || spec.isTls
    }

    // TODO - these nullability warnings existed in Java
    if (builder.sslSocketFactory != null || !isTLS) {
      this.sslSocketFactory = builder.sslSocketFactory
      this.certificateChainCleaner = builder.certificateChainCleaner
    } else {
      val trustManager = Util.platformTrustManager()
      this.sslSocketFactory = newSslSocketFactory(trustManager)
      this.certificateChainCleaner = CertificateChainCleaner.get(trustManager)
    }

    if (sslSocketFactory != null) {
      Platform.get().configureSslSocketFactory(sslSocketFactory)
    }

    this.certificatePinner = builder.certificatePinner
        .withCertificateChainCleaner(certificateChainCleaner)

    check(null !in (interceptors as List<Interceptor?>)) {
      "Null interceptor: $interceptors"
    }
    check(null !in (networkInterceptors as List<Interceptor?>)) {
      "Null network interceptor: $networkInterceptors"
    }
  }

  open fun dispatcher(): Dispatcher = dispatcher

  open fun proxy(): Proxy? = proxy

  open fun protocols(): List<Protocol> = protocols

  open fun connectionSpecs(): List<ConnectionSpec> = connectionSpecs

  /**
   * Returns an immutable list of interceptors that observe the full span of each call: from before
   * the connection is established (if any) until after the response source is selected (either the
   * origin server, cache, or both).
   */
  open fun interceptors(): List<Interceptor> = interceptors

  /**
   * Returns an immutable list of interceptors that observe a single network request and response.
   * These interceptors must call [Interceptor.Chain.proceed] exactly once: it is an error for
   * a network interceptor to short-circuit or repeat a network request.
   */
  open fun networkInterceptors(): List<Interceptor> = networkInterceptors

  open fun eventListenerFactory(): EventListener.Factory = eventListenerFactory

  open fun proxySelector(): ProxySelector = proxySelector

  open fun cookieJar(): CookieJar = cookieJar

  open fun cache(): Cache? = cache

  open fun socketFactory(): SocketFactory = socketFactory

  open fun sslSocketFactory(): SSLSocketFactory = sslSocketFactory!!

  open fun hostnameVerifier(): HostnameVerifier = hostnameVerifier

  open fun certificatePinner(): CertificatePinner = certificatePinner

  open fun proxyAuthenticator(): Authenticator = proxyAuthenticator

  open fun authenticator(): Authenticator = authenticator

  open fun connectionPool(): ConnectionPool = connectionPool

  open fun dns(): Dns = dns

  open fun followSslRedirects(): Boolean = followSslRedirects

  open fun followRedirects(): Boolean = followRedirects

  open fun retryOnConnectionFailure(): Boolean = retryOnConnectionFailure

  /**
   * Default call timeout (in milliseconds). By default there is no timeout for complete calls, but
   * there is for the connect, write, and read actions within a call.
   */
  open fun callTimeoutMillis(): Int = callTimeout

  /** Default connect timeout (in milliseconds). The default is 10 seconds.  */
  open fun connectTimeoutMillis(): Int = connectTimeout

  /** Default read timeout (in milliseconds). The default is 10 seconds. */
  open fun readTimeoutMillis(): Int = readTimeout

  /** Default write timeout (in milliseconds). The default is 10 seconds. */
  open fun writeTimeoutMillis(): Int = writeTimeout

  /** Web socket and HTTP/2 ping interval (in milliseconds). By default pings are not sent. */
  open fun pingIntervalMillis(): Int = pingInterval

  internal fun internalCache(): InternalCache? {
    return cache?.internalCache ?: internalCache
  }

  /** Prepares the [request] to be executed at some point in the future. */
  override fun newCall(request: Request): Call {
    return RealCall.newRealCall(this, request, false /* for web socket */)
  }

  /** Uses [request] to connect a new web socket. */
  override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
    val webSocket = RealWebSocket(request, listener, Random(), pingInterval.toLong())
    webSocket.connect(this)
    return webSocket
  }

  open fun newBuilder(): Builder = Builder(this)

  class Builder constructor() {
    internal var dispatcher: Dispatcher = Dispatcher()
    internal var proxy: Proxy? = null
    internal var protocols: List<Protocol> = DEFAULT_PROTOCOLS
    internal var connectionSpecs: List<ConnectionSpec> = DEFAULT_CONNECTION_SPECS
    internal val interceptors: MutableList<Interceptor> = mutableListOf()
    internal val networkInterceptors: MutableList<Interceptor> = mutableListOf()
    internal var eventListenerFactory: EventListener.Factory = Util.eventListenerFactory(
        EventListener.NONE)
    internal var proxySelector: ProxySelector = ProxySelector.getDefault() ?: NullProxySelector()
    internal var cookieJar: CookieJar = CookieJar.NO_COOKIES
    internal var cache: Cache? = null
    internal var internalCache: InternalCache? = null
    internal var socketFactory: SocketFactory = SocketFactory.getDefault()
    internal var sslSocketFactory: SSLSocketFactory? = null
    internal var certificateChainCleaner: CertificateChainCleaner? = null
    internal var hostnameVerifier: HostnameVerifier = OkHostnameVerifier.INSTANCE
    internal var certificatePinner: CertificatePinner = CertificatePinner.DEFAULT
    internal var proxyAuthenticator: Authenticator = Authenticator.NONE
    internal var authenticator: Authenticator = Authenticator.NONE
    internal var connectionPool: ConnectionPool = ConnectionPool()
    internal var dns: Dns = Dns.SYSTEM
    internal var followSslRedirects: Boolean = true
    internal var followRedirects: Boolean = true
    internal var retryOnConnectionFailure: Boolean = true
    internal var callTimeout: Int = 0
    internal var connectTimeout: Int = 10000
    internal var readTimeout: Int = 10000
    internal var writeTimeout: Int = 10000
    internal var pingInterval: Int = 0

    internal constructor(okHttpClient: OkHttpClient) : this() {
      this.dispatcher = okHttpClient.dispatcher
      this.proxy = okHttpClient.proxy
      this.protocols = okHttpClient.protocols
      this.connectionSpecs = okHttpClient.connectionSpecs
      this.interceptors += okHttpClient.interceptors
      this.networkInterceptors += okHttpClient.networkInterceptors
      this.eventListenerFactory = okHttpClient.eventListenerFactory
      this.proxySelector = okHttpClient.proxySelector
      this.cookieJar = okHttpClient.cookieJar
      this.internalCache = okHttpClient.internalCache
      this.cache = okHttpClient.cache
      this.socketFactory = okHttpClient.socketFactory
      this.sslSocketFactory = okHttpClient.sslSocketFactory
      this.certificateChainCleaner = okHttpClient.certificateChainCleaner
      this.hostnameVerifier = okHttpClient.hostnameVerifier
      this.certificatePinner = okHttpClient.certificatePinner
      this.proxyAuthenticator = okHttpClient.proxyAuthenticator
      this.authenticator = okHttpClient.authenticator
      this.connectionPool = okHttpClient.connectionPool
      this.dns = okHttpClient.dns
      this.followSslRedirects = okHttpClient.followSslRedirects
      this.followRedirects = okHttpClient.followRedirects
      this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure
      this.callTimeout = okHttpClient.callTimeout
      this.connectTimeout = okHttpClient.connectTimeout
      this.readTimeout = okHttpClient.readTimeout
      this.writeTimeout = okHttpClient.writeTimeout
      this.pingInterval = okHttpClient.pingInterval
    }

    /**
     * Sets the default timeout for complete calls. A value of 0 means no timeout, otherwise values
     * must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The call timeout spans the entire call: resolving DNS, connecting, writing the request body,
     * server processing, and reading the response body. If the call requires redirects or retries
     * all must complete within one timeout period.
     *
     * The default value is 0 which imposes no timeout.
     */
    fun callTimeout(timeout: Long, unit: TimeUnit) = apply {
      callTimeout = checkDuration("timeout", timeout, unit)
    }

    /**
     * Sets the default timeout for complete calls. A value of 0 means no timeout, otherwise values
     * must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The call timeout spans the entire call: resolving DNS, connecting, writing the request body,
     * server processing, and reading the response body. If the call requires redirects or retries
     * all must complete within one timeout period.
     *
     * The default value is 0 which imposes no timeout.
     */
    @IgnoreJRERequirement
    fun callTimeout(duration: Duration) = apply {
      callTimeout = checkDuration("timeout", duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Sets the default connect timeout for new connections. A value of 0 means no timeout,
     * otherwise values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The connect timeout is applied when connecting a TCP socket to the target host. The default
     * value is 10 seconds.
     */
    fun connectTimeout(timeout: Long, unit: TimeUnit) = apply {
      connectTimeout = checkDuration("timeout", timeout, unit)
    }

    /**
     * Sets the default connect timeout for new connections. A value of 0 means no timeout,
     * otherwise values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The connect timeout is applied when connecting a TCP socket to the target host. The default
     * value is 10 seconds.
     */
    @IgnoreJRERequirement
    fun connectTimeout(duration: Duration) = apply {
      connectTimeout = checkDuration("timeout", duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The read timeout is applied to both the TCP socket and for individual read IO operations
     * including on [Source] of the [Response]. The default value is 10 seconds.
     *
     * @see Socket.setSoTimeout
     * @see Source.timeout
     */
    fun readTimeout(timeout: Long, unit: TimeUnit) = apply {
      readTimeout = checkDuration("timeout", timeout, unit)
    }

    /**
     * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The read timeout is applied to both the TCP socket and for individual read IO operations
     * including on [Source] of the [Response]. The default value is 10 seconds.
     *
     * @see Socket.setSoTimeout
     * @see Source.timeout
     */
    @IgnoreJRERequirement
    fun readTimeout(duration: Duration) = apply {
      readTimeout = checkDuration("timeout", duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The write timeout is applied for individual write IO operations. The default value is 10
     * seconds.
     *
     * @see Sink.timeout
     */
    fun writeTimeout(timeout: Long, unit: TimeUnit) = apply {
      writeTimeout = checkDuration("timeout", timeout, unit)
    }

    /**
     * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and [Integer.MAX_VALUE] when converted to milliseconds.
     *
     * The write timeout is applied for individual write IO operations. The default value is 10
     * seconds.
     *
     * @see Sink.timeout
     */
    @IgnoreJRERequirement
    fun writeTimeout(duration: Duration) = apply {
      writeTimeout = checkDuration("timeout", duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to
     * automatically send ping frames until either the connection fails or it is closed. This keeps
     * the connection alive and may detect connectivity failures.
     *
     * If the server does not respond to each ping with a pong within `interval`, this client will
     * assume that connectivity has been lost. When this happens on a web socket the connection is
     * canceled and its listener is [notified][WebSocketListener.onFailure]. When it happens on an
     * HTTP/2 connection the connection is closed and any calls it is carrying
     * [will fail with an IOException][java.io.IOException].
     *
     * The default value of 0 disables client-initiated pings.
     */
    fun pingInterval(interval: Long, unit: TimeUnit) = apply {
      pingInterval = checkDuration("interval", interval, unit)
    }

    /**
     * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to
     * automatically send ping frames until either the connection fails or it is closed. This keeps
     * the connection alive and may detect connectivity failures.
     *
     * If the server does not respond to each ping with a pong within `interval`, this client will
     * assume that connectivity has been lost. When this happens on a web socket the connection is
     * canceled and its listener is [notified][WebSocketListener.onFailure]. When it happens on an
     * HTTP/2 connection the connection is closed and any calls it is carrying
     * [will fail with an IOException][java.io.IOException].
     *
     * The default value of 0 disables client-initiated pings.
     */
    @IgnoreJRERequirement
    fun pingInterval(duration: Duration) = apply {
      pingInterval = checkDuration("timeout", duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Sets the HTTP proxy that will be used by connections created by this client. This takes
     * precedence over [proxySelector], which is only honored when this proxy is null (which it is
     * by default). To disable proxy use completely, call `proxy(Proxy.NO_PROXY)`.
     */
    fun proxy(proxy: Proxy?) = apply {
      this.proxy = proxy
    }

    /**
     * Sets the proxy selection policy to be used if no [proxy][proxy] is specified explicitly. The
     * proxy selector may return multiple proxies; in that case they will be tried in sequence until
     * a successful connection is established.
     *
     * If unset, the [system-wide default][ProxySelector.getDefault] proxy selector will be used.
     */
    fun proxySelector(proxySelector: ProxySelector) = apply {
      this.proxySelector = proxySelector
    }

    /**
     * Sets the handler that can accept cookies from incoming HTTP responses and provides cookies to
     * outgoing HTTP requests.
     *
     * If unset, [no cookies][CookieJar.NO_COOKIES] will be accepted nor provided.
     */
    fun cookieJar(cookieJar: CookieJar) = apply {
      this.cookieJar = cookieJar
    }

    /** Sets the response cache to be used to read and write cached responses.  */
    fun cache(cache: Cache?) = apply {
      this.cache = cache
      this.internalCache = null
    }

    /**
     * Sets the DNS service used to lookup IP addresses for hostnames.
     *
     * If unset, the [system-wide default][Dns.SYSTEM] DNS will be used.
     */
    fun dns(dns: Dns) = apply {
      this.dns = dns
    }

    /**
     * Sets the socket factory used to create connections. OkHttp only uses the parameterless
     * [SocketFactory.createSocket] method to create unconnected sockets. Overriding this method,
     * e. g., allows the socket to be bound to a specific local address.
     *
     * If unset, the [system-wide default][SocketFactory.getDefault] socket factory will be used.
     */
    fun socketFactory(socketFactory: SocketFactory) = apply {
      if (socketFactory is SSLSocketFactory) {
        throw IllegalArgumentException("socketFactory instanceof SSLSocketFactory")
      }
      this.socketFactory = socketFactory
    }

    /**
     * Sets the socket factory used to secure HTTPS connections. If unset, the system default will
     * be used.
     *
     * @deprecated [SSLSocketFactory] does not expose its [X509TrustManager], which is a field that
     *     OkHttp needs to build a clean certificate chain. This method instead must use reflection
     *     to extract the trust manager. Applications should prefer to call
     *     `sslSocketFactory(SSLSocketFactory, X509TrustManager)`, which avoids such reflection.
     */
    @Deprecated(
        message = "Use the sslSocketFactory overload that accepts a X509TrustManager.",
        level = ERROR
    )
    fun sslSocketFactory(sslSocketFactory: SSLSocketFactory) = apply {
      this.sslSocketFactory = sslSocketFactory
      this.certificateChainCleaner = Platform.get().buildCertificateChainCleaner(sslSocketFactory)
    }

    /**
     * Sets the socket factory and trust manager used to secure HTTPS connections. If unset, the
     * system defaults will be used.
     *
     * Most applications should not call this method, and instead use the system defaults. Those
     * classes include special optimizations that can be lost if the implementations are decorated.
     *
     * If necessary, you can create and configure the defaults yourself with the following code:
     *
     * ```
     * TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
     * TrustManagerFactory.getDefaultAlgorithm());
     * trustManagerFactory.init((KeyStore) null);
     * TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
     * if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
     *     throw new IllegalStateException("Unexpected default trust managers:"
     *         + Arrays.toString(trustManagers));
     * }
     * X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
     *
     * SSLContext sslContext = SSLContext.getInstance("TLS");
     * sslContext.init(null, new TrustManager[] { trustManager }, null);
     * SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
     *
     * OkHttpClient client = new OkHttpClient.Builder()
     *     .sslSocketFactory(sslSocketFactory, trustManager)
     *     .build();
     * ```
     */
    fun sslSocketFactory(
      sslSocketFactory: SSLSocketFactory, trustManager: X509TrustManager
    ) = apply {
      this.sslSocketFactory = sslSocketFactory
      this.certificateChainCleaner = CertificateChainCleaner.get(trustManager)
    }

    /**
     * Sets the verifier used to confirm that response certificates apply to requested hostnames for
     * HTTPS connections.
     *
     * If unset, a default hostname verifier will be used.
     */
    fun hostnameVerifier(hostnameVerifier: HostnameVerifier) = apply {
      this.hostnameVerifier = hostnameVerifier
    }

    /**
     * Sets the certificate pinner that constrains which certificates are trusted. By default HTTPS
     * connections rely on only the [SSL socket factory][sslSocketFactory] to establish trust.
     * Pinning certificates avoids the need to trust certificate authorities.
     */
    fun certificatePinner(certificatePinner: CertificatePinner) = apply {
      this.certificatePinner = certificatePinner
    }

    /**
     * Sets the authenticator used to respond to challenges from origin servers. Use
     * [proxyAuthenticator] to set the authenticator for proxy servers.
     *
     * If unset, the [no authentication will be attempted][Authenticator.NONE].
     */
    fun authenticator(authenticator: Authenticator) = apply {
      this.authenticator = authenticator
    }

    /**
     * Sets the authenticator used to respond to challenges from proxy servers. Use [authenticator]
     * to set the authenticator for origin servers.
     *
     * If unset, the [no authentication will be attempted][Authenticator.NONE].
     */
    fun proxyAuthenticator(proxyAuthenticator: Authenticator) = apply {
      this.proxyAuthenticator = proxyAuthenticator
    }

    /**
     * Sets the connection pool used to recycle HTTP and HTTPS connections.
     *
     * If unset, a new connection pool will be used.
     */
    fun connectionPool(connectionPool: ConnectionPool) = apply {
      this.connectionPool = connectionPool
    }

    /**
     * Configure this client to follow redirects from HTTPS to HTTP and from HTTP to HTTPS.
     *
     * If unset, protocol redirects will be followed. This is different than the built-in
     * `HttpURLConnection`'s default.
     */
    fun followSslRedirects(followProtocolRedirects: Boolean) = apply {
      this.followSslRedirects = followProtocolRedirects
    }

    /** Configure this client to follow redirects. If unset, redirects will be followed. */
    fun followRedirects(followRedirects: Boolean) = apply {
      this.followRedirects = followRedirects
    }

    /**
     * Configure this client to retry or not when a connectivity problem is encountered. By default,
     * this client silently recovers from the following problems:
     *
     * * **Unreachable IP addresses.** If the URL's host has multiple IP addresses,
     *   failure to reach any individual IP address doesn't fail the overall request. This can
     *   increase availability of multi-homed services.
     *
     * * **Stale pooled connections.** The [ConnectionPool] reuses sockets
     *   to decrease request latency, but these connections will occasionally time out.
     *
     * * **Unreachable proxy servers.** A [ProxySelector] can be used to
     *   attempt multiple proxy servers in sequence, eventually falling back to a direct
     *   connection.
     *
     * Set this to false to avoid retrying requests when doing so is destructive. In this case the
     * calling application should do its own recovery of connectivity failures.
     */
    fun retryOnConnectionFailure(retryOnConnectionFailure: Boolean) = apply {
      this.retryOnConnectionFailure = retryOnConnectionFailure
    }

    /**
     * Sets the dispatcher used to set policy and execute asynchronous requests. Must not be null.
     */
    fun dispatcher(dispatcher: Dispatcher) = apply {
      this.dispatcher = dispatcher
    }

    /**
     * Configure the protocols used by this client to communicate with remote servers. By default
     * this client will prefer the most efficient transport available, falling back to more
     * ubiquitous protocols. Applications should only call this method to avoid specific
     * compatibility problems, such as web servers that behave incorrectly when HTTP/2 is enabled.
     *
     * The following protocols are currently supported:
     *
     * * [http/1.1][rfc_2616]
     * * [h2][rfc_7540]
     * * [h2 with prior knowledge(cleartext only)][rfc_7540_34]
     *
     * **This is an evolving set.** Future releases include support for transitional
     * protocols. The http/1.1 transport will never be dropped.
     *
     * If multiple protocols are specified, [ALPN][alpn] will be used to negotiate a transport.
     * Protocol negotiation is only attempted for HTTPS URLs.
     *
     * [Protocol.HTTP_1_0] is not supported in this set. Requests are initiated with `HTTP/1.1`. If
     * the server responds with `HTTP/1.0`, that will be exposed by [Response.protocol].
     *
     * [alpn]: http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg
     * [rfc_2616]: http://www.w3.org/Protocols/rfc2616/rfc2616.html
     * [rfc_7540]: https://tools.ietf.org/html/rfc7540
     * [rfc_7540_34]: https://tools.ietf.org/html/rfc7540#section-3.4
     *
     * @param protocols the protocols to use, in order of preference. If the list contains
     *     [Protocol.H2_PRIOR_KNOWLEDGE] then that must be the only protocol and HTTPS URLs will not
     *     be supported. Otherwise the list must contain [Protocol.HTTP_1_1]. The list must
     *     not contain null or [Protocol.HTTP_1_0].
     */
    fun protocols(protocols: List<Protocol>) = apply {
      // Create a private copy of the list.
      val protocolsCopy = protocols.toMutableList()

      // Validate that the list has everything we require and nothing we forbid.
      require(Protocol.H2_PRIOR_KNOWLEDGE in protocolsCopy || Protocol.HTTP_1_1 in protocolsCopy) {
        "protocols must contain h2_prior_knowledge or http/1.1: $protocolsCopy"
      }
      require(Protocol.H2_PRIOR_KNOWLEDGE !in protocolsCopy || protocolsCopy.size <= 1) {
        "protocols containing h2_prior_knowledge cannot use other protocols: $protocolsCopy"
      }
      require(Protocol.HTTP_1_0 !in protocolsCopy) {
        "protocols must not contain http/1.0: $protocolsCopy"
      }
      require(null !in (protocols as List<Protocol?>)) {
        "protocols must not contain null"
      }

      // Remove protocolsCopy that we no longer support.
      @Suppress("DEPRECATION")
      protocolsCopy.remove(Protocol.SPDY_3)

      // Assign as an unmodifiable list. This is effectively immutable.
      this.protocols = Collections.unmodifiableList(protocols)
    }

    fun connectionSpecs(connectionSpecs: List<ConnectionSpec>) = apply {
      this.connectionSpecs = Util.immutableList(connectionSpecs)
    }

    /**
     * Returns a modifiable list of interceptors that observe the full span of each call: from
     * before the connection is established (if any) until after the response source is selected
     * (either the origin server, cache, or both).
     */
    fun interceptors(): List<Interceptor> = interceptors

    fun addInterceptor(interceptor: Interceptor) = apply {
      interceptors += interceptor
    }

    /**
     * Returns a modifiable list of interceptors that observe a single network request and response.
     * These interceptors must call [Interceptor.Chain.proceed] exactly once: it is an error for a
     * network interceptor to short-circuit or repeat a network request.
     */
    fun networkInterceptors(): List<Interceptor> = networkInterceptors

    fun addNetworkInterceptor(interceptor: Interceptor) = apply {
      networkInterceptors += interceptor
    }

    /**
     * Configure a single client scoped listener that will receive all analytic events for this
     * client.
     *
     * @see EventListener for semantics and restrictions on listener implementations.
     */
    fun eventListener(eventListener: EventListener) = apply {
      this.eventListenerFactory = Util.eventListenerFactory(eventListener)
    }

    /**
     * Configure a factory to provide per-call scoped listeners that will receive analytic events
     * for this client.
     *
     * @see EventListener for semantics and restrictions on listener implementations.
     */
    fun eventListenerFactory(eventListenerFactory: EventListener.Factory) = apply {
      this.eventListenerFactory = eventListenerFactory
    }

    fun build(): OkHttpClient = OkHttpClient(this)
  }

  companion object {
    internal val DEFAULT_PROTOCOLS = Util.immutableList(HTTP_2, HTTP_1_1)

    internal val DEFAULT_CONNECTION_SPECS = Util.immutableList(
        ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)

    init {
      Internal.instance = object : Internal() {
        override fun realConnectionPool(connectionPool: ConnectionPool) =
            connectionPool.delegate

        override fun equalsNonHost(a: Address, b: Address) = a.equalsNonHost(b)

        override fun code(responseBuilder: Response.Builder) = responseBuilder.code

        override fun newWebSocketCall(client: OkHttpClient, originalRequest: Request) =
            RealCall.newRealCall(client, originalRequest, true)

        override fun initExchange(responseBuilder: Response.Builder, exchange: Exchange) {
          responseBuilder.initExchange(exchange)
        }

        override fun exchange(response: Response) = response.exchange
      }
    }

    private fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
      try {
        val sslContext = Platform.get().getSSLContext()
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return sslContext.socketFactory
      } catch (e: GeneralSecurityException) {
        throw AssertionError("No System TLS", e) // The system has no TLS. Just give up.
      }
    }
  }
}
