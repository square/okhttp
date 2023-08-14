/*
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
package okhttp3

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection.HTTP_PROXY_AUTH
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.commonAddHeader
import okhttp3.internal.commonBody
import okhttp3.internal.commonCacheControl
import okhttp3.internal.commonCacheResponse
import okhttp3.internal.commonClose
import okhttp3.internal.commonCode
import okhttp3.internal.commonEmptyResponse
import okhttp3.internal.commonHeader
import okhttp3.internal.commonHeaders
import okhttp3.internal.commonIsRedirect
import okhttp3.internal.commonIsSuccessful
import okhttp3.internal.commonMessage
import okhttp3.internal.commonNetworkResponse
import okhttp3.internal.commonNewBuilder
import okhttp3.internal.commonPriorResponse
import okhttp3.internal.commonProtocol
import okhttp3.internal.commonRemoveHeader
import okhttp3.internal.commonRequest
import okhttp3.internal.commonToString
import okhttp3.internal.commonTrailers
import okhttp3.internal.connection.Exchange
import okhttp3.internal.http.parseChallenges
import okio.Buffer

actual class Response internal constructor(
  @get:JvmName("request") actual val request: Request,

  @get:JvmName("protocol") actual val protocol: Protocol,

  @get:JvmName("message") actual val message: String,

  @get:JvmName("code") actual val code: Int,

  /**
   * Returns the TLS handshake of the connection that carried this response, or null if the
   * response was received without TLS.
   */
  @get:JvmName("handshake") val handshake: Handshake?,

  /** Returns the HTTP headers. */
  @get:JvmName("headers") actual val headers: Headers,

  @get:JvmName("body") actual val body: ResponseBody,

  @get:JvmName("networkResponse") actual val networkResponse: Response?,

  @get:JvmName("cacheResponse") actual val cacheResponse: Response?,

  @get:JvmName("priorResponse") actual val priorResponse: Response?,

  /**
   * Returns a [timestamp][System.currentTimeMillis] taken immediately before OkHttp
   * transmitted the initiating request over the network. If this response is being served from the
   * cache then this is the timestamp of the original request.
   */
  @get:JvmName("sentRequestAtMillis") val sentRequestAtMillis: Long,

  /**
   * Returns a [timestamp][System.currentTimeMillis] taken immediately after OkHttp
   * received this response's headers from the network. If this response is being served from the
   * cache then this is the timestamp of the original response.
   */
  @get:JvmName("receivedResponseAtMillis") val receivedResponseAtMillis: Long,

  @get:JvmName("exchange") internal val exchange: Exchange?,

  private var trailersFn: (() -> Headers)
) : Closeable {

  internal actual var lazyCacheControl: CacheControl? = null

  @JvmName("-deprecated_request")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "request"),
      level = DeprecationLevel.ERROR)
  fun request(): Request = request

  @JvmName("-deprecated_protocol")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "protocol"),
      level = DeprecationLevel.ERROR)
  fun protocol(): Protocol = protocol

  @JvmName("-deprecated_code")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "code"),
      level = DeprecationLevel.ERROR)
  fun code(): Int = code

  actual val isSuccessful: Boolean = commonIsSuccessful

  @JvmName("-deprecated_message")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "message"),
      level = DeprecationLevel.ERROR)
  fun message(): String = message

  @JvmName("-deprecated_handshake")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "handshake"),
      level = DeprecationLevel.ERROR)
  fun handshake(): Handshake? = handshake

  actual fun headers(name: String): List<String> = commonHeaders(name)

  @JvmOverloads
  actual fun header(name: String, defaultValue: String?): String? = commonHeader(name, defaultValue)

  @JvmName("-deprecated_headers")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "headers"),
      level = DeprecationLevel.ERROR)
  fun headers(): Headers = headers

  /**
   * Returns the trailers after the HTTP response, which may be empty. It is an error to call this
   * before the entire HTTP response body has been consumed.
   */
  @Throws(IOException::class)
  actual fun trailers(): Headers = trailersFn()

  @Throws(IOException::class)
  actual fun peekBody(byteCount: Long): ResponseBody {
    val peeked = body.source().peek()
    val buffer = Buffer()
    peeked.request(byteCount)
    buffer.write(peeked, minOf(byteCount, peeked.buffer.size))
    return buffer.asResponseBody(body.contentType(), buffer.size)
  }

  @JvmName("-deprecated_body")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "body"),
      level = DeprecationLevel.ERROR)
  fun body() = body

  actual fun newBuilder(): Builder = commonNewBuilder()

  /** Returns true if this response redirects to another resource. */
  actual val isRedirect: Boolean = commonIsRedirect

  @JvmName("-deprecated_networkResponse")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "networkResponse"),
      level = DeprecationLevel.ERROR)
  fun networkResponse(): Response? = networkResponse

  @JvmName("-deprecated_cacheResponse")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "cacheResponse"),
      level = DeprecationLevel.ERROR)
  fun cacheResponse(): Response? = cacheResponse

  @JvmName("-deprecated_priorResponse")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "priorResponse"),
      level = DeprecationLevel.ERROR)
  fun priorResponse(): Response? = priorResponse

  /**
   * Returns the RFC 7235 authorization challenges appropriate for this response's code. If the
   * response code is 401 unauthorized, this returns the "WWW-Authenticate" challenges. If the
   * response code is 407 proxy unauthorized, this returns the "Proxy-Authenticate" challenges.
   * Otherwise this returns an empty list of challenges.
   *
   * If a challenge uses the `token68` variant instead of auth params, there is exactly one
   * auth param in the challenge at key null. Invalid headers and challenges are ignored.
   * No semantic validation is done, for example that `Basic` auth must have a `realm`
   * auth param, this is up to the caller that interprets these challenges.
   */
  fun challenges(): List<Challenge> {
    return headers.parseChallenges(
        when (code) {
          HTTP_UNAUTHORIZED -> "WWW-Authenticate"
          HTTP_PROXY_AUTH -> "Proxy-Authenticate"
          else -> return emptyList()
        }
    )
  }

  @get:JvmName("cacheControl") actual val cacheControl: CacheControl
    get() = commonCacheControl

  @JvmName("-deprecated_cacheControl")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "cacheControl"),
      level = DeprecationLevel.ERROR)
  fun cacheControl(): CacheControl = cacheControl

  @JvmName("-deprecated_sentRequestAtMillis")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "sentRequestAtMillis"),
      level = DeprecationLevel.ERROR)
  fun sentRequestAtMillis(): Long = sentRequestAtMillis

  @JvmName("-deprecated_receivedResponseAtMillis")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "receivedResponseAtMillis"),
      level = DeprecationLevel.ERROR)
  fun receivedResponseAtMillis(): Long = receivedResponseAtMillis

  actual override fun close() = commonClose()

  actual override fun toString(): String = commonToString()

  actual open class Builder {
    internal actual var request: Request? = null
    internal actual var protocol: Protocol? = null
    internal actual var code = -1
    internal actual var message: String? = null
    internal var handshake: Handshake? = null
    internal actual var headers: Headers.Builder
    internal actual var body: ResponseBody = commonEmptyResponse
    internal actual var networkResponse: Response? = null
    internal actual var cacheResponse: Response? = null
    internal actual var priorResponse: Response? = null
    internal var sentRequestAtMillis: Long = 0
    internal var receivedResponseAtMillis: Long = 0
    internal var exchange: Exchange? = null
    internal actual var trailersFn: (() -> Headers) = { Headers.headersOf() }

    actual constructor() {
      headers = Headers.Builder()
    }

    internal actual constructor(response: Response) {
      this.request = response.request
      this.protocol = response.protocol
      this.code = response.code
      this.message = response.message
      this.handshake = response.handshake
      this.headers = response.headers.newBuilder()
      this.body = response.body
      this.networkResponse = response.networkResponse
      this.cacheResponse = response.cacheResponse
      this.priorResponse = response.priorResponse
      this.sentRequestAtMillis = response.sentRequestAtMillis
      this.receivedResponseAtMillis = response.receivedResponseAtMillis
      this.exchange = response.exchange
      this.trailersFn = response.trailersFn
    }

    actual open fun request(request: Request) = commonRequest(request)

    actual open fun protocol(protocol: Protocol) =commonProtocol(protocol)

    actual open fun code(code: Int) = commonCode(code)

    actual open fun message(message: String) = commonMessage(message)

    open fun handshake(handshake: Handshake?) = apply {
      this.handshake = handshake
    }

    actual open fun header(name: String, value: String) = commonHeader(name, value)

    actual open fun addHeader(name: String, value: String) = commonAddHeader(name, value)

    actual open fun removeHeader(name: String) = commonRemoveHeader(name)

    actual open fun headers(headers: Headers) = commonHeaders(headers)

    actual open fun body(body: ResponseBody) = commonBody(body)

    actual open fun networkResponse(networkResponse: Response?) = commonNetworkResponse(networkResponse)

    actual open fun cacheResponse(cacheResponse: Response?) = commonCacheResponse(cacheResponse)

    actual open fun priorResponse(priorResponse: Response?) = commonPriorResponse(priorResponse)

    actual open fun trailers(trailersFn: (() -> Headers)): Builder = commonTrailers(trailersFn)

    open fun sentRequestAtMillis(sentRequestAtMillis: Long) = apply {
      this.sentRequestAtMillis = sentRequestAtMillis
    }

    open fun receivedResponseAtMillis(receivedResponseAtMillis: Long) = apply {
      this.receivedResponseAtMillis = receivedResponseAtMillis
    }

    internal fun initExchange(exchange: Exchange) {
      this.exchange = exchange
      this.trailersFn = { exchange.trailers() }
    }

    actual open fun build(): Response {
      check(code >= 0) { "code < 0: $code" }
      return Response(
          checkNotNull(request) { "request == null" },
          checkNotNull(protocol) { "protocol == null" },
          checkNotNull(message) { "message == null" },
          code,
          handshake,
          headers.build(),
          body,
          networkResponse,
          cacheResponse,
          priorResponse,
          sentRequestAtMillis,
          receivedResponseAtMillis,
          exchange,
          trailersFn
      )
    }
  }
}
