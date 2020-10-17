/*
 * Copyright (C) 2011 Google Inc.
 * Copyright (C) 2013 Square, Inc.
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

import okhttp3.HttpUrl
import okhttp3.Protocol
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * A scriptable web server. Callers supply canned responses and the server replays them upon request
 * in sequence.
 */
class MockWebServer : TestRule, Closeable {
  internal val mockWebServer: mockwebserver3.MockWebServer = mockwebserver3.MockWebServer()

  override fun apply(base: Statement, description: Description?): Statement {
    return statement(base)
  }

  private fun statement(base: Statement): Statement {
    return object : Statement() {
      @Throws(Throwable::class)
      override fun evaluate() {
        // Server may have been started manually or implicitly by accessing properties
        if (!mockWebServer.started) {
          start()
        }

        val errors = ArrayList<Throwable>()
        try {
          base.evaluate()
        } catch (t: Throwable) {
          errors.add(t)
        } finally {
          try {
            shutdown()
          } catch (e: IOException) {
            logger.log(Level.WARNING, "MockWebServer shutdown failed", e)
          } catch (t: Throwable) {
            errors.add(t)
          }
        }
        MultipleFailureException.assertEmpty(errors)
      }
    }
  }

  /**
   * The number of HTTP requests received thus far by this server. This may exceed the number of
   * HTTP connections when connection reuse is in practice.
   */
  val requestCount: Int by mockWebServer::requestCount

  /** The number of bytes of the POST body to keep in memory to the given limit. */
  var bodyLimit = Long.MAX_VALUE

  var serverSocketFactory: ServerSocketFactory? by mockWebServer::serverSocketFactory

  /**
   * The dispatcher used to respond to HTTP requests. The default dispatcher is a [QueueDispatcher],
   * which serves a fixed sequence of responses from a [queue][enqueue].
   *
   * Other dispatchers can be configured. They can vary the response based on timing or the content
   * of the request.
   */
  var dispatcher: Dispatcher by mockWebServer::dispatcher

  val port: Int by mockWebServer::port

  val hostName: String by mockWebServer::hostName

  /**
   * True if ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1 or
   * HTTP/2. This is true by default; set to false to disable negotiation and restrict connections
   * to HTTP/1.1.
   */
  var protocolNegotiationEnabled: Boolean by mockWebServer::protocolNegotiationEnabled

  /**
   * The protocols supported by ALPN on incoming HTTPS connections in order of preference. The list
   * must contain [Protocol.HTTP_1_1]. It must not contain null.
   *
   * This list is ignored when [negotiation is disabled][protocolNegotiationEnabled].
   */
  @get:JvmName("protocols") var protocols: List<Protocol> by mockWebServer::protocols

  @JvmName("-deprecated_port")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "port"),
    level = DeprecationLevel.ERROR)
  fun getPort(): Int = port

  fun toProxyAddress(): Proxy = this.mockWebServer.toProxyAddress()

  @JvmName("-deprecated_serverSocketFactory")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(
      expression = "run { this.serverSocketFactory = serverSocketFactory }"
    ),
    level = DeprecationLevel.ERROR)
  fun setServerSocketFactory(serverSocketFactory: ServerSocketFactory) = run {
    this.mockWebServer.serverSocketFactory = serverSocketFactory
  }

  /**
   * Returns a URL for connecting to this server.
   *
   * @param path the request path, such as "/".
   */
  fun url(path: String): HttpUrl = this.mockWebServer.url(path)

  @JvmName("-deprecated_bodyLimit")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(
      expression = "run { this.bodyLimit = bodyLimit }"
    ),
    level = DeprecationLevel.ERROR)
  fun setBodyLimit(bodyLimit: Long) = run { this.mockWebServer.bodyLimit = bodyLimit }

  @JvmName("-deprecated_protocolNegotiationEnabled")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(
      expression = "run { this.protocolNegotiationEnabled = protocolNegotiationEnabled }"
    ),
    level = DeprecationLevel.ERROR)
  fun setProtocolNegotiationEnabled(protocolNegotiationEnabled: Boolean) = run {
    this.mockWebServer.protocolNegotiationEnabled = protocolNegotiationEnabled
  }

  @JvmName("-deprecated_protocols")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(expression = "run { this.protocols = protocols }"),
    level = DeprecationLevel.ERROR)
  fun setProtocols(protocols: List<Protocol>) = run { this.mockWebServer.protocols = protocols }

  @JvmName("-deprecated_protocols")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(expression = "protocols"),
    level = DeprecationLevel.ERROR)
  fun protocols(): List<Protocol> = this.protocols

  /**
   * Serve requests with HTTPS rather than otherwise.
   *
   * @param tunnelProxy true to expect the HTTP CONNECT method before negotiating TLS.
   */
  fun useHttps(sslSocketFactory: SSLSocketFactory, tunnelProxy: Boolean) {
    this.mockWebServer.useHttps(sslSocketFactory, tunnelProxy)
  }

  /**
   * Configure the server to not perform SSL authentication of the client. This leaves
   * authentication to another layer such as in an HTTP cookie or header. This is the default and
   * most common configuration.
   */
  fun noClientAuth() {
    this.mockWebServer.noClientAuth()
  }

  /**
   * Configure the server to [want client auth][SSLSocket.setWantClientAuth]. If the
   * client presents a certificate that is [trusted][TrustManager] the handshake will
   * proceed normally. The connection will also proceed normally if the client presents no
   * certificate at all! But if the client presents an untrusted certificate the handshake
   * will fail and no connection will be established.
   */
  fun requestClientAuth() {
    this.mockWebServer.requestClientAuth()
  }

  /**
   * Configure the server to [need client auth][SSLSocket.setNeedClientAuth]. If the
   * client presents a certificate that is [trusted][TrustManager] the handshake will
   * proceed normally. If the client presents an untrusted certificate or no certificate at all the
   * handshake will fail and no connection will be established.
   */
  fun requireClientAuth() {
    this.mockWebServer.requireClientAuth()
  }

  /**
   * Awaits the next HTTP request, removes it, and returns it. Callers should use this to verify the
   * request was sent as intended. This method will block until the request is available, possibly
   * forever.
   *
   * @return the head of the request queue
   */
  @Throws(InterruptedException::class)
  fun takeRequest(): RecordedRequest = mockWebServer.takeRequest()

  /**
   * Awaits the next HTTP request (waiting up to the specified wait time if necessary), removes it,
   * and returns it. Callers should use this to verify the request was sent as intended within the
   * given time.
   *
   * @param timeout how long to wait before giving up, in units of [unit]
   * @param unit a [TimeUnit] determining how to interpret the [timeout] parameter
   * @return the head of the request queue
   */
  @Throws(InterruptedException::class)
  fun takeRequest(timeout: Long, unit: TimeUnit): RecordedRequest? =
    mockWebServer.takeRequest(timeout, unit)

  @JvmName("-deprecated_requestCount")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "requestCount"),
    level = DeprecationLevel.ERROR)
  fun getRequestCount(): Int = mockWebServer.requestCount

  /**
   * Scripts [response] to be returned to a request made in sequence. The first request is
   * served by the first enqueued response; the second request by the second enqueued response; and
   * so on.
   *
   * @throws ClassCastException if the default dispatcher has been
   * replaced with [setDispatcher][dispatcher].
   */
  fun enqueue(response: MockResponse) = mockWebServer.enqueue(response)

  /**
   * Starts the server on the loopback interface for the given port.
   *
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  @JvmOverloads fun start(port: Int = 0) = mockWebServer.start(port)

  /**
   * Starts the server on the given address and port.
   *
   * @param inetAddress the address to create the server socket on
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  fun start(inetAddress: InetAddress, port: Int) = mockWebServer.start(inetAddress, port)

  @Throws(IOException::class)
  fun shutdown() {
    mockWebServer.shutdown()
  }

  override fun toString(): String = mockWebServer.toString()

  @Throws(IOException::class)
  override fun close() = mockWebServer.close()

  companion object {
    private val logger = Logger.getLogger(MockWebServer::class.java.name)
  }
}
