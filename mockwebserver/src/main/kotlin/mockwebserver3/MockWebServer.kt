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

package mockwebserver3

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
import mockwebserver3.SocketPolicy.DisconnectAfterRequest
import mockwebserver3.SocketPolicy.DisconnectAtEnd
import mockwebserver3.SocketPolicy.DisconnectAtStart
import mockwebserver3.SocketPolicy.DisconnectDuringRequestBody
import mockwebserver3.SocketPolicy.DisconnectDuringResponseBody
import mockwebserver3.SocketPolicy.DoNotReadRequestBody
import mockwebserver3.SocketPolicy.FailHandshake
import mockwebserver3.SocketPolicy.HalfCloseAfterRequest
import mockwebserver3.SocketPolicy.NoResponse
import mockwebserver3.SocketPolicy.ResetStreamAtStart
import mockwebserver3.SocketPolicy.ShutdownInputAtEnd
import mockwebserver3.SocketPolicy.ShutdownOutputAtEnd
import mockwebserver3.SocketPolicy.ShutdownServerAfterResponse
import mockwebserver3.SocketPolicy.StallSocketAtStart
import mockwebserver3.internal.ThrottledSink
import mockwebserver3.internal.TriggerSink
import mockwebserver3.internal.duplex.RealStream
import mockwebserver3.internal.sleepNanos
import okhttp3.ExperimentalOkHttpApi
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
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
import okhttp3.internal.ws.WebSocketExtensions
import okhttp3.internal.ws.WebSocketProtocol
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source

/**
 * A scriptable web server. Callers supply canned responses and the server replays them upon request
 * in sequence.
 */
@ExperimentalOkHttpApi
class MockWebServer : Closeable {
  private val taskRunnerBackend =
    TaskRunner.RealBackend(
      threadFactory("MockWebServer TaskRunner", daemon = false),
    )
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
  var bodyLimit: Long = Long.MAX_VALUE

  var serverSocketFactory: ServerSocketFactory? = null
    @Synchronized get() {
      if (field == null && started) {
        field = ServerSocketFactory.getDefault() // Build the default value lazily.
      }
      return field
    }

    @Synchronized set(value) {
      check(!started) { "serverSocketFactory must not be set after start()" }
      field = value
    }

  private var serverSocket: ServerSocket? = null
  private var sslSocketFactory: SSLSocketFactory? = null
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
      return _inetSocketAddress!!.address.hostName
    }

  private var _inetSocketAddress: InetSocketAddress? = null

  val inetSocketAddress: InetSocketAddress
    get() {
      before()
      return InetSocketAddress(hostName, portField)
    }

  /**
   * True if ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1 or
   * HTTP/2. This is true by default; set to false to disable negotiation and restrict connections
   * to HTTP/1.1.
   */
  var protocolNegotiationEnabled: Boolean = true

  /**
   * The protocols supported by ALPN on incoming HTTPS connections in order of preference. The list
   * must contain [Protocol.HTTP_1_1]. It must not contain null.
   *
   * This list is ignored when [negotiation is disabled][protocolNegotiationEnabled].
   */
  var protocols: List<Protocol> = immutableListOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
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

  var started: Boolean = false
  private var shutdown: Boolean = false

  @Synchronized private fun before() {
    if (started) return // Don't call start() in case we're already shut down.
    try {
      start()
    } catch (e: IOException) {
      throw RuntimeException(e)
    }
  }

  fun toProxyAddress(): Proxy {
    before()
    val address = InetSocketAddress(_inetSocketAddress!!.address.hostName, port)
    return Proxy(Proxy.Type.HTTP, address)
  }

  /**
   * Returns a URL for connecting to this server.
   *
   * @param path the request path, such as "/".
   */
  fun url(path: String): HttpUrl =
    HttpUrl
      .Builder()
      .scheme(if (sslSocketFactory != null) "https" else "http")
      .host(hostName)
      .port(port)
      .build()
      .resolve(path)!!

  /**
   * Serve requests with HTTPS rather than otherwise.
   */
  fun useHttps(sslSocketFactory: SSLSocketFactory) {
    this.sslSocketFactory = sslSocketFactory
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
  fun takeRequest(
    timeout: Long,
    unit: TimeUnit,
  ): RecordedRequest? = requestQueue.poll(timeout, unit)

  /**
   * Scripts [response] to be returned to a request made in sequence. The first request is
   * served by the first enqueued response; the second request by the second enqueued response; and
   * so on.
   *
   * @throws ClassCastException if the default dispatcher has been
   * replaced with [setDispatcher][dispatcher].
   */
  fun enqueue(response: MockResponse) = (dispatcher as QueueDispatcher).enqueueResponse(response)

  /**
   * Starts the server on the loopback interface for the given port.
   *
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  @JvmOverloads
  fun start(port: Int = 0) = start(InetAddress.getByName("localhost"), port)

  /**
   * Starts the server on the given address and port.
   *
   * @param inetAddress the address to create the server socket on
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  fun start(
    inetAddress: InetAddress,
    port: Int,
  ) = start(InetSocketAddress(inetAddress, port))

  /**
   * Starts the server and binds to the given socket address.
   *
   * @param inetSocketAddress the socket address to bind the server on
   */
  @Synchronized
  @Throws(IOException::class)
  private fun start(inetSocketAddress: InetSocketAddress) {
    check(!shutdown) { "shutdown() already called" }
    if (started) return
    started = true

    this._inetSocketAddress = inetSocketAddress

    serverSocket = serverSocketFactory!!.createServerSocket()

    // Reuse if the user specified a port
    serverSocket!!.reuseAddress = inetSocketAddress.port != 0
    serverSocket!!.bind(inetSocketAddress, 50)

    portField = serverSocket!!.localPort

    taskRunner.newQueue().execute("MockWebServer $portField", cancelable = false) {
      try {
        logger.fine("$this starting to accept connections")
        acceptConnections()
      } catch (e: Throwable) {
        logger.log(Level.WARNING, "$this failed unexpectedly", e)
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
        logger.fine("${this@MockWebServer} done accepting connections: ${e.message}")
        return
      }

      val socketPolicy = dispatcher.peek().socketPolicy
      if (socketPolicy === DisconnectAtStart) {
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
    if (shutdown) return
    shutdown = true

    if (!started) return // Nothing to shut down.
    val serverSocket = this.serverSocket ?: return // If this is null, start() must have failed.

    // Cause acceptConnections() to break out.
    serverSocket.close()

    // Await shutdown.
    for (queue in taskRunner.activeQueues()) {
      if (!queue.idleLatch().await(5, TimeUnit.SECONDS)) {
        throw IOException("Gave up waiting for queue to shut down")
      }
    }
    taskRunnerBackend.shutdown()
  }

  private fun serveConnection(raw: Socket) {
    taskRunner.newQueue().execute("MockWebServer ${raw.remoteSocketAddress}", cancelable = false) {
      try {
        SocketHandler(raw).handle()
      } catch (e: IOException) {
        logger.fine("$this connection from ${raw.inetAddress} failed: $e")
      } catch (e: Exception) {
        logger.log(Level.SEVERE, "$this connection from ${raw.inetAddress} crashed", e)
      }
    }
  }

  internal inner class SocketHandler(
    private val raw: Socket,
  ) {
    private var sequenceNumber = 0

    @Throws(Exception::class)
    fun handle() {
      if (!processTunnelRequests()) return

      val socketPolicy = dispatcher.peek().socketPolicy
      val protocol: Protocol
      val socket: Socket
      when {
        sslSocketFactory != null -> {
          if (socketPolicy === FailHandshake) {
            dispatchBookkeepingRequest(sequenceNumber, raw)
            processHandshakeFailure(raw)
            return
          }
          socket =
            sslSocketFactory!!.createSocket(
              raw,
              raw.inetAddress.hostAddress,
              raw.port,
              true,
            )
          val sslSocket = socket as SSLSocket
          sslSocket.useClientMode = false
          if (clientAuth == CLIENT_AUTH_REQUIRED) {
            sslSocket.needClientAuth = true
          } else if (clientAuth == CLIENT_AUTH_REQUESTED) {
            sslSocket.wantClientAuth = true
          }
          openClientSockets.add(socket)

          if (protocolNegotiationEnabled) {
            Platform.get().configureTlsExtensions(sslSocket, null, protocols)
          }

          sslSocket.startHandshake()

          if (protocolNegotiationEnabled) {
            val protocolString = Platform.get().getSelectedProtocol(sslSocket)
            protocol =
              when {
                protocolString != null -> Protocol.get(protocolString)
                else -> Protocol.HTTP_1_1
              }
            Platform.get().afterHandshake(sslSocket)
          } else {
            protocol = Protocol.HTTP_1_1
          }
          openClientSockets.remove(raw)
        }
        else -> {
          protocol =
            when {
              Protocol.H2_PRIOR_KNOWLEDGE in protocols -> Protocol.H2_PRIOR_KNOWLEDGE
              else -> Protocol.HTTP_1_1
            }
          socket = raw
        }
      }

      if (socketPolicy === StallSocketAtStart) {
        dispatchBookkeepingRequest(sequenceNumber, socket)
        return // Ignore the socket until the server is shut down!
      }

      if (protocol === Protocol.HTTP_2 || protocol === Protocol.H2_PRIOR_KNOWLEDGE) {
        val http2SocketHandler = Http2SocketHandler(socket, protocol)
        val connection =
          Http2Connection
            .Builder(false, taskRunner)
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
          "${this@MockWebServer} connection from ${raw.inetAddress} didn't make a request",
        )
      }

      socket.close()
      openClientSockets.remove(socket)
    }

    /**
     * Respond to `CONNECT` requests until a non-tunnel response is peeked. Returns true if further
     * calls should be attempted on the socket.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun processTunnelRequests(): Boolean {
      if (!dispatcher.peek().inTunnel) return true // No tunnel requests.

      val source = raw.source().buffer()
      val sink = raw.sink().buffer()
      while (true) {
        val socketStillGood = processOneRequest(raw, source, sink)

        // Clean up after the last exchange on a socket.
        if (!socketStillGood) {
          raw.close()
          openClientSockets.remove(raw)
          return false
        }

        if (!dispatcher.peek().inTunnel) return true // No more tunnel requests.
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
      sink: BufferedSink,
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
      if (response.socketPolicy === DisconnectAfterRequest) {
        socket.close()
        return false
      }
      if (response.socketPolicy === HalfCloseAfterRequest) {
        socket.shutdownOutput()
        return false
      }
      if (response.socketPolicy === NoResponse) {
        // This read should block until the socket is closed. (Because nobody is writing.)
        if (source.exhausted()) return false
        throw ProtocolException("unexpected data")
      }

      var reuseSocket = true
      val requestWantsWebSockets =
        "Upgrade".equals(request.headers["Connection"], ignoreCase = true) &&
          "websocket".equals(request.headers["Upgrade"], ignoreCase = true)
      val responseWantsWebSockets = response.webSocketListener != null
      if (requestWantsWebSockets && responseWantsWebSockets) {
        handleWebSocketUpgrade(socket, source, sink, request, response)
        reuseSocket = false
      } else {
        writeHttpResponse(socket, sink, response)
      }

      if (logger.isLoggable(Level.FINE)) {
        logger.fine(
          "${this@MockWebServer} received request: $request and responded: $response",
        )
      }

      // See warnings associated with these socket policies in SocketPolicy.
      when (response.socketPolicy) {
        DisconnectAtEnd, is DoNotReadRequestBody -> {
          socket.close()
          return false
        }
        ShutdownInputAtEnd -> socket.shutdownInput()
        ShutdownOutputAtEnd -> socket.shutdownOutput()
        ShutdownServerAfterResponse -> shutdown()
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
    val socket =
      sslSocketFactory.createSocket(
        raw,
        raw.inetAddress.hostAddress,
        raw.port,
        true,
      ) as SSLSocket
    try {
      socket.startHandshake() // we're testing a handshake failure
      throw AssertionError()
    } catch (expected: IOException) {
    }
    socket.close()
  }

  @Throws(InterruptedException::class)
  private fun dispatchBookkeepingRequest(
    sequenceNumber: Int,
    socket: Socket,
  ) {
    val request =
      RecordedRequest(
        "",
        headersOf(),
        emptyList(),
        0L,
        Buffer(),
        sequenceNumber,
        socket,
      )
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
    sequenceNumber: Int,
  ): RecordedRequest {
    var request = ""
    val headers = Headers.Builder()
    var contentLength = -1L
    var chunked = false
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
        val lowercaseHeader = header.lowercase(Locale.US)
        if (contentLength == -1L && lowercaseHeader.startsWith("content-length:")) {
          contentLength = header.substring(15).trim().toLong()
        }
        if (lowercaseHeader.startsWith("transfer-encoding:") &&
          lowercaseHeader.substring(18).trim() == "chunked"
        ) {
          chunked = true
        }
      }

      val peek = dispatcher.peek()
      for (response in peek.informationalResponses) {
        writeHttpResponse(socket, sink, response)
      }

      var hasBody = false
      val policy = dispatcher.peek()
      val requestBodySink =
        requestBody
          .withThrottlingAndSocketPolicy(
            policy = policy,
            disconnectHalfway = policy.socketPolicy == DisconnectDuringRequestBody,
            expectedByteCount = contentLength,
            socket = socket,
          ).buffer()
      requestBodySink.use {
        when {
          policy.socketPolicy is DoNotReadRequestBody -> {
            // Ignore the body completely.
          }

          contentLength != -1L -> {
            hasBody = contentLength > 0L
            requestBodySink.write(source, contentLength)
          }

          chunked -> {
            hasBody = true
            while (true) {
              val chunkSize = source.readUtf8LineStrict().trim().toInt(16)
              if (chunkSize == 0) {
                readEmptyLine(source)
                break
              }
              chunkSizes.add(chunkSize)
              requestBodySink.write(source, chunkSize.toLong())
              readEmptyLine(source)
            }
          }

          else -> Unit // No request body.
        }
      }

      val method = request.substringBefore(' ')
      require(!hasBody || HttpMethod.permitsRequestBody(method)) {
        "Request must not have a body: $request"
      }
    } catch (e: IOException) {
      failure = e
    }

    return RecordedRequest(
      requestLine = request,
      headers = headers.build(),
      chunkSizes = chunkSizes,
      bodySize = requestBody.receivedByteCount,
      body = requestBody.buffer,
      sequenceNumber = sequenceNumber,
      socket = socket,
      failure = failure,
    )
  }

  @Throws(IOException::class)
  private fun handleWebSocketUpgrade(
    socket: Socket,
    source: BufferedSource,
    sink: BufferedSink,
    request: RecordedRequest,
    response: MockResponse,
  ) {
    val key = request.headers["Sec-WebSocket-Key"]
    val webSocketResponse =
      response
        .newBuilder()
        .setHeader("Sec-WebSocket-Accept", WebSocketProtocol.acceptHeader(key!!))
        .build()
    writeHttpResponse(socket, sink, webSocketResponse)

    // Adapt the request and response into our Request and Response domain model.
    val scheme = if (request.handshake != null) "https" else "http"
    val authority = request.headers["Host"] // Has host and port.
    val fancyRequest =
      Request
        .Builder()
        .url("$scheme://$authority/")
        .headers(request.headers)
        .build()
    val fancyResponse =
      Response
        .Builder()
        .code(webSocketResponse.code)
        .message(webSocketResponse.message)
        .headers(webSocketResponse.headers)
        .request(fancyRequest)
        .protocol(Protocol.HTTP_1_1)
        .build()

    val connectionClose = CountDownLatch(1)
    val streams =
      object : RealWebSocket.Streams(false, source, sink) {
        override fun close() = connectionClose.countDown()

        override fun cancel() {
          socket.closeQuietly()
        }
      }
    val webSocket =
      RealWebSocket(
        taskRunner = taskRunner,
        originalRequest = fancyRequest,
        listener = webSocketResponse.webSocketListener!!,
        random = SecureRandom(),
        pingIntervalMillis = 0,
        extensions = WebSocketExtensions.parse(webSocketResponse.headers),
        // Compress all messages if compression is enabled.
        minimumDeflateSize = 0L,
        webSocketCloseTimeout = RealWebSocket.CANCEL_AFTER_CLOSE_MILLIS,
      )
    val name = "MockWebServer WebSocket ${request.path!!}"
    webSocket.initReaderAndWriter(name, streams)
    try {
      webSocket.loopReader(fancyResponse)

      // Even if messages are no longer being read we need to wait for the connection close signal.
      connectionClose.await()
    } finally {
      source.closeQuietly()
    }
  }

  @Throws(IOException::class)
  private fun writeHttpResponse(
    socket: Socket,
    sink: BufferedSink,
    response: MockResponse,
  ) {
    sleepNanos(response.headersDelayNanos)
    sink.writeUtf8(response.status)
    sink.writeUtf8("\r\n")

    writeHeaders(sink, response.headers)

    val body = response.body ?: return
    sleepNanos(response.bodyDelayNanos)
    val responseBodySink =
      sink
        .withThrottlingAndSocketPolicy(
          policy = response,
          disconnectHalfway = response.socketPolicy == DisconnectDuringResponseBody,
          expectedByteCount = body.contentLength,
          socket = socket,
        ).buffer()
    body.writeTo(responseBodySink)
    responseBodySink.emit()

    if ("chunked".equals(response.headers["Transfer-Encoding"], ignoreCase = true)) {
      writeHeaders(sink, response.trailers)
    }
  }

  @Throws(IOException::class)
  private fun writeHeaders(
    sink: BufferedSink,
    headers: Headers,
  ) {
    for ((name, value) in headers) {
      sink.writeUtf8(name)
      sink.writeUtf8(": ")
      sink.writeUtf8(value)
      sink.writeUtf8("\r\n")
    }
    sink.writeUtf8("\r\n")
    sink.flush()
  }

  /** Returns a sink that applies throttling and disconnecting. */
  private fun Sink.withThrottlingAndSocketPolicy(
    policy: MockResponse,
    disconnectHalfway: Boolean,
    expectedByteCount: Long,
    socket: Socket,
  ): Sink {
    var result: Sink = this

    if (policy.throttlePeriodNanos > 0L) {
      result =
        ThrottledSink(
          delegate = result,
          bytesPerPeriod = policy.throttleBytesPerPeriod,
          periodDelayNanos = policy.throttlePeriodNanos,
        )
    }

    if (disconnectHalfway) {
      val halfwayByteCount =
        when {
          expectedByteCount != -1L -> expectedByteCount / 2
          else -> 0L
        }
      result =
        TriggerSink(
          delegate = result,
          triggerByteCount = halfwayByteCount,
        ) {
          result.flush()
          socket.close()
        }
    }

    return result
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
  private class TruncatingBuffer(
    private var remainingByteCount: Long,
  ) : Sink {
    val buffer = Buffer()
    var receivedByteCount = 0L

    @Throws(IOException::class)
    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
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
    private val protocol: Protocol,
  ) : Http2Connection.Listener() {
    private val sequenceNumber = AtomicInteger()

    @Throws(IOException::class)
    override fun onStream(stream: Http2Stream) {
      val peekedResponse = dispatcher.peek()
      if (peekedResponse.socketPolicy is ResetStreamAtStart) {
        dispatchBookkeepingRequest(sequenceNumber.getAndIncrement(), socket)
        stream.close(ErrorCode.fromHttp2(peekedResponse.socketPolicy.http2ErrorCode)!!, null)
        return
      }

      val request = readRequest(stream)
      atomicRequestCount.incrementAndGet()
      requestQueue.add(request)
      if (request.failure != null) {
        return // Nothing to respond to.
      }

      val response: MockResponse = dispatcher.dispatch(request)

      val socketPolicy = response.socketPolicy
      if (socketPolicy === DisconnectAfterRequest) {
        socket.close()
        return
      }
      writeResponse(stream, request, response)
      if (logger.isLoggable(Level.FINE)) {
        logger.fine(
          "${this@MockWebServer} received request: $request " +
            "and responded: $response protocol is $protocol",
        )
      }

      when (socketPolicy) {
        DisconnectAtEnd -> {
          stream.connection.shutdown(ErrorCode.NO_ERROR)
        }
        is DoNotReadRequestBody -> {
          stream.close(ErrorCode.fromHttp2(socketPolicy.http2ErrorCode)!!, null)
        }
        else -> {
        }
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
      for (response in peek.informationalResponses) {
        sleepNanos(response.headersDelayNanos)
        stream.writeHeaders(response.toHttp2Headers(), outFinished = false, flushHeaders = true)
        if (response.code == 100) {
          readBody = true
        }
      }

      val body = Buffer()
      val requestLine = "$method $path HTTP/1.1"
      var exception: IOException? = null
      if (readBody && peek.streamHandler == null && peek.socketPolicy !is DoNotReadRequestBody) {
        try {
          val contentLengthString = headers["content-length"]
          val requestBodySink =
            body
              .withThrottlingAndSocketPolicy(
                policy = peek,
                disconnectHalfway = peek.socketPolicy == DisconnectDuringRequestBody,
                expectedByteCount = contentLengthString?.toLong() ?: Long.MAX_VALUE,
                socket = socket,
              ).buffer()
          requestBodySink.use {
            it.writeAll(stream.getSource())
          }
        } catch (e: IOException) {
          exception = e
        }
      }

      return RecordedRequest(
        requestLine = requestLine,
        headers = headers,
        chunkSizes = emptyList(),
        bodySize = body.size,
        body = body,
        sequenceNumber = sequenceNumber.getAndIncrement(),
        socket = socket,
        failure = exception,
      )
    }

    private fun MockResponse.toHttp2Headers(): List<Header> {
      val result = mutableListOf<Header>()
      result += Header(Header.RESPONSE_STATUS, code.toString())
      for ((name, value) in headers) {
        result += Header(name, value)
      }
      return result
    }

    @Throws(IOException::class)
    private fun writeResponse(
      stream: Http2Stream,
      request: RecordedRequest,
      response: MockResponse,
    ) {
      val settings = response.settings
      stream.connection.setSettings(settings)

      if (response.socketPolicy === NoResponse) {
        return
      }

      val bodyDelayNanos = response.bodyDelayNanos
      val trailers = response.trailers
      val body = response.body
      val streamHandler = response.streamHandler
      val outFinished = (
        body == null &&
          response.pushPromises.isEmpty() &&
          streamHandler == null
      )
      val flushHeaders = body == null || bodyDelayNanos != 0L
      require(!outFinished || trailers.size == 0) {
        "unsupported: no body and non-empty trailers $trailers"
      }

      sleepNanos(response.headersDelayNanos)
      stream.writeHeaders(response.toHttp2Headers(), outFinished, flushHeaders)

      if (trailers.size > 0) {
        stream.enqueueTrailers(trailers)
      }
      pushPromises(stream, request, response.pushPromises)
      if (body != null) {
        sleepNanos(bodyDelayNanos)
        val responseBodySink =
          stream
            .getSink()
            .withThrottlingAndSocketPolicy(
              policy = response,
              disconnectHalfway = response.socketPolicy == DisconnectDuringResponseBody,
              expectedByteCount = body.contentLength,
              socket = socket,
            ).buffer()
        responseBodySink.use {
          body.writeTo(responseBodySink)
        }
      } else if (streamHandler != null) {
        streamHandler.handle(RealStream(stream))
      } else if (!outFinished) {
        stream.close(ErrorCode.NO_ERROR, null)
      }
    }

    @Throws(IOException::class)
    private fun pushPromises(
      stream: Http2Stream,
      request: RecordedRequest,
      promises: List<PushPromise>,
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
        requestQueue.add(
          RecordedRequest(
            requestLine = requestLine,
            headers = pushPromise.headers,
            chunkSizes = chunkSizes,
            bodySize = 0,
            body = Buffer(),
            sequenceNumber = sequenceNumber.getAndIncrement(),
            socket = socket,
          ),
        )
        val hasBody = pushPromise.response.body != null
        val pushedStream = stream.connection.pushStream(stream.id, pushedHeaders, hasBody)
        writeResponse(pushedStream, request, pushPromise.response)
      }
    }
  }

  @ExperimentalOkHttpApi
  companion object {
    private const val CLIENT_AUTH_NONE = 0
    private const val CLIENT_AUTH_REQUESTED = 1
    private const val CLIENT_AUTH_REQUIRED = 2

    private val UNTRUSTED_TRUST_MANAGER =
      object : X509TrustManager {
        @Throws(CertificateException::class)
        override fun checkClientTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) = throw CertificateException()

        override fun checkServerTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) = throw AssertionError()

        override fun getAcceptedIssuers(): Array<X509Certificate> = throw AssertionError()
      }

    private val logger = Logger.getLogger(MockWebServer::class.java.name)
  }
}
