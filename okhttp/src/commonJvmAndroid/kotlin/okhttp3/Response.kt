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

/**
 * An HTTP response. Instances of this class are not immutable: the response body is a one-shot
 * value that may be consumed only once and then closed. All other properties are immutable.
 *
 * This class implements [Closeable]. Closing it simply closes its response body. See
 * [ResponseBody] for an explanation and examples.
 */
class Response internal constructor(
  /**
   * The request that initiated this HTTP response. This is not necessarily the same request issued
   * by the application:
   *
   * * It may be transformed by the user's interceptors. For example, an application interceptor
   *   may add headers like `User-Agent`.
   * * It may be the request generated in response to an HTTP redirect or authentication
   *   challenge. In this case the request URL may be different than the initial request URL.
   *
   * Use the `request` of the [networkResponse] field to get the wire-level request that was
   * transmitted. In the case of follow-ups and redirects, also look at the `request` of the
   * [priorResponse] objects, which have its own [priorResponse].
   */
  @get:JvmName("request") val request: Request,
  /** Returns the HTTP protocol, such as [Protocol.HTTP_1_1] or [Protocol.HTTP_1_0]. */
  @get:JvmName("protocol") val protocol: Protocol,
  /** Returns the HTTP status message. */
  @get:JvmName("message") val message: String,
  /** Returns the HTTP status code. */
  @get:JvmName("code") val code: Int,
  /**
   * Returns the TLS handshake of the connection that carried this response, or null if the
   * response was received without TLS.
   */
  @get:JvmName("handshake") val handshake: Handshake?,
  /** Returns the HTTP headers. */
  @get:JvmName("headers") val headers: Headers,
  /**
   * Returns a non-null stream with the server's response. The returned value must be
   * [closed][ResponseBody] and may be consumed only once.
   *
   * If this is a [cacheResponse], [networkResponse], or [priorResponse], the server's response body
   * is not available and it is always an error to attempt read its streamed content. Reading from
   * [ResponseBody.source] always throws on such instances.
   *
   * It is safe and supported to call [ResponseBody.contentType] and [ResponseBody.contentLength] on
   * all instances of [ResponseBody].
   */
  @get:JvmName("body") val body: ResponseBody,
  /**
   * Returns the raw response received from the network. Will be null if this response didn't use
   * the network, such as when the response is fully cached. The body of the returned response
   * should not be read.
   */
  @get:JvmName("networkResponse") val networkResponse: Response?,
  /**
   * Returns the raw response received from the cache. Will be null if this response didn't use
   * the cache. For conditional get requests the cache response and network response may both be
   * non-null. The body of the returned response should not be read.
   */
  @get:JvmName("cacheResponse") val cacheResponse: Response?,
  /**
   * Returns the response for the HTTP redirect or authorization challenge that triggered this
   * response, or null if this response wasn't triggered by an automatic retry. The body of the
   * returned response should not be read because it has already been consumed by the redirecting
   * client.
   */
  @get:JvmName("priorResponse") val priorResponse: Response?,
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
  private var trailersFn: (() -> Headers),
) : Closeable {
  internal var lazyCacheControl: CacheControl? = null

  @JvmName("-deprecated_request")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "request"),
    level = DeprecationLevel.ERROR,
  )
  fun request(): Request = request

  @JvmName("-deprecated_protocol")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "protocol"),
    level = DeprecationLevel.ERROR,
  )
  fun protocol(): Protocol = protocol

  @JvmName("-deprecated_code")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "code"),
    level = DeprecationLevel.ERROR,
  )
  fun code(): Int = code

  /**
   * Returns true if the code is in [200..300), which means the request was successfully received,
   * understood, and accepted.
   */
  val isSuccessful: Boolean = commonIsSuccessful

  @JvmName("-deprecated_message")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "message"),
    level = DeprecationLevel.ERROR,
  )
  fun message(): String = message

  @JvmName("-deprecated_handshake")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "handshake"),
    level = DeprecationLevel.ERROR,
  )
  fun handshake(): Handshake? = handshake

  fun headers(name: String): List<String> = commonHeaders(name)

  @JvmOverloads
  fun header(
    name: String,
    defaultValue: String? = null,
  ): String? = commonHeader(name, defaultValue)

  @JvmName("-deprecated_headers")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "headers"),
    level = DeprecationLevel.ERROR,
  )
  fun headers(): Headers = headers

  /**
   * Returns the trailers after the HTTP response, which may be empty. It is an error to call this
   * before the entire HTTP response body has been consumed.
   */
  @Throws(IOException::class)
  fun trailers(): Headers = trailersFn()

  /**
   * Peeks up to [byteCount] bytes from the response body and returns them as a new response
   * body. If fewer than [byteCount] bytes are in the response body, the full response body is
   * returned. If more than [byteCount] bytes are in the response body, the returned value
   * will be truncated to [byteCount] bytes.
   *
   * It is an error to call this method after the body has been consumed.
   *
   * **Warning:** this method loads the requested bytes into memory. Most applications should set
   * a modest limit on `byteCount`, such as 1 MiB.
   */
  @Throws(IOException::class)
  fun peekBody(byteCount: Long): ResponseBody {
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
    level = DeprecationLevel.ERROR,
  )
  fun body() = body

  fun newBuilder(): Builder = commonNewBuilder()

  /** Returns true if this response redirects to another resource. */
  val isRedirect: Boolean = commonIsRedirect

  @JvmName("-deprecated_networkResponse")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "networkResponse"),
    level = DeprecationLevel.ERROR,
  )
  fun networkResponse(): Response? = networkResponse

  @JvmName("-deprecated_cacheResponse")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "cacheResponse"),
    level = DeprecationLevel.ERROR,
  )
  fun cacheResponse(): Response? = cacheResponse

  @JvmName("-deprecated_priorResponse")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "priorResponse"),
    level = DeprecationLevel.ERROR,
  )
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
      },
    )
  }

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no `Cache-Control` header.
   */
  @get:JvmName("cacheControl")
  val cacheControl: CacheControl
    get() = commonCacheControl

  @JvmName("-deprecated_cacheControl")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "cacheControl"),
    level = DeprecationLevel.ERROR,
  )
  fun cacheControl(): CacheControl = cacheControl

  @JvmName("-deprecated_sentRequestAtMillis")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "sentRequestAtMillis"),
    level = DeprecationLevel.ERROR,
  )
  fun sentRequestAtMillis(): Long = sentRequestAtMillis

  @JvmName("-deprecated_receivedResponseAtMillis")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "receivedResponseAtMillis"),
    level = DeprecationLevel.ERROR,
  )
  fun receivedResponseAtMillis(): Long = receivedResponseAtMillis

  /**
   * Closes the response body. Equivalent to `body().close()`.
   *
   * Prior to OkHttp 5.0, it was an error to close a response that is not eligible for a body. This
   * includes the responses returned from [cacheResponse], [networkResponse], and [priorResponse].
   */
  override fun close() = commonClose()

  override fun toString(): String = commonToString()

  open class Builder {
    internal var request: Request? = null
    internal var protocol: Protocol? = null
    internal var code = -1
    internal var message: String? = null
    internal var handshake: Handshake? = null
    internal var headers: Headers.Builder
    internal var body: ResponseBody = ResponseBody.Empty
    internal var networkResponse: Response? = null
    internal var cacheResponse: Response? = null
    internal var priorResponse: Response? = null
    internal var sentRequestAtMillis: Long = 0
    internal var receivedResponseAtMillis: Long = 0
    internal var exchange: Exchange? = null
    internal var trailersFn: (() -> Headers) = { Headers.headersOf() }

    constructor() {
      headers = Headers.Builder()
    }

    internal constructor(response: Response) {
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

    open fun request(request: Request) = commonRequest(request)

    open fun protocol(protocol: Protocol) = commonProtocol(protocol)

    open fun code(code: Int) = commonCode(code)

    open fun message(message: String) = commonMessage(message)

    open fun handshake(handshake: Handshake?) =
      apply {
        this.handshake = handshake
      }

    /**
     * Sets the header named [name] to [value]. If this request already has any headers
     * with that name, they are all replaced.
     */
    open fun header(
      name: String,
      value: String,
    ) = commonHeader(name, value)

    /**
     * Adds a header with [name] to [value]. Prefer this method for multiply-valued
     * headers like "Set-Cookie".
     */
    open fun addHeader(
      name: String,
      value: String,
    ) = commonAddHeader(name, value)

    /** Removes all headers named [name] on this builder. */
    open fun removeHeader(name: String) = commonRemoveHeader(name)

    /** Removes all headers on this builder and adds [headers]. */
    open fun headers(headers: Headers) = commonHeaders(headers)

    open fun body(body: ResponseBody) = commonBody(body)

    open fun networkResponse(networkResponse: Response?) = commonNetworkResponse(networkResponse)

    open fun cacheResponse(cacheResponse: Response?) = commonCacheResponse(cacheResponse)

    open fun priorResponse(priorResponse: Response?) = commonPriorResponse(priorResponse)

    @ExperimentalOkHttpApi
    open fun trailers(trailersFn: (() -> Headers)): Builder = commonTrailers(trailersFn)

    open fun sentRequestAtMillis(sentRequestAtMillis: Long) =
      apply {
        this.sentRequestAtMillis = sentRequestAtMillis
      }

    open fun receivedResponseAtMillis(receivedResponseAtMillis: Long) =
      apply {
        this.receivedResponseAtMillis = receivedResponseAtMillis
      }

    internal fun initExchange(exchange: Exchange) {
      this.exchange = exchange
      this.trailersFn = { exchange.trailers() }
    }

    open fun build(): Response {
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
        trailersFn,
      )
    }
  }
}
