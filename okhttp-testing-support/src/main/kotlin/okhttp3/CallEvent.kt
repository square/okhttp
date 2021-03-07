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

  /** Returns the open event that this close event closes, or null if this is not a close event. */
  open fun closes(timestampNs: Long): CallEvent? = null

  data class ProxySelectStart(
    override val timestampNs: Long,
    override val call: Call,
    val url: HttpUrl
  ) : CallEvent()

  data class ProxySelectEnd(
    override val timestampNs: Long,
    override val call: Call,
    val url: HttpUrl,
    val proxies: List<Proxy>?
  ) : CallEvent()

  data class DnsStart(
    override val timestampNs: Long,
    override val call: Call,
    val domainName: String
  ) : CallEvent()

  data class DnsEnd(
    override val timestampNs: Long,
    override val call: Call,
    val domainName: String,
    val inetAddressList: List<InetAddress>
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = DnsStart(timestampNs, call, domainName)
  }

  data class ConnectStart(
    override val timestampNs: Long,
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy?
  ) : CallEvent()

  data class ConnectEnd(
    override val timestampNs: Long,
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy?,
    val protocol: Protocol?
  ) : CallEvent() {
    override fun closes(timestampNs: Long) =
      ConnectStart(timestampNs, call, inetSocketAddress, proxy)
  }

  data class ConnectFailed(
    override val timestampNs: Long,
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy,
    val protocol: Protocol?,
    val ioe: IOException
  ) : CallEvent() {
    override fun closes(timestampNs: Long) =
      ConnectStart(timestampNs, call, inetSocketAddress, proxy)
  }

  data class SecureConnectStart(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class SecureConnectEnd(
    override val timestampNs: Long,
    override val call: Call,
    val handshake: Handshake?
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = SecureConnectStart(timestampNs, call)
  }

  data class ConnectionAcquired(
    override val timestampNs: Long,
    override val call: Call,
    val connection: Connection
  ) : CallEvent()

  data class ConnectionReleased(
    override val timestampNs: Long,
    override val call: Call,
    val connection: Connection
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = ConnectionAcquired(timestampNs, call, connection)
  }

  data class CallStart(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class CallEnd(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = CallStart(timestampNs, call)
  }

  data class CallFailed(
    override val timestampNs: Long,
    override val call: Call,
    val ioe: IOException
  ) : CallEvent()

  data class Canceled(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class RequestHeadersStart(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class RequestHeadersEnd(
    override val timestampNs: Long,
    override val call: Call,
    val headerLength: Long
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = RequestHeadersStart(timestampNs, call)
  }

  data class RequestBodyStart(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class RequestBodyEnd(
    override val timestampNs: Long,
    override val call: Call,
    val bytesWritten: Long
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = RequestBodyStart(timestampNs, call)
  }

  data class RequestFailed(
    override val timestampNs: Long,
    override val call: Call,
    val ioe: IOException
  ) : CallEvent()

  data class ResponseHeadersStart(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class ResponseHeadersEnd(
    override val timestampNs: Long,
    override val call: Call,
    val headerLength: Long
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = RequestHeadersStart(timestampNs, call)
  }

  data class ResponseBodyStart(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class ResponseBodyEnd(
    override val timestampNs: Long,
    override val call: Call,
    val bytesRead: Long
  ) : CallEvent() {
    override fun closes(timestampNs: Long) = ResponseBodyStart(timestampNs, call)
  }

  data class ResponseFailed(
    override val timestampNs: Long,
    override val call: Call,
    val ioe: IOException
  ) : CallEvent()

  data class SatisfactionFailure(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class CacheHit(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class CacheMiss(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()

  data class CacheConditionalHit(
    override val timestampNs: Long,
    override val call: Call
  ) : CallEvent()
}
