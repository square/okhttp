/*
 * Copyright (C) 2019 Square, Inc.
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

import kotlin.time.DurationUnit

/**
 * A Cache-Control header with cache directives from a server or client. These directives set policy
 * on what responses can be stored, and which requests can be satisfied by those stored responses.
 *
 * See [RFC 7234, 5.2](https://tools.ietf.org/html/rfc7234#section-5.2).
 */
expect class CacheControl internal constructor(
  noCache: Boolean,

  noStore: Boolean,

  maxAgeSeconds: Int,

  sMaxAgeSeconds: Int,

  isPrivate: Boolean,
  isPublic: Boolean,

  mustRevalidate: Boolean,

  maxStaleSeconds: Int,

  minFreshSeconds: Int,

  onlyIfCached: Boolean,

  noTransform: Boolean,

  immutable: Boolean,

  headerValue: String?
) {

  /**
   * In a response, this field's name "no-cache" is misleading. It doesn't prevent us from caching
   * the response; it only means we have to validate the response with the origin server before
   * returning it. We can do this with a conditional GET.
   *
   * In a request, it means do not use a cache to satisfy the request.
   */
  val noCache: Boolean

  /** If true, this response should not be cached. */
  val noStore: Boolean

  /** The duration past the response's served date that it can be served without validation. */
  val maxAgeSeconds: Int

  /**
   * The "s-maxage" directive is the max age for shared caches. Not to be confused with "max-age"
   * for non-shared caches, As in Firefox and Chrome, this directive is not honored by this cache.
   */
  val sMaxAgeSeconds: Int

  val isPrivate: Boolean
  val isPublic: Boolean

  val mustRevalidate: Boolean

  val maxStaleSeconds: Int

  val minFreshSeconds: Int

  /**
   * This field's name "only-if-cached" is misleading. It actually means "do not use the network".
   * It is set by a client who only wants to make a request if it can be fully satisfied by the
   * cache. Cached responses that would require validation (ie. conditional gets) are not permitted
   * if this header is set.
   */
  val onlyIfCached: Boolean

  val noTransform: Boolean

  val immutable: Boolean

  internal var headerValue: String?

  override fun toString(): String

  /** Builds a `Cache-Control` request header. */
  class Builder constructor() {
    internal var noCache: Boolean
    internal var noStore: Boolean
    internal var maxAgeSeconds: Int
    internal var maxStaleSeconds: Int
    internal var minFreshSeconds: Int
    internal var onlyIfCached: Boolean
    internal var noTransform: Boolean
    internal var immutable: Boolean

    /** Don't accept an unvalidated cached response. */
    fun noCache(): Builder

    /** Don't store the server's response in any cache. */
    fun noStore(): Builder

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds [maxAge], it
     * will not be used and a network request will be made.
     *
     * @param maxAge a non-negative integer. This is stored and transmitted with [TimeUnit.SECONDS]
     *     precision; finer precision will be lost.
     */
    fun maxAge(maxAge: Int, timeUnit: DurationUnit): Builder

    /**
     * Accept cached responses that have exceeded their freshness lifetime by up to `maxStale`. If
     * unspecified, stale cache responses will not be used.
     *
     * @param maxStale a non-negative integer. This is stored and transmitted with
     *     [TimeUnit.SECONDS] precision; finer precision will be lost.
     */
    fun maxStale(maxStale: Int, timeUnit: DurationUnit): Builder

    /**
     * Sets the minimum number of seconds that a response will continue to be fresh for. If the
     * response will be stale when [minFresh] have elapsed, the cached response will not be used and
     * a network request will be made.
     *
     * @param minFresh a non-negative integer. This is stored and transmitted with
     *     [TimeUnit.SECONDS] precision; finer precision will be lost.
     */
    fun minFresh(minFresh: Int, timeUnit: DurationUnit): Builder

    /**
     * Only accept the response if it is in the cache. If the response isn't cached, a `504
     * Unsatisfiable Request` response will be returned.
     */
    fun onlyIfCached(): Builder

    /** Don't accept a transformed response. */
    fun noTransform(): Builder

    fun immutable(): Builder

    fun build(): CacheControl
  }

  companion object {
    /**
     * Cache control request directives that require network validation of responses. Note that such
     * requests may be assisted by the cache via conditional GET requests.
     */
    val FORCE_NETWORK: CacheControl

    /**
     * Cache control request directives that uses the cache only, even if the cached response is
     * stale. If the response isn't available in the cache or requires server validation, the call
     * will fail with a `504 Unsatisfiable Request`.
     */
   val FORCE_CACHE: CacheControl

    /**
     * Returns the cache directives of [headers]. This honors both Cache-Control and Pragma headers
     * if they are present.
     */
   fun parse(headers: Headers): CacheControl
  }
}
