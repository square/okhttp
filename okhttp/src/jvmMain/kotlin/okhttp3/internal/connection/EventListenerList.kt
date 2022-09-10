/*
 * Copyright (C) 2022 Square, Inc.
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

internal class EventListenerList(
  vararg initialCalls: EventListener
) : EventListener() {
  private val listeners = initialCalls.toMutableList()

  override fun callStart(call: Call) {
    listeners.forEach {
      it.callStart(call)
    }
  }

  override fun proxySelectStart(call: Call, url: HttpUrl) {
    listeners.forEach {
      it.proxySelectStart(call, url)
    }
  }

  override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
    listeners.forEach {
      it.proxySelectEnd(call, url, proxies)
    }
  }

  override fun dnsStart(call: Call, domainName: String) {
    listeners.forEach {
      it.dnsStart(call, domainName)
    }
  }

  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    listeners.forEach {
      it.dnsEnd(call, domainName, inetAddressList)
    }
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    listeners.forEach {
      it.connectStart(call, inetSocketAddress, proxy)
    }
  }

  override fun secureConnectStart(call: Call) {
    listeners.forEach {
      it.secureConnectStart(call)
    }
  }

  override fun secureConnectEnd(call: Call, handshake: Handshake?) {
    listeners.forEach {
      it.secureConnectEnd(call, handshake)
    }
  }

  override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
    listeners.forEach {
      it.connectEnd(call, inetSocketAddress, proxy, protocol)
    }
  }

  override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
    listeners.forEach {
      it.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }
  }

  override fun connectionAcquired(call: Call, connection: Connection) {
    listeners.forEach {
      it.connectionAcquired(call, connection)
    }
  }

  override fun connectionReleased(call: Call, connection: Connection) {
    listeners.forEach {
      it.connectionReleased(call, connection)
    }
  }

  override fun requestHeadersStart(call: Call) {
    listeners.forEach {
      it.requestHeadersStart(call)
    }
  }

  override fun requestHeadersEnd(call: Call, request: Request) {
    listeners.forEach {
      it.requestHeadersEnd(call, request)
    }
  }

  override fun requestBodyStart(call: Call) {
    listeners.forEach {
      it.requestBodyStart(call)
    }
  }

  override fun requestBodyEnd(call: Call, byteCount: Long) {
    listeners.forEach {
      it.requestBodyEnd(call, byteCount)
    }
  }

  override fun requestFailed(call: Call, ioe: IOException) {
    listeners.forEach {
      it.requestFailed(call, ioe)
    }
  }

  override fun responseHeadersStart(call: Call) {
    listeners.forEach {
      it.responseHeadersStart(call)
    }
  }

  override fun responseHeadersEnd(call: Call, response: Response) {
    listeners.forEach {
      it.responseHeadersEnd(call, response)
    }
  }

  override fun responseBodyStart(call: Call) {
    listeners.forEach {
      it.responseBodyStart(call)
    }
  }

  override fun responseBodyEnd(call: Call, byteCount: Long) {
    listeners.forEach {
      it.responseBodyEnd(call, byteCount)
    }
  }

  override fun responseFailed(call: Call, ioe: IOException) {
    listeners.forEach {
      it.responseFailed(call, ioe)
    }
  }

  override fun callEnd(call: Call) {
    listeners.forEach {
      it.callEnd(call)
    }
  }

  override fun callFailed(call: Call, ioe: IOException) {
    listeners.forEach {
      it.callFailed(call, ioe)
    }
  }

  override fun canceled(call: Call) {
    listeners.forEach {
      it.canceled(call)
    }
  }

  override fun satisfactionFailure(call: Call, response: Response) {
    listeners.forEach {
      it.satisfactionFailure(call, response)
    }
  }

  override fun cacheHit(call: Call, response: Response) {
    listeners.forEach {
      it.cacheHit(call, response)
    }
  }

  override fun cacheMiss(call: Call) {
    listeners.forEach {
      it.cacheMiss(call)
    }
  }

  override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
    listeners.forEach {
      it.cacheConditionalHit(call, cachedResponse)
    }
  }

  fun addListener(eventListener: EventListener) {
    listeners.add(eventListener)
  }
}
