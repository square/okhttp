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

actual class Request internal constructor(
  actual val url: String,
  actual val method: String,
  actual val headers: Headers,
  actual val body: RequestBody?,
) {
  internal actual var lazyCacheControl: CacheControl? = null

  actual val isHttps: Boolean
    get() = url.startsWith("https://")

  actual fun header(name: String): String? = commonHeader(name)

  actual fun headers(name: String): List<String> = commonHeaders(name)

  actual fun newBuilder(): Builder = commonNewBuilder()

  actual val cacheControl: CacheControl
    get() = commonCacheControl()

  override fun toString(): String = buildString {
    append("Request{method=")
    append(method)
    append(", url=")
    append(url)
    if (headers.size != 0) {
      append(", headers=[")
      headers.forEachIndexed { index, (name, value) ->
        if (index > 0) {
          append(", ")
        }
        append(name)
        append(':')
        append(value)
      }
      append(']')
    }
    // if (tags.isNotEmpty()) {
    //   append(", tags=")
    //   append(tags)
    // }
    append('}')
  }

  actual open class Builder {
    internal actual var url: String? = null
    internal actual var method: String
    internal actual var headers: Headers.Builder
    internal actual var body: RequestBody? = null

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
      // this.tags = if (request.tags.isEmpty()) {
      //   mutableMapOf()
      // } else {
      //   request.tags.toMutableMap()
      // }
      this.headers = request.headers.newBuilder()
    }

    // open fun url(url: HttpUrl): Builder = apply {
    //   this.url = url
    // }

    actual open fun url(url: String): Builder = apply {
      this.url = canonicalUrl(url)
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

    // /** Attaches [tag] to the request using `Object.class` as a key. */
    // open fun tag(tag: Any?): Builder = tag(Any::class.java, tag)

    // /**
    //  * Attaches [tag] to the request using [type] as a key. Tags can be read from a
    //  * request using [Request.tag]. Use null to remove any existing tag assigned for [type].
    //  *
    //  * Use this API to attach timing, debugging, or other application data to a request so that
    //  * you may read it in interceptors, event listeners, or callbacks.
    //  */
    // open fun <T> tag(type: Class<in T>, tag: T?) = apply {
    //   if (tag == null) {
    //     tags.remove(type)
    //   } else {
    //     if (tags.isEmpty()) {
    //       tags = mutableMapOf()
    //     }
    //     tags[type] = type.cast(tag)!! // Force-unwrap due to lack of contracts on Class#cast()
    //   }
    // }

    actual open fun build(): Request {
      return Request(
          checkNotNull(url) { "url == null" },
          method,
          headers.build(),
          body,
      )
    }
  }
}
