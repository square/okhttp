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

import okhttp3.internal.HttpUrlRepresentation
import okhttp3.internal.commonEmptyRequestBody

/**
 * An HTTP request. Instances of this class are immutable if their [body] is null or itself
 * immutable.
 */
expect class Request {
  val url: HttpUrlRepresentation
  val method: String
  val headers: Headers
  val body: RequestBody?

  internal var lazyCacheControl: CacheControl?

  val isHttps: Boolean

  fun header(name: String): String?

  fun headers(name: String): List<String>

  // /**
  //  * Returns the tag attached with `Object.class` as a key, or null if no tag is attached with
  //  * that key.
  //  *
  //  * Prior to OkHttp 3.11, this method never returned null if no tag was attached. Instead it
  //  * returned either this request, or the request upon which this request was derived with
  //  * [newBuilder].
  //  */
  // fun tag(): Any? = tag(Any::class.java)
  //
  // /**
  //  * Returns the tag attached with [type] as a key, or null if no tag is attached with that
  //  * key.
  //  */
  // fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])

  fun newBuilder(): Builder

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no `Cache-Control` header.
   */
  val cacheControl: CacheControl

  open class Builder {
    internal var url: HttpUrlRepresentation?
    internal var method: String
    internal var headers: Headers.Builder
    internal var body: RequestBody?

    constructor()

    internal constructor(request: Request)

    // /** A mutable map of tags, or an immutable empty map if we don't have any. */
    // internal var tags: MutableMap<Class<*>, Any> = mutableMapOf()

    // Wait for HttpUrl
    // open fun url(url: HttpUrl): Builder

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if [url] is not a valid HTTP or HTTPS URL. Avoid this
     *     exception by calling [HttpUrl.parse]; it returns null for invalid URLs.
     */
    open fun url(url: String): Builder

    /**
     * Sets the header named [name] to [value]. If this request already has any headers
     * with that name, they are all replaced.
     */
    open fun header(name: String, value: String): Builder

    /**
     * Adds a header with [name] and [value]. Prefer this method for multiply-valued
     * headers like "Cookie".
     *
     * Note that for some headers including `Content-Length` and `Content-Encoding`,
     * OkHttp may replace [value] with a header derived from the request body.
     */
    open fun addHeader(name: String, value: String): Builder

    /** Removes all headers named [name] on this builder. */
    open fun removeHeader(name: String): Builder

    /** Removes all headers on this builder and adds [headers]. */
    open fun headers(headers: Headers): Builder

    /**
     * Sets this request's `Cache-Control` header, replacing any cache control headers already
     * present. If [cacheControl] doesn't define any directives, this clears this request's
     * cache-control headers.
     */
    open fun cacheControl(cacheControl: CacheControl): Builder

    open fun get(): Builder

    open fun head(): Builder

    open fun post(body: RequestBody): Builder

    open fun delete(body: RequestBody? = commonEmptyRequestBody): Builder

    open fun put(body: RequestBody): Builder

    open fun patch(body: RequestBody): Builder

    open fun method(method: String, body: RequestBody?): Builder

    // /** Attaches [tag] to the request using `Object.class` as a key. */
    // open fun tag(tag: Any?): Builder = tag(Any::class.java, tag)
    //
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

    open fun build(): Request
  }
}
