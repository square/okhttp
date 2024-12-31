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

import java.net.URL
import kotlin.reflect.KClass
import kotlin.reflect.cast
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.canonicalUrl
import okhttp3.internal.commonAddHeader
import okhttp3.internal.commonCacheControl
import okhttp3.internal.commonDelete
import okhttp3.internal.commonEmptyRequestBody
import okhttp3.internal.commonGet
import okhttp3.internal.commonHead
import okhttp3.internal.commonHeader
import okhttp3.internal.commonHeaders
import okhttp3.internal.commonMethod
import okhttp3.internal.commonPatch
import okhttp3.internal.commonPost
import okhttp3.internal.commonPut
import okhttp3.internal.commonRemoveHeader
import okhttp3.internal.commonTag
import okhttp3.internal.commonToString

/**
 * An HTTP request. Instances of this class are immutable if their [body] is null or itself
 * immutable.
 */
class Request internal constructor(builder: Builder) {
  @get:JvmName("url")
  val url: HttpUrl = checkNotNull(builder.url) { "url == null" }

  @get:JvmName("method")
  val method: String = builder.method

  @get:JvmName("headers")
  val headers: Headers = builder.headers.build()

  @get:JvmName("body")
  val body: RequestBody? = builder.body

  @get:JvmName("cacheUrlOverride")
  val cacheUrlOverride: HttpUrl? = builder.cacheUrlOverride

  internal val tags: Map<KClass<*>, Any> = builder.tags.toMap()

  internal var lazyCacheControl: CacheControl? = null

  val isHttps: Boolean
    get() = url.isHttps

  /**
   * Constructs a new request.
   *
   * Use [Builder] for more fluent construction, including helper methods for various HTTP methods.
   *
   * @param method defaults to "GET" if [body] is null, and "POST" otherwise.
   */
  constructor(
    url: HttpUrl,
    headers: Headers = headersOf(),
    // '\u0000' is a sentinel value that'll choose based on what the body is:
    method: String = "\u0000",
    body: RequestBody? = null,
  ) : this(
    Builder()
      .url(url)
      .headers(headers)
      .method(
        when {
          method != "\u0000" -> method
          body != null -> "POST"
          else -> "GET"
        },
        body,
      ),
  )

  fun header(name: String): String? = commonHeader(name)

  fun headers(name: String): List<String> = commonHeaders(name)

  /** Returns the tag attached with [T] as a key, or null if no tag is attached with that key. */
  @JvmName("reifiedTag")
  inline fun <reified T : Any> tag(): T? = tag(T::class)

  /** Returns the tag attached with [type] as a key, or null if no tag is attached with that key. */
  fun <T : Any> tag(type: KClass<T>): T? = type.java.cast(tags[type])

  /**
   * Returns the tag attached with `Object.class` as a key, or null if no tag is attached with
   * that key.
   *
   * Prior to OkHttp 3.11, this method never returned null if no tag was attached. Instead it
   * returned either this request, or the request upon which this request was derived with
   * [newBuilder].
   *
   * @suppress this method breaks Dokka! https://github.com/Kotlin/dokka/issues/2473
   */
  fun tag(): Any? = tag<Any>()

  /**
   * Returns the tag attached with [type] as a key, or null if no tag is attached with that
   * key.
   */
  fun <T> tag(type: Class<out T>): T? = tag(type.kotlin)

  fun newBuilder(): Builder = Builder(this)

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no `Cache-Control` header.
   */
  @get:JvmName("cacheControl")
  val cacheControl: CacheControl
    get() {
      var result = lazyCacheControl
      if (result == null) {
        result = CacheControl.parse(headers)
        lazyCacheControl = result
      }
      return result
    }

  @JvmName("-deprecated_url")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "url"),
    level = DeprecationLevel.ERROR,
  )
  fun url(): HttpUrl = url

  @JvmName("-deprecated_method")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "method"),
    level = DeprecationLevel.ERROR,
  )
  fun method(): String = method

  @JvmName("-deprecated_headers")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "headers"),
    level = DeprecationLevel.ERROR,
  )
  fun headers(): Headers = headers

  @JvmName("-deprecated_body")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "body"),
    level = DeprecationLevel.ERROR,
  )
  fun body(): RequestBody? = body

  @JvmName("-deprecated_cacheControl")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "cacheControl"),
    level = DeprecationLevel.ERROR,
  )
  fun cacheControl(): CacheControl = cacheControl

  override fun toString(): String = commonToString()

  open class Builder {
    internal var url: HttpUrl? = null
    internal var method: String
    internal var headers: Headers.Builder
    internal var body: RequestBody? = null
    internal var cacheUrlOverride: HttpUrl? = null

    /** A mutable map of tags, or an immutable empty map if we don't have any. */
    internal var tags = mapOf<KClass<*>, Any>()

    constructor() {
      this.method = "GET"
      this.headers = Headers.Builder()
    }

    internal constructor(request: Request) {
      this.url = request.url
      this.method = request.method
      this.body = request.body
      this.tags =
        when {
          request.tags.isEmpty() -> mapOf()
          else -> request.tags.toMutableMap()
        }
      this.headers = request.headers.newBuilder()
      this.cacheUrlOverride = request.cacheUrlOverride
    }

    open fun url(url: HttpUrl): Builder =
      apply {
        this.url = url
      }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if [url] is not a valid HTTP or HTTPS URL. Avoid this
     *     exception by calling [HttpUrl.parse]; it returns null for invalid URLs.
     */
    open fun url(url: String): Builder {
      return url(canonicalUrl(url).toHttpUrl())
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if the scheme of [url] is not `http` or `https`.
     */
    open fun url(url: URL) = url(url.toString().toHttpUrl())

    /**
     * Sets the header named [name] to [value]. If this request already has any headers
     * with that name, they are all replaced.
     */
    open fun header(
      name: String,
      value: String,
    ) = commonHeader(name, value)

    /**
     * Adds a header with [name] and [value]. Prefer this method for multiply-valued
     * headers like "Cookie".
     *
     * Note that for some headers including `Content-Length` and `Content-Encoding`,
     * OkHttp may replace [value] with a header derived from the request body.
     */
    open fun addHeader(
      name: String,
      value: String,
    ) = commonAddHeader(name, value)

    /** Removes all headers named [name] on this builder. */
    open fun removeHeader(name: String) = commonRemoveHeader(name)

    /** Removes all headers on this builder and adds [headers]. */
    open fun headers(headers: Headers) = commonHeaders(headers)

    /**
     * Sets this request's `Cache-Control` header, replacing any cache control headers already
     * present. If [cacheControl] doesn't define any directives, this clears this request's
     * cache-control headers.
     */
    open fun cacheControl(cacheControl: CacheControl): Builder = commonCacheControl(cacheControl)

    open fun get(): Builder = commonGet()

    open fun head(): Builder = commonHead()

    open fun post(body: RequestBody): Builder = commonPost(body)

    @JvmOverloads
    open fun delete(body: RequestBody? = commonEmptyRequestBody): Builder = commonDelete(body)

    open fun put(body: RequestBody): Builder = commonPut(body)

    open fun patch(body: RequestBody): Builder = commonPatch(body)

    open fun method(
      method: String,
      body: RequestBody?,
    ): Builder = commonMethod(method, body)

    /**
     * Attaches [tag] to the request using [T] as a key. Tags can be read from a request using
     * [Request.tag]. Use null to remove any existing tag assigned for [T].
     *
     * Use this API to attach timing, debugging, or other application data to a request so that
     * you may read it in interceptors, event listeners, or callbacks.
     */
    @JvmName("reifiedTag")
    inline fun <reified T : Any> tag(tag: T?): Builder = tag(T::class, tag)

    /**
     * Attaches [tag] to the request using [type] as a key. Tags can be read from a request using
     * [Request.tag]. Use null to remove any existing tag assigned for [type].
     *
     * Use this API to attach timing, debugging, or other application data to a request so that
     * you may read it in interceptors, event listeners, or callbacks.
     */
    fun <T : Any> tag(
      type: KClass<T>,
      tag: T?,
    ): Builder = commonTag(type, type.cast(tag))

    /** Attaches [tag] to the request using `Object.class` as a key. */
    open fun tag(tag: Any?): Builder = commonTag(Any::class, tag)

    /**
     * Attaches [tag] to the request using [type] as a key. Tags can be read from a
     * request using [Request.tag]. Use null to remove any existing tag assigned for [type].
     *
     * Use this API to attach timing, debugging, or other application data to a request so that
     * you may read it in interceptors, event listeners, or callbacks.
     */
    open fun <T> tag(
      type: Class<in T>,
      tag: T?,
    ) = commonTag(type.kotlin, tag)

    /**
     * Override the [Request.url] for caching, if it is either polluted with
     * transient query params, or has a canonical URL possibly for a CDN.
     *
     * Note that POST requests will not be sent to the server if this URL is set
     * and matches a cached response.
     */
    fun cacheUrlOverride(cacheUrlOverride: HttpUrl?) =
      apply {
        this.cacheUrlOverride = cacheUrlOverride
      }

    open fun build(): Request = Request(this)
  }
}
