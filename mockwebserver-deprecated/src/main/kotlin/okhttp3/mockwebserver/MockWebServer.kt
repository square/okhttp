/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.mockwebserver

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLSocketFactory
import okhttp3.HttpUrl
import okhttp3.Protocol
import org.junit.rules.ExternalResource

class MockWebServer :
  ExternalResource(),
  Closeable {
  val delegate = mockwebserver3.MockWebServer()

  val requestCount: Int by delegate::requestCount

  var bodyLimit: Long by delegate::bodyLimit

  var serverSocketFactory: ServerSocketFactory? by delegate::serverSocketFactory

  var dispatcher: Dispatcher = QueueDispatcher()
    set(value) {
      field = value
      delegate.dispatcher = value.wrap()
    }

  val port: Int
    get() {
      before() // This implicitly starts the delegate.
      return delegate.port
    }

  val hostName: String
    get() {
      before() // This implicitly starts the delegate.
      return delegate.hostName
    }

  var protocolNegotiationEnabled: Boolean by delegate::protocolNegotiationEnabled

  @get:JvmName("protocols")
  var protocols: List<Protocol> by delegate::protocols

  init {
    delegate.dispatcher = dispatcher.wrap()
  }

  private var started: Boolean = false

  @Synchronized override fun before() {
    if (started) return
    try {
      start()
    } catch (e: IOException) {
      throw RuntimeException(e)
    }
  }

  @JvmName("-deprecated_port")
  fun getPort(): Int = port

  fun toProxyAddress(): Proxy {
    before() // This implicitly starts the delegate.
    return delegate.proxyAddress
  }

  @JvmName("-deprecated_serverSocketFactory")
  fun setServerSocketFactory(serverSocketFactory: ServerSocketFactory) {
    delegate.serverSocketFactory = serverSocketFactory
  }

  fun url(path: String): HttpUrl {
    before() // This implicitly starts the delegate.
    return delegate.url(path)
  }

  @JvmName("-deprecated_bodyLimit")
  fun setBodyLimit(bodyLimit: Long) {
    delegate.bodyLimit = bodyLimit
  }

  @JvmName("-deprecated_protocolNegotiationEnabled")
  fun setProtocolNegotiationEnabled(protocolNegotiationEnabled: Boolean) {
    delegate.protocolNegotiationEnabled = protocolNegotiationEnabled
  }

  @JvmName("-deprecated_protocols")
  fun setProtocols(protocols: List<Protocol>) {
    delegate.protocols = protocols
  }

  @JvmName("-deprecated_protocols")
  fun protocols(): List<Protocol> = delegate.protocols

  fun useHttps(
    sslSocketFactory: SSLSocketFactory,
    tunnelProxy: Boolean,
  ) {
    delegate.useHttps(sslSocketFactory)
  }

  fun noClientAuth() {
    delegate.noClientAuth()
  }

  fun requestClientAuth() {
    delegate.requestClientAuth()
  }

  fun requireClientAuth() {
    delegate.requireClientAuth()
  }

  @Throws(InterruptedException::class)
  fun takeRequest(): RecordedRequest = delegate.takeRequest().unwrap()

  @Throws(InterruptedException::class)
  fun takeRequest(
    timeout: Long,
    unit: TimeUnit,
  ): RecordedRequest? = delegate.takeRequest(timeout, unit)?.unwrap()

  @JvmName("-deprecated_requestCount")
  fun getRequestCount(): Int = delegate.requestCount

  fun enqueue(response: MockResponse) {
    delegate.enqueue(response.wrap())
  }

  @Throws(IOException::class)
  @JvmOverloads
  fun start(port: Int = 0) {
    started = true
    delegate.start(port)
  }

  @Throws(IOException::class)
  fun start(
    inetAddress: InetAddress,
    port: Int,
  ) {
    started = true
    delegate.start(inetAddress, port)
  }

  @Synchronized
  @Throws(IOException::class)
  fun shutdown() {
    delegate.close()
  }

  @Synchronized override fun after() {
    try {
      shutdown()
    } catch (e: IOException) {
      logger.log(Level.WARNING, "MockWebServer shutdown failed", e)
    }
  }

  override fun toString(): String = delegate.toString()

  @Throws(IOException::class)
  override fun close() = delegate.close()

  companion object {
    private val logger = Logger.getLogger(MockWebServer::class.java.name)
  }
}
