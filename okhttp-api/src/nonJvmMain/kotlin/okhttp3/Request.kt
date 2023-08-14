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
import okhttp3.internal.commonNewBuilder
import okhttp3.internal.commonPatch
import okhttp3.internal.commonPost
import okhttp3.internal.commonPut
import okhttp3.internal.commonRemoveHeader
import okhttp3.internal.commonTag
import okhttp3.internal.commonToString
import kotlin.reflect.KClass

actual class Request internal actual constructor(builder: Builder) {
  actual val url: HttpUrl = checkNotNull(builder.url) { "url == null" }
  actual val method: String = builder.method
  actual val headers: Headers = builder.headers.build()
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

  actual fun newBuilder(): Builder = commonNewBuilder()

  actual val cacheControl: CacheControl
    get() = commonCacheControl()

  actual inline fun <reified T : Any> tag(): T? = tag(T::class)

  actual fun <T : Any> tag(type: KClass<T>): T? = tags[type] as T?

  override fun toString(): String = commonToString()

  actual open class Builder {
    internal actual var url: HttpUrl? = null
    internal actual var method: String
    internal actual var headers: Headers.Builder
    internal actual var body: RequestBody? = null
    internal actual var tags = mapOf<KClass<*>, Any>()

    // /** A mutable map of tags, or an immutable empty map if we don't have any. */
    // internal var tags: MutableMap<Class<*>, Any> = mutableMapOf()

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

    actual open fun url(url: String): Builder = apply {
      url(canonicalUrl(url).toHttpUrl())
    }

    actual open fun header(name: String, value: String) = commonHeader(name, value)

    actual open fun addHeader(name: String, value: String) = commonAddHeader(name, value)

    actual open fun removeHeader(name: String) = commonRemoveHeader(name)

    actual open fun headers(headers: Headers) = commonHeaders(headers)

    actual open fun cacheControl(cacheControl: CacheControl): Builder = commonCacheControl(cacheControl)

    actual open fun get(): Builder = commonGet()

    actual open fun head(): Builder = commonHead()

    actual open fun post(body: RequestBody): Builder = commonPost(body)

    actual open fun delete(body: RequestBody?): Builder = commonDelete(body)

    actual open fun put(body: RequestBody): Builder = commonPut(body)

    actual open fun patch(body: RequestBody): Builder = commonPatch(body)

    actual open fun method(method: String, body: RequestBody?): Builder = commonMethod(method, body)

    actual inline fun <reified T : Any> tag(tag: T?): Builder = tag(T::class, tag)

    actual fun <T : Any> tag(type: KClass<T>, tag: T?): Builder = commonTag(type, tag)

    actual open fun build(): Request = Request(this)
  }
}
