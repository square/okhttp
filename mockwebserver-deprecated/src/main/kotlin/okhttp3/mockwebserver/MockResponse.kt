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

import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.WebSocketListener
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.http2.Settings
import okio.Buffer

class MockResponse : Cloneable {
  @set:JvmName("status")
  var status: String = ""

  private var headersBuilder = Headers.Builder()
  private var trailersBuilder = Headers.Builder()

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

  var throttleBytesPerPeriod: Long = Long.MAX_VALUE
    private set
  private var throttlePeriodAmount = 1L
  private var throttlePeriodUnit = TimeUnit.SECONDS

  @set:JvmName("socketPolicy")
  var socketPolicy: SocketPolicy = SocketPolicy.KEEP_OPEN

  @set:JvmName("http2ErrorCode")
  var http2ErrorCode: Int = -1

  private var bodyDelayAmount = 0L
  private var bodyDelayUnit = TimeUnit.MILLISECONDS

  private var headersDelayAmount = 0L
  private var headersDelayUnit = TimeUnit.MILLISECONDS

  private var promises = mutableListOf<PushPromise>()
  var settings: Settings = Settings()
    private set
  var webSocketListener: WebSocketListener? = null
    private set

  val pushPromises: List<PushPromise>
    get() = promises

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
    level = DeprecationLevel.ERROR,
  )
  fun getStatus(): String = status

  fun setStatus(status: String) =
    apply {
      this.status = status
    }

  fun setResponseCode(code: Int): MockResponse {
    val reason =
      when (code) {
        in 100..199 -> "Informational"
        in 200..299 -> "OK"
        in 300..399 -> "Redirection"
        in 400..499 -> "Client Error"
        in 500..599 -> "Server Error"
        else -> "Mock Response"
      }
    return apply { status = "HTTP/1.1 $code $reason" }
  }

  fun clearHeaders() =
    apply {
      headersBuilder = Headers.Builder()
    }

  fun addHeader(header: String) =
    apply {
      headersBuilder.add(header)
    }

  fun addHeader(
    name: String,
    value: Any,
  ) = apply {
    headersBuilder.add(name, value.toString())
  }

  fun addHeaderLenient(
    name: String,
    value: Any,
  ) = apply {
    addHeaderLenient(headersBuilder, name, value.toString())
  }

  fun setHeader(
    name: String,
    value: Any,
  ) = apply {
    removeHeader(name)
    addHeader(name, value)
  }

  fun removeHeader(name: String) =
    apply {
      headersBuilder.removeAll(name)
    }

  fun getBody(): Buffer? = body?.clone()

  fun setBody(body: Buffer) =
    apply {
      setHeader("Content-Length", body.size)
      this.body = body.clone() // Defensive copy.
    }

  fun setBody(body: String): MockResponse = setBody(Buffer().writeUtf8(body))

  fun setChunkedBody(
    body: Buffer,
    maxChunkSize: Int,
  ) = apply {
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

  fun setChunkedBody(
    body: String,
    maxChunkSize: Int,
  ): MockResponse = setChunkedBody(Buffer().writeUtf8(body), maxChunkSize)

  @JvmName("-deprecated_getHeaders")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(expression = "headers"),
    level = DeprecationLevel.ERROR,
  )
  fun getHeaders(): Headers = headers

  fun setHeaders(headers: Headers) = apply { this.headers = headers }

  @JvmName("-deprecated_getTrailers")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(expression = "trailers"),
    level = DeprecationLevel.ERROR,
  )
  fun getTrailers(): Headers = trailers

  fun setTrailers(trailers: Headers) = apply { this.trailers = trailers }

  @JvmName("-deprecated_getSocketPolicy")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(expression = "socketPolicy"),
    level = DeprecationLevel.ERROR,
  )
  fun getSocketPolicy(): SocketPolicy = socketPolicy

  fun setSocketPolicy(socketPolicy: SocketPolicy) =
    apply {
      this.socketPolicy = socketPolicy
    }

  @JvmName("-deprecated_getHttp2ErrorCode")
  @Deprecated(
    message = "moved to var",
    replaceWith = ReplaceWith(expression = "http2ErrorCode"),
    level = DeprecationLevel.ERROR,
  )
  fun getHttp2ErrorCode(): Int = http2ErrorCode

  fun setHttp2ErrorCode(http2ErrorCode: Int) =
    apply {
      this.http2ErrorCode = http2ErrorCode
    }

  fun throttleBody(
    bytesPerPeriod: Long,
    period: Long,
    unit: TimeUnit,
  ) = apply {
    throttleBytesPerPeriod = bytesPerPeriod
    throttlePeriodAmount = period
    throttlePeriodUnit = unit
  }

  fun getThrottlePeriod(unit: TimeUnit): Long = unit.convert(throttlePeriodAmount, throttlePeriodUnit)

  fun setBodyDelay(
    delay: Long,
    unit: TimeUnit,
  ) = apply {
    bodyDelayAmount = delay
    bodyDelayUnit = unit
  }

  fun getBodyDelay(unit: TimeUnit): Long = unit.convert(bodyDelayAmount, bodyDelayUnit)

  fun setHeadersDelay(
    delay: Long,
    unit: TimeUnit,
  ) = apply {
    headersDelayAmount = delay
    headersDelayUnit = unit
  }

  fun getHeadersDelay(unit: TimeUnit): Long = unit.convert(headersDelayAmount, headersDelayUnit)

  fun withPush(promise: PushPromise) =
    apply {
      promises.add(promise)
    }

  fun withSettings(settings: Settings) =
    apply {
      this.settings = settings
    }

  fun withWebSocketUpgrade(listener: WebSocketListener) =
    apply {
      status = "HTTP/1.1 101 Switching Protocols"
      setHeader("Connection", "Upgrade")
      setHeader("Upgrade", "websocket")
      body = null
      webSocketListener = listener
    }

  override fun toString(): String = status

  companion object {
    private const val CHUNKED_BODY_HEADER = "Transfer-encoding: chunked"
  }
}
