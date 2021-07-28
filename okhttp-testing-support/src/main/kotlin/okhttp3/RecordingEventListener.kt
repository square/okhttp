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
import java.util.Deque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
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
import okhttp3.CallEvent.SatisfactionFailure
import okhttp3.CallEvent.SecureConnectEnd
import okhttp3.CallEvent.SecureConnectStart
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Assertions.assertTrue

open class RecordingEventListener : EventListener() {
  val eventSequence: Deque<CallEvent> = ConcurrentLinkedDeque()

  private val forbiddenLocks = mutableListOf<Any>()

  /** The timestamp of the last taken event, used to measure elapsed time between events. */
  private var lastTimestampNs: Long? = null

  /** Confirm that the thread does not hold a lock on `lock` during the callback. */
  fun forbidLock(lock: Any) {
    forbiddenLocks.add(lock)
  }

  /**
   * Removes recorded events up to (and including) an event is found whose class equals [eventClass]
   * and returns it.
   */
  fun <T> removeUpToEvent(eventClass: Class<T>): T {
    val fullEventSequence = eventSequence.toList()
    try {
      while (true) {
        val event = takeEvent()
        if (eventClass.isInstance(event)) {
          return eventClass.cast(event)
        }
      }
    } catch (e: NoSuchElementException) {
      throw AssertionError("full event sequence: $fullEventSequence", e)
    }
  }

  /**
   * Remove and return the next event from the recorded sequence.
   *
   * @param eventClass a class to assert that the returned event is an instance of, or null to
   *     take any event class.
   * @param elapsedMs the time in milliseconds elapsed since the immediately-preceding event, or
   *     -1L to take any duration.
   */
  fun takeEvent(
    eventClass: Class<*>? = null,
    elapsedMs: Long = -1L
  ): CallEvent {
    val result = eventSequence.remove()
    val actualElapsedNs = result.timestampNs - (lastTimestampNs ?: result.timestampNs)
    lastTimestampNs = result.timestampNs

    if (eventClass != null) {
      assertThat(result).isInstanceOf(eventClass)
    }

    if (elapsedMs != -1L) {
      assertThat(
          TimeUnit.NANOSECONDS.toMillis(actualElapsedNs)
              .toDouble()
      )
          .isCloseTo(elapsedMs.toDouble(), Offset.offset(100.0))
    }

    return result
  }

  fun recordedEventTypes() = eventSequence.map { it.name }

  fun clearAllEvents() {
    while (eventSequence.isNotEmpty()) {
      takeEvent()
    }
  }

  private fun logEvent(e: CallEvent) {
    for (lock in forbiddenLocks) {
      assertThat(Thread.holdsLock(lock))
          .overridingErrorMessage(lock.toString())
          .isFalse()
    }

    val startEvent = e.closes(-1L)
    if (startEvent != null) {
      assertTrue(eventSequence.any { it == e.closes(it.timestampNs) })
    }

    eventSequence.offer(e)
  }

  override fun proxySelectStart(
    call: Call,
    url: HttpUrl
  ) = logEvent(ProxySelectStart(System.nanoTime(), call, url))

  override fun proxySelectEnd(
    call: Call,
    url: HttpUrl,
    proxies: List<Proxy>
  ) = logEvent(ProxySelectEnd(System.nanoTime(), call, url, proxies))

  override fun dnsStart(
    call: Call,
    domainName: String
  ) = logEvent(DnsStart(System.nanoTime(), call, domainName))

  override fun dnsEnd(
    call: Call,
    domainName: String,
    inetAddressList: List<InetAddress>
  ) = logEvent(DnsEnd(System.nanoTime(), call, domainName, inetAddressList))

  override fun connectStart(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy
  ) = logEvent(ConnectStart(System.nanoTime(), call, inetSocketAddress, proxy))

  override fun secureConnectStart(
    call: Call
  ) = logEvent(SecureConnectStart(System.nanoTime(), call))

  override fun secureConnectEnd(
    call: Call,
    handshake: Handshake?
  ) = logEvent(SecureConnectEnd(System.nanoTime(), call, handshake))

  override fun connectEnd(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?
  ) = logEvent(ConnectEnd(System.nanoTime(), call, inetSocketAddress, proxy, protocol))

  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException
  ) = logEvent(ConnectFailed(System.nanoTime(), call, inetSocketAddress, proxy, protocol, ioe))

  override fun connectionAcquired(
    call: Call,
    connection: Connection
  ) = logEvent(ConnectionAcquired(System.nanoTime(), call, connection))

  override fun connectionReleased(
    call: Call,
    connection: Connection
  ) = logEvent(ConnectionReleased(System.nanoTime(), call, connection))

  override fun callStart(
    call: Call
  ) = logEvent(CallStart(System.nanoTime(), call))

  override fun requestHeadersStart(
    call: Call
  ) = logEvent(RequestHeadersStart(System.nanoTime(), call))

  override fun requestHeadersEnd(
    call: Call,
    request: Request
  ) = logEvent(RequestHeadersEnd(System.nanoTime(), call, request.headers.byteCount()))

  override fun requestBodyStart(
    call: Call
  ) = logEvent(RequestBodyStart(System.nanoTime(), call))

  override fun requestBodyEnd(
    call: Call,
    byteCount: Long
  ) = logEvent(RequestBodyEnd(System.nanoTime(), call, byteCount))

  override fun requestFailed(
    call: Call,
    ioe: IOException
  ) = logEvent(RequestFailed(System.nanoTime(), call, ioe))

  override fun responseHeadersStart(
    call: Call
  ) = logEvent(ResponseHeadersStart(System.nanoTime(), call))

  override fun responseHeadersEnd(
    call: Call,
    response: Response
  ) = logEvent(ResponseHeadersEnd(System.nanoTime(), call, response.headers.byteCount()))

  override fun responseBodyStart(
    call: Call
  ) = logEvent(ResponseBodyStart(System.nanoTime(), call))

  override fun responseBodyEnd(
    call: Call,
    byteCount: Long
  ) = logEvent(ResponseBodyEnd(System.nanoTime(), call, byteCount))

  override fun responseFailed(
    call: Call,
    ioe: IOException
  ) = logEvent(ResponseFailed(System.nanoTime(), call, ioe))

  override fun callEnd(
    call: Call
  ) = logEvent(CallEnd(System.nanoTime(), call))

  override fun callFailed(
    call: Call,
    ioe: IOException
  ) = logEvent(CallFailed(System.nanoTime(), call, ioe))

  override fun canceled(
    call: Call
  ) = logEvent(Canceled(System.nanoTime(), call))

  override fun satisfactionFailure(
    call: Call,
    response: Response
  ) = logEvent(SatisfactionFailure(System.nanoTime(), call))

  override fun cacheMiss(
    call: Call
  ) = logEvent(CacheMiss(System.nanoTime(), call))

  override fun cacheHit(
    call: Call,
    response: Response
  ) = logEvent(CacheHit(System.nanoTime(), call))

  override fun cacheConditionalHit(call: Call, cachedResponse: Response) =
    logEvent(CacheConditionalHit(System.nanoTime(), call))
}
