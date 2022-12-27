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
import mockwebserver3.internal.duplex.DuplexResponseBody
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.WebSocketListener
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.http2.Settings
import okio.Buffer

/** A scripted response to be replayed by the mock web server. */
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

  private val body: Buffer?

  val inTunnel: Boolean
  val informationalResponses: List<MockResponse>

  val throttleBytesPerPeriod: Long
  private val throttlePeriodAmount: Long
  private val throttlePeriodUnit: TimeUnit

  val socketPolicy: SocketPolicy

  /**
   * Sets the [HTTP/2 error code](https://tools.ietf.org/html/rfc7540#section-7) to be
   * returned when resetting the stream. This is only valid with
   * [SocketPolicy.RESET_STREAM_AT_START] and [SocketPolicy.DO_NOT_READ_REQUEST_BODY].
   */
  val http2ErrorCode: Int

  private val bodyDelayAmount: Long
  private val bodyDelayUnit: TimeUnit

  private val headersDelayAmount: Long
  private var headersDelayUnit: TimeUnit

  val pushPromises: List<PushPromise>

  val settings: Settings

  val webSocketListener: WebSocketListener?
  val duplexResponseBody: DuplexResponseBody?

  @JvmOverloads
  constructor(
    code: Int = 200,
    headers: Headers = headersOf(),
    body: String = "",
  ) : this(Builder()
    .apply {
      this.code = code
      this.headers.addAll(headers)
      this.setBody(body)
    }
  )

  private constructor(builder: Builder) {
    this.status = builder.status
    this.headers = builder.headers.build()
    this.trailers = builder.trailers.build()
    this.body = builder.body?.clone()
    this.inTunnel = builder.inTunnel
    this.informationalResponses = builder.informationalResponses.toList()
    this.throttleBytesPerPeriod = builder.throttleBytesPerPeriod
    this.throttlePeriodAmount = builder.throttlePeriodAmount
    this.throttlePeriodUnit = builder.throttlePeriodUnit
    this.socketPolicy = builder.socketPolicy
    this.http2ErrorCode = builder.http2ErrorCode
    this.bodyDelayAmount = builder.bodyDelayAmount
    this.bodyDelayUnit = builder.bodyDelayUnit
    this.headersDelayAmount = builder.headersDelayAmount
    this.headersDelayUnit = builder.headersDelayUnit
    this.pushPromises = builder.pushPromises.toList()
    this.settings = Settings().apply {
      merge(builder.settings)
    }
    this.webSocketListener = builder.webSocketListener
    this.duplexResponseBody = builder.duplexResponseBody
  }

  /** Returns a copy of the raw HTTP payload. */
  fun getBody(): Buffer? = body?.clone()

  val isDuplex: Boolean
    get() = duplexResponseBody != null

  fun getThrottlePeriod(unit: TimeUnit): Long =
    unit.convert(throttlePeriodAmount, throttlePeriodUnit)

  fun getBodyDelay(unit: TimeUnit): Long =
    unit.convert(bodyDelayAmount, bodyDelayUnit)

  fun getHeadersDelay(unit: TimeUnit): Long =
    unit.convert(headersDelayAmount, headersDelayUnit)

  fun newBuilder(): Builder = Builder(this)

  override fun toString(): String = status

  class Builder : Cloneable {
    var inTunnel: Boolean
      private set

    val informationalResponses: MutableList<MockResponse>

    @set:JvmName("status")
    var status: String

    var code: Int
      get() {
        val statusParts = status.split(' ', limit = 3)
        require(statusParts.size >= 2) { "Unexpected status: $status" }
        return statusParts[1].toInt()
      }
      set(value) {
        val reason = when (value) {
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

    internal var body: Buffer?

    var throttleBytesPerPeriod: Long
      private set
    internal var throttlePeriodAmount: Long
    internal var throttlePeriodUnit: TimeUnit

    @set:JvmName("socketPolicy")
    var socketPolicy: SocketPolicy

    @set:JvmName("http2ErrorCode")
    var http2ErrorCode: Int

    internal var bodyDelayAmount: Long
    internal var bodyDelayUnit: TimeUnit

    internal var headersDelayAmount: Long
    internal var headersDelayUnit: TimeUnit

    /** The streams the server will push with this response. */
    val pushPromises: MutableList<PushPromise>

    val settings: Settings
    var webSocketListener: WebSocketListener?
      private set
    var duplexResponseBody: DuplexResponseBody?
      private set

    constructor() {
      this.inTunnel = false
      this.informationalResponses = mutableListOf()
      this.status = "HTTP/1.1 200 OK"
      this.body = null
      this.headers = Headers.Builder()
        .add("Content-Length", "0")
      this.trailers = Headers.Builder()
      this.throttleBytesPerPeriod = Long.MAX_VALUE
      this.throttlePeriodAmount = 1L
      this.throttlePeriodUnit = TimeUnit.SECONDS
      this.socketPolicy = SocketPolicy.KEEP_OPEN
      this.http2ErrorCode = -1
      this.bodyDelayAmount = 0L
      this.bodyDelayUnit = TimeUnit.MILLISECONDS
      this.headersDelayAmount = 0L
      this.headersDelayUnit = TimeUnit.MILLISECONDS
      this.pushPromises = mutableListOf()
      this.settings = Settings()
      this.webSocketListener = null
      this.duplexResponseBody = null
    }

    internal constructor(mockResponse: MockResponse) {
      this.inTunnel = mockResponse.inTunnel
      this.informationalResponses = mockResponse.informationalResponses.toMutableList()
      this.status = mockResponse.status
      this.headers = mockResponse.headers.newBuilder()
      this.trailers = mockResponse.trailers.newBuilder()
      this.body = mockResponse.body
      this.throttleBytesPerPeriod = mockResponse.throttleBytesPerPeriod
      this.throttlePeriodAmount = mockResponse.throttlePeriodAmount
      this.throttlePeriodUnit = mockResponse.throttlePeriodUnit
      this.socketPolicy = mockResponse.socketPolicy
      this.http2ErrorCode = mockResponse.http2ErrorCode
      this.bodyDelayAmount = mockResponse.bodyDelayAmount
      this.bodyDelayUnit = mockResponse.bodyDelayUnit
      this.headersDelayAmount = mockResponse.headersDelayAmount
      this.headersDelayUnit = mockResponse.headersDelayUnit
      this.pushPromises = mockResponse.pushPromises.toMutableList()
      this.settings = Settings().apply {
        merge(mockResponse.settings)
      }
      this.webSocketListener = mockResponse.webSocketListener
      this.duplexResponseBody = mockResponse.duplexResponseBody
    }

    fun setResponseCode(code: Int) = apply {
      this.code = code
    }

    /** Sets the status and returns this. */
    fun setStatus(status: String) = apply {
      this.status = status
    }

    /**
     * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
     * were added by default.
     */
    fun clearHeaders() = apply {
      headers = Headers.Builder()
    }

    /**
     * Adds [header] as an HTTP header. For well-formed HTTP [header] should contain a name followed
     * by a colon and a value.
     */
    fun addHeader(header: String) = apply {
      headers.add(header)
    }

    /**
     * Adds a new header with the name and value. This may be used to add multiple headers with the
     * same name.
     */
    fun addHeader(name: String, value: Any) = apply {
      headers.add(name, value.toString())
    }

    /**
     * Adds a new header with the name and value. This may be used to add multiple headers with the
     * same name. Unlike [addHeader] this does not validate the name and
     * value.
     */
    fun addHeaderLenient(name: String, value: Any) = apply {
      addHeaderLenient(headers, name, value.toString())
    }

    /** Removes all headers named [name], then adds a new header with the name and value. */
    fun setHeader(name: String, value: Any) = apply {
      removeHeader(name)
      addHeader(name, value)
    }

    /** Removes all headers named [name]. */
    fun removeHeader(name: String) = apply {
      headers.removeAll(name)
    }

    fun setBody(body: Buffer) = apply {
      setHeader("Content-Length", body.size)
      this.body = body.clone() // Defensive copy.
    }

    /** Sets the response body to the UTF-8 encoded bytes of [body]. */
    fun setBody(body: String): Builder = setBody(Buffer().writeUtf8(body))

    fun setBody(duplexResponseBody: DuplexResponseBody) = apply {
      this.duplexResponseBody = duplexResponseBody
    }

    /**
     * Sets the response body to [body], chunked every [maxChunkSize] bytes.
     */
    fun setChunkedBody(body: Buffer, maxChunkSize: Int) = apply {
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
      this.body = bytesOut
    }

    /**
     * Sets the response body to the UTF-8 encoded bytes of [body],
     * chunked every [maxChunkSize] bytes.
     */
    fun setChunkedBody(body: String, maxChunkSize: Int): Builder =
      setChunkedBody(Buffer().writeUtf8(body), maxChunkSize)

    /** Sets the headers and returns this. */
    fun setHeaders(headers: Headers) = apply {
      this.headers = headers.newBuilder()
    }

    /** Sets the trailers and returns this. */
    fun setTrailers(trailers: Headers) = apply {
      this.trailers = trailers.newBuilder()
    }

    /** Sets the socket policy and returns this. */
    fun setSocketPolicy(socketPolicy: SocketPolicy) = apply {
      this.socketPolicy = socketPolicy
    }

    /** Sets the HTTP/2 error code and returns this. */
    fun setHttp2ErrorCode(http2ErrorCode: Int) = apply {
      this.http2ErrorCode = http2ErrorCode
    }

    /**
     * Throttles the request reader and response writer to sleep for the given period after each
     * series of [bytesPerPeriod] bytes are transferred. Use this to simulate network behavior.
     */
    fun throttleBody(bytesPerPeriod: Long, period: Long, unit: TimeUnit) = apply {
      throttleBytesPerPeriod = bytesPerPeriod
      throttlePeriodAmount = period
      throttlePeriodUnit = unit
    }

    /**
     * Set the delayed time of the response body to [delay]. This applies to the response body
     * only; response headers are not affected.
     */
    fun setBodyDelay(delay: Long, unit: TimeUnit) = apply {
      bodyDelayAmount = delay
      bodyDelayUnit = unit
    }

    fun setHeadersDelay(delay: Long, unit: TimeUnit) = apply {
      headersDelayAmount = delay
      headersDelayUnit = unit
    }

    /**
     * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this attaches a
     * pushed stream to this response.
     */
    fun withPush(promise: PushPromise) = apply {
      this.pushPromises.add(promise)
    }

    /**
     * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this pushes
     * [settings] before writing the response.
     */
    fun withSettings(settings: Settings) = apply {
      this.settings.clear()
      this.settings.merge(settings)
    }

    /**
     * Attempts to perform a web socket upgrade on the connection.
     * This will overwrite any previously set status or body.
     */
    fun withWebSocketUpgrade(listener: WebSocketListener) = apply {
      status = "HTTP/1.1 101 Switching Protocols"
      setHeader("Connection", "Upgrade")
      setHeader("Upgrade", "websocket")
      body = null
      webSocketListener = listener
    }

    /**
     * Configures this response to be served as a response to an HTTP CONNECT request, either for
     * doing HTTPS through an HTTP proxy, or HTTP/2 prior knowledge through an HTTP proxy.
     *
     * When a new connection is received, all in-tunnel responses are served before the connection is
     * upgraded to HTTPS or HTTP/2.
     */
    fun inTunnel() = apply {
      removeHeader("Content-Length")
      inTunnel = true
    }

    /**
     * Adds an HTTP 1xx response to precede this response. Note that this response's
     * [headers delay][setHeadersDelay] applies after this response is transmitted. Set a
     * headers delay on that response to delay its transmission.
     */
    fun addInformationalResponse(response: MockResponse) = apply {
      informationalResponses += response
    }

    fun add100Continue() = apply {
      addInformationalResponse(
        Builder()
          .apply {
            code = 100
          }
          .build()
      )
    }

    public override fun clone(): Builder = build().newBuilder()

    fun build(): MockResponse = MockResponse(this)
  }

  companion object {
    private const val CHUNKED_BODY_HEADER = "Transfer-encoding: chunked"
  }
}
