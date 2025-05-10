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
import okhttp3.internal.SuppressSignatureCheck

/** Data classes that correspond to each of the methods of [EventListener]. */
@SuppressSignatureCheck
sealed class CallEvent {
  abstract val timestampNs: Long
  abstract val call: Call

  val name: String
    get() = javaClass.simpleName

  /** Returns if the event closes this event, or null if this is no open event. */
  open fun closes(event: CallEvent): Boolean? = null

  data class ProxySelectStart(
    override val timestampNs: Long,
    override val call: Call,
    val url: HttpUrl,
  ) : CallEvent()

  data class ProxySelectEnd(
    override val timestampNs: Long,
    override val call: Call,
    val url: HttpUrl,
    val proxies: List<Proxy>?,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is ProxySelectStart && call == event.call && url == event.url
  }

  data class DnsStart(
    override val timestampNs: Long,
    override val call: Call,
    val domainName: String,
  ) : CallEvent()

  data class DnsEnd(
    override val timestampNs: Long,
    override val call: Call,
    val domainName: String,
    val inetAddressList: List<InetAddress>,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is DnsStart && call == event.call && domainName == event.domainName
  }

  data class ConnectStart(
    override val timestampNs: Long,
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy?,
  ) : CallEvent()

  data class ConnectEnd(
    override val timestampNs: Long,
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy?,
    val protocol: Protocol?,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean =
      event is ConnectStart && call == event.call && inetSocketAddress == event.inetSocketAddress && proxy == event.proxy
  }

  data class ConnectFailed(
    override val timestampNs: Long,
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy,
    val protocol: Protocol?,
    val ioe: IOException,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean =
      event is ConnectStart && call == event.call && inetSocketAddress == event.inetSocketAddress && proxy == event.proxy
  }

  data class SecureConnectStart(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class SecureConnectEnd(
    override val timestampNs: Long,
    override val call: Call,
    val handshake: Handshake?,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is SecureConnectStart && call == event.call
  }

  data class ConnectionAcquired(
    override val timestampNs: Long,
    override val call: Call,
    val connection: Connection,
  ) : CallEvent()

  data class ConnectionReleased(
    override val timestampNs: Long,
    override val call: Call,
    val connection: Connection,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is ConnectionAcquired && call == event.call && connection == event.connection
  }

  data class CallStart(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class CallEnd(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is CallStart && call == event.call
  }

  data class CallFailed(
    override val timestampNs: Long,
    override val call: Call,
    val ioe: IOException,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is CallStart && call == event.call
  }

  data class Canceled(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class RequestHeadersStart(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class RequestHeadersEnd(
    override val timestampNs: Long,
    override val call: Call,
    val headerLength: Long,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is RequestHeadersStart && call == event.call
  }

  data class RequestBodyStart(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class RequestBodyEnd(
    override val timestampNs: Long,
    override val call: Call,
    val bytesWritten: Long,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is RequestBodyStart && call == event.call
  }

  data class RequestFailed(
    override val timestampNs: Long,
    override val call: Call,
    val ioe: IOException,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is RequestHeadersStart && call == event.call
  }

  data class ResponseHeadersStart(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class ResponseHeadersEnd(
    override val timestampNs: Long,
    override val call: Call,
    val headerLength: Long,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is ResponseHeadersStart && call == event.call
  }

  data class ResponseBodyStart(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class ResponseBodyEnd(
    override val timestampNs: Long,
    override val call: Call,
    val bytesRead: Long,
  ) : CallEvent() {
    override fun closes(event: CallEvent): Boolean = event is ResponseBodyStart && call == event.call
  }

  data class ResponseFailed(
    override val timestampNs: Long,
    override val call: Call,
    val ioe: IOException,
  ) : CallEvent()

  data class SatisfactionFailure(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class CacheHit(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class CacheMiss(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class CacheConditionalHit(
    override val timestampNs: Long,
    override val call: Call,
  ) : CallEvent()

  data class RetryDecision(
    override val timestampNs: Long,
    override val call: Call,
    val shouldRetry: Boolean,
    val reason: String,
  ) : CallEvent()
}
