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
@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
  "ktlint:standard:property-naming",
)

package mockwebserver3

import java.util.concurrent.TimeUnit
import mockwebserver3.SocketPolicy.KeepOpen
import mockwebserver3.internal.toMockResponseBody
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.WebSocketListener
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.http2.Settings
import okio.Buffer

/** A scripted response to be replayed by the mock web server. */
public class MockResponse {
  /** Returns the HTTP response line, such as "HTTP/1.1 200 OK". */
  public val status: String

  public val code: Int
    get() {
      val statusParts = status.split(' ', limit = 3)
      require(statusParts.size >= 2) { "Unexpected status: $status" }
      return statusParts[1].toInt()
    }

  public val message: String
    get() {
      val statusParts = status.split(' ', limit = 3)
      require(statusParts.size >= 2) { "Unexpected status: $status" }
      return statusParts[2]
    }

  public val headers: Headers
  public val trailers: Headers

  // At most one of (body,webSocketListener,streamHandler) is non-null.
  public val body: MockResponseBody?
  public val webSocketListener: WebSocketListener?
  public val socketHandler: SocketHandler?

  public val inTunnel: Boolean
  public val informationalResponses: List<MockResponse>

  public val throttleBytesPerPeriod: Long
  public val throttlePeriodNanos: Long

  public val socketPolicy: SocketPolicy

  public val headersDelayNanos: Long
  public val bodyDelayNanos: Long
  public val trailersDelayNanos: Long

  /** The streams the server will push with this response. */
  public val pushPromises: List<PushPromise>

  public val settings: Settings

  public constructor(
    code: Int = 200,
    headers: Headers = headersOf(),
    body: String = "",
    socketPolicy: SocketPolicy = KeepOpen,
  ) : this(
    Builder()
      .code(code)
      .headers(headers)
      .body(body)
      .socketPolicy(socketPolicy),
  )

  private constructor(builder: Builder) {
    this.status = builder.status
    this.headers = builder.headers
    this.trailers = builder.trailers
    this.body = builder.body
    this.socketHandler = builder.socketHandler
    this.webSocketListener = builder.webSocketListener
    this.inTunnel = builder.inTunnel
    this.informationalResponses = builder.informationalResponses
    this.throttleBytesPerPeriod = builder.throttleBytesPerPeriod
    this.throttlePeriodNanos = builder.throttlePeriodNanos
    this.socketPolicy = builder.socketPolicy
    this.headersDelayNanos = builder.headersDelayNanos
    this.bodyDelayNanos = builder.bodyDelayNanos
    this.trailersDelayNanos = builder.trailersDelayNanos
    this.pushPromises = builder.pushPromises
    this.settings = builder.settings
  }

  public fun newBuilder(): Builder = Builder(this)

  public override fun toString(): String = status

  public class Builder : Cloneable {
    public var inTunnel: Boolean
      private set

    private val informationalResponses_: MutableList<MockResponse>
    public val informationalResponses: List<MockResponse>
      get() = informationalResponses_.toList()

    public var status: String
      private set

    public var code: Int
      get() {
        val statusParts = status.split(' ', limit = 3)
        require(statusParts.size >= 2) { "Unexpected status: $status" }
        return statusParts[1].toInt()
      }
      private set(value) {
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

    private var headers_: Headers.Builder
    public val headers: Headers
      get() = headers_.build()

    private var trailers_: Headers.Builder
    public val trailers: Headers
      get() = trailers_.build()

    // At most one of (body,webSocketListener,socketHandler) is non-null.
    private var bodyVar: MockResponseBody? = null
    private var socketHandlerVar: SocketHandler? = null
    private var webSocketListenerVar: WebSocketListener? = null

    public var body: MockResponseBody?
      get() = bodyVar
      private set(value) {
        bodyVar = value
        socketHandlerVar = null
        webSocketListenerVar = null
      }
    public var socketHandler: SocketHandler?
      get() = socketHandlerVar
      private set(value) {
        socketHandlerVar = value
        bodyVar = null
        webSocketListenerVar = null
      }
    public var webSocketListener: WebSocketListener?
      get() = webSocketListenerVar
      private set(value) {
        webSocketListenerVar = value
        bodyVar = null
        socketHandlerVar = null
      }

    public var throttleBytesPerPeriod: Long
      private set
    public var throttlePeriodNanos: Long
      private set

    public var socketPolicy: SocketPolicy
      private set

    public var headersDelayNanos: Long
      private set
    public var bodyDelayNanos: Long
      private set
    public var trailersDelayNanos: Long
      private set

    private val pushPromises_: MutableList<PushPromise>
    public val pushPromises: List<PushPromise>
      get() = pushPromises_.toList()

    private val settings_: Settings
    public val settings: Settings
      get() = Settings().apply { merge(settings_) }

    public constructor() {
      this.inTunnel = false
      this.informationalResponses_ = mutableListOf()
      this.status = "HTTP/1.1 200 OK"
      this.bodyVar = null
      this.socketHandlerVar = null
      this.webSocketListenerVar = null
      this.headers_ =
        Headers
          .Builder()
          .add("Content-Length", "0")
      this.trailers_ = Headers.Builder()
      this.throttleBytesPerPeriod = Long.MAX_VALUE
      this.throttlePeriodNanos = 0L
      this.socketPolicy = KeepOpen
      this.headersDelayNanos = 0L
      this.bodyDelayNanos = 0L
      this.trailersDelayNanos = 0L
      this.pushPromises_ = mutableListOf()
      this.settings_ = Settings()
    }

    internal constructor(mockResponse: MockResponse) {
      this.inTunnel = mockResponse.inTunnel
      this.informationalResponses_ = mockResponse.informationalResponses.toMutableList()
      this.status = mockResponse.status
      this.headers_ = mockResponse.headers.newBuilder()
      this.trailers_ = mockResponse.trailers.newBuilder()
      this.bodyVar = mockResponse.body
      this.socketHandlerVar = mockResponse.socketHandler
      this.webSocketListenerVar = mockResponse.webSocketListener
      this.throttleBytesPerPeriod = mockResponse.throttleBytesPerPeriod
      this.throttlePeriodNanos = mockResponse.throttlePeriodNanos
      this.socketPolicy = mockResponse.socketPolicy
      this.headersDelayNanos = mockResponse.headersDelayNanos
      this.bodyDelayNanos = mockResponse.bodyDelayNanos
      this.trailersDelayNanos = mockResponse.trailersDelayNanos
      this.pushPromises_ = mockResponse.pushPromises.toMutableList()
      this.settings_ =
        Settings().apply {
          merge(mockResponse.settings)
        }
    }

    public fun code(code: Int): Builder =
      apply {
        this.code = code
      }

    /** Sets the status and returns this. */
    public fun status(status: String): Builder =
      apply {
        this.status = status
      }

    /**
     * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
     * were added by default.
     */
    public fun clearHeaders(): Builder =
      apply {
        headers_ = Headers.Builder()
      }

    /**
     * Adds [header] as an HTTP header. For well-formed HTTP [header] should contain a name followed
     * by a colon and a value.
     */
    public fun addHeader(header: String): Builder =
      apply {
        headers_.add(header)
      }

    /**
     * Adds a new header with the name and value. This may be used to add multiple headers with the
     * same name.
     */
    public fun addHeader(
      name: String,
      value: Any,
    ): Builder =
      apply {
        headers_.add(name, value.toString())
      }

    /**
     * Adds a new header with the name and value. This may be used to add multiple headers with the
     * same name. Unlike [addHeader] this does not validate the name and
     * value.
     */
    public fun addHeaderLenient(
      name: String,
      value: Any,
    ): Builder =
      apply {
        addHeaderLenient(headers_, name, value.toString())
      }

    /** Removes all headers named [name], then adds a new header with the name and value. */
    public fun setHeader(
      name: String,
      value: Any,
    ): Builder =
      apply {
        removeHeader(name)
        addHeader(name, value)
      }

    /** Removes all headers named [name]. */
    public fun removeHeader(name: String): Builder =
      apply {
        headers_.removeAll(name)
      }

    public fun body(body: Buffer): Builder = body(body.toMockResponseBody())

    public fun body(body: MockResponseBody): Builder =
      apply {
        setHeader("Content-Length", body.contentLength)
        this.body = body
      }

    /** Sets the response body to the UTF-8 encoded bytes of [body]. */
    public fun body(body: String): Builder = body(Buffer().writeUtf8(body))

    public fun socketHandler(socketHandler: SocketHandler): Builder =
      apply {
        this.socketHandler = socketHandler
      }

    /**
     * Sets the response body to [body], chunked every [maxChunkSize] bytes.
     */
    public fun chunkedBody(
      body: Buffer,
      maxChunkSize: Int = Int.MAX_VALUE,
    ): Builder =
      apply {
        removeHeader("Content-Length")
        headers_.add("Transfer-encoding: chunked")

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
    public fun chunkedBody(
      body: String,
      maxChunkSize: Int = Int.MAX_VALUE,
    ): Builder = chunkedBody(Buffer().writeUtf8(body), maxChunkSize)

    /** Sets the headers and returns this. */
    public fun headers(headers: Headers): Builder =
      apply {
        this.headers_ = headers.newBuilder()
      }

    /** Sets the trailers and returns this. */
    public fun trailers(trailers: Headers): Builder =
      apply {
        this.trailers_ = trailers.newBuilder()
      }

    /** Sets the socket policy and returns this. */
    public fun socketPolicy(socketPolicy: SocketPolicy): Builder =
      apply {
        this.socketPolicy = socketPolicy
      }

    /**
     * Throttles the request reader and response writer to sleep for the given period after each
     * series of [bytesPerPeriod] bytes are transferred. Use this to simulate network behavior.
     */
    public fun throttleBody(
      bytesPerPeriod: Long,
      period: Long,
      unit: TimeUnit,
    ): Builder =
      apply {
        throttleBytesPerPeriod = bytesPerPeriod
        throttlePeriodNanos = unit.toNanos(period)
      }

    public fun headersDelay(
      delay: Long,
      unit: TimeUnit,
    ): Builder =
      apply {
        headersDelayNanos = unit.toNanos(delay)
      }

    /**
     * Set the delayed time of the response body to [delay]. This applies to the response body
     * only; response headers are not affected.
     */
    public fun bodyDelay(
      delay: Long,
      unit: TimeUnit,
    ): Builder =
      apply {
        bodyDelayNanos = unit.toNanos(delay)
      }

    public fun trailersDelay(
      delay: Long,
      unit: TimeUnit,
    ): Builder =
      apply {
        trailersDelayNanos = unit.toNanos(delay)
      }

    /**
     * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this attaches a
     * pushed stream to this response.
     */
    public fun addPush(promise: PushPromise): Builder =
      apply {
        this.pushPromises_ += promise
      }

    /**
     * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this pushes
     * [settings] before writing the response.
     */
    public fun settings(settings: Settings): Builder =
      apply {
        this.settings_.clear()
        this.settings_.merge(settings)
      }

    /**
     * Attempts to perform a web socket upgrade on the connection.
     * This will overwrite any previously set status, body, or streamHandler.
     */
    public fun webSocketUpgrade(listener: WebSocketListener): Builder =
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
    public fun inTunnel(): Builder =
      apply {
        removeHeader("Content-Length")
        inTunnel = true
      }

    /**
     * Adds an HTTP 1xx response to precede this response. Note that this response's
     * [headers delay][headersDelay] applies after this response is transmitted. Set a
     * headers delay on that response to delay its transmission.
     */
    public fun addInformationalResponse(response: MockResponse): Builder =
      apply {
        informationalResponses_ += response
      }

    public fun add100Continue(): Builder =
      apply {
        addInformationalResponse(MockResponse(code = 100))
      }

    public override fun clone(): Builder = build().newBuilder()

    public fun build(): MockResponse = MockResponse(this)
  }
}
