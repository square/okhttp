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
package okhttp3.mockwebserver

import okhttp3.Headers
import okhttp3.WebSocketListener
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.http2.Settings
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/** A scripted response to be replayed by the mock web server. */
class MockResponse : Cloneable {
  /** Returns the HTTP response line, such as "HTTP/1.1 200 OK". */
  @set:JvmName("status")
  var status: String = ""

  private var headersBuilder = Headers.Builder()
  private var trailersBuilder = Headers.Builder()

  /** The HTTP headers, such as "Content-Length: 0". */
  @set:JvmName("headers")
  var headers: Headers
    get() = headersBuilder.build()
    set(value) {
      this.headersBuilder = value.newBuilder()
    }

  @set:JvmName("trailers")
  var trailers: Headers
    get() = trailersBuilder.build()
    set(value) {
      this.trailersBuilder = value.newBuilder()
    }

  private var body: Buffer? = null

  var throttleBytesPerPeriod = Long.MAX_VALUE
    private set
  private var throttlePeriodAmount = 1L
  private var throttlePeriodUnit = TimeUnit.SECONDS

  @set:JvmName("socketPolicy")
  var socketPolicy = SocketPolicy.KEEP_OPEN

  /**
   * Sets the [HTTP/2 error code](https://tools.ietf.org/html/rfc7540#section-7) to be
   * returned when resetting the stream.
   * This is only valid with [SocketPolicy.RESET_STREAM_AT_START].
   */
  @set:JvmName("http2ErrorCode")
  var http2ErrorCode = -1

  private var bodyDelayAmount = 0L
  private var bodyDelayUnit = TimeUnit.MILLISECONDS

  private var headersDelayAmount = 0L
  private var headersDelayUnit = TimeUnit.MILLISECONDS

  private var promises = mutableListOf<PushPromise>()
  var settings: Settings = Settings()
    private set
  var webSocketListener: WebSocketListener? = null
    private set
  var duplexResponseBody: DuplexResponseBody? = null
    private set
  val isDuplex: Boolean
    get() = duplexResponseBody != null

  /** Returns the streams the server will push with this response. */
  val pushPromises: List<PushPromise>
    get() = promises

  /** Creates a new mock response with an empty body. */
  init {
    setResponseCode(200)
    setHeader("Content-Length", 0L)
  }

  public override fun clone(): MockResponse {
    val result = super.clone() as MockResponse
    result.headersBuilder = headersBuilder.build().newBuilder()
    result.promises = promises.toMutableList()
    return result
  }

  @JvmName("-deprecated_getStatus")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "status"),
      level = DeprecationLevel.ERROR)
  fun getStatus(): String = status

  @Deprecated(
      message = "moved to var.  Replace setStatus(...) with status(...) to fix Java",
      replaceWith = ReplaceWith(expression = "apply { this.status = status }"),
      level = DeprecationLevel.WARNING)
  fun setStatus(status: String) = apply {
    this.status = status
  }

  fun setResponseCode(code: Int): MockResponse {
    val reason = when (code) {
      in 100..199 -> "Informational"
      in 200..299 -> "OK"
      in 300..399 -> "Redirection"
      in 400..499 -> "Client Error"
      in 500..599 -> "Server Error"
      else -> "Mock Response"
    }
    return apply { status = "HTTP/1.1 $code $reason" }
  }

  /**
   * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
   * were added by default.
   */
  fun clearHeaders() = apply {
    headersBuilder = Headers.Builder()
  }

  /**
   * Adds [header] as an HTTP header. For well-formed HTTP [header] should contain a
   * name followed by a colon and a value.
   */
  fun addHeader(header: String) = apply {
    headersBuilder.add(header)
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name.
   */
  fun addHeader(name: String, value: Any) = apply {
    headersBuilder.add(name, value.toString())
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name. Unlike [addHeader] this does not validate the name and
   * value.
   */
  fun addHeaderLenient(name: String, value: Any) = apply {
    addHeaderLenient(headersBuilder, name, value.toString())
  }

  /**
   * Removes all headers named [name], then adds a new header with the name and value.
   */
  fun setHeader(name: String, value: Any) = apply {
    removeHeader(name)
    addHeader(name, value)
  }

  /** Removes all headers named [name]. */
  fun removeHeader(name: String) = apply {
    headersBuilder.removeAll(name)
  }

  /** Returns a copy of the raw HTTP payload. */
  fun getBody(): Buffer? = body?.clone()

  fun setBody(body: Buffer) = apply {
    setHeader("Content-Length", body.size)
    this.body = body.clone() // Defensive copy.
  }

  /** Sets the response body to the UTF-8 encoded bytes of [body]. */
  fun setBody(body: String): MockResponse = setBody(Buffer().writeUtf8(body))

  fun setBody(duplexResponseBody: DuplexResponseBody) = apply {
    this.duplexResponseBody = duplexResponseBody
  }

  /**
   * Sets the response body to [body], chunked every [maxChunkSize] bytes.
   */
  fun setChunkedBody(body: Buffer, maxChunkSize: Int) = apply {
    removeHeader("Content-Length")
    headersBuilder.add(CHUNKED_BODY_HEADER)

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
  fun setChunkedBody(body: String, maxChunkSize: Int): MockResponse =
    setChunkedBody(Buffer().writeUtf8(body), maxChunkSize)

  @JvmName("-deprecated_getHeaders")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "headers"),
      level = DeprecationLevel.ERROR)
  fun getHeaders(): Headers = headers

  @Deprecated(
      message = "moved to var. Replace setHeaders(...) with headers(...) to fix Java",
      replaceWith = ReplaceWith(expression = "apply { this.headers = headers }"),
      level = DeprecationLevel.WARNING)
  fun setHeaders(headers: Headers) = apply { this.headers = headers }

  @JvmName("-deprecated_getTrailers")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "trailers"),
      level = DeprecationLevel.ERROR)
  fun getTrailers(): Headers = trailers

  @Deprecated(
      message = "moved to var. Replace setTrailers(...) with trailers(...) to fix Java",
      replaceWith = ReplaceWith(expression = "apply { this.trailers = trailers }"),
      level = DeprecationLevel.WARNING)
  fun setTrailers(trailers: Headers) = apply { this.trailers = trailers }

  @JvmName("-deprecated_getSocketPolicy")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "socketPolicy"),
      level = DeprecationLevel.ERROR)
  fun getSocketPolicy() = socketPolicy

  @Deprecated(
      message = "moved to var. Replace setSocketPolicy(...) with socketPolicy(...) to fix Java",
      replaceWith = ReplaceWith(expression = "apply { this.socketPolicy = socketPolicy }"),
      level = DeprecationLevel.WARNING)
  fun setSocketPolicy(socketPolicy: SocketPolicy) = apply {
    this.socketPolicy = socketPolicy
  }

  @JvmName("-deprecated_getHttp2ErrorCode")
  @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "http2ErrorCode"),
      level = DeprecationLevel.ERROR)
  fun getHttp2ErrorCode() = http2ErrorCode

  @Deprecated(
      message = "moved to var. Replace setHttp2ErrorCode(...) with http2ErrorCode(...) to fix Java",
      replaceWith = ReplaceWith(expression = "apply { this.http2ErrorCode = http2ErrorCode }"),
      level = DeprecationLevel.WARNING)
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

  fun getThrottlePeriod(unit: TimeUnit): Long =
    unit.convert(throttlePeriodAmount, throttlePeriodUnit)

  /**
   * Set the delayed time of the response body to [delay]. This applies to the response body
   * only; response headers are not affected.
   */
  fun setBodyDelay(delay: Long, unit: TimeUnit) = apply {
    bodyDelayAmount = delay
    bodyDelayUnit = unit
  }

  fun getBodyDelay(unit: TimeUnit): Long =
    unit.convert(bodyDelayAmount, bodyDelayUnit)

  fun setHeadersDelay(delay: Long, unit: TimeUnit) = apply {
    headersDelayAmount = delay
    headersDelayUnit = unit
  }

  fun getHeadersDelay(unit: TimeUnit): Long =
    unit.convert(headersDelayAmount, headersDelayUnit)

  /**
   * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this attaches a
   * pushed stream to this response.
   */
  fun withPush(promise: PushPromise) = apply {
    promises.add(promise)
  }

  /**
   * When [protocols][MockWebServer.protocols] include [HTTP_2][okhttp3.Protocol], this pushes
   * [settings] before writing the response.
   */
  fun withSettings(settings: Settings) = apply {
    this.settings = settings
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

  override fun toString() = status

  companion object {
    private const val CHUNKED_BODY_HEADER = "Transfer-encoding: chunked"
  }
}
