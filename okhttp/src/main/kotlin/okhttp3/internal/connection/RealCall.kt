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
package okhttp3.internal.connection

import java.io.IOException
import java.io.InterruptedIOException
import java.lang.ref.WeakReference
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import okhttp3.Address
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.cache.CacheInterceptor
import okhttp3.internal.closeQuietly
import okhttp3.internal.http.BridgeInterceptor
import okhttp3.internal.http.CallServerInterceptor
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http.RetryAndFollowUpInterceptor
import okhttp3.internal.platform.Platform
import okhttp3.internal.threadName
import okio.AsyncTimeout

/**
 * Bridge between OkHttp's application and network layers. This class exposes high-level application
 * layer primitives: connections, requests, responses, and streams.
 *
 * This class supports [asynchronous canceling][cancel]. This is intended to have the smallest
 * blast radius possible. If an HTTP/2 stream is active, canceling will cancel that stream but not
 * the other streams sharing its connection. But if the TLS handshake is still in progress then
 * canceling may break the entire connection.
 */
class RealCall(
  val client: OkHttpClient,
  /** The application's original request unadulterated by redirects or auth headers. */
  val originalRequest: Request,
  val forWebSocket: Boolean
) : Call {
  private val connectionPool: RealConnectionPool = client.connectionPool.delegate

  internal val eventListener: EventListener = client.eventListenerFactory.create(this)

  private val timeout = object : AsyncTimeout() {
    override fun timedOut() {
      cancel()
    }
  }.apply {
    timeout(client.callTimeoutMillis.toLong(), MILLISECONDS)
  }

  private val executed = AtomicBoolean()

  // These properties are only accessed by the thread executing the call.

  /** Initialized in [callStart]. */
  private var callStackTrace: Any? = null

  /** Finds an exchange to send the next request and receive the next response. */
  private var exchangeFinder: ExchangeFinder? = null

  var connection: RealConnection? = null
    private set
  private var timeoutEarlyExit = false

  /**
   * This is the same value as [exchange], but scoped to the execution of the network interceptors.
   * The [exchange] field is assigned to null when its streams end, which may be before or after the
   * network interceptors return.
   */
  internal var interceptorScopedExchange: Exchange? = null
    private set

  // These properties are guarded by this. They are typically only accessed by the thread executing
  // the call, but they may be accessed by other threads for duplex requests.

  /** True if this call still has a request body open. */
  private var requestBodyOpen = false

  /** True if this call still has a response body open. */
  private var responseBodyOpen = false

  /** True if there are more exchanges expected for this call. */
  private var expectMoreExchanges = true

  // These properties are accessed by canceling threads. Any thread can cancel a call, and once it's
  // canceled it's canceled forever.

  @Volatile private var canceled = false
  @Volatile private var exchange: Exchange? = null
  @Volatile var connectionToCancel: RealConnection? = null

  override fun timeout() = timeout

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  override fun clone() = RealCall(client, originalRequest, forWebSocket)

  override fun request(): Request = originalRequest

  /**
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   *
   * This method is safe to be called concurrently, but provides limited guarantees. If a transport
   * layer connection has been established (such as a HTTP/2 stream) that is terminated. Otherwise
   * if a socket connection is being established, that is terminated.
   */
  override fun cancel() {
    if (canceled) return // Already canceled.

    canceled = true
    exchange?.cancel()
    connectionToCancel?.cancel()

    eventListener.canceled(this)
  }

  override fun isCanceled() = canceled

  override fun execute(): Response {
    check(executed.compareAndSet(false, true)) { "Already Executed" }

    timeout.enter()
    callStart()
    try {
      client.dispatcher.executed(this)
      return getResponseWithInterceptorChain()
    } finally {
      client.dispatcher.finished(this)
    }
  }

  override fun enqueue(responseCallback: Callback) {
    check(executed.compareAndSet(false, true)) { "Already Executed" }

    callStart()
    client.dispatcher.enqueue(AsyncCall(responseCallback))
  }

  override fun isExecuted(): Boolean = executed.get()

  private fun callStart() {
    this.callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()")
    eventListener.callStart(this)
  }

  @Throws(IOException::class)
  internal fun getResponseWithInterceptorChain(): Response {
    // Build a full stack of interceptors.
    val interceptors = mutableListOf<Interceptor>()
    interceptors += client.interceptors
    interceptors += RetryAndFollowUpInterceptor(client)
    interceptors += BridgeInterceptor(client.cookieJar)
    interceptors += CacheInterceptor(client.cache)
    interceptors += ConnectInterceptor
    if (!forWebSocket) {
      interceptors += client.networkInterceptors
    }
    interceptors += CallServerInterceptor(forWebSocket)

    val chain = RealInterceptorChain(
        call = this,
        interceptors = interceptors,
        index = 0,
        exchange = null,
        request = originalRequest,
        connectTimeoutMillis = client.connectTimeoutMillis,
        readTimeoutMillis = client.readTimeoutMillis,
        writeTimeoutMillis = client.writeTimeoutMillis
    )

    var calledNoMoreExchanges = false
    try {
      val response = chain.proceed(originalRequest)
      if (isCanceled()) {
        response.closeQuietly()
        throw IOException("Canceled")
      }
      return response
    } catch (e: IOException) {
      calledNoMoreExchanges = true
      throw noMoreExchanges(e) as Throwable
    } finally {
      if (!calledNoMoreExchanges) {
        noMoreExchanges(null)
      }
    }
  }

  /**
   * Prepare for a potential trip through all of this call's network interceptors. This prepares to
   * find an exchange to carry the request.
   *
   * Note that an exchange will not be needed if the request is satisfied by the cache.
   *
   * @param newExchangeFinder true if this is not a retry and new routing can be performed.
   */
  fun enterNetworkInterceptorExchange(request: Request, newExchangeFinder: Boolean) {
    check(interceptorScopedExchange == null)

    synchronized(this) {
      check(!responseBodyOpen) {
        "cannot make a new request because the previous response is still open: " +
            "please call response.close()"
      }
      check(!requestBodyOpen)
    }

    if (newExchangeFinder) {
      this.exchangeFinder = ExchangeFinder(
          connectionPool,
          createAddress(request.url),
          this,
          eventListener
      )
    }
  }

  /** Finds a new or pooled connection to carry a forthcoming request and response. */
  internal fun initExchange(chain: RealInterceptorChain): Exchange {
    synchronized(this) {
      check(expectMoreExchanges) { "released" }
      check(!responseBodyOpen)
      check(!requestBodyOpen)
    }

    val exchangeFinder = this.exchangeFinder!!
    val codec = exchangeFinder.find(client, chain)
    val result = Exchange(this, eventListener, exchangeFinder, codec)
    this.interceptorScopedExchange = result
    this.exchange = result
    synchronized(this) {
      this.requestBodyOpen = true
      this.responseBodyOpen = true
    }

    if (canceled) throw IOException("Canceled")
    return result
  }

  fun acquireConnectionNoEvents(connection: RealConnection) {
    connection.assertThreadHoldsLock()

    check(this.connection == null)
    this.connection = connection
    connection.calls.add(CallReference(this, callStackTrace))
  }

  /**
   * Releases resources held with the request or response of [exchange]. This should be called when
   * the request completes normally or when it fails due to an exception, in which case [e] should
   * be non-null.
   *
   * If the exchange was canceled or timed out, this will wrap [e] in an exception that provides
   * that additional context. Otherwise [e] is returned as-is.
   */
  internal fun <E : IOException?> messageDone(
    exchange: Exchange,
    requestDone: Boolean,
    responseDone: Boolean,
    e: E
  ): E {
    if (exchange != this.exchange) return e // This exchange was detached violently!

    var bothStreamsDone = false
    var callDone = false
    synchronized(this) {
      if (requestDone && requestBodyOpen || responseDone && responseBodyOpen) {
        if (requestDone) requestBodyOpen = false
        if (responseDone) responseBodyOpen = false
        bothStreamsDone = !requestBodyOpen && !responseBodyOpen
        callDone = !requestBodyOpen && !responseBodyOpen && !expectMoreExchanges
      }
    }

    if (bothStreamsDone) {
      this.exchange = null
      this.connection?.incrementSuccessCount()
    }

    if (callDone) {
      return callDone(e)
    }

    return e
  }

  internal fun noMoreExchanges(e: IOException?): IOException? {
    var callDone = false
    synchronized(this) {
      if (expectMoreExchanges) {
        expectMoreExchanges = false
        callDone = !requestBodyOpen && !responseBodyOpen
      }
    }

    if (callDone) {
      return callDone(e)
    }

    return e
  }

  /**
   * Complete this call. This should be called once these properties are all false:
   * [requestBodyOpen], [responseBodyOpen], and [expectMoreExchanges].
   *
   * This will release the connection if it is still held.
   *
   * It will also notify the listener that the call completed; either successfully or
   * unsuccessfully.
   *
   * If the call was canceled or timed out, this will wrap [e] in an exception that provides that
   * additional context. Otherwise [e] is returned as-is.
   */
  private fun <E : IOException?> callDone(e: E): E {
    assertThreadDoesntHoldLock()

    val connection = this.connection
    if (connection != null) {
      connection.assertThreadDoesntHoldLock()
      val socket = synchronized(connection) {
        releaseConnectionNoEvents() // Sets this.connection to null.
      }
      if (this.connection == null) {
        socket?.closeQuietly()
        eventListener.connectionReleased(this, connection)
      } else {
        check(socket == null) // If we still have a connection we shouldn't be closing any sockets.
      }
    }

    val result = timeoutExit(e)
    if (e != null) {
      eventListener.callFailed(this, result!!)
    } else {
      eventListener.callEnd(this)
    }
    return result
  }

  /**
   * Remove this call from the connection's list of allocations. Returns a socket that the caller
   * should close.
   */
  internal fun releaseConnectionNoEvents(): Socket? {
    val connection = this.connection!!
    connection.assertThreadHoldsLock()

    val calls = connection.calls
    val index = calls.indexOfFirst { it.get() == this@RealCall }
    check(index != -1)

    calls.removeAt(index)
    this.connection = null

    if (calls.isEmpty()) {
      connection.idleAtNs = System.nanoTime()
      if (connectionPool.connectionBecameIdle(connection)) {
        return connection.socket()
      }
    }

    return null
  }

  private fun <E : IOException?> timeoutExit(cause: E): E {
    if (timeoutEarlyExit) return cause
    if (!timeout.exit()) return cause

    val e = InterruptedIOException("timeout")
    if (cause != null) e.initCause(cause)
    @Suppress("UNCHECKED_CAST") // E is either IOException or IOException?
    return e as E
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

  /**
   * @param closeExchange true if the current exchange should be closed because it will not be used.
   *     This is usually due to either an exception or a retry.
   */
  internal fun exitNetworkInterceptorExchange(closeExchange: Boolean) {
    synchronized(this) {
      check(expectMoreExchanges) { "released" }
    }

    if (closeExchange) {
      exchange?.detachWithViolence()
    }

    interceptorScopedExchange = null
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

    return Address(
        uriHost = url.host,
        uriPort = url.port,
        dns = client.dns,
        socketFactory = client.socketFactory,
        sslSocketFactory = sslSocketFactory,
        hostnameVerifier = hostnameVerifier,
        certificatePinner = certificatePinner,
        proxyAuthenticator = client.proxyAuthenticator,
        proxy = client.proxy,
        protocols = client.protocols,
        connectionSpecs = client.connectionSpecs,
        proxySelector = client.proxySelector
    )
  }

  fun retryAfterFailure() = exchangeFinder!!.retryAfterFailure()

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private fun toLoggableString(): String {
    return ((if (isCanceled()) "canceled " else "") +
        (if (forWebSocket) "web socket" else "call") +
        " to " + redactedUrl())
  }

  internal fun redactedUrl(): String = originalRequest.url.redact()

  inner class AsyncCall(
    private val responseCallback: Callback
  ) : Runnable {
    @Volatile var callsPerHost = AtomicInteger(0)
      private set

    fun reuseCallsPerHostFrom(other: AsyncCall) {
      this.callsPerHost = other.callsPerHost
    }

    val host: String
      get() = originalRequest.url.host

    val request: Request
        get() = originalRequest

    val call: RealCall
        get() = this@RealCall

    /**
     * Attempt to enqueue this async call on [executorService]. This will attempt to clean up
     * if the executor has been shut down by reporting the call as failed.
     */
    fun executeOn(executorService: ExecutorService) {
      client.dispatcher.assertThreadDoesntHoldLock()

      var success = false
      try {
        executorService.execute(this)
        success = true
      } catch (e: RejectedExecutionException) {
        val ioException = InterruptedIOException("executor rejected")
        ioException.initCause(e)
        noMoreExchanges(ioException)
        responseCallback.onFailure(this@RealCall, ioException)
      } finally {
        if (!success) {
          client.dispatcher.finished(this) // This call is no longer running!
        }
      }
    }

    override fun run() {
      threadName("OkHttp ${redactedUrl()}") {
        var signalledCallback = false
        timeout.enter()
        try {
          val response = getResponseWithInterceptorChain()
          signalledCallback = true
          responseCallback.onResponse(this@RealCall, response)
        } catch (e: IOException) {
          if (signalledCallback) {
            // Do not signal the callback twice!
            Platform.get().log("Callback failure for ${toLoggableString()}", Platform.INFO, e)
          } else {
            responseCallback.onFailure(this@RealCall, e)
          }
        } catch (t: Throwable) {
          cancel()
          if (!signalledCallback) {
            val canceledException = IOException("canceled due to $t")
            canceledException.addSuppressed(t)
            responseCallback.onFailure(this@RealCall, canceledException)
          }
          throw t
        } finally {
          client.dispatcher.finished(this)
        }
      }
    }
  }

  internal class CallReference(
    referent: RealCall,
    /**
     * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
     * identifying the origin of connection leaks.
     */
    val callStackTrace: Any?
  ) : WeakReference<RealCall>(referent)
}
