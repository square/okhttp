/*
 * Copyright (C) 2022 Block, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.UnknownServiceException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import okhttp3.CertificatePinner
import okhttp3.ConnectionListener
import okhttp3.ConnectionSpec
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.RoutePlanner.ConnectResult
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http1.Http1ExchangeCodec
import okhttp3.internal.platform.Platform
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.internal.toHostHeader
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source

/**
 * A single attempt to connect to a remote server, including these steps:
 *
 *  * [TCP handshake][connectSocket]
 *  * Optional [CONNECT tunnels][connectTunnel]. When using an HTTP proxy to reach an HTTPS server
 *    we must send a `CONNECT` request, and handle authorization challenges from the proxy.
 *  * Optional [TLS handshake][connectTls].
 *
 * Each step may fail. If a retry is possible, a new instance is created with the next plan, which
 * will be configured differently.
 */
class ConnectPlan(
  // Configuration and state scoped to the call.
  private val client: OkHttpClient,
  private val call: RealCall,
  private val chain: Interceptor.Chain,
  private val routePlanner: RealRoutePlanner,

  // Specifics to this plan.
  override val route: Route,
  internal val routes: List<Route>?,
  private val attempt: Int,
  private val tunnelRequest: Request?,
  internal val connectionSpecIndex: Int,
  internal val isTlsFallback: Boolean,
  internal val connectionListener: ConnectionListener
) : RoutePlanner.Plan, ExchangeCodec.Carrier {
  private val eventListener = call.eventListener

  /** True if this connect was canceled; typically because it lost a race. */
  @Volatile private var canceled = false

  // These properties are initialized by connect() and never reassigned.

  /** The low-level TCP socket. */
  private var rawSocket: Socket? = null

  /**
   * The application layer socket. Either an [SSLSocket] layered over [rawSocket], or [rawSocket]
   * itself if this connection does not use SSL.
   */
  internal var socket: Socket? = null
  private var handshake: Handshake? = null
  private var protocol: Protocol? = null
  private var source: BufferedSource? = null
  private var sink: BufferedSink? = null
  private var connection: RealConnection? = null

  /** True if this connection is ready for use, including TCP, tunnels, and TLS. */
  override val isReady: Boolean
    get() = protocol != null

  private fun copy(
    attempt: Int = this.attempt,
    tunnelRequest: Request? = this.tunnelRequest,
    connectionSpecIndex: Int = this.connectionSpecIndex,
    isTlsFallback: Boolean = this.isTlsFallback,
  ): ConnectPlan {
    return ConnectPlan(
      client = client,
      call = call,
      chain = chain,
      routePlanner = routePlanner,
      route = route,
      routes = routes,
      attempt = attempt,
      tunnelRequest = tunnelRequest,
      connectionSpecIndex = connectionSpecIndex,
      isTlsFallback = isTlsFallback,
      connectionListener = connectionListener
    )
  }

  override fun connectTcp(): ConnectResult {
    check(rawSocket == null) { "TCP already connected" }

    var success = false

    // Tell the call about the connecting call so async cancels work.
    call.plansToCancel += this
    try {
      eventListener.connectStart(call, route.socketAddress, route.proxy)
      connectionListener.connectStart(route, call)

      connectSocket()
      success = true
      return ConnectResult(plan = this)
    } catch (e: IOException) {
      eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)
      connectionListener.connectFailed(route, call, e)
      return ConnectResult(plan = this, throwable = e)
    } finally {
      call.plansToCancel -= this
      if (!success) {
        rawSocket?.closeQuietly()
      }
    }
  }

  override fun connectTlsEtc(): ConnectResult {
    check(rawSocket != null) { "TCP not connected" }
    check(!isReady) { "already connected" }

    val connectionSpecs = route.address.connectionSpecs
    var retryTlsConnection: ConnectPlan? = null
    var success = false

    // Tell the call about the connecting call so async cancels work.
    call.plansToCancel += this
    try {
      if (tunnelRequest != null) {
        val tunnelResult = connectTunnel()

        // Tunnel didn't work. Start it all again.
        if (tunnelResult.nextPlan != null || tunnelResult.throwable != null) {
          return tunnelResult
        }
      }

      if (route.address.sslSocketFactory != null) {
        // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
        // that happens, then we will have buffered bytes that are needed by the SSLSocket!
        // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
        // that it will almost certainly fail because the proxy has sent unexpected data.
        if (source?.buffer?.exhausted() == false || sink?.buffer?.exhausted() == false) {
          throw IOException("TLS tunnel buffered too many bytes!")
        }

        eventListener.secureConnectStart(call)

        // Create the wrapper over the connected socket.
        val sslSocket = route.address.sslSocketFactory.createSocket(
          rawSocket,
          route.address.url.host,
          route.address.url.port,
          true /* autoClose */
        ) as SSLSocket

        val tlsEquipPlan = planWithCurrentOrInitialConnectionSpec(connectionSpecs, sslSocket)
        val connectionSpec = connectionSpecs[tlsEquipPlan.connectionSpecIndex]

        // Figure out the next connection spec in case we need a retry.
        retryTlsConnection = tlsEquipPlan.nextConnectionSpec(connectionSpecs, sslSocket)

        connectionSpec.apply(sslSocket, isFallback = tlsEquipPlan.isTlsFallback)
        connectTls(sslSocket, connectionSpec)
        eventListener.secureConnectEnd(call, handshake)
      } else {
        socket = rawSocket
        protocol = when {
          Protocol.H2_PRIOR_KNOWLEDGE in route.address.protocols -> Protocol.H2_PRIOR_KNOWLEDGE
          else -> Protocol.HTTP_1_1
        }
      }

      val connection = RealConnection(
        taskRunner = client.taskRunner,
        connectionPool = client.connectionPool.delegate,
        route = route,
        rawSocket = rawSocket,
        socket = socket,
        handshake = handshake,
        protocol = protocol,
        source = source,
        sink = sink,
        pingIntervalMillis = client.pingIntervalMillis,
        connectionListener = client.connectionPool.connectionListener
      )
      this.connection = connection
      connection.start()

      // Success.
      eventListener.connectEnd(call, route.socketAddress, route.proxy, protocol)
      success = true
      return ConnectResult(plan = this)
    } catch (e: IOException) {
      eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)
      connectionListener.connectFailed(route, call, e)

      if (!client.retryOnConnectionFailure || !retryTlsHandshake(e)) {
        retryTlsConnection = null
      }

      return ConnectResult(
        plan = this,
        nextPlan = retryTlsConnection,
        throwable = e
      )
    } finally {
      call.plansToCancel -= this
      if (!success) {
        socket?.closeQuietly()
        rawSocket?.closeQuietly()
      }
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  @Throws(IOException::class)
  private fun connectSocket() {
    val rawSocket = when (route.proxy.type()) {
      Proxy.Type.DIRECT, Proxy.Type.HTTP -> route.address.socketFactory.createSocket()!!
      else -> Socket(route.proxy)
    }
    this.rawSocket = rawSocket

    // Handle the race where cancel() precedes connectSocket(). We don't want to miss a cancel.
    if (canceled) {
      throw IOException("canceled")
    }

    rawSocket.soTimeout = chain.readTimeoutMillis()
    try {
      Platform.get().connectSocket(rawSocket, route.socketAddress, chain.connectTimeoutMillis())
    } catch (e: ConnectException) {
      throw ConnectException("Failed to connect to ${route.socketAddress}").apply {
        initCause(e)
      }
    }

    // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
    // More details:
    // https://github.com/square/okhttp/issues/3245
    // https://android-review.googlesource.com/#/c/271775/
    try {
      source = rawSocket.source().buffer()
      sink = rawSocket.sink().buffer()
    } catch (npe: NullPointerException) {
      if (npe.message == NPE_THROW_WITH_NULL) {
        throw IOException(npe)
      }
    }
  }

  /**
   * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
   * proxy server can issue an auth challenge and then close the connection.
   *
   * @return the next plan to attempt, or null if no further attempt should be made either because
   *     we've successfully connected or because no further attempts should be made.
   */
  @Throws(IOException::class)
  internal fun connectTunnel(): ConnectResult {
    val nextTunnelRequest = createTunnel()
      ?: return ConnectResult(plan = this) // Success.

    // The proxy decided to close the connection after an auth challenge. Retry with different
    // auth credentials.
    rawSocket?.closeQuietly()

    val nextAttempt = attempt + 1
    return when {
      nextAttempt < MAX_TUNNEL_ATTEMPTS -> {
        eventListener.connectEnd(call, route.socketAddress, route.proxy, null)
        ConnectResult(
          plan = this,
          nextPlan = copy(
            attempt = nextAttempt,
            tunnelRequest = nextTunnelRequest,
          )
        )
      }
      else -> {
        val failure = ProtocolException(
          "Too many tunnel connections attempted: $MAX_TUNNEL_ATTEMPTS"
        )
        eventListener.connectFailed(call, route.socketAddress, route.proxy, null, failure)
        connectionListener.connectFailed(route, call, failure)
        return ConnectResult(plan = this, throwable = failure)
      }
    }
  }

  @Throws(IOException::class)
  private fun connectTls(sslSocket: SSLSocket, connectionSpec: ConnectionSpec) {
    val address = route.address
    var success = false
    try {
      if (connectionSpec.supportsTlsExtensions) {
        Platform.get().configureTlsExtensions(sslSocket, address.url.host, address.protocols)
      }

      // Force handshake. This can throw!
      sslSocket.startHandshake()
      // block for session establishment
      val sslSocketSession = sslSocket.session
      val unverifiedHandshake = sslSocketSession.handshake()

      // Verify that the socket's certificates are acceptable for the target host.
      if (!address.hostnameVerifier!!.verify(address.url.host, sslSocketSession)) {
        val peerCertificates = unverifiedHandshake.peerCertificates
        if (peerCertificates.isNotEmpty()) {
          val cert = peerCertificates[0] as X509Certificate
          throw SSLPeerUnverifiedException(
            """
            |Hostname ${address.url.host} not verified:
            |    certificate: ${CertificatePinner.pin(cert)}
            |    DN: ${cert.subjectDN.name}
            |    subjectAltNames: ${OkHostnameVerifier.allSubjectAltNames(cert)}
            """.trimMargin()
          )
        } else {
          throw SSLPeerUnverifiedException(
            "Hostname ${address.url.host} not verified (no certificates)"
          )
        }
      }

      val certificatePinner = address.certificatePinner!!

      val handshake = Handshake(
        unverifiedHandshake.tlsVersion,
        unverifiedHandshake.cipherSuite,
        unverifiedHandshake.localCertificates
      ) {
        certificatePinner.certificateChainCleaner!!.clean(
          unverifiedHandshake.peerCertificates,
          address.url.host
        )
      }
      this.handshake = handshake

      // Check that the certificate pinner is satisfied by the certificates presented.
      certificatePinner.check(address.url.host) {
        handshake.peerCertificates.map { it as X509Certificate }
      }

      // Success! Save the handshake and the ALPN protocol.
      val maybeProtocol = if (connectionSpec.supportsTlsExtensions) {
        Platform.get().getSelectedProtocol(sslSocket)
      } else {
        null
      }
      socket = sslSocket
      source = sslSocket.source().buffer()
      sink = sslSocket.sink().buffer()
      protocol = if (maybeProtocol != null) Protocol.get(maybeProtocol) else Protocol.HTTP_1_1
      success = true
    } finally {
      Platform.get().afterHandshake(sslSocket)
      if (!success) {
        sslSocket.closeQuietly()
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
   * the proxy connection. This may need to be retried if the proxy requires authorization.
   */
  @Throws(IOException::class)
  private fun createTunnel(): Request? {
    var nextRequest = tunnelRequest!!
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    val url = route.address.url
    val requestLine = "CONNECT ${url.toHostHeader(includeDefaultPort = true)} HTTP/1.1"
    while (true) {
      val source = this.source!!
      val sink = this.sink!!
      val tunnelCodec = Http1ExchangeCodec(
        client = null, // No client for CONNECT tunnels.
        carrier = this,
        source = source,
        sink = sink
      )
      source.timeout().timeout(client.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
      sink.timeout().timeout(client.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
      tunnelCodec.writeRequest(nextRequest.headers, requestLine)
      tunnelCodec.finishRequest()
      val response = tunnelCodec.readResponseHeaders(false)!!
        .request(nextRequest)
        .build()
      tunnelCodec.skipConnectBody(response)

      when (response.code) {
        HttpURLConnection.HTTP_OK -> return null

        HttpURLConnection.HTTP_PROXY_AUTH -> {
          nextRequest = route.address.proxyAuthenticator.authenticate(route, response)
            ?: throw IOException("Failed to authenticate with proxy")

          if ("close".equals(response.header("Connection"), ignoreCase = true)) {
            return nextRequest
          }
        }

        else -> throw IOException("Unexpected response code for CONNECT: ${response.code}")
      }
    }
  }

  /**
   * Returns this if its [connectionSpecIndex] is defined, or a new connection with it defined
   * otherwise.
   */
  @Throws(IOException::class)
  internal fun planWithCurrentOrInitialConnectionSpec(
    connectionSpecs: List<ConnectionSpec>,
    sslSocket: SSLSocket
  ): ConnectPlan {
    if (connectionSpecIndex != -1) return this
    return nextConnectionSpec(connectionSpecs, sslSocket)
      ?: throw UnknownServiceException(
        "Unable to find acceptable protocols." +
          " isFallback=${isTlsFallback}," +
          " modes=$connectionSpecs," +
          " supported protocols=${sslSocket.enabledProtocols!!.contentToString()}"
      )
  }

  /**
   * Returns a copy of this connection with the next connection spec to try, or null if no other
   * compatible connection specs are available.
   */
  internal fun nextConnectionSpec(
    connectionSpecs: List<ConnectionSpec>,
    sslSocket: SSLSocket
  ): ConnectPlan? {
    for (i in connectionSpecIndex + 1 until connectionSpecs.size) {
      if (connectionSpecs[i].isCompatible(sslSocket)) {
        return copy(connectionSpecIndex = i, isTlsFallback = (connectionSpecIndex != -1))
      }
    }
    return null
  }

  /** Returns the connection to use, which might be different from [connection]. */
  override fun handleSuccess(): RealConnection {
    call.client.routeDatabase.connected(route)

    val connection = this.connection!!
    connectionListener.connectEnd(connection, route, call)

    // If we raced another call connecting to this host, coalesce the connections. This makes for
    // 3 different lookups in the connection pool!
    val pooled3 = routePlanner.planReusePooledConnection(this, routes)
    if (pooled3 != null) return pooled3.connection

    synchronized(connection) {
      client.connectionPool.delegate.put(connection)
      call.acquireConnectionNoEvents(connection)
    }

    eventListener.connectionAcquired(call, connection)
    connection.connectionListener.connectionAcquired(connection, call)
    return connection
  }

  override fun trackFailure(call: RealCall, e: IOException?) {
    // Do nothing.
  }

  override fun noNewExchanges() {
    // Do nothing.
  }

  override fun cancel() {
    canceled = true
    // Close the raw socket so we don't end up doing synchronous I/O.
    rawSocket?.closeQuietly()
  }

  override fun retry(): RoutePlanner.Plan {
    return ConnectPlan(
      client = client,
      call = call,
      chain = chain,
      routePlanner = routePlanner,
      route = route,
      routes = routes,
      attempt = attempt,
      tunnelRequest = tunnelRequest,
      connectionSpecIndex = connectionSpecIndex,
      isTlsFallback = isTlsFallback,
      connectionListener = connectionListener
    )
  }

  fun closeQuietly() {
    socket?.closeQuietly()
  }

  companion object {
    private const val NPE_THROW_WITH_NULL = "throw with null exception"
    private const val MAX_TUNNEL_ATTEMPTS = 21
  }
}
