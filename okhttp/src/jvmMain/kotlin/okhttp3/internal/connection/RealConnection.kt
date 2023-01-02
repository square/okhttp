/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import java.io.IOException
import java.lang.ref.Reference
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import okhttp3.Address
import okhttp3.Connection
import okhttp3.ConnectionListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Route
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http1.Http1ExchangeCodec
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2ExchangeCodec
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.http2.Settings
import okhttp3.internal.http2.StreamResetException
import okhttp3.internal.isHealthy
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.internal.ws.RealWebSocket
import okio.BufferedSink
import okio.BufferedSource

/**
 * A connection to a remote web server capable of carrying 1 or more concurrent streams.
 *
 * Connections are shared in a connection pool. Accesses to the connection's state must be guarded
 * by holding a lock on the connection.
 */
class RealConnection(
  val taskRunner: TaskRunner,
  val connectionPool: RealConnectionPool,
  override val route: Route,

  /** The low-level TCP socket. */
  private var rawSocket: Socket?,

  /**
   * The application layer socket. Either an [SSLSocket] layered over [rawSocket], or [rawSocket]
   * itself if this connection does not use SSL.
   */
  private var socket: Socket?,
  private var handshake: Handshake?,
  private var protocol: Protocol?,
  private var source: BufferedSource?,
  private var sink: BufferedSink?,
  private val pingIntervalMillis: Int,
  internal val connectionListener: ConnectionListener
) : Http2Connection.Listener(), Connection, ExchangeCodec.Carrier {
  private var http2Connection: Http2Connection? = null

  // These properties are guarded by this.

  /**
   * If true, no new exchanges can be created on this connection. It is necessary to set this to
   * true when removing a connection from the pool; otherwise a racing caller might get it from the
   * pool when it shouldn't. Symmetrically, this must always be checked before returning a
   * connection from the pool.
   *
   * Once true this is always true. Guarded by this.
   */
  var noNewExchanges = false

  /**
   * If true, this connection may not be used for coalesced requests. These are requests that could
   * share the same connection without sharing the same hostname.
   */
  private var noCoalescedConnections = false

  /**
   * The number of times there was a problem establishing a stream that could be due to route
   * chosen. Guarded by this.
   */
  internal var routeFailureCount = 0

  private var successCount = 0
  private var refusedStreamCount = 0

  /**
   * The maximum number of concurrent streams that can be carried by this connection. If
   * `allocations.size() < allocationLimit` then new streams can be created on this connection.
   */
  private var allocationLimit = 1

  /** Current calls carried by this connection. */
  val calls = mutableListOf<Reference<RealCall>>()

  /** Timestamp when `allocations.size()` reached zero. Also assigned upon initial connection. */
  var idleAtNs = Long.MAX_VALUE

  /**
   * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
   * requests simultaneously.
   */
  internal val isMultiplexed: Boolean
    get() = http2Connection != null

  /** Prevent further exchanges from being created on this connection. */
  override fun noNewExchanges() {
    synchronized(this) {
      noNewExchanges = true
    }
    connectionListener.noNewExchanges(this)
  }

  /** Prevent this connection from being used for hosts other than the one in [route]. */
  @Synchronized internal fun noCoalescedConnections() {
    noCoalescedConnections = true
  }

  @Synchronized internal fun incrementSuccessCount() {
    successCount++
  }

  @Throws(IOException::class)
  fun start() {
    idleAtNs = System.nanoTime()
    if (protocol == Protocol.HTTP_2 || protocol == Protocol.H2_PRIOR_KNOWLEDGE) {
      startHttp2()
    }
  }

  @Throws(IOException::class)
  private fun startHttp2() {
    val socket = this.socket!!
    val source = this.source!!
    val sink = this.sink!!
    socket.soTimeout = 0 // HTTP/2 connection timeouts are set per-stream.
    val http2Connection = Http2Connection.Builder(client = true, taskRunner)
      .socket(socket, route.address.url.host, source, sink)
      .listener(this)
      .pingIntervalMillis(pingIntervalMillis)
      .build()
    this.http2Connection = http2Connection
    this.allocationLimit = Http2Connection.DEFAULT_SETTINGS.getMaxConcurrentStreams()
    http2Connection.start()
  }

  /**
   * Returns true if this connection can carry a stream allocation to `address`. If non-null
   * `route` is the resolved route for a connection.
   */
  internal fun isEligible(address: Address, routes: List<Route>?): Boolean {
    assertThreadHoldsLock()

    // If this connection is not accepting new exchanges, we're done.
    if (calls.size >= allocationLimit || noNewExchanges) return false

    // If the non-host fields of the address don't overlap, we're done.
    if (!this.route.address.equalsNonHost(address)) return false

    // If the host exactly matches, we're done: this connection can carry the address.
    if (address.url.host == this.route().address.url.host) {
      return true // This connection is a perfect match.
    }

    // At this point we don't have a hostname match. But we still be able to carry the request if
    // our connection coalescing requirements are met. See also:
    // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

    // 1. This connection must be HTTP/2.
    if (http2Connection == null) return false

    // 2. The routes must share an IP address.
    if (routes == null || !routeMatchesAny(routes)) return false

    // 3. This connection's server certificate's must cover the new host.
    if (address.hostnameVerifier !== OkHostnameVerifier) return false
    if (!supportsUrl(address.url)) return false

    // 4. Certificate pinning must match the host.
    try {
      address.certificatePinner!!.check(address.url.host, handshake()!!.peerCertificates)
    } catch (_: SSLPeerUnverifiedException) {
      return false
    }

    return true // The caller's address can be carried by this connection.
  }

  /**
   * Returns true if this connection's route has the same address as any of [candidates]. This
   * requires us to have a DNS address for both hosts, which only happens after route planning. We
   * can't coalesce connections that use a proxy, since proxies don't tell us the origin server's IP
   * address.
   */
  private fun routeMatchesAny(candidates: List<Route>): Boolean {
    return candidates.any {
      it.proxy.type() == Proxy.Type.DIRECT &&
        route.proxy.type() == Proxy.Type.DIRECT &&
        route.socketAddress == it.socketAddress
    }
  }

  private fun supportsUrl(url: HttpUrl): Boolean {
    assertThreadHoldsLock()

    val routeUrl = route.address.url

    if (url.port != routeUrl.port) {
      return false // Port mismatch.
    }

    if (url.host == routeUrl.host) {
      return true // Host match. The URL is supported.
    }

    // We have a host mismatch. But if the certificate matches, we're still good.
    return !noCoalescedConnections && handshake != null && certificateSupportHost(url, handshake!!)
  }

  private fun certificateSupportHost(url: HttpUrl, handshake: Handshake): Boolean {
    val peerCertificates = handshake.peerCertificates

    return peerCertificates.isNotEmpty() &&
      OkHostnameVerifier.verify(url.host, peerCertificates[0] as X509Certificate)
  }

  @Throws(SocketException::class)
  internal fun newCodec(client: OkHttpClient, chain: RealInterceptorChain): ExchangeCodec {
    val socket = this.socket!!
    val source = this.source!!
    val sink = this.sink!!
    val http2Connection = this.http2Connection

    return if (http2Connection != null) {
      Http2ExchangeCodec(client, this, chain, http2Connection)
    } else {
      socket.soTimeout = chain.readTimeoutMillis()
      source.timeout().timeout(chain.readTimeoutMillis.toLong(), MILLISECONDS)
      sink.timeout().timeout(chain.writeTimeoutMillis.toLong(), MILLISECONDS)
      Http1ExchangeCodec(client, this, source, sink)
    }
  }

  @Throws(SocketException::class)
  internal fun newWebSocketStreams(exchange: Exchange): RealWebSocket.Streams {
    val socket = this.socket!!
    val source = this.source!!
    val sink = this.sink!!

    socket.soTimeout = 0
    noNewExchanges()
    return object : RealWebSocket.Streams(true, source, sink) {
      override fun close() {
        exchange.bodyComplete<IOException?>(-1L, responseDone = true, requestDone = true, e = null)
      }
    }
  }

  override fun route(): Route = route

  override fun cancel() {
    // Close the raw socket so we don't end up doing synchronous I/O.
    rawSocket?.closeQuietly()
  }

  override fun socket(): Socket = socket!!

  /** Returns true if this connection is ready to host new streams. */
  fun isHealthy(doExtensiveChecks: Boolean): Boolean {
    assertThreadDoesntHoldLock()

    val nowNs = System.nanoTime()

    val rawSocket = this.rawSocket!!
    val socket = this.socket!!
    val source = this.source!!
    if (rawSocket.isClosed || socket.isClosed || socket.isInputShutdown ||
      socket.isOutputShutdown) {
      return false
    }

    val http2Connection = this.http2Connection
    if (http2Connection != null) {
      return http2Connection.isHealthy(nowNs)
    }

    val idleDurationNs = synchronized(this) { nowNs - idleAtNs }
    if (idleDurationNs >= IDLE_CONNECTION_HEALTHY_NS && doExtensiveChecks) {
      return socket.isHealthy(source)
    }

    return true
  }

  /** Refuse incoming streams. */
  @Throws(IOException::class)
  override fun onStream(stream: Http2Stream) {
    stream.close(ErrorCode.REFUSED_STREAM, null)
  }

  /** When settings are received, adjust the allocation limit. */
  @Synchronized override fun onSettings(connection: Http2Connection, settings: Settings) {
    allocationLimit = settings.getMaxConcurrentStreams()
  }

  override fun handshake(): Handshake? = handshake

  /** Track a bad route in the route database. Other routes will be attempted first. */
  internal fun connectFailed(client: OkHttpClient, failedRoute: Route, failure: IOException) {
    // Tell the proxy selector when we fail to connect on a fresh connection.
    if (failedRoute.proxy.type() != Proxy.Type.DIRECT) {
      val address = failedRoute.address
      address.proxySelector.connectFailed(
        address.url.toUri(), failedRoute.proxy.address(), failure
      )
    }

    client.routeDatabase.failed(failedRoute)
  }

  /**
   * Track a failure using this connection. This may prevent both the connection and its route from
   * being used for future exchanges.
   */
  override fun trackFailure(call: RealCall, e: IOException?) {
    var noNewExchangesEvent = false
    synchronized(this) {
      if (e is StreamResetException) {
        when {
          e.errorCode == ErrorCode.REFUSED_STREAM -> {
            // Stop using this connection on the 2nd REFUSED_STREAM error.
            refusedStreamCount++
            if (refusedStreamCount > 1) {
              noNewExchangesEvent = !noNewExchanges
              noNewExchanges = true
              routeFailureCount++
            }
          }

          e.errorCode == ErrorCode.CANCEL && call.isCanceled() -> {
            // Permit any number of CANCEL errors on locally-canceled calls.
          }

          else -> {
            // Everything else wants a fresh connection.
            noNewExchangesEvent = !noNewExchanges
            noNewExchanges = true
            routeFailureCount++
          }
        }
      } else if (!isMultiplexed || e is ConnectionShutdownException) {
        noNewExchangesEvent = !noNewExchanges
        noNewExchanges = true

        // If this route hasn't completed a call, avoid it for new connections.
        if (successCount == 0) {
          if (e != null) {
            connectFailed(call.client, route, e)
          }
          routeFailureCount++
        }
      }

      Unit
    }

    if (noNewExchangesEvent) {
      connectionListener.noNewExchanges(this)
    }
  }

  override fun protocol(): Protocol = protocol!!

  override fun toString(): String {
    return "Connection{${route.address.url.host}:${route.address.url.port}," +
      " proxy=${route.proxy}" +
      " hostAddress=${route.socketAddress}" +
      " cipherSuite=${handshake?.cipherSuite ?: "none"}" +
      " protocol=$protocol}"
  }

  companion object {
    const val IDLE_CONNECTION_HEALTHY_NS = 10_000_000_000 // 10 seconds.

    fun newTestConnection(
      taskRunner: TaskRunner,
      connectionPool: RealConnectionPool,
      route: Route,
      socket: Socket,
      idleAtNs: Long
    ): RealConnection {
      val result = RealConnection(
        taskRunner = taskRunner,
        connectionPool = connectionPool,
        route = route,
        rawSocket = null,
        socket = socket,
        handshake = null,
        protocol = null,
        source = null,
        sink = null,
        pingIntervalMillis = 0,
        ConnectionListener.NONE
      )
      result.idleAtNs = idleAtNs
      return result
    }
  }
}
