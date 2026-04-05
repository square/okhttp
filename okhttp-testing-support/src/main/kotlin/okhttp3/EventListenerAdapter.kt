/*
 * Copyright (C) 2025 Square, Inc.
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
import okhttp3.CallEvent.CacheConditionalHit
import okhttp3.CallEvent.CacheHit
import okhttp3.CallEvent.CacheMiss
import okhttp3.CallEvent.CallEnd
import okhttp3.CallEvent.CallFailed
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.Canceled
import okhttp3.CallEvent.ConnectEnd
import okhttp3.CallEvent.ConnectFailed
import okhttp3.CallEvent.ConnectStart
import okhttp3.CallEvent.ConnectionAcquired
import okhttp3.CallEvent.ConnectionReleased
import okhttp3.CallEvent.DispatcherQueueEnd
import okhttp3.CallEvent.DispatcherQueueStart
import okhttp3.CallEvent.DnsEnd
import okhttp3.CallEvent.DnsStart
import okhttp3.CallEvent.FollowUpDecision
import okhttp3.CallEvent.ProxySelectEnd
import okhttp3.CallEvent.ProxySelectStart
import okhttp3.CallEvent.RequestBodyEnd
import okhttp3.CallEvent.RequestBodyStart
import okhttp3.CallEvent.RequestFailed
import okhttp3.CallEvent.RequestHeadersEnd
import okhttp3.CallEvent.RequestHeadersStart
import okhttp3.CallEvent.ResponseBodyEnd
import okhttp3.CallEvent.ResponseBodyStart
import okhttp3.CallEvent.ResponseFailed
import okhttp3.CallEvent.ResponseHeadersEnd
import okhttp3.CallEvent.ResponseHeadersStart
import okhttp3.CallEvent.RetryDecision
import okhttp3.CallEvent.SatisfactionFailure
import okhttp3.CallEvent.SecureConnectEnd
import okhttp3.CallEvent.SecureConnectStart

/**
 * This accepts events as function calls on [EventListener], and publishes them as subtypes of
 * [CallEvent].
 */
class EventListenerAdapter : EventListener() {
  var listeners = listOf<(CallEvent) -> Unit>()

  private fun onEvent(listener: CallEvent) {
    for (function in listeners) {
      function(listener)
    }
  }

  override fun dispatcherQueueStart(
    call: Call,
    dispatcher: Dispatcher,
  ) = onEvent(DispatcherQueueStart(System.nanoTime(), call, dispatcher))

  override fun dispatcherQueueEnd(
    call: Call,
    dispatcher: Dispatcher,
  ) = onEvent(DispatcherQueueEnd(System.nanoTime(), call, dispatcher))

  override fun proxySelectStart(
    call: Call,
    url: HttpUrl,
  ) = onEvent(ProxySelectStart(System.nanoTime(), call, url))

  override fun proxySelectEnd(
    call: Call,
    url: HttpUrl,
    proxies: List<Proxy>,
  ) = onEvent(ProxySelectEnd(System.nanoTime(), call, url, proxies))

  override fun dnsStart(
    call: Call,
    domainName: String,
  ) = onEvent(DnsStart(System.nanoTime(), call, domainName))

  override fun dnsEnd(
    call: Call,
    domainName: String,
    inetAddressList: List<InetAddress>,
  ) = onEvent(DnsEnd(System.nanoTime(), call, domainName, inetAddressList))

  override fun connectStart(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
  ) = onEvent(ConnectStart(System.nanoTime(), call, inetSocketAddress, proxy))

  override fun secureConnectStart(call: Call) =
    onEvent(
      SecureConnectStart(
        System.nanoTime(),
        call,
      ),
    )

  override fun secureConnectEnd(
    call: Call,
    handshake: Handshake?,
  ) = onEvent(SecureConnectEnd(System.nanoTime(), call, handshake))

  override fun connectEnd(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
  ) = onEvent(ConnectEnd(System.nanoTime(), call, inetSocketAddress, proxy, protocol))

  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException,
  ) = onEvent(
    ConnectFailed(
      System.nanoTime(),
      call,
      inetSocketAddress,
      proxy,
      protocol,
      ioe,
    ),
  )

  override fun connectionAcquired(
    call: Call,
    connection: Connection,
  ) = onEvent(ConnectionAcquired(System.nanoTime(), call, connection))

  override fun connectionReleased(
    call: Call,
    connection: Connection,
  ) = onEvent(ConnectionReleased(System.nanoTime(), call, connection))

  override fun callStart(call: Call) = onEvent(CallStart(System.nanoTime(), call))

  override fun requestHeadersStart(call: Call) =
    onEvent(
      RequestHeadersStart(
        System.nanoTime(),
        call,
      ),
    )

  override fun requestHeadersEnd(
    call: Call,
    request: Request,
  ) = onEvent(RequestHeadersEnd(System.nanoTime(), call, request.headers.byteCount()))

  override fun requestBodyStart(call: Call) =
    onEvent(
      RequestBodyStart(
        System.nanoTime(),
        call,
      ),
    )

  override fun requestBodyEnd(
    call: Call,
    byteCount: Long,
  ) = onEvent(RequestBodyEnd(System.nanoTime(), call, byteCount))

  override fun requestFailed(
    call: Call,
    ioe: IOException,
  ) = onEvent(RequestFailed(System.nanoTime(), call, ioe))

  override fun responseHeadersStart(call: Call) =
    onEvent(
      ResponseHeadersStart(
        System.nanoTime(),
        call,
      ),
    )

  override fun responseHeadersEnd(
    call: Call,
    response: Response,
  ) = onEvent(ResponseHeadersEnd(System.nanoTime(), call, response.headers.byteCount()))

  override fun responseBodyStart(call: Call) =
    onEvent(
      ResponseBodyStart(
        System.nanoTime(),
        call,
      ),
    )

  override fun responseBodyEnd(
    call: Call,
    byteCount: Long,
  ) = onEvent(ResponseBodyEnd(System.nanoTime(), call, byteCount))

  override fun responseFailed(
    call: Call,
    ioe: IOException,
  ) = onEvent(ResponseFailed(System.nanoTime(), call, ioe))

  override fun callEnd(call: Call) = onEvent(CallEnd(System.nanoTime(), call))

  override fun callFailed(
    call: Call,
    ioe: IOException,
  ) = onEvent(CallFailed(System.nanoTime(), call, ioe))

  override fun canceled(call: Call) = onEvent(Canceled(System.nanoTime(), call))

  override fun satisfactionFailure(
    call: Call,
    response: Response,
  ) = onEvent(SatisfactionFailure(System.nanoTime(), call))

  override fun cacheMiss(call: Call) = onEvent(CacheMiss(System.nanoTime(), call))

  override fun cacheHit(
    call: Call,
    response: Response,
  ) = onEvent(CacheHit(System.nanoTime(), call))

  override fun cacheConditionalHit(
    call: Call,
    cachedResponse: Response,
  ) = onEvent(CacheConditionalHit(System.nanoTime(), call))

  override fun retryDecision(
    call: Call,
    exception: IOException,
    retry: Boolean,
  ) = onEvent(RetryDecision(System.nanoTime(), call, exception, retry))

  override fun followUpDecision(
    call: Call,
    networkResponse: Response,
    nextRequest: Request?,
  ) = onEvent(FollowUpDecision(System.nanoTime(), call, networkResponse, nextRequest))
}
