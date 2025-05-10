/*
 * Copyright (C) 2011 Google Inc.
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

import java.util.concurrent.TimeUnit
import mockwebserver3.SocketPolicy.KeepOpen
import mockwebserver3.internal.toMockResponseBody
import okhttp3.ExperimentalOkHttpApi
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.WebSocketListener
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.http2.Settings
import okio.Buffer

/** A scripted response to be replayed by the mock web server. */
@ExperimentalOkHttpApi
class MockResponse {
  /** Returns the HTTP response line, such as "HTTP/1.1 200 OK". */
  val status: String

  val code: Int
    get() {
      val statusParts = status.split(' ', limit = 3)
      require(statusParts.size >= 2) { "Unexpected status: $status" }
      return statusParts[1].toInt()
    }

  val message: String
    get() {
      val statusParts = status.split(' ', limit = 3)
      require(statusParts.size >= 2) { "Unexpected status: $status" }
      return statusParts[2]
    }

  val headers: Headers
  val trailers: Headers

  // At most one of (body,webSocketListener,streamHandler) is non-null.
  val body: MockResponseBody?
  val webSocketListener: WebSocketListener?
  val streamHandler: StreamHandler?

  val inTunnel: Boolean
  val informationalResponses: List<MockResponse>

  val throttleBytesPerPeriod: Long
  val throttlePeriodNanos: Long

  val socketPolicy: SocketPolicy

  val bodyDelayNanos: Long
  val headersDelayNanos: Long

  val pushPromises: List<PushPromise>

  val settings: Settings

  @JvmOverloads
  constructor(
    code: Int = 200,
    headers: Headers = headersOf(),
    body: String = "",
    inTunnel: Boolean = false,
    socketPolicy: SocketPolicy = KeepOpen,
  ) : this(
    Builder()
      .apply {
        this.code = code
        this.headers.addAll(headers)
        if (inTunnel) inTunnel()
        this.body(body)
        this.socketPolicy = socketPolicy
      },
  )

  private constructor(builder: Builder) {
    this.status = builder.status
    this.headers = builder.headers.build()
    this.trailers = builder.trailers.build()
    this.body = builder.body
    this.streamHandler = builder.streamHandler
    this.webSocketListener = builder.webSocketListener
    this.inTunnel = builder.inTunnel
    this.informationalResponses = builder.informationalResponses.toList()
    this.throttleBytesPerPeriod = builder.throttleBytesPerPeriod
    this.throttlePeriodNanos = builder.throttlePeriodNanos
    this.socketPolicy = builder.socketPolicy
    this.bodyDelayNanos = builder.bodyDelayNanos
    this.headersDelayNanos = builder.headersDelayNanos
    this.pushPromises = builder.pushPromises.toList()
    this.settings =
      Settings().apply {
        merge(builder.settings)
      }
  }

  fun newBuilder(): Builder = Builder(this)

  override fun toString(): String = status

  @ExperimentalOkHttpApi
  class Builder : Cloneable {
    var inTunnel: Boolean
      internal set

    val informationalResponses: MutableList<MockResponse>

    var status: String

    var code: Int
      get() {
        val statusParts = status.split(' ', limit = 3)
        require(statusParts.size >= 2) { "Unexpected status: $status" }
        return statusParts[1].toInt()
      }
      set(value) {
        val reason =
          when (value) {
            in 100..199 -> "Informational"
            in 200..299 -> "OK"
            in 300..399 -> "Redirection"
            in 400..499 -> "Client Error"
            in 500..599 -> "Server Error"
            else -> "Mock Response"
          }
        status = "HTTP/1.1 $value $reason"
      }

    internal var headers: Headers.Builder

    internal var trailers: Headers.Builder

    // At most one of (body,webSocketListener,streamHandler) is non-null.
    private var bodyVar: MockResponseBody? = null
    private var streamHandlerVar: StreamHandler? = null
    private var webSocketListenerVar: WebSocketListener? = null

    var body: MockResponseBody?
      get() = bodyVar
      set(value) {
        bodyVar = value
        streamHandlerVar = null
        webSocketListenerVar = null
      }
    var streamHandler: StreamHandler?
      get() = streamHandlerVar
      set(value) {
        streamHandlerVar = value
        bodyVar = null
        webSocketListenerVar = null
      }
    var webSocketListener: WebSocketListener?
      get() = webSocketListenerVar
      set(value) {
        webSocketListenerVar = value
        bodyVar = null
        streamHandlerVar = null
      }

    var throttleBytesPerPeriod: Long
      private set
    internal var throttlePeriodNanos: Long

    var socketPolicy: SocketPolicy

    internal var bodyDelayNanos: Long

    internal var headersDelayNanos: Long

    /** The streams the server will push with this response. */
    val pushPromises: MutableList<PushPromise>

    val settings: Settings

    constructor() {
      this.inTunnel = false
      this.informationalResponses = mutableListOf()
      this.status = "HTTP/1.1 200 OK"
      this.bodyVar = null
      this.streamHandlerVar = null
      this.webSocketListenerVar = null
      this.headers =
        Headers
          .Builder()
          .add("Content-Length", "0")
      this.trailers = Headers.Builder()
      this.throttleBytesPerPeriod = Long.MAX_VALUE
      this.throttlePeriodNanos = 0L
      this.socketPolicy = KeepOpen
      this.bodyDelayNanos = 0L
      this.headersDelayNanos = 0L
      this.pushPromises = mutableListOf()
      this.settings = Settings()
    }

    internal constructor(mockResponse: MockResponse) {
      this.inTunnel = mockResponse.inTunnel
      this.informationalResponses = mockResponse.informationalResponses.toMutableList()
      this.status = mockResponse.status
      this.headers = mockResponse.headers.newBuilder()
      this.trailers = mockResponse.trailers.newBuilder()
      this.bodyVar = mockResponse.body
      this.streamHandlerVar = mockResponse.streamHandler
      this.webSocketListenerVar = mockResponse.webSocketListener
      this.throttleBytesPerPeriod = mockResponse.throttleBytesPerPeriod
      this.throttlePeriodNanos = mockResponse.throttlePeriodNanos
      this.socketPolicy = mockResponse.socketPolicy
      this.bodyDelayNanos = mockResponse.bodyDelayNanos
      this.headersDelayNanos = mockResponse.headersDelayNanos
      this.pushPromises = mockResponse.pushPromises.toMutableList()
      this.settings =
        Settings().apply {
          merge(mockResponse.settings)
        }
    }

    fun code(code: Int) =
      apply {
        this.code = code
      }

    /** Sets the status and returns this. */
    fun status(status: String) =
      apply {
        this.status = status
      }

    /**
     * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
     * were added by default.
     */
    fun clearHeaders() =
      apply {
        headers = Headers.Builder()
      }

    /**
     * Adds [header] as an HTTP header. For well-formed HTTP [header] should contain a name followed
     * by a colon and a value.
     */
    fun addHeader(header: String) =
      apply {
        headers.add(header)
      }

    /**
     * Adds a new header with the name and value. This may be used to add multiple headers with the
     * same name.
     */
    fun addHeader(
      name: String,
      value: Any,
    ) = apply {
      headers.add(name, value.toString())
    }

    /**
     * Adds a new header with the name and value. This may be used to add multiple headers with the
     * same name. Unlike [addHeader] this does not validate the name and
     * value.
     */
    fun addHeaderLenient(
      name: String,
      value: Any,
    ) = apply {
      addHeaderLenient(headers, name, value.toString())
    }

    /** Removes all headers named [name], then adds a new header with the name and value. */
    fun setHeader(
      name: String,
      value: Any,
    ) = apply {
      removeHeader(name)
      addHeader(name, value)
    }

    /** Removes all headers named [name]. */
    fun removeHeader(name: String) =
      apply {
        headers.removeAll(name)
      }

    fun body(body: Buffer) = body(body.toMockResponseBody())

    fun body(body: MockResponseBody) =
      apply {
        setHeader("Content-Length", body.contentLength)
        this.body = body
      }

    /** Sets the response body to the UTF-8 encoded bytes of [body]. */
    fun body(body: String): Builder = body(Buffer().writeUtf8(body))

    fun streamHandler(streamHandler: StreamHandler) =
      apply {
        this.streamHandler = streamHandler
      }

    /**
     * Sets the response body to [body], chunked every [maxChunkSize] bytes.
     */
    fun chunkedBody(
      body: Buffer,
      maxChunkSize: Int,
    ) = apply {
      removeHeader("Content-Length")
      headers.add(CHUNKED_BODY_HEADER)

      val bytesOut = Buffer()
      while (!body.exhausted()) {
        val chunkSize = minOf(body.size, maxChunkSize.toLong())
        bytesOut.writeHexadecimalUnsignedLong(chunkSize)
        bytesOut.writeUtf8("\r\n")
        bytesOut.write(body, chunkSize)
        bytesOut.writeUtf8("\r\n")
      }
      bytesOut.writeUtf8("0\r\n") // Last chunk. Trailers follow!
      this.body = bytesOut.toMockResponseBody()
    }

    /**
     * Sets the response body to the UTF-8 encoded bytes of [body],
     * chunked every [maxChunkSize] bytes.
     */
    fun chunkedBody(
      body: String,
      maxChunkSize: Int,
    ): Builder = chunkedBody(Buffer().writeUtf8(body), maxChunkSize)

    /** Sets the headers and returns this. */
    fun headers(headers: Headers) =
      apply {
        this.headers = headers.newBuilder()
      }

    /** Sets the trailers and returns this. */
    fun trailers(trailers: Headers) =
      apply {
        this.trailers = trailers.newBuilder()
      }

    /** Sets the socket policy and returns this. */
    fun socketPolicy(socketPolicy: SocketPolicy) =
      apply {
        this.socketPolicy = socketPolicy
      }

    /**
     * Throttles the request reader and response writer to sleep for the given period after each
     * series of [bytesPerPeriod] bytes are transferred. Use this to simulate network behavior.
     */
    fun throttleBody(
      bytesPerPeriod: Long,
      period: Long,
      unit: TimeUnit,
    ) = apply {
      throttleBytesPerPeriod = bytesPerPeriod
      throttlePeriodNanos = unit.toNanos(period)
    }

    /**
     * Set the delayed time of the response body to [delay]. This applies to the response body
     * only; response headers are not affected.
     */
    fun bodyDelay(
      delay: Long,
      unit: TimeUnit,
    ) = apply {
      bodyDelayNanos = unit.toNanos(delay)
    }

    fun headersDelay(
      delay: Long,
      unit: TimeUnit,
    ) = apply {
      headersDelayNanos = unit.toNanos(delay)
    }

    /**
     * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this attaches a
     * pushed stream to this response.
     */
    fun addPush(promise: PushPromise) =
      apply {
        this.pushPromises += promise
      }

    /**
     * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this pushes
     * [settings] before writing the response.
     */
    fun settings(settings: Settings) =
      apply {
        this.settings.clear()
        this.settings.merge(settings)
      }

    /**
     * Attempts to perform a web socket upgrade on the connection.
     * This will overwrite any previously set status, body, or streamHandler.
     */
    fun webSocketUpgrade(listener: WebSocketListener) =
      apply {
        status = "HTTP/1.1 101 Switching Protocols"
        setHeader("Connection", "Upgrade")
        setHeader("Upgrade", "websocket")
        webSocketListener = listener
      }

    /**
     * Configures this response to be served as a response to an HTTP CONNECT request, either for
     * doing HTTPS through an HTTP proxy, or HTTP/2 prior knowledge through an HTTP proxy.
     *
     * When a new connection is received, all in-tunnel responses are served before the connection is
     * upgraded to HTTPS or HTTP/2.
     */
    fun inTunnel() =
      apply {
        removeHeader("Content-Length")
        inTunnel = true
      }

    /**
     * Adds an HTTP 1xx response to precede this response. Note that this response's
     * [headers delay][headersDelay] applies after this response is transmitted. Set a
     * headers delay on that response to delay its transmission.
     */
    fun addInformationalResponse(response: MockResponse) =
      apply {
        informationalResponses += response
      }

    fun add100Continue() =
      apply {
        addInformationalResponse(MockResponse(code = 100))
      }

    public override fun clone(): Builder = build().newBuilder()

    fun build(): MockResponse = MockResponse(this)
  }

  @ExperimentalOkHttpApi
  companion object {
    private const val CHUNKED_BODY_HEADER = "Transfer-encoding: chunked"
  }
}
