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

import okhttp3.internal.commonAddHeader
import okhttp3.internal.commonBody
import okhttp3.internal.commonCacheControl
import okhttp3.internal.commonCacheResponse
import okhttp3.internal.commonCode
import okhttp3.internal.commonEmptyResponse
import okhttp3.internal.commonHeader
import okhttp3.internal.commonHeaders
import okhttp3.internal.commonIsRedirect
import okhttp3.internal.commonIsSuccessful
import okhttp3.internal.commonMessage
import okhttp3.internal.commonNetworkResponse
import okhttp3.internal.commonNewBuilder
import okhttp3.internal.commonPeekBody
import okhttp3.internal.commonPriorResponse
import okhttp3.internal.commonProtocol
import okhttp3.internal.commonRemoveHeader
import okhttp3.internal.commonRequest
import okhttp3.internal.commonTrailers
import okio.Closeable

/**
 * An HTTP response. Instances of this class are not immutable: the response body is a one-shot
 * value that may be consumed only once and then closed. All other properties are immutable.
 *
 * This class implements [Closeable]. Closing it simply closes its response body. See
 * [ResponseBody] for an explanation and examples.
 */
actual class Response internal constructor(
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
  actual val request: Request,

  /** Returns the HTTP protocol, such as [Protocol.HTTP_1_1] or [Protocol.HTTP_1_0]. */
  actual val protocol: Protocol,

  /** Returns the HTTP status message. */
  actual val message: String,

  /** Returns the HTTP status code. */
  actual val code: Int,

  /** Returns the HTTP headers. */
  actual val headers: Headers,

  /**
   * Returns a non-null value if this response was passed to [Callback.onResponse] or returned
   * from [Call.execute]. Response bodies must be [closed][ResponseBody] and may
   * be consumed only once.
   *
   * This always returns null on responses returned from [cacheResponse], [networkResponse],
   * and [priorResponse].
   */
  actual val body: ResponseBody,

  /**
   * Returns the raw response received from the network. Will be null if this response didn't use
   * the network, such as when the response is fully cached. The body of the returned response
   * should not be read.
   */
  actual val networkResponse: Response?,

  /**
   * Returns the raw response received from the cache. Will be null if this response didn't use
   * the cache. For conditional get requests the cache response and network response may both be
   * non-null. The body of the returned response should not be read.
   */
  actual val cacheResponse: Response?,

  /**
   * Returns the response for the HTTP redirect or authorization challenge that triggered this
   * response, or null if this response wasn't triggered by an automatic retry. The body of the
   * returned response should not be read because it has already been consumed by the redirecting
   * client.
   */
  actual val priorResponse: Response?,

  private var trailersFn: (() -> Headers) = { Headers.headersOf() }
) : Closeable {

  internal actual var lazyCacheControl: CacheControl? = null

  /**
   * Returns true if the code is in [200..300), which means the request was successfully received,
   * understood, and accepted.
   */
  actual val isSuccessful: Boolean = commonIsSuccessful

  actual fun headers(name: String): List<String> = commonHeaders(name)

  actual fun header(name: String, defaultValue: String?): String? = commonHeader(name, defaultValue)

  actual fun trailers(): Headers = trailersFn.invoke()

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
  actual fun peekBody(byteCount: Long): ResponseBody = commonPeekBody(byteCount)

  actual fun newBuilder(): Builder = commonNewBuilder()

  /** Returns true if this response redirects to another resource. */
  actual val isRedirect: Boolean
    get() = commonIsRedirect

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no `Cache-Control` header.
   */
  actual val cacheControl: CacheControl
    get() = commonCacheControl

  /**
   * Closes the response body. Equivalent to `body().close()`.
   *
   * Prior to OkHttp 5.0, it was an error to close a response that is not eligible for a body. This
   * includes the responses returned from [cacheResponse], [networkResponse], and [priorResponse].
   */
  actual override fun close() {
    body.close()
  }

  actual override fun toString(): String =
      "Response{protocol=$protocol, code=$code, message=$message, url=${request.url}}"

  actual open class Builder {
    internal actual var request: Request? = null
    internal actual var protocol: Protocol? = null
    internal actual var code = -1
    internal actual var message: String? = null
    internal actual var headers: Headers.Builder
    internal actual var body: ResponseBody = commonEmptyResponse
    internal actual var networkResponse: Response? = null
    internal actual var cacheResponse: Response? = null
    internal actual var priorResponse: Response? = null
    internal actual var trailersFn: (() -> Headers) = { Headers.headersOf() }

    actual constructor() {
      headers = Headers.Builder()
    }

    internal actual constructor(response: Response) {
      this.request = response.request
      this.protocol = response.protocol
      this.code = response.code
      this.message = response.message
      this.headers = response.headers.newBuilder()
      this.body = response.body
      this.networkResponse = response.networkResponse
      this.cacheResponse = response.cacheResponse
      this.priorResponse = response.priorResponse
    }

    actual open fun request(request: Request) = commonRequest(request)

    actual open fun protocol(protocol: Protocol) =commonProtocol(protocol)

    actual open fun code(code: Int) = commonCode(code)

    actual open fun message(message: String) = commonMessage(message)

    /**
     * Sets the header named [name] to [value]. If this request already has any headers
     * with that name, they are all replaced.
     */
    actual open fun header(name: String, value: String) = commonHeader(name, value)

    /**
     * Adds a header with [name] to [value]. Prefer this method for multiply-valued
     * headers like "Set-Cookie".
     */
    actual open fun addHeader(name: String, value: String) = commonAddHeader(name, value)

    /** Removes all headers named [name] on this builder. */
    actual open fun removeHeader(name: String) = commonRemoveHeader(name)

    /** Removes all headers on this builder and adds [headers]. */
    actual open fun headers(headers: Headers) = commonHeaders(headers)

    actual open fun trailers(trailersFn: (() -> Headers)): Builder = commonTrailers(trailersFn)

    actual open fun body(body: ResponseBody) = commonBody(body)

    actual open fun networkResponse(networkResponse: Response?) = commonNetworkResponse(networkResponse)

    actual open fun cacheResponse(cacheResponse: Response?) = commonCacheResponse(cacheResponse)

    actual open fun priorResponse(priorResponse: Response?) = commonPriorResponse(priorResponse)

    actual open fun build(): Response {
      check(code >= 0) { "code < 0: $code" }
      return Response(
          checkNotNull(request) { "request == null" },
          checkNotNull(protocol) { "protocol == null" },
          checkNotNull(message) { "message == null" },
          code,
          headers.build(),
          body,
          networkResponse,
          cacheResponse,
          priorResponse,
          trailersFn
      )
    }
  }
}
