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

import okhttp3.CallEvent.CallEnd
import okhttp3.CallEvent.CallFailed
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.ConnectEnd
import okhttp3.CallEvent.ConnectFailed
import okhttp3.CallEvent.ConnectStart
import okhttp3.CallEvent.ConnectionAcquired
import okhttp3.CallEvent.ConnectionReleased
import okhttp3.CallEvent.DnsEnd
import okhttp3.CallEvent.DnsStart
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
import okhttp3.CallEvent.SecureConnectEnd
import okhttp3.CallEvent.SecureConnectStart
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertTrue
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Deque
import java.util.concurrent.ConcurrentLinkedDeque

open class RecordingEventListener : EventListener() {
  val eventSequence: Deque<CallEvent> = ConcurrentLinkedDeque()

  private val forbiddenLocks = mutableListOf<Any>()

  /** Confirm that the thread does not hold a lock on `lock` during the callback. */
  fun forbidLock(lock: Any) {
    forbiddenLocks.add(lock)
  }

  /**
   * Removes recorded events up to (and including) an event is found whose class equals [eventClass]
   * and returns it.
   */
  fun <T> removeUpToEvent(eventClass: Class<T>): T {
    val fullEventSequence = eventSequence.toMutableList()
    var event = eventSequence.poll()
    while (event != null && !eventClass.isInstance(event)) {
      event = eventSequence.poll()
    }
    if (event == null) {
      throw AssertionError("${eventClass.simpleName} not found. Found $fullEventSequence.")
    }
    return eventClass.cast(event)
  }

  fun recordedEventTypes() = eventSequence.map { it.name }

  fun clearAllEvents() {
    eventSequence.clear()
  }

  private fun logEvent(e: CallEvent) {
    for (lock in forbiddenLocks) {
      assertThat(Thread.holdsLock(lock))
          .overridingErrorMessage(lock.toString())
          .isFalse()
    }

    val startEvent = e.closes()
    if (startEvent != null) {
      assertTrue(eventSequence.contains(startEvent))
    }

    eventSequence.offer(e)
  }

  override fun proxySelectStart(
    call: Call,
    url: HttpUrl
  ) = logEvent(ProxySelectStart(call, url))

  override fun proxySelectEnd(
    call: Call,
    url: HttpUrl,
    proxies: List<Proxy>
  ) = logEvent(ProxySelectEnd(call, url, proxies))

  override fun dnsStart(
    call: Call,
    domainName: String
  ) = logEvent(DnsStart(call, domainName))

  override fun dnsEnd(
    call: Call,
    domainName: String,
    inetAddressList: List<InetAddress>
  ) = logEvent(DnsEnd(call, domainName, inetAddressList))

  override fun connectStart(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy
  ) = logEvent(ConnectStart(call, inetSocketAddress, proxy))

  override fun secureConnectStart(
    call: Call
  ) = logEvent(SecureConnectStart(call))

  override fun secureConnectEnd(
    call: Call,
    handshake: Handshake?
  ) = logEvent(SecureConnectEnd(call, handshake))

  override fun connectEnd(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?
  ) = logEvent(ConnectEnd(call, inetSocketAddress, proxy, protocol))

  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException
  ) = logEvent(ConnectFailed(call, inetSocketAddress, proxy, protocol, ioe))

  override fun connectionAcquired(
    call: Call,
    connection: Connection
  ) = logEvent(ConnectionAcquired(call, connection))

  override fun connectionReleased(
    call: Call,
    connection: Connection
  ) = logEvent(ConnectionReleased(call, connection))

  override fun callStart(
    call: Call
  ) = logEvent(CallStart(call))

  override fun requestHeadersStart(
    call: Call
  ) = logEvent(RequestHeadersStart(call))

  override fun requestHeadersEnd(
    call: Call,
    request: Request
  ) = logEvent(RequestHeadersEnd(call, request.headers.byteCount()))

  override fun requestBodyStart(
    call: Call
  ) = logEvent(RequestBodyStart(call))

  override fun requestBodyEnd(
    call: Call,
    byteCount: Long
  ) = logEvent(RequestBodyEnd(call, byteCount))

  override fun requestFailed(
    call: Call,
    ioe: IOException
  ) = logEvent(RequestFailed(call, ioe))

  override fun responseHeadersStart(
    call: Call
  ) = logEvent(ResponseHeadersStart(call))

  override fun responseHeadersEnd(
    call: Call,
    response: Response
  ) = logEvent(ResponseHeadersEnd(call, response.headers.byteCount()))

  override fun responseBodyStart(
    call: Call
  ) = logEvent(ResponseBodyStart(call))

  override fun responseBodyEnd(
    call: Call,
    byteCount: Long
  ) = logEvent(ResponseBodyEnd(call, byteCount))

  override fun responseFailed(
    call: Call,
    ioe: IOException
  ) = logEvent(ResponseFailed(call, ioe))

  override fun callEnd(
    call: Call
  ) = logEvent(CallEnd(call))

  override fun callFailed(
    call: Call,
    ioe: IOException
  ) = logEvent(CallFailed(call, ioe))
}
