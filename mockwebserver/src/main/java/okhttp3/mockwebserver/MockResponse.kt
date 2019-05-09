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

/** A scripted response to be replayed by the mock web server.  */
class MockResponse : Cloneable {
  private var status: String = ""
  private var headers = Headers.Builder()
  private var trailers = Headers.Builder()

  private var body: Buffer? = null

  var throttleBytesPerPeriod = Long.MAX_VALUE
    private set
  private var throttlePeriodAmount = 1L
  private var throttlePeriodUnit = TimeUnit.SECONDS

  private var socketPolicy = SocketPolicy.KEEP_OPEN
  private var http2ErrorCode = -1

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

  /** Returns the streams the server will push with this response.  */
  val pushPromises: List<PushPromise>
    get() = promises

  /** Creates a new mock response with an empty body.  */
  init {
    setResponseCode(200)
    setHeader("Content-Length", 0L)
  }

  public override fun clone(): MockResponse {
    val result = super.clone() as MockResponse
    result.headers = headers.build().newBuilder()
    result.promises = promises.toMutableList()
    return result
  }

  /** Returns the HTTP response line, such as "HTTP/1.1 200 OK".  */
  fun getStatus(): String = status

  fun setResponseCode(code: Int): MockResponse {
    val reason: String = when (code) {
      in 100..199 -> "Informational"
      in 200..299 -> "OK"
      in 300..399 -> "Redirection"
      in 400..499 -> "Client Error"
      in 500..599 -> "Server Error"
      else -> "Mock Response"
    }
    return setStatus("HTTP/1.1 $code $reason")
  }

  fun setStatus(status: String): MockResponse {
    this.status = status
    return this
  }

  /** Returns the HTTP headers, such as "Content-Length: 0".  */
  fun getHeaders(): Headers = headers.build()

  fun getTrailers(): Headers = trailers.build()

  /**
   * Removes all HTTP headers including any "Content-Length" and "Transfer-encoding" headers that
   * were added by default.
   */
  fun clearHeaders(): MockResponse {
    headers = Headers.Builder()
    return this
  }

  /**
   * Adds [header] as an HTTP header. For well-formed HTTP [header] should contain a
   * name followed by a colon and a value.
   */
  fun addHeader(header: String): MockResponse {
    headers.add(header)
    return this
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name.
   */
  fun addHeader(name: String, value: Any): MockResponse {
    headers.add(name, value.toString())
    return this
  }

  /**
   * Adds a new header with the name and value. This may be used to add multiple headers with the
   * same name. Unlike [addHeader] this does not validate the name and
   * value.
   */
  fun addHeaderLenient(name: String, value: Any): MockResponse {
    addHeaderLenient(headers, name, value.toString())
    return this
  }

  /**
   * Removes all headers named [name], then adds a new header with the name and value.
   */
  fun setHeader(name: String, value: Any): MockResponse {
    removeHeader(name)
    return addHeader(name, value)
  }

  /** Replaces all headers with those specified.  */
  fun setHeaders(headers: Headers): MockResponse {
    this.headers = headers.newBuilder()
    return this
  }

  /** Replaces all trailers with those specified.  */
  fun setTrailers(trailers: Headers): MockResponse {
    this.trailers = trailers.newBuilder()
    return this
  }

  /** Removes all headers named [name].  */
  fun removeHeader(name: String): MockResponse {
    headers.removeAll(name)
    return this
  }

  /** Returns a copy of the raw HTTP payload.  */
  fun getBody(): Buffer? {
    return body?.clone()
  }

  fun setBody(body: Buffer): MockResponse {
    setHeader("Content-Length", body.size)
    this.body = body.clone() // Defensive copy.
    return this
  }

  /** Sets the response body to the UTF-8 encoded bytes of [body].  */
  fun setBody(body: String): MockResponse {
    return setBody(Buffer().writeUtf8(body))
  }

  fun setBody(duplexResponseBody: DuplexResponseBody): MockResponse {
    this.duplexResponseBody = duplexResponseBody
    return this
  }

  /**
   * Sets the response body to [body], chunked every [maxChunkSize] bytes.
   */
  fun setChunkedBody(body: Buffer, maxChunkSize: Int): MockResponse {
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
    return this
  }

  /**
   * Sets the response body to the UTF-8 encoded bytes of [body],
   * chunked every [maxChunkSize] bytes.
   */
  fun setChunkedBody(body: String, maxChunkSize: Int): MockResponse {
    return setChunkedBody(Buffer().writeUtf8(body), maxChunkSize)
  }

  fun getSocketPolicy(): SocketPolicy = socketPolicy

  fun setSocketPolicy(socketPolicy: SocketPolicy): MockResponse {
    this.socketPolicy = socketPolicy
    return this
  }

  fun getHttp2ErrorCode(): Int = http2ErrorCode

  /**
   * Sets the [HTTP/2 error code](https://tools.ietf.org/html/rfc7540#section-7) to be
   * returned when resetting the stream.
   * This is only valid with [SocketPolicy.RESET_STREAM_AT_START].
   */
  fun setHttp2ErrorCode(http2ErrorCode: Int): MockResponse {
    this.http2ErrorCode = http2ErrorCode
    return this
  }

  /**
   * Throttles the request reader and response writer to sleep for the given period after each
   * series of [bytesPerPeriod] bytes are transferred. Use this to simulate network behavior.
   */
  fun throttleBody(bytesPerPeriod: Long, period: Long, unit: TimeUnit): MockResponse {
    this.throttleBytesPerPeriod = bytesPerPeriod
    this.throttlePeriodAmount = period
    this.throttlePeriodUnit = unit
    return this
  }

  fun getThrottlePeriod(unit: TimeUnit): Long {
    return unit.convert(throttlePeriodAmount, throttlePeriodUnit)
  }

  /**
   * Set the delayed time of the response body to [delay]. This applies to the response body
   * only; response headers are not affected.
   */
  fun setBodyDelay(delay: Long, unit: TimeUnit): MockResponse {
    bodyDelayAmount = delay
    bodyDelayUnit = unit
    return this
  }

  fun getBodyDelay(unit: TimeUnit): Long {
    return unit.convert(bodyDelayAmount, bodyDelayUnit)
  }

  fun setHeadersDelay(delay: Long, unit: TimeUnit): MockResponse {
    headersDelayAmount = delay
    headersDelayUnit = unit
    return this
  }

  fun getHeadersDelay(unit: TimeUnit): Long {
    return unit.convert(headersDelayAmount, headersDelayUnit)
  }

  /**
   * When [protocols][MockWebServer.setProtocols] include [HTTP_2][okhttp3.Protocol],
   * this attaches a pushed stream to this response.
   */
  fun withPush(promise: PushPromise): MockResponse {
    this.promises.add(promise)
    return this
  }

  /**
   * When [protocols][MockWebServer.setProtocols] include [HTTP_2][okhttp3.Protocol],
   * this pushes [settings] before writing the response.
   */
  fun withSettings(settings: Settings): MockResponse {
    this.settings = settings
    return this
  }

  /**
   * Attempts to perform a web socket upgrade on the connection.
   * This will overwrite any previously set status or body.
   */
  fun withWebSocketUpgrade(listener: WebSocketListener): MockResponse {
    setStatus("HTTP/1.1 101 Switching Protocols")
    setHeader("Connection", "Upgrade")
    setHeader("Upgrade", "websocket")
    body = null
    webSocketListener = listener
    return this
  }

  override fun toString(): String = status

  companion object {
    private const val CHUNKED_BODY_HEADER = "Transfer-encoding: chunked"
  }
}
