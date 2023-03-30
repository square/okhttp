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
  var existingListener: EventListener
) : EventListener() {
  private var additionalListeners: MutableList<EventListener>? = null

  inline fun forEachListener(fn: EventListener.() -> Unit) {
    existingListener.fn()
    synchronized(this) {
      additionalListeners?.forEach(fn)
    }
  }

  override fun callStart(call: Call) {
    forEachListener {
      callStart(call)
    }
  }

  override fun proxySelectStart(call: Call, url: HttpUrl) {
    forEachListener {
      proxySelectStart(call, url)
    }
  }

  override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
    forEachListener {
      proxySelectEnd(call, url, proxies)
    }
  }

  override fun dnsStart(call: Call, domainName: String) {
    forEachListener {
      dnsStart(call, domainName)
    }
  }

  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    forEachListener {
      dnsEnd(call, domainName, inetAddressList)
    }
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    forEachListener {
      connectStart(call, inetSocketAddress, proxy)
    }
  }

  override fun secureConnectStart(call: Call) {
    forEachListener {
      secureConnectStart(call)
    }
  }

  override fun secureConnectEnd(call: Call, handshake: Handshake?) {
    forEachListener {
      secureConnectEnd(call, handshake)
    }
  }

  override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
    forEachListener {
      connectEnd(call, inetSocketAddress, proxy, protocol)
    }
  }

  override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
    forEachListener {
      connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }
  }

  override fun connectionAcquired(call: Call, connection: Connection) {
    forEachListener {
      connectionAcquired(call, connection)
    }
  }

  override fun connectionReleased(call: Call, connection: Connection) {
    forEachListener {
      connectionReleased(call, connection)
    }
  }

  override fun requestHeadersStart(call: Call) {
    forEachListener {
      requestHeadersStart(call)
    }
  }

  override fun requestHeadersEnd(call: Call, request: Request) {
    forEachListener {
      requestHeadersEnd(call, request)
    }
  }

  override fun requestBodyStart(call: Call) {
    forEachListener {
      requestBodyStart(call)
    }
  }

  override fun requestBodyEnd(call: Call, byteCount: Long) {
    forEachListener {
      requestBodyEnd(call, byteCount)
    }
  }

  override fun requestFailed(call: Call, ioe: IOException) {
    forEachListener {
      requestFailed(call, ioe)
    }
  }

  override fun responseHeadersStart(call: Call) {
    forEachListener {
      responseHeadersStart(call)
    }
  }

  override fun responseHeadersEnd(call: Call, response: Response) {
    forEachListener {
      responseHeadersEnd(call, response)
    }
  }

  override fun responseBodyStart(call: Call) {
    forEachListener {
      responseBodyStart(call)
    }
  }

  override fun responseBodyEnd(call: Call, byteCount: Long) {
    forEachListener {
      responseBodyEnd(call, byteCount)
    }
  }

  override fun responseFailed(call: Call, ioe: IOException) {
    forEachListener {
      responseFailed(call, ioe)
    }
  }

  override fun callEnd(call: Call) {
    forEachListener {
      callEnd(call)
    }
  }

  override fun callFailed(call: Call, ioe: IOException) {
    forEachListener {
      callFailed(call, ioe)
    }
  }

  override fun canceled(call: Call) {
    forEachListener {
      canceled(call)
    }
  }

  override fun satisfactionFailure(call: Call, response: Response) {
    forEachListener {
      satisfactionFailure(call, response)
    }
  }

  override fun cacheHit(call: Call, response: Response) {
    forEachListener {
      cacheHit(call, response)
    }
  }

  override fun cacheMiss(call: Call) {
    forEachListener {
      cacheMiss(call)
    }
  }

  override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
    forEachListener {
      cacheConditionalHit(call, cachedResponse)
    }
  }

  fun addListener(eventListener: EventListener) {
    synchronized(this) {
      if (additionalListeners == null) {
        additionalListeners = mutableListOf(eventListener)
      } else {
        additionalListeners?.add(eventListener)
      }
    }
  }
}
