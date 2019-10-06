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

import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.duplex.MwsDuplexAccess
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.immutableListOf
import okhttp3.internal.platform.Platform
import okhttp3.internal.threadFactory
import okhttp3.internal.toImmutableList
import okhttp3.internal.ws.RealWebSocket
import okhttp3.internal.ws.WebSocketProtocol
import okhttp3.mockwebserver.SocketPolicy.CONTINUE_ALWAYS
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_REQUEST_BODY
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
import okhttp3.mockwebserver.SocketPolicy.EXPECT_CONTINUE
import okhttp3.mockwebserver.SocketPolicy.FAIL_HANDSHAKE
import okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE
import okhttp3.mockwebserver.SocketPolicy.RESET_STREAM_AT_START
import okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_INPUT_AT_END
import okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_OUTPUT_AT_END
import okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_SERVER_AFTER_RESPONSE
import okhttp3.mockwebserver.SocketPolicy.STALL_SOCKET_AT_START
import okhttp3.mockwebserver.SocketPolicy.UPGRADE_TO_SSL_AT_END
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source
import org.junit.rules.ExternalResource
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A scriptable web server. Callers supply canned responses and the server replays them upon request
 * in sequence.
 */
class MockWebServer : ExternalResource(), Closeable {
  private val taskRunnerBackend = TaskRunner.RealBackend(
      threadFactory("MockWebServer TaskRunner", true))
  private val taskRunner = TaskRunner(taskRunnerBackend)
  private val requestQueue = LinkedBlockingQueue<RecordedRequest>()
  private val openClientSockets =
      Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
  private val openConnections =
      Collections.newSetFromMap(ConcurrentHashMap<Http2Connection, Boolean>())

  private val atomicRequestCount = AtomicInteger()

  /**
   * The number of HTTP requests received thus far by this server. This may exceed the number of
   * HTTP connections when connection reuse is in practice.
   */
  val requestCount: Int
    get() = atomicRequestCount.get()

  /** The number of bytes of the POST body to keep in memory to the given limit. */
  var bodyLimit = Long.MAX_VALUE

  var serverSocketFactory: ServerSocketFactory? = null
    get() {
      if (field == null && started) {
        field = ServerSocketFactory.getDefault() // Build the default value lazily.
      }
      return field
    }
    set(value) {
      check(!started) { "serverSocketFactory must not be set after start()" }
      field = value
    }

  private var serverSocket: ServerSocket? = null
  private var sslSocketFactory: SSLSocketFactory? = null
  private var tunnelProxy: Boolean = false
  private var clientAuth = CLIENT_AUTH_NONE

  /**
   * The dispatcher used to respond to HTTP requests. The default dispatcher is a [QueueDispatcher],
   * which serves a fixed sequence of responses from a [queue][enqueue].
   *
   * Other dispatchers can be configured. They can vary the response based on timing or the content
   * of the request.
   */
  var dispatcher: Dispatcher = QueueDispatcher()

  private var portField: Int = -1
  val port: Int
    get() {
      before()
      return portField
    }

  val hostName: String
    get() {
      before()
      return inetSocketAddress!!.address.canonicalHostName
    }

  private var inetSocketAddress: InetSocketAddress? = null

  /**
   * True if ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1 or
   * HTTP/2. This is true by default; set to false to disable negotiation and restrict connections
   * to HTTP/1.1.
   */
  var protocolNegotiationEnabled = true

  /**
   * The protocols supported by ALPN on incoming HTTPS connections in order of preference. The list
   * must contain [Protocol.HTTP_1_1]. It must not contain null.
   *
   * This list is ignored when [negotiation is disabled][protocolNegotiationEnabled].
   */
  @get:JvmName("protocols") var protocols: List<Protocol> =
      immutableListOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    set(value) {
      val protocolList = value.toImmutableList()
      require(Protocol.H2_PRIOR_KNOWLEDGE !in protocolList || protocolList.size == 1) {
        "protocols containing h2_prior_knowledge cannot use other protocols: $protocolList"
      }
      require(Protocol.HTTP_1_1 in protocolList || Protocol.H2_PRIOR_KNOWLEDGE in protocolList) {
        "protocols doesn't contain http/1.1: $protocolList"
      }
      require(null !in protocolList as List<Protocol?>) { "protocols must not contain null" }
      field = protocolList
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
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "port"),
      level = DeprecationLevel.ERROR)
  fun getPort(): Int = port

  fun toProxyAddress(): Proxy {
    before()
    val address = InetSocketAddress(inetSocketAddress!!.address.canonicalHostName, port)
    return Proxy(Proxy.Type.HTTP, address)
  }

  @JvmName("-deprecated_serverSocketFactory")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(
          expression = "run { this.serverSocketFactory = serverSocketFactory }"
      ),
      level = DeprecationLevel.ERROR)
  fun setServerSocketFactory(serverSocketFactory: ServerSocketFactory) = run {
    this.serverSocketFactory = serverSocketFactory
  }

  /**
   * Returns a URL for connecting to this server.
   *
   * @param path the request path, such as "/".
   */
  fun url(path: String): HttpUrl {
    return HttpUrl.Builder()
        .scheme(if (sslSocketFactory != null) "https" else "http")
        .host(hostName)
        .port(port)
        .build()
        .resolve(path)!!
  }

  @JvmName("-deprecated_bodyLimit")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(
          expression = "run { this.bodyLimit = bodyLimit }"
      ),
      level = DeprecationLevel.ERROR)
  fun setBodyLimit(bodyLimit: Long) = run { this.bodyLimit = bodyLimit }

  @JvmName("-deprecated_protocolNegotiationEnabled")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(
          expression = "run { this.protocolNegotiationEnabled = protocolNegotiationEnabled }"
      ),
      level = DeprecationLevel.ERROR)
  fun setProtocolNegotiationEnabled(protocolNegotiationEnabled: Boolean) = run {
    this.protocolNegotiationEnabled = protocolNegotiationEnabled
  }

  @JvmName("-deprecated_protocols")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "run { this.protocols = protocols }"),
      level = DeprecationLevel.ERROR)
  fun setProtocols(protocols: List<Protocol>) = run { this.protocols = protocols }

  @JvmName("-deprecated_protocols")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "protocols"),
      level = DeprecationLevel.ERROR)
  fun protocols(): List<Protocol> = protocols

  /**
   * Serve requests with HTTPS rather than otherwise.
   *
   * @param tunnelProxy true to expect the HTTP CONNECT method before negotiating TLS.
   */
  fun useHttps(sslSocketFactory: SSLSocketFactory, tunnelProxy: Boolean) {
    this.sslSocketFactory = sslSocketFactory
    this.tunnelProxy = tunnelProxy
  }

  /**
   * Configure the server to not perform SSL authentication of the client. This leaves
   * authentication to another layer such as in an HTTP cookie or header. This is the default and
   * most common configuration.
   */
  fun noClientAuth() {
    this.clientAuth = CLIENT_AUTH_NONE
  }

  /**
   * Configure the server to [want client auth][SSLSocket.setWantClientAuth]. If the
   * client presents a certificate that is [trusted][TrustManager] the handshake will
   * proceed normally. The connection will also proceed normally if the client presents no
   * certificate at all! But if the client presents an untrusted certificate the handshake
   * will fail and no connection will be established.
   */
  fun requestClientAuth() {
    this.clientAuth = CLIENT_AUTH_REQUESTED
  }

  /**
   * Configure the server to [need client auth][SSLSocket.setNeedClientAuth]. If the
   * client presents a certificate that is [trusted][TrustManager] the handshake will
   * proceed normally. If the client presents an untrusted certificate or no certificate at all the
   * handshake will fail and no connection will be established.
   */
  fun requireClientAuth() {
    this.clientAuth = CLIENT_AUTH_REQUIRED
  }

  /**
   * Awaits the next HTTP request, removes it, and returns it. Callers should use this to verify the
   * request was sent as intended. This method will block until the request is available, possibly
   * forever.
   *
   * @return the head of the request queue
   */
  @Throws(InterruptedException::class)
  fun takeRequest(): RecordedRequest = requestQueue.take()

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
      requestQueue.poll(timeout, unit)

  @JvmName("-deprecated_requestCount")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "requestCount"),
      level = DeprecationLevel.ERROR)
  fun getRequestCount(): Int = requestCount

  /**
   * Scripts [response] to be returned to a request made in sequence. The first request is
   * served by the first enqueued response; the second request by the second enqueued response; and
   * so on.
   *
   * @throws ClassCastException if the default dispatcher has been
   * replaced with [setDispatcher][dispatcher].
   */
  fun enqueue(response: MockResponse) =
      (dispatcher as QueueDispatcher).enqueueResponse(response.clone())

  /**
   * Starts the server on the loopback interface for the given port.
   *
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  @JvmOverloads fun start(port: Int = 0) = start(InetAddress.getByName("localhost"), port)

  /**
   * Starts the server on the given address and port.
   *
   * @param inetAddress the address to create the server socket on
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  fun start(inetAddress: InetAddress, port: Int) = start(InetSocketAddress(inetAddress, port))

  /**
   * Starts the server and binds to the given socket address.
   *
   * @param inetSocketAddress the socket address to bind the server on
   */
  @Synchronized @Throws(IOException::class)
  private fun start(inetSocketAddress: InetSocketAddress) {
    require(!started) { "start() already called" }
    started = true

    this.inetSocketAddress = inetSocketAddress

    serverSocket = serverSocketFactory!!.createServerSocket()

    // Reuse if the user specified a port
    serverSocket!!.reuseAddress = inetSocketAddress.port != 0
    serverSocket!!.bind(inetSocketAddress, 50)

    portField = serverSocket!!.localPort

    taskRunner.newQueue().execute("MockWebServer $portField", cancelable = false) {
      try {
        logger.info("${this@MockWebServer} starting to accept connections")
        acceptConnections()
      } catch (e: Throwable) {
        logger.log(Level.WARNING, "${this@MockWebServer}  failed unexpectedly", e)
      }

      // Release all sockets and all threads, even if any close fails.
      serverSocket?.closeQuietly()

      val openClientSocket = openClientSockets.iterator()
      while (openClientSocket.hasNext()) {
        openClientSocket.next().closeQuietly()
        openClientSocket.remove()
      }

      val httpConnection = openConnections.iterator()
      while (httpConnection.hasNext()) {
        httpConnection.next().closeQuietly()
        httpConnection.remove()
      }
      dispatcher.shutdown()
    }
  }

  @Throws(Exception::class)
  private fun acceptConnections() {
    while (true) {
      val socket: Socket
      try {
        socket = serverSocket!!.accept()
      } catch (e: SocketException) {
        logger.info("${this@MockWebServer} done accepting connections: ${e.message}")
        return
      }

      val socketPolicy = dispatcher.peek().socketPolicy
      if (socketPolicy === DISCONNECT_AT_START) {
        dispatchBookkeepingRequest(0, socket)
        socket.close()
      } else {
        openClientSockets.add(socket)
        serveConnection(socket)
      }
    }
  }

  @Synchronized
  @Throws(IOException::class)
  fun shutdown() {
    if (!started) return
    require(serverSocket != null) { "shutdown() before start()" }

    // Cause acceptConnections() to break out.
    serverSocket!!.close()

    // Await shutdown.
    for (queue in taskRunner.activeQueues()) {
      if (!queue.awaitIdle(TimeUnit.SECONDS.toNanos(5))) {
        throw IOException("Gave up waiting for queue to shut down")
      }
    }
    taskRunnerBackend.shutdown()
  }

  @Synchronized override fun after() {
    try {
      shutdown()
    } catch (e: IOException) {
      logger.log(Level.WARNING, "MockWebServer shutdown failed", e)
    }
  }

  private fun serveConnection(raw: Socket) {
    taskRunner.newQueue().execute("MockWebServer ${raw.remoteSocketAddress}", cancelable = false) {
      try {
        SocketHandler(raw).handle()
      } catch (e: IOException) {
        logger.info("${this@MockWebServer} connection from ${raw.inetAddress} failed: $e")
      } catch (e: Exception) {
        logger.log(Level.SEVERE,
            "${this@MockWebServer} connection from ${raw.inetAddress} crashed", e)
      }
    }
  }

  internal inner class SocketHandler(private val raw: Socket) {
    private var sequenceNumber = 0

    @Throws(Exception::class)
    fun handle() {
      val socketPolicy = dispatcher.peek().socketPolicy
      var protocol = Protocol.HTTP_1_1
      val socket: Socket
      when {
        sslSocketFactory != null -> {
          if (tunnelProxy) {
            createTunnel()
          }
          if (socketPolicy === FAIL_HANDSHAKE) {
            dispatchBookkeepingRequest(sequenceNumber, raw)
            processHandshakeFailure(raw)
            return
          }
          socket = sslSocketFactory!!.createSocket(raw, raw.inetAddress.hostAddress,
              raw.port, true)
          val sslSocket = socket as SSLSocket
          sslSocket.useClientMode = false
          if (clientAuth == CLIENT_AUTH_REQUIRED) {
            sslSocket.needClientAuth = true
          } else if (clientAuth == CLIENT_AUTH_REQUESTED) {
            sslSocket.wantClientAuth = true
          }
          openClientSockets.add(socket)

          if (protocolNegotiationEnabled) {
            Platform.get().configureTlsExtensions(sslSocket, protocols)
          }

          sslSocket.startHandshake()

          if (protocolNegotiationEnabled) {
            val protocolString = Platform.get().getSelectedProtocol(sslSocket)
            protocol =
                if (protocolString != null) Protocol.get(protocolString) else Protocol.HTTP_1_1
            Platform.get().afterHandshake(sslSocket)
          }
          openClientSockets.remove(raw)
        }
        Protocol.H2_PRIOR_KNOWLEDGE in protocols -> {
          socket = raw
          protocol = Protocol.H2_PRIOR_KNOWLEDGE
        }
        else -> socket = raw
      }

      if (socketPolicy === STALL_SOCKET_AT_START) {
        return // Ignore the socket until the server is shut down!
      }

      if (protocol === Protocol.HTTP_2 || protocol === Protocol.H2_PRIOR_KNOWLEDGE) {
        val http2SocketHandler = Http2SocketHandler(socket, protocol)
        val connection = Http2Connection.Builder(false, taskRunner)
            .socket(socket)
            .listener(http2SocketHandler)
            .build()
        connection.start()
        openConnections.add(connection)
        openClientSockets.remove(socket)
        return
      } else if (protocol !== Protocol.HTTP_1_1) {
        throw AssertionError()
      }

      val source = socket.source().buffer()
      val sink = socket.sink().buffer()

      while (processOneRequest(socket, source, sink)) {
      }

      if (sequenceNumber == 0) {
        logger.warning(
            "${this@MockWebServer} connection from ${raw.inetAddress} didn't make a request")
      }

      socket.close()
      openClientSockets.remove(socket)
    }

    /**
     * Respond to CONNECT requests until a SWITCH_TO_SSL_AT_END response is
     * dispatched.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun createTunnel() {
      val source = raw.source().buffer()
      val sink = raw.sink().buffer()
      while (true) {
        val socketPolicy = dispatcher.peek().socketPolicy
        check(processOneRequest(raw, source, sink)) { "Tunnel without any CONNECT!" }
        if (socketPolicy === UPGRADE_TO_SSL_AT_END) return
      }
    }

    /**
     * Reads a request and writes its response. Returns true if further calls should be attempted
     * on the socket.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun processOneRequest(
      socket: Socket,
      source: BufferedSource,
      sink: BufferedSink
    ): Boolean {
      if (source.exhausted()) {
        return false // No more requests on this socket.
      }

      val request = readRequest(socket, source, sink, sequenceNumber)
      atomicRequestCount.incrementAndGet()
      requestQueue.add(request)

      if (request.failure != null) {
        return false // Nothing to respond to.
      }

      val response = dispatcher.dispatch(request)
      if (response.socketPolicy === DISCONNECT_AFTER_REQUEST) {
        socket.close()
        return false
      }
      if (response.socketPolicy === NO_RESPONSE) {
        // This read should block until the socket is closed. (Because nobody is writing.)
        if (source.exhausted()) return false
        throw ProtocolException("unexpected data")
      }

      var reuseSocket = true
      val requestWantsWebSockets = "Upgrade".equals(request.getHeader("Connection"),
          ignoreCase = true) && "websocket".equals(request.getHeader("Upgrade"),
          ignoreCase = true)
      val responseWantsWebSockets = response.webSocketListener != null
      if (requestWantsWebSockets && responseWantsWebSockets) {
        handleWebSocketUpgrade(socket, source, sink, request, response)
        reuseSocket = false
      } else {
        writeHttpResponse(socket, sink, response)
      }

      if (logger.isLoggable(Level.INFO)) {
        logger.info(
            "${this@MockWebServer} received request: $request and responded: $response")
      }

      // See warnings associated with these socket policies in SocketPolicy.
      when (response.socketPolicy) {
        DISCONNECT_AT_END -> {
          socket.close()
          return false
        }
        SHUTDOWN_INPUT_AT_END -> socket.shutdownInput()
        SHUTDOWN_OUTPUT_AT_END -> socket.shutdownOutput()
        SHUTDOWN_SERVER_AFTER_RESPONSE -> shutdown()
        else -> {
        }
      }
      sequenceNumber++
      return reuseSocket
    }
  }

  @Throws(Exception::class)
  private fun processHandshakeFailure(raw: Socket) {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(UNTRUSTED_TRUST_MANAGER), SecureRandom())
    val sslSocketFactory = context.socketFactory
    val socket = sslSocketFactory.createSocket(
        raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
    try {
      socket.startHandshake() // we're testing a handshake failure
      throw AssertionError()
    } catch (expected: IOException) {
    }
    socket.close()
  }

  @Throws(InterruptedException::class)
  private fun dispatchBookkeepingRequest(sequenceNumber: Int, socket: Socket) {
    val request = RecordedRequest(
        "", headersOf(), emptyList(), 0L, Buffer(), sequenceNumber, socket)
    atomicRequestCount.incrementAndGet()
    requestQueue.add(request)
    dispatcher.dispatch(request)
  }

  /** @param sequenceNumber the index of this request on this connection.*/
  @Throws(IOException::class)
  private fun readRequest(
    socket: Socket,
    source: BufferedSource,
    sink: BufferedSink,
    sequenceNumber: Int
  ): RecordedRequest {
    var request = ""
    val headers = Headers.Builder()
    var contentLength = -1L
    var chunked = false
    var expectContinue = false
    val requestBody = TruncatingBuffer(bodyLimit)
    val chunkSizes = mutableListOf<Int>()
    var failure: IOException? = null

    try {
      request = source.readUtf8LineStrict()
      if (request.isEmpty()) {
        throw ProtocolException("no request because the stream is exhausted")
      }

      while (true) {
        val header = source.readUtf8LineStrict()
        if (header.isEmpty()) {
          break
        }
        addHeaderLenient(headers, header)
        val lowercaseHeader = header.toLowerCase(Locale.US)
        if (contentLength == -1L && lowercaseHeader.startsWith("content-length:")) {
          contentLength = header.substring(15).trim().toLong()
        }
        if (lowercaseHeader.startsWith("transfer-encoding:") && lowercaseHeader.substring(
                18).trim() == "chunked") {
          chunked = true
        }
        if (lowercaseHeader.startsWith("expect:") && lowercaseHeader.substring(
                7).trim().equals("100-continue", ignoreCase = true)) {
          expectContinue = true
        }
      }

      val socketPolicy = dispatcher.peek().socketPolicy
      if (expectContinue && socketPolicy === EXPECT_CONTINUE || socketPolicy === CONTINUE_ALWAYS) {
        sink.writeUtf8("HTTP/1.1 100 Continue\r\n")
        sink.writeUtf8("Content-Length: 0\r\n")
        sink.writeUtf8("\r\n")
        sink.flush()
      }

      var hasBody = false
      val policy = dispatcher.peek()
      if (contentLength != -1L) {
        hasBody = contentLength > 0L
        throttledTransfer(policy, socket, source, requestBody.buffer(), contentLength, true)
      } else if (chunked) {
        hasBody = true
        while (true) {
          val chunkSize = source.readUtf8LineStrict().trim().toInt(16)
          if (chunkSize == 0) {
            readEmptyLine(source)
            break
          }
          chunkSizes.add(chunkSize)
          throttledTransfer(policy, socket, source,
              requestBody.buffer(), chunkSize.toLong(), true)
          readEmptyLine(source)
        }
      }

      val method = request.substringBefore(' ')
      require(!hasBody || HttpMethod.permitsRequestBody(method)) {
        "Request must not have a body: $request"
      }
    } catch (e: IOException) {
      failure = e
    }

    return RecordedRequest(request, headers.build(), chunkSizes, requestBody.receivedByteCount,
        requestBody.buffer, sequenceNumber, socket, failure)
  }

  @Throws(IOException::class)
  private fun handleWebSocketUpgrade(
    socket: Socket,
    source: BufferedSource,
    sink: BufferedSink,
    request: RecordedRequest,
    response: MockResponse
  ) {
    val key = request.getHeader("Sec-WebSocket-Key")
    response.setHeader("Sec-WebSocket-Accept", WebSocketProtocol.acceptHeader(key!!))

    writeHttpResponse(socket, sink, response)

    // Adapt the request and response into our Request and Response domain model.
    val scheme = if (request.tlsVersion != null) "https" else "http"
    val authority = request.getHeader("Host") // Has host and port.
    val fancyRequest = Request.Builder()
        .url("$scheme://$authority/")
        .headers(request.headers)
        .build()
    val statusParts = response.status.split(' ', limit = 3)
    val fancyResponse = Response.Builder()
        .code(statusParts[1].toInt())
        .message(statusParts[2])
        .headers(response.headers)
        .request(fancyRequest)
        .protocol(Protocol.HTTP_1_1)
        .build()

    val connectionClose = CountDownLatch(1)
    val streams = object : RealWebSocket.Streams(false, source, sink) {
      override fun close() = connectionClose.countDown()
    }
    val webSocket = RealWebSocket(
        taskRunner = taskRunner,
        originalRequest = fancyRequest,
        listener = response.webSocketListener!!,
        random = SecureRandom(),
        pingIntervalMillis = 0
    )
    response.webSocketListener!!.onOpen(webSocket, fancyResponse)
    val name = "MockWebServer WebSocket ${request.path!!}"
    webSocket.initReaderAndWriter(name, streams)
    try {
      webSocket.loopReader()

      // Even if messages are no longer being read we need to wait for the connection close signal.
      connectionClose.await()
    } catch (e: IOException) {
      webSocket.failWebSocket(e, null)
    } finally {
      source.closeQuietly()
    }
  }

  @Throws(IOException::class)
  private fun writeHttpResponse(socket: Socket, sink: BufferedSink, response: MockResponse) {
    sleepIfDelayed(response.getHeadersDelay(TimeUnit.MILLISECONDS))
    sink.writeUtf8(response.status)
    sink.writeUtf8("\r\n")

    writeHeaders(sink, response.headers)

    val body = response.getBody() ?: return
    sleepIfDelayed(response.getBodyDelay(TimeUnit.MILLISECONDS))
    throttledTransfer(response, socket, body, sink, body.size, false)

    if ("chunked".equals(response.headers["Transfer-Encoding"], ignoreCase = true)) {
      writeHeaders(sink, response.trailers)
    }
  }

  @Throws(IOException::class)
  private fun writeHeaders(sink: BufferedSink, headers: Headers) {
    for ((name, value) in headers) {
      sink.writeUtf8(name)
      sink.writeUtf8(": ")
      sink.writeUtf8(value)
      sink.writeUtf8("\r\n")
    }
    sink.writeUtf8("\r\n")
    sink.flush()
  }

  private fun sleepIfDelayed(delayMs: Long) {
    if (delayMs != 0L) {
      Thread.sleep(delayMs)
    }
  }

  /**
   * Transfer bytes from [source] to [sink] until either [byteCount] bytes have
   * been transferred or [source] is exhausted. The transfer is throttled according to [policy].
   */
  @Throws(IOException::class)
  private fun throttledTransfer(
    policy: MockResponse,
    socket: Socket,
    source: BufferedSource,
    sink: BufferedSink,
    byteCount: Long,
    isRequest: Boolean
  ) {
    var byteCountNum = byteCount
    if (byteCountNum == 0L) return

    val buffer = Buffer()
    val bytesPerPeriod = policy.throttleBytesPerPeriod
    val periodDelayMs = policy.getThrottlePeriod(TimeUnit.MILLISECONDS)

    val halfByteCount = byteCountNum / 2
    val disconnectHalfway = if (isRequest) {
      policy.socketPolicy === DISCONNECT_DURING_REQUEST_BODY
    } else {
      policy.socketPolicy === DISCONNECT_DURING_RESPONSE_BODY
    }

    while (!socket.isClosed) {
      var b = 0L
      while (b < bytesPerPeriod) {
        // Ensure we do not read past the allotted bytes in this period.
        var toRead = minOf(byteCountNum, bytesPerPeriod - b)
        // Ensure we do not read past halfway if the policy will kill the connection.
        if (disconnectHalfway) {
          toRead = minOf(toRead, byteCountNum - halfByteCount)
        }

        val read = source.read(buffer, toRead)
        if (read == -1L) return

        sink.write(buffer, read)
        sink.flush()
        b += read
        byteCountNum -= read

        if (disconnectHalfway && byteCountNum == halfByteCount) {
          socket.close()
          return
        }

        if (byteCountNum == 0L) return
      }

      if (periodDelayMs != 0L) {
        Thread.sleep(periodDelayMs)
      }
    }
  }

  @Throws(IOException::class)
  private fun readEmptyLine(source: BufferedSource) {
    val line = source.readUtf8LineStrict()
    check(line.isEmpty()) { "Expected empty but was: $line" }
  }

  override fun toString(): String = "MockWebServer[$portField]"

  @Throws(IOException::class)
  override fun close() = shutdown()

  /** A buffer wrapper that drops data after [bodyLimit] bytes. */
  private class TruncatingBuffer internal constructor(
    private var remainingByteCount: Long
  ) : Sink {
    internal val buffer = Buffer()
    internal var receivedByteCount = 0L

    @Throws(IOException::class)
    override fun write(source: Buffer, byteCount: Long) {
      val toRead = minOf(remainingByteCount, byteCount)
      if (toRead > 0L) {
        source.read(buffer, toRead)
      }
      val toSkip = byteCount - toRead
      if (toSkip > 0L) {
        source.skip(toSkip)
      }
      remainingByteCount -= toRead
      receivedByteCount += byteCount
    }

    @Throws(IOException::class)
    override fun flush() {
    }

    override fun timeout(): Timeout = Timeout.NONE

    @Throws(IOException::class)
    override fun close() {
    }
  }

  /** Processes HTTP requests layered over HTTP/2. */
  private inner class Http2SocketHandler constructor(
    private val socket: Socket,
    private val protocol: Protocol
  ) : Http2Connection.Listener() {
    private val sequenceNumber = AtomicInteger()

    @Throws(IOException::class)
    override fun onStream(stream: Http2Stream) {
      val peekedResponse = dispatcher.peek()
      if (peekedResponse.socketPolicy === RESET_STREAM_AT_START) {
        dispatchBookkeepingRequest(sequenceNumber.getAndIncrement(), socket)
        stream.close(ErrorCode.fromHttp2(peekedResponse.http2ErrorCode)!!, null)
        return
      }

      val request = readRequest(stream)
      atomicRequestCount.incrementAndGet()
      requestQueue.add(request)
      if (request.failure != null) {
        return // Nothing to respond to.
      }

      val response: MockResponse = dispatcher.dispatch(request)

      if (response.socketPolicy === DISCONNECT_AFTER_REQUEST) {
        socket.close()
        return
      }
      writeResponse(stream, request, response)
      if (logger.isLoggable(Level.INFO)) {
        logger.info(
            "${this@MockWebServer} received request: $request " +
                "and responded: $response protocol is $protocol")
      }

      if (response.socketPolicy === DISCONNECT_AT_END) {
        val connection = stream.connection
        connection.shutdown(ErrorCode.NO_ERROR)
      }
    }

    @Throws(IOException::class)
    private fun readRequest(stream: Http2Stream): RecordedRequest {
      val streamHeaders = stream.takeHeaders()
      val httpHeaders = Headers.Builder()
      var method = "<:method omitted>"
      var path = "<:path omitted>"
      var readBody = true
      for ((name, value) in streamHeaders) {
        if (name == Header.TARGET_METHOD_UTF8) {
          method = value
        } else if (name == Header.TARGET_PATH_UTF8) {
          path = value
        } else if (protocol === Protocol.HTTP_2 || protocol === Protocol.H2_PRIOR_KNOWLEDGE) {
          httpHeaders.add(name, value)
        } else {
          throw IllegalStateException()
        }
        if (name == "expect" && value.equals("100-continue", ignoreCase = true)) {
          // Don't read the body unless we've invited the client to send it.
          readBody = false
        }
      }
      val headers = httpHeaders.build()

      val peek = dispatcher.peek()
      if (!readBody && peek.socketPolicy === EXPECT_CONTINUE) {
        val continueHeaders =
            listOf(Header(Header.RESPONSE_STATUS, "100 Continue".encodeUtf8()))
        stream.writeHeaders(continueHeaders, outFinished = false, flushHeaders = true)
        stream.connection.flush()
        readBody = true
      }

      val body = Buffer()
      val requestLine = "$method $path HTTP/1.1"
      var exception: IOException? = null
      if (readBody && !peek.isDuplex) {
        try {
          val contentLengthString = headers["content-length"]
          val byteCount = contentLengthString?.toLong() ?: Long.MAX_VALUE
          throttledTransfer(peek, socket, stream.getSource().buffer(),
              body, byteCount, true)
        } catch (e: IOException) {
          exception = e
        }
      }

      return RecordedRequest(requestLine, headers, emptyList(), body.size, body,
          sequenceNumber.getAndIncrement(), socket, exception)
    }

    @Throws(IOException::class)
    private fun writeResponse(
      stream: Http2Stream,
      request: RecordedRequest,
      response: MockResponse
    ) {
      val settings = response.settings
      stream.connection.setSettings(settings)

      if (response.socketPolicy === NO_RESPONSE) {
        return
      }
      val http2Headers = mutableListOf<Header>()
      val statusParts = response.status.split(' ', limit = 3)

      if (statusParts.size < 2) {
        throw AssertionError("Unexpected status: ${response.status}")
      }
      // TODO: constants for well-known header names.
      http2Headers.add(Header(Header.RESPONSE_STATUS, statusParts[1]))
      val headers = response.headers
      for ((name, value) in headers) {
        http2Headers.add(Header(name, value))
      }
      val trailers = response.trailers

      sleepIfDelayed(response.getHeadersDelay(TimeUnit.MILLISECONDS))

      val body = response.getBody()
      val outFinished = (body == null &&
          response.pushPromises.isEmpty() &&
          !response.isDuplex)
      val flushHeaders = body == null
      require(!outFinished || trailers.size == 0) {
        "unsupported: no body and non-empty trailers $trailers"
      }
      stream.writeHeaders(http2Headers, outFinished, flushHeaders)
      if (trailers.size > 0) {
        stream.enqueueTrailers(trailers)
      }
      pushPromises(stream, request, response.pushPromises)
      if (body != null) {
        stream.getSink().buffer().use { sink ->
          sleepIfDelayed(response.getBodyDelay(TimeUnit.MILLISECONDS))
          throttledTransfer(response, socket, body, sink, body.size, false)
        }
      } else if (response.isDuplex) {
        stream.getSink().buffer().use { sink ->
          stream.getSource().buffer().use { source ->
            val duplexResponseBody = response.duplexResponseBody
            duplexResponseBody!!.onRequest(request, source, sink)
          }
        }
      } else if (!outFinished) {
        stream.close(ErrorCode.NO_ERROR, null)
      }
    }

    @Throws(IOException::class)
    private fun pushPromises(
      stream: Http2Stream,
      request: RecordedRequest,
      promises: List<PushPromise>
    ) {
      for (pushPromise in promises) {
        val pushedHeaders = mutableListOf<Header>()
        pushedHeaders.add(Header(Header.TARGET_AUTHORITY, url(pushPromise.path).host))
        pushedHeaders.add(Header(Header.TARGET_METHOD, pushPromise.method))
        pushedHeaders.add(Header(Header.TARGET_PATH, pushPromise.path))
        val pushPromiseHeaders = pushPromise.headers
        for ((name, value) in pushPromiseHeaders) {
          pushedHeaders.add(Header(name, value))
        }
        val requestLine = "${pushPromise.method} ${pushPromise.path} HTTP/1.1"
        val chunkSizes = emptyList<Int>() // No chunked encoding for HTTP/2.
        requestQueue.add(RecordedRequest(requestLine, pushPromise.headers, chunkSizes, 0,
            Buffer(), sequenceNumber.getAndIncrement(), socket))
        val hasBody = pushPromise.response.getBody() != null
        val pushedStream = stream.connection.pushStream(stream.id, pushedHeaders, hasBody)
        writeResponse(pushedStream, request, pushPromise.response)
      }
    }
  }

  companion object {
    init {
      MwsDuplexAccess.instance = object : MwsDuplexAccess() {
        override fun setBody(
          mockResponse: MockResponse,
          duplexResponseBody: DuplexResponseBody
        ) {
          mockResponse.setBody(duplexResponseBody)
        }
      }
    }

    private const val CLIENT_AUTH_NONE = 0
    private const val CLIENT_AUTH_REQUESTED = 1
    private const val CLIENT_AUTH_REQUIRED = 2

    private val UNTRUSTED_TRUST_MANAGER = object : X509TrustManager {
      @Throws(CertificateException::class)
      override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String
      ) = throw CertificateException()

      override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String
      ) = throw AssertionError()

      override fun getAcceptedIssuers(): Array<X509Certificate> = throw AssertionError()
    }

    private val logger = Logger.getLogger(MockWebServer::class.java.name)
  }
}
