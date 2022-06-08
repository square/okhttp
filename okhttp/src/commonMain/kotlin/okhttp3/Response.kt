/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
package okhttp3

import okio.Closeable
import okio.IOException

/**
 * An HTTP response. Instances of this class are not immutable: the response body is a one-shot
 * value that may be consumed only once and then closed. All other properties are immutable.
 *
 * This class implements [Closeable]. Closing it simply closes its response body. See
 * [ResponseBody] for an explanation and examples.
 */
expect class Response : Closeable {
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
  val request: Request

  /** Returns the HTTP protocol, such as [Protocol.HTTP_1_1] or [Protocol.HTTP_1_0]. */
  val protocol: Protocol

  /** Returns the HTTP status message. */
  val message: String

  /** Returns the HTTP status code. */
  val code: Int

  ///**
  // * Returns the TLS handshake of the connection that carried this response, or null if the
  // * response was received without TLS.
  // */
  //val handshake: Handshake?

  /** Returns the HTTP headers. */
  val headers: Headers

  /**
   * Returns a non-null value if this response was passed to [Callback.onResponse] or returned
   * from [Call.execute]. Response bodies must be [closed][ResponseBody] and may
   * be consumed only once.
   *
   * This always returns null on responses returned from [cacheResponse], [networkResponse],
   * and [priorResponse].
   */
  val body: ResponseBody

  /**
   * Returns the raw response received from the network. Will be null if this response didn't use
   * the network, such as when the response is fully cached. The body of the returned response
   * should not be read.
   */
  val networkResponse: Response?

  /**
   * Returns the raw response received from the cache. Will be null if this response didn't use
   * the cache. For conditional get requests the cache response and network response may both be
   * non-null. The body of the returned response should not be read.
   */
  val cacheResponse: Response?

  /**
   * Returns the response for the HTTP redirect or authorization challenge that triggered this
   * response, or null if this response wasn't triggered by an automatic retry. The body of the
   * returned response should not be read because it has already been consumed by the redirecting
   * client.
   */
  val priorResponse: Response?

  internal var lazyCacheControl: CacheControl?

  /**
   * Returns true if the code is in [200..300), which means the request was successfully received,
   * understood, and accepted.
   */
  val isSuccessful: Boolean

  fun headers(name: String): List<String>

  fun header(name: String, defaultValue: String? = null): String?

  /**
   * Returns the trailers after the HTTP response, which may be empty. It is an error to call this
   * before the entire HTTP response body has been consumed.
   */
  @Throws(IOException::class)
  fun trailers(): Headers

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
  fun peekBody(byteCount: Long): ResponseBody

  fun newBuilder(): Builder

  /** Returns true if this response redirects to another resource. */
  val isRedirect: Boolean

  // /**
  //  * Returns the RFC 7235 authorization challenges appropriate for this response's code. If the
  //  * response code is 401 unauthorized, this returns the "WWW-Authenticate" challenges. If the
  //  * response code is 407 proxy unauthorized, this returns the "Proxy-Authenticate" challenges.
  //  * Otherwise this returns an empty list of challenges.
  //  *
  //  * If a challenge uses the `token68` variant instead of auth params, there is exactly one
  //  * auth param in the challenge at key null. Invalid headers and challenges are ignored.
  //  * No semantic validation is done, for example that `Basic` auth must have a `realm`
  //  * auth param, this is up to the caller that interprets these challenges.
  //  */
  // fun challenges(): List<Challenge>

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no `Cache-Control` header.
   */
  val cacheControl: CacheControl

  /**
   * Closes the response body. Equivalent to `body().close()`.
   *
   * Prior to OkHttp 5.0, it was an error to close a response that is not eligible for a body. This
   * includes the responses returned from [cacheResponse], [networkResponse], and [priorResponse].
   */
  override fun close()

  override fun toString(): String

  open class Builder {
    internal var request: Request?
    internal var protocol: Protocol?
    internal var code: Int
    internal var message: String?
    // internal var handshake: Handshake?
    internal var headers: Headers.Builder
    internal var body: ResponseBody
    internal var networkResponse: Response?
    internal var cacheResponse: Response?
    internal var priorResponse: Response?
    internal var trailersFn: (() -> Headers)

    constructor()

    internal constructor(response: Response)

    open fun request(request: Request): Builder

    open fun protocol(protocol: Protocol): Builder

    open fun code(code: Int): Builder

    open fun message(message: String): Builder

    // open fun handshake(handshake: Handshake?): Builder

    /**
     * Sets the header named [name] to [value]. If this request already has any headers
     * with that name, they are all replaced.
     */
    open fun header(name: String, value: String): Builder

    /**
     * Adds a header with [name] to [value]. Prefer this method for multiply-valued
     * headers like "Set-Cookie".
     */
    open fun addHeader(name: String, value: String): Builder

    /** Removes all headers named [name] on this builder. */
    open fun removeHeader(name: String): Builder

    /** Removes all headers on this builder and adds [headers]. */
    open fun headers(headers: Headers): Builder

    open fun trailers(trailersFn: (() -> Headers)): Builder

    open fun body(body: ResponseBody): Builder

    open fun networkResponse(networkResponse: Response?): Builder

    open fun cacheResponse(cacheResponse: Response?): Builder

    open fun priorResponse(priorResponse: Response?): Builder

    open fun build(): Response
  }
}
