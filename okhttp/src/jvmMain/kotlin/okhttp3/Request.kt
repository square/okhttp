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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.canonicalUrl
import okhttp3.internal.commonAddHeader
import okhttp3.internal.commonCacheControl
import okhttp3.internal.commonDelete
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

actual class Request internal actual constructor(builder: Builder) {
  @get:JvmName("url")
  actual val url: HttpUrl = checkNotNull(builder.url) { "url == null" }

  @get:JvmName("method")
  actual val method: String = builder.method

  @get:JvmName("headers")
  actual val headers: Headers = builder.headers.build()

  @get:JvmName("body")
  actual val body: RequestBody? = builder.body

  internal actual val tags: Map<KClass<*>, Any> = builder.tags.toMap()

  internal actual var lazyCacheControl: CacheControl? = null

  actual val isHttps: Boolean
    get() = url.isHttps

  /**
   * Constructs a new request.
   *
   * Use [Builder] for more fluent construction, including helper methods for various HTTP methods.
   *
   * @param method defaults to "GET" if [body] is null, and "POST" otherwise.
   */
  actual constructor(
    url: HttpUrl,
    headers: Headers,
    method: String,
    body: RequestBody?,
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
        body
      )
  )

  actual fun header(name: String): String? = commonHeader(name)

  actual fun headers(name: String): List<String> = commonHeaders(name)

  @JvmName("reifiedTag")
  actual inline fun <reified T : Any> tag(): T? = tag(T::class)

  actual fun <T : Any> tag(type: KClass<T>): T? = type.java.cast(tags[type])

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

  actual fun newBuilder(): Builder = Builder(this)

  @get:JvmName("cacheControl") actual val cacheControl: CacheControl
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
      level = DeprecationLevel.ERROR)
  fun url(): HttpUrl = url

  @JvmName("-deprecated_method")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "method"),
      level = DeprecationLevel.ERROR)
  fun method(): String = method

  @JvmName("-deprecated_headers")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "headers"),
      level = DeprecationLevel.ERROR)
  fun headers(): Headers = headers

  @JvmName("-deprecated_body")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "body"),
      level = DeprecationLevel.ERROR)
  fun body(): RequestBody? = body

  @JvmName("-deprecated_cacheControl")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "cacheControl"),
      level = DeprecationLevel.ERROR)
  fun cacheControl(): CacheControl = cacheControl

  override fun toString(): String = commonToString()

  actual open class Builder {
    internal actual var url: HttpUrl? = null
    internal actual var method: String
    internal actual var headers: Headers.Builder
    internal actual var body: RequestBody? = null
    internal actual var tags = mapOf<KClass<*>, Any>()

    actual constructor() {
      this.method = "GET"
      this.headers = Headers.Builder()
    }

    internal actual constructor(request: Request) {
      this.url = request.url
      this.method = request.method
      this.body = request.body
      this.tags = when {
        request.tags.isEmpty() -> mapOf()
        else -> request.tags.toMutableMap()
      }
      this.headers = request.headers.newBuilder()
    }

    actual open fun url(url: HttpUrl): Builder = apply {
      this.url = url
    }

    actual open fun url(url: String): Builder {
      return url(canonicalUrl(url).toHttpUrl())
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if the scheme of [url] is not `http` or `https`.
     */
    open fun url(url: URL) = url(url.toString().toHttpUrl())

    actual open fun header(name: String, value: String) = commonHeader(name, value)

    actual open fun addHeader(name: String, value: String) = commonAddHeader(name, value)

    actual open fun removeHeader(name: String) = commonRemoveHeader(name)

    actual open fun headers(headers: Headers) = commonHeaders(headers)

    actual open fun cacheControl(cacheControl: CacheControl): Builder = commonCacheControl(cacheControl)

    actual open fun get(): Builder = commonGet()

    actual open fun head(): Builder = commonHead()

    actual open fun post(body: RequestBody): Builder = commonPost(body)

    @JvmOverloads
    actual open fun delete(body: RequestBody?): Builder = commonDelete(body)

    actual open fun put(body: RequestBody): Builder = commonPut(body)

    actual open fun patch(body: RequestBody): Builder = commonPatch(body)

    actual open fun method(method: String, body: RequestBody?): Builder = commonMethod(method, body)

    @JvmName("reifiedTag")
    actual inline fun <reified T : Any> tag(tag: T?): Builder = tag(T::class, tag)

    actual fun <T : Any> tag(type: KClass<T>, tag: T?): Builder = commonTag(type, type.cast(tag))

    /** Attaches [tag] to the request using `Object.class` as a key. */
    open fun tag(tag: Any?): Builder = commonTag(Any::class, tag)

    /**
     * Attaches [tag] to the request using [type] as a key. Tags can be read from a
     * request using [Request.tag]. Use null to remove any existing tag assigned for [type].
     *
     * Use this API to attach timing, debugging, or other application data to a request so that
     * you may read it in interceptors, event listeners, or callbacks.
     */
    open fun <T> tag(type: Class<in T>, tag: T?) = commonTag(type.kotlin, tag)

    actual open fun build(): Request = Request(this)
  }
}
