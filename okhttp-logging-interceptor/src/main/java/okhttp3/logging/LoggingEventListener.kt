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
package okhttp3.logging

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * An OkHttp EventListener, which logs call events. Can be applied as an
 * [event listener factory][OkHttpClient.eventListenerFactory].
 *
 * The format of the logs created by this class should not be considered stable and may change
 * slightly between releases. If you need a stable logging format, use your own event listener.
 */
class LoggingEventListener private constructor(
  private val logger: HttpLoggingInterceptor.Logger
) : EventListener() {
  private var startNs: Long = 0

  override fun callStart(call: Call) {
    startNs = System.nanoTime()

    logWithTime("callStart: ${call.request()}")
  }

  override fun proxySelectStart(call: Call, url: HttpUrl) {
    logWithTime("proxySelectStart: $url")
  }

  override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
    logWithTime("proxySelectEnd: $proxies")
  }

  override fun dnsStart(call: Call, domainName: String) {
    logWithTime("dnsStart: $domainName")
  }

  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    logWithTime("dnsEnd: $inetAddressList")
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    logWithTime("connectStart: $inetSocketAddress $proxy")
  }

  override fun secureConnectStart(call: Call) {
    logWithTime("secureConnectStart")
  }

  override fun secureConnectEnd(call: Call, handshake: Handshake?) {
    logWithTime("secureConnectEnd: $handshake")
  }

  override fun connectEnd(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?
  ) {
    logWithTime("connectEnd: $protocol")
  }

  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException
  ) {
    logWithTime("connectFailed: $protocol $ioe")
  }

  override fun connectionAcquired(call: Call, connection: Connection) {
    logWithTime("connectionAcquired: $connection")
  }

  override fun connectionReleased(call: Call, connection: Connection) {
    logWithTime("connectionReleased")
  }

  override fun requestHeadersStart(call: Call) {
    logWithTime("requestHeadersStart")
  }

  override fun requestHeadersEnd(call: Call, request: Request) {
    logWithTime("requestHeadersEnd")
  }

  override fun requestBodyStart(call: Call) {
    logWithTime("requestBodyStart")
  }

  override fun requestBodyEnd(call: Call, byteCount: Long) {
    logWithTime("requestBodyEnd: byteCount=$byteCount")
  }

  override fun requestFailed(call: Call, ioe: IOException) {
    logWithTime("requestFailed: $ioe")
  }

  override fun responseHeadersStart(call: Call) {
    logWithTime("responseHeadersStart")
  }

  override fun responseHeadersEnd(call: Call, response: Response) {
    logWithTime("responseHeadersEnd: $response")
  }

  override fun responseBodyStart(call: Call) {
    logWithTime("responseBodyStart")
  }

  override fun responseBodyEnd(call: Call, byteCount: Long) {
    logWithTime("responseBodyEnd: byteCount=$byteCount")
  }

  override fun responseFailed(call: Call, ioe: IOException) {
    logWithTime("responseFailed: $ioe")
  }

  override fun callEnd(call: Call) {
    logWithTime("callEnd")
  }

  override fun callFailed(call: Call, ioe: IOException) {
    logWithTime("callFailed: $ioe")
  }

  private fun logWithTime(message: String) {
    val timeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    logger.log("[$timeMs ms] $message")
  }

  open class Factory @JvmOverloads constructor(
    private val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
  ) : EventListener.Factory {
    override fun create(call: Call): EventListener = LoggingEventListener(logger)
  }
}
