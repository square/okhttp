/*
 * Copyright (C) 2017 Square, Inc.
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Listener for metrics events. Extend this class to monitor the quantity, size, and duration of
 * your application's HTTP calls.
 *
 * All start/connect/acquire events will eventually receive a matching end/release event, either
 * successful (non-null parameters), or failed (non-null throwable). The first common parameters of
 * each event pair are used to link the event in case of concurrent or repeated events e.g.
 * `dnsStart(call, domainName)` → `dnsEnd(call, domainName, inetAddressList)`.
 *
 * Events are typically nested with this structure:
 *
 *  * call ([callStart], [callEnd], [callFailed])
 *    * proxy selection ([proxySelectStart], [proxySelectEnd])
 *    * dns ([dnsStart], [dnsEnd])
 *    * connect ([connectStart], [connectEnd], [connectFailed])
 *      * secure connect ([secureConnectStart], [secureConnectEnd])
 *    * connection held ([connectionAcquired], [connectionReleased])
 *      * request ([requestFailed])
 *        * headers ([requestHeadersStart], [requestHeadersEnd])
 *        * body ([requestBodyStart], [requestBodyEnd])
 *      * response ([responseFailed])
 *        * headers ([responseHeadersStart], [responseHeadersEnd])
 *        * body ([responseBodyStart], [responseBodyEnd])
 *
 * This nesting is typical but not strict. For example, when calls use "Expect: continue" the
 * request body start and end events occur within the response header events. Similarly,
 * [duplex calls][RequestBody.isDuplex] interleave the request and response bodies.
 *
 * Since connections may be reused, the proxy selection, DNS, and connect events may not be present
 * for a call. In future releases of OkHttp these events may also occur concurrently to permit
 * multiple routes to be attempted simultaneously.
 *
 * Events and sequences of events may be repeated for retries and follow-ups.
 *
 * All event methods must execute fast, without external locking, cannot throw exceptions, attempt
 * to mutate the event parameters, or be re-entrant back into the client. Any IO - writing to files
 * or network should be done asynchronously.
 */
abstract class EventListener {
  /**
   * Invoked as soon as a call is enqueued or executed by a client. In case of thread or stream
   * limits, this call may be executed well before processing the request is able to begin.
   *
   * This will be invoked only once for a single [Call]. Retries of different routes or redirects
   * will be handled within the boundaries of a single [callStart] and [callEnd]/[callFailed] pair.
   */
  open fun callStart(
    call: Call
  ) {
  }

  /**
   * Invoked prior to a proxy selection.
   *
   * This will be invoked for route selection regardless of whether the client
   * is configured with a single proxy, a proxy selector, or neither.
   *
   * @param url a URL with only the scheme, hostname, and port specified.
   */
  open fun proxySelectStart(
    call: Call,
    url: HttpUrl
  ) {
  }

  /**
   * Invoked after proxy selection.
   *
   * Note that the list of proxies is never null, but it may be a list containing
   * only [Proxy.NO_PROXY]. This comes up in several situations:
   *
   * * If neither a proxy nor proxy selector is configured.
   * * If the proxy is configured explicitly as [Proxy.NO_PROXY].
   * * If the proxy selector returns only [Proxy.NO_PROXY].
   * * If the proxy selector returns an empty list or null.
   *
   * Otherwise it lists the proxies in the order they will be attempted.
   *
   * @param url a URL with only the scheme, hostname, and port specified.
   */
  open fun proxySelectEnd(
    call: Call,
    url: HttpUrl,
    proxies: List<@JvmSuppressWildcards Proxy>
  ) {
  }

  /**
   * Invoked just prior to a DNS lookup. See [Dns.lookup].
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different host.
   *
   * If the [Call] is able to reuse an existing pooled connection, this method will not be invoked.
   * See [ConnectionPool].
   */
  open fun dnsStart(
    call: Call,
    domainName: String
  ) {
  }

  /**
   * Invoked immediately after a DNS lookup.
   *
   * This method is invoked after [dnsStart].
   */
  open fun dnsEnd(
    call: Call,
    domainName: String,
    inetAddressList: List<@JvmSuppressWildcards InetAddress>
  ) {
  }

  /**
   * Invoked just prior to initiating a socket connection.
   *
   * This method will be invoked if no existing connection in the [ConnectionPool] can be reused.
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different address, or a connection is retried.
   */
  open fun connectStart(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy
  ) {
  }

  /**
   * Invoked just prior to initiating a TLS connection.
   *
   * This method is invoked if the following conditions are met:
   *
   *  * The [Call.request] requires TLS.
   *
   *  * No existing connection from the [ConnectionPool] can be reused.
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different address, or a connection is retried.
   */
  open fun secureConnectStart(
    call: Call
  ) {
  }

  /**
   * Invoked immediately after a TLS connection was attempted.
   *
   * This method is invoked after [secureConnectStart].
   */
  open fun secureConnectEnd(
    call: Call,
    handshake: Handshake?
  ) {
  }

  /**
   * Invoked immediately after a socket connection was attempted.
   *
   * If the `call` uses HTTPS, this will be invoked after [secureConnectEnd], otherwise it will
   * invoked after [connectStart].
   */
  open fun connectEnd(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?
  ) {
  }

  /**
   * Invoked when a connection attempt fails. This failure is not terminal if further routes are
   * available and failure recovery is enabled.
   *
   * If the `call` uses HTTPS, this will be invoked after [secureConnectEnd], otherwise it will
   * invoked after [connectStart].
   */
  open fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException
  ) {
  }

  /**
   * Invoked after a connection has been acquired for the `call`.
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response
   * to the [Call.request] is a redirect to a different address.
   */
  open fun connectionAcquired(
    call: Call,
    connection: Connection
  ) {
  }

  /**
   * Invoked after a connection has been released for the `call`.
   *
   * This method is always invoked after [connectionAcquired].
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different address.
   */
  open fun connectionReleased(
    call: Call,
    connection: Connection
  ) {
  }

  /**
   * Invoked just prior to sending request headers.
   *
   * The connection is implicit, and will generally relate to the last [connectionAcquired] event.
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different address.
   */
  open fun requestHeadersStart(
    call: Call
  ) {
  }

  /**
   * Invoked immediately after sending request headers.
   *
   * This method is always invoked after [requestHeadersStart].
   *
   * @param request the request sent over the network. It is an error to access the body of this
   *     request.
   */
  open fun requestHeadersEnd(call: Call, request: Request) {
  }

  /**
   * Invoked just prior to sending a request body.  Will only be invoked for request allowing and
   * having a request body to send.
   *
   * The connection is implicit, and will generally relate to the last [connectionAcquired] event.
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different address.
   */
  open fun requestBodyStart(
    call: Call
  ) {
  }

  /**
   * Invoked immediately after sending a request body.
   *
   * This method is always invoked after [requestBodyStart].
   */
  open fun requestBodyEnd(
    call: Call,
    byteCount: Long
  ) {
  }

  /**
   * Invoked when a request fails to be written.
   *
   * This method is invoked after [requestHeadersStart] or [requestBodyStart]. Note that request
   * failures do not necessarily fail the entire call.
   */
  open fun requestFailed(
    call: Call,
    ioe: IOException
  ) {
  }

  /**
   * Invoked when response headers are first returned from the server.
   *
   * The connection is implicit, and will generally relate to the last [connectionAcquired] event.
   *
   * This can be invoked more than 1 time for a single [Call]. For example, if the response to the
   * [Call.request] is a redirect to a different address.
   *
   * Prior to OkHttp 4.3 this was incorrectly invoked when the client was ready to read headers.
   * This was misleading for tracing because it was too early.
   */
  open fun responseHeadersStart(
    call: Call
  ) {
  }

  /**
   * Invoked immediately after receiving response headers.
   *
   * This method is always invoked after [responseHeadersStart].
   *
   * @param response the response received over the network. It is an error to access the body of
   *     this response.
   */
  open fun responseHeadersEnd(
    call: Call,
    response: Response
  ) {
  }

  /**
   * Invoked when data from the response body is first available to the application.
   *
   * This is typically invoked immediately before bytes are returned to the application. If the
   * response body is empty this is invoked immediately before returning that to the application.
   *
   * If the application closes the response body before attempting a read, this is invoked at the
   * time it is closed.
   *
   * The connection is implicit, and will generally relate to the last [connectionAcquired] event.
   *
   * This will usually be invoked only 1 time for a single [Call], exceptions are a limited set of
   * cases including failure recovery.
   *
   * Prior to OkHttp 4.3 this was incorrectly invoked when the client was ready to read the response
   * body. This was misleading for tracing because it was too early.
   */
  open fun responseBodyStart(
    call: Call
  ) {
  }

  /**
   * Invoked immediately after receiving a response body and completing reading it.
   *
   * Will only be invoked for requests having a response body e.g. won't be invoked for a web socket
   * upgrade.
   *
   * If the response body is closed before the response body is exhausted, this is invoked at the
   * time it is closed. In such calls [byteCount] is the number of bytes returned to the
   * application. This may be smaller than the resource's byte count if were read to completion.
   *
   * This method is always invoked after [responseBodyStart].
   */
  open fun responseBodyEnd(
    call: Call,
    byteCount: Long
  ) {
  }

  /**
   * Invoked when a response fails to be read.
   *
   * Note that response failures do not necessarily fail the entire call.
   *
   * Starting with OkHttp 4.3 this may be invoked without a prior call to [responseHeadersStart]
   * or [responseBodyStart]. In earlier releases this method was documented to only be invoked after
   * one of those methods.
   */
  open fun responseFailed(
    call: Call,
    ioe: IOException
  ) {
  }

  /**
   * Invoked immediately after a call has completely ended.  This includes delayed consumption
   * of response body by the caller.
   *
   * This method is always invoked after [callStart].
   */
  open fun callEnd(
    call: Call
  ) {
  }

  /**
   * Invoked when a call fails permanently.
   *
   * This method is always invoked after [callStart].
   */
  open fun callFailed(
    call: Call,
    ioe: IOException
  ) {
  }

  /**
   * Invoked when a call is canceled.
   *
   * Like all methods in this interface, this is invoked on the thread that triggered the event. But
   * while other events occur sequentially; cancels may occur concurrently with other events. For
   * example, thread A may be executing [responseBodyStart] while thread B executes [canceled].
   * Implementations must support such concurrent calls.
   *
   * Note that cancellation is best-effort and that a call may proceed normally after it has been
   * canceled. For example, happy-path events like [requestHeadersStart] and [requestHeadersEnd] may
   * occur after a call is canceled. Typically cancellation takes effect when an expensive I/O
   * operation is required.
   *
   * This is invoked at most once, even if [Call.cancel] is invoked multiple times. It may be
   * invoked at any point in a call's life, including before [callStart] and after [callEnd].
   */
  open fun canceled(
    call: Call
  ) {
  }

  /**
   * Invoked when a call fails due to cache rules.
   * For example, we're forbidden from using the network and the cache is insufficient
   */
  open fun satisfactionFailure(call: Call, response: Response) {
  }

  /**
   * Invoked when a result is served from the cache. The Response provided is the top level
   * Response and normal event sequences will not be received.
   *
   * This event will only be received when a Cache is configured for the client.
   */
  open fun cacheHit(call: Call, response: Response) {
  }

  /**
   * Invoked when a response will be served from the network. The Response will be
   * available from normal event sequences.
   *
   * This event will only be received when a Cache is configured for the client.
   */
  open fun cacheMiss(call: Call) {
  }

  /**
   * Invoked when a response will be served from the cache or network based on validating the
   * cached Response freshness. Will be followed by cacheHit or cacheMiss after the network
   * Response is available.
   *
   * This event will only be received when a Cache is configured for the client.
   */
  open fun cacheConditionalHit(call: Call, cachedResponse: Response) {
  }

  fun interface Factory {
    /**
     * Creates an instance of the [EventListener] for a particular [Call]. The returned
     * [EventListener] instance will be used during the lifecycle of [call].
     *
     * This method is invoked after [call] is created. See [OkHttpClient.newCall].
     *
     * **It is an error for implementations to issue any mutating operations on the [call] instance
     * from this method.**
     */
    fun create(call: Call): EventListener
  }

  companion object {
    @JvmField
    val NONE: EventListener = object : EventListener() {
    }
  }
}
