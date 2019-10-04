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
package okhttp3.internal.connection

import okhttp3.Address
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.platform.Platform
import okio.AsyncTimeout
import okio.Timeout
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.ref.WeakReference
import java.net.Socket
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory

/**
 * Bridge between OkHttp's application and network layers. This class exposes high-level application
 * layer primitives: connections, requests, responses, and streams.
 *
 * This class supports [asynchronous canceling][cancel]. This is intended to have the smallest
 * blast radius possible. If an HTTP/2 stream is active, canceling will cancel that stream but not
 * the other streams sharing its connection. But if the TLS handshake is still in progress then
 * canceling may break the entire connection.
 */
class Transmitter(
  private val client: OkHttpClient,
  private val call: Call
) {
  private val connectionPool: RealConnectionPool = client.connectionPool.delegate
  private val eventListener: EventListener = client.eventListenerFactory.create(call)
  private val timeout = object : AsyncTimeout() {
    override fun timedOut() {
      cancel()
    }
  }.apply {
    timeout(client.callTimeoutMillis.toLong(), MILLISECONDS)
  }

  private var callStackTrace: Any? = null

  private var request: Request? = null
  private var exchangeFinder: ExchangeFinder? = null

  // Guarded by connectionPool.
  var connection: RealConnection? = null
  private var exchange: Exchange? = null
  private var exchangeRequestDone = false
  private var exchangeResponseDone = false
  private var canceled = false
  private var timeoutEarlyExit = false
  private var noMoreExchanges = false

  val isCanceled: Boolean
    get() {
      synchronized(connectionPool) {
        return canceled
      }
    }

  fun timeout(): Timeout = timeout

  fun timeoutEnter() {
    timeout.enter()
  }

  /**
   * Stops applying the timeout before the call is entirely complete. This is used for WebSockets
   * and duplex calls where the timeout only applies to the initial setup.
   */
  fun timeoutEarlyExit() {
    check(!timeoutEarlyExit)
    timeoutEarlyExit = true
    timeout.exit()
  }

  private fun <E : IOException?> timeoutExit(cause: E): E {
    if (timeoutEarlyExit) return cause
    if (!timeout.exit()) return cause

    val e = InterruptedIOException("timeout")
    if (cause != null) e.initCause(cause)
    @Suppress("UNCHECKED_CAST") // E is either IOException or IOException?
    return e as E
  }

  fun callStart() {
    this.callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()")
    eventListener.callStart(call)
  }

  /**
   * Prepare to create a stream to carry [request]. This prefers to use the existing connection if
   * it exists.
   */
  fun prepareToConnect(request: Request) {
    if (this.request != null) {
      if (this.request!!.url.canReuseConnectionFor(request.url) && exchangeFinder!!.hasRouteToTry()) {
        return // Already ready.
      }
      check(exchange == null)

      if (exchangeFinder != null) {
        maybeReleaseConnection(null, true)
        exchangeFinder = null
      }
    }

    this.request = request
    this.exchangeFinder = ExchangeFinder(
        this, connectionPool, createAddress(request.url), call, eventListener)
  }

  private fun createAddress(url: HttpUrl): Address {
    var sslSocketFactory: SSLSocketFactory? = null
    var hostnameVerifier: HostnameVerifier? = null
    var certificatePinner: CertificatePinner? = null
    if (url.isHttps) {
      sslSocketFactory = client.sslSocketFactory
      hostnameVerifier = client.hostnameVerifier
      certificatePinner = client.certificatePinner
    }

    return Address(url.host, url.port, client.dns, client.socketFactory,
        sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator,
        client.proxy, client.protocols, client.connectionSpecs, client.proxySelector)
  }

  /** Returns a new exchange to carry a new request and response. */
  internal fun newExchange(chain: Interceptor.Chain, doExtensiveHealthChecks: Boolean): Exchange {
    synchronized(connectionPool) {
      check(!noMoreExchanges) { "released" }
      check(exchange == null) {
        "cannot make a new request because the previous response is still open: " +
            "please call response.close()"
      }
    }

    val codec = exchangeFinder!!.find(client, chain, doExtensiveHealthChecks)
    val result = Exchange(this, call, eventListener, exchangeFinder!!, codec)

    synchronized(connectionPool) {
      this.exchange = result
      this.exchangeRequestDone = false
      this.exchangeResponseDone = false
      return result
    }
  }

  fun acquireConnectionNoEvents(connection: RealConnection) {
    assert(Thread.holdsLock(connectionPool))

    check(this.connection == null)
    this.connection = connection
    connection.transmitters.add(TransmitterReference(this, callStackTrace))
  }

  /**
   * Remove the transmitter from the connection's list of allocations. Returns a socket that the
   * caller should close.
   */
  fun releaseConnectionNoEvents(): Socket? {
    assert(Thread.holdsLock(connectionPool))

    val index = connection!!.transmitters.indexOfFirst { it.get() == this@Transmitter }
    check(index != -1)

    val released = this.connection
    released!!.transmitters.removeAt(index)
    this.connection = null

    if (released.transmitters.isEmpty()) {
      released.idleAtNanos = System.nanoTime()
      if (connectionPool.connectionBecameIdle(released)) {
        return released.socket()
      }
    }

    return null
  }

  fun exchangeDoneDueToException() {
    synchronized(connectionPool) {
      exchange?.detachWithViolence()
      check(!noMoreExchanges)
      exchange = null
    }
  }

  /**
   * Releases resources held with the request or response of [exchange]. This should be called when
   * the request completes normally or when it fails due to an exception, in which case [e] should
   * be non-null.
   *
   * If the exchange was canceled or timed out, this will wrap [e] in an exception that provides
   * that additional context. Otherwise [e] is returned as-is.
   */
  internal fun <E : IOException?> exchangeMessageDone(
    exchange: Exchange,
    requestDone: Boolean,
    responseDone: Boolean,
    e: E
  ): E {
    var result = e
    var exchangeDone = false
    synchronized(connectionPool) {
      if (exchange != this.exchange) {
        return result // This exchange was detached violently!
      }
      var changed = false
      if (requestDone) {
        if (!exchangeRequestDone) changed = true
        this.exchangeRequestDone = true
      }
      if (responseDone) {
        if (!exchangeResponseDone) changed = true
        this.exchangeResponseDone = true
      }
      if (exchangeRequestDone && exchangeResponseDone && changed) {
        exchangeDone = true
        this.exchange!!.connection()!!.successCount++
        this.exchange = null
      }
    }
    if (exchangeDone) {
      result = maybeReleaseConnection(result, false)
    }
    return result
  }

  fun noMoreExchanges(e: IOException?): IOException? {
    synchronized(connectionPool) {
      noMoreExchanges = true
    }
    return maybeReleaseConnection(e, false)
  }

  /**
   * Release the connection if it is no longer needed. This is called after each exchange completes
   * and after the call signals that no more exchanges are expected.
   *
   * If the transmitter was canceled or timed out, this will wrap [e] in an exception that provides
   * that additional context. Otherwise [e] is returned as-is.
   *
   * @param force true to release the connection even if more exchanges are expected for the call.
   */
  private fun <E : IOException?> maybeReleaseConnection(e: E, force: Boolean): E {
    var result = e
    val socket: Socket?
    var releasedConnection: Connection?
    val callEnd: Boolean
    synchronized(connectionPool) {
      check(!force || exchange == null) { "cannot release connection while it is in use" }
      releasedConnection = this.connection
      socket = if (this.connection != null && exchange == null && (force || noMoreExchanges)) {
        releaseConnectionNoEvents()
      } else {
        null
      }
      if (this.connection != null) releasedConnection = null
      callEnd = noMoreExchanges && exchange == null
    }
    socket?.closeQuietly()

    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection!!)
    }

    if (callEnd) {
      val callFailed = result != null
      result = timeoutExit(result)
      if (callFailed) {
        eventListener.callFailed(call, result!!)
      } else {
        eventListener.callEnd(call)
      }
    }
    return result
  }

  fun canRetry(): Boolean {
    return exchangeFinder!!.hasStreamFailure() && exchangeFinder!!.hasRouteToTry()
  }

  fun hasExchange(): Boolean {
    synchronized(connectionPool) {
      return exchange != null
    }
  }

  /**
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   *
   * This method is safe to be called concurrently, but provides limited guarantees. If a transport
   * layer connection has been established (such as a HTTP/2 stream) that is terminated. Otherwise
   * if a socket connection is being established, that is terminated.
   */
  fun cancel() {
    val exchangeToCancel: Exchange?
    val connectionToCancel: RealConnection?
    synchronized(connectionPool) {
      canceled = true
      exchangeToCancel = exchange
      connectionToCancel = exchangeFinder?.connectingConnection() ?: connection
    }
    exchangeToCancel?.cancel() ?: connectionToCancel?.cancel()
  }

  internal class TransmitterReference(
    referent: Transmitter,
    /**
     * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
     * identifying the origin of connection leaks.
     */
    val callStackTrace: Any?
  ) : WeakReference<Transmitter>(referent)
}
