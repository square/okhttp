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

/** Data classes that correspond to each of the methods of [EventListener]. */
sealed class CallEvent {
  abstract val call: Call

  val name: String
    get() = javaClass.simpleName

  open fun closes(): CallEvent? = null

  data class ProxySelectStart(
    override val call: Call,
    val url: HttpUrl
  ) : CallEvent()

  data class ProxySelectEnd(
    override val call: Call,
    val url: HttpUrl,
    val proxies: List<Proxy>?
  ) : CallEvent()

  data class DnsStart(
    override val call: Call,
    val domainName: String
  ) : CallEvent()

  data class DnsEnd(
    override val call: Call,
    val domainName: String,
    val inetAddressList: List<InetAddress>
  ) : CallEvent() {
    override fun closes() = DnsStart(call, domainName)
  }

  data class ConnectStart(
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy?
  ) : CallEvent()

  data class ConnectEnd(
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy?,
    val protocol: Protocol?
  ) : CallEvent() {
    override fun closes() = ConnectStart(call, inetSocketAddress, proxy)
  }

  data class ConnectFailed(
    override val call: Call,
    val inetSocketAddress: InetSocketAddress,
    val proxy: Proxy,
    val protocol: Protocol?,
    val ioe: IOException
  ) : CallEvent() {
    override fun closes() = ConnectStart(call, inetSocketAddress, proxy)
  }

  data class SecureConnectStart(
    override val call: Call
  ) : CallEvent()

  data class SecureConnectEnd(
    override val call: Call,
    val handshake: Handshake?
  ) : CallEvent() {
    override fun closes() = SecureConnectStart(call)
  }

  data class ConnectionAcquired(
    override val call: Call,
    val connection: Connection
  ) : CallEvent()

  data class ConnectionReleased(
    override val call: Call,
    val connection: Connection
  ) : CallEvent() {
    override fun closes() = ConnectionAcquired(call, connection)
  }

  data class CallStart(
    override val call: Call
  ) : CallEvent()

  data class CallEnd(
    override val call: Call
  ) : CallEvent() {
    override fun closes() = CallStart(call)
  }

  data class CallFailed(
    override val call: Call,
    val ioe: IOException
  ) : CallEvent()

  data class RequestHeadersStart(
    override val call: Call
  ) : CallEvent()

  data class RequestHeadersEnd(
    override val call: Call,
    val headerLength: Long
  ) : CallEvent() {
    override fun closes() = RequestHeadersStart(call)
  }

  data class RequestBodyStart(
    override val call: Call
  ) : CallEvent()

  data class RequestBodyEnd(
    override val call: Call,
    val bytesWritten: Long
  ) : CallEvent() {
    override fun closes() = RequestBodyStart(call)
  }

  data class RequestFailed(
    override val call: Call,
    val ioe: IOException
  ) : CallEvent()

  data class ResponseHeadersStart(
    override val call: Call
  ) : CallEvent()

  data class ResponseHeadersEnd(
    override val call: Call,
    val headerLength: Long
  ) : CallEvent() {
    override fun closes() = RequestHeadersStart(call)
  }

  data class ResponseBodyStart(
    override val call: Call
  ) : CallEvent()

  data class ResponseBodyEnd(
    override val call: Call,
    val bytesRead: Long
  ) : CallEvent() {
    override fun closes() = ResponseBodyStart(call)
  }

  data class ResponseFailed(
    override val call: Call,
    val ioe: IOException
  ) : CallEvent()
}
