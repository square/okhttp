/*
 * Copyright (C) 2018 Square, Inc.
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
import java.util.concurrent.TimeUnit

class ClientRuleEventListener(val delegate: EventListener = NONE, var logger: (String) -> Unit) : EventListener(),
    EventListener.Factory {
  private var startNs: Long = 0

  override fun create(call: Call): EventListener = this

  override fun callStart(call: Call) {
    startNs = System.nanoTime()

    logWithTime("callStart: ${call.request()}")

    delegate.callStart(call)
  }

  override fun proxySelectStart(call: Call, url: HttpUrl) {
    logWithTime("proxySelectStart: $url")

    delegate.proxySelectStart(call, url)
  }

  override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
    logWithTime("proxySelectEnd: $proxies")

    delegate.proxySelectEnd(call, url, proxies)
  }

  override fun dnsStart(call: Call, domainName: String) {
    logWithTime("dnsStart: $domainName")

    delegate.dnsStart(call, domainName)
  }

  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    logWithTime("dnsEnd: $inetAddressList")

    delegate.dnsEnd(call, domainName, inetAddressList)
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    logWithTime("connectStart: $inetSocketAddress $proxy")

    delegate.connectStart(call, inetSocketAddress, proxy)
  }

  override fun secureConnectStart(call: Call) {
    logWithTime("secureConnectStart")

    delegate.secureConnectStart(call)
  }

  override fun secureConnectEnd(call: Call, handshake: Handshake?) {
    logWithTime("secureConnectEnd: $handshake")

    delegate.secureConnectEnd(call, handshake)
  }

  override fun connectEnd(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?
  ) {
    logWithTime("connectEnd: $protocol")

    delegate.connectEnd(call, inetSocketAddress, proxy, protocol)
  }

  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException
  ) {
    logWithTime("connectFailed: $protocol $ioe")

    delegate.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
  }

  override fun connectionAcquired(call: Call, connection: Connection) {
    logWithTime("connectionAcquired: $connection")

    delegate.connectionAcquired(call, connection)
  }

  override fun connectionReleased(call: Call, connection: Connection) {
    logWithTime("connectionReleased")

    delegate.connectionReleased(call, connection)
  }

  override fun requestHeadersStart(call: Call) {
    logWithTime("requestHeadersStart")

    delegate.requestHeadersStart(call)
  }

  override fun requestHeadersEnd(call: Call, request: Request) {
    logWithTime("requestHeadersEnd")

    delegate.requestHeadersEnd(call, request)
  }

  override fun requestBodyStart(call: Call) {
    logWithTime("requestBodyStart")

    delegate.requestBodyStart(call)
  }

  override fun requestBodyEnd(call: Call, byteCount: Long) {
    logWithTime("requestBodyEnd: byteCount=$byteCount")

    delegate.requestBodyEnd(call, byteCount)
  }

  override fun requestFailed(call: Call, ioe: IOException) {
    logWithTime("requestFailed: $ioe")

    delegate.requestFailed(call, ioe)
  }

  override fun responseHeadersStart(call: Call) {
    logWithTime("responseHeadersStart")

    delegate.responseHeadersStart(call)
  }

  override fun responseHeadersEnd(call: Call, response: Response) {
    logWithTime("responseHeadersEnd: $response")

    delegate.responseHeadersEnd(call, response)
  }

  override fun responseBodyStart(call: Call) {
    logWithTime("responseBodyStart")

    delegate.responseBodyStart(call)
  }

  override fun responseBodyEnd(call: Call, byteCount: Long) {
    logWithTime("responseBodyEnd: byteCount=$byteCount")

    delegate.responseBodyEnd(call, byteCount)
  }

  override fun responseFailed(call: Call, ioe: IOException) {
    logWithTime("responseFailed: $ioe")

    delegate.responseFailed(call, ioe)
  }

  override fun callEnd(call: Call) {
    logWithTime("callEnd")

    delegate.callEnd(call)
  }

  override fun callFailed(call: Call, ioe: IOException) {
    logWithTime("callFailed: $ioe")

    delegate.callFailed(call, ioe)
  }

  private fun logWithTime(message: String) {
    val timeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    logger.invoke("[$timeMs ms] $message")
  }
}
