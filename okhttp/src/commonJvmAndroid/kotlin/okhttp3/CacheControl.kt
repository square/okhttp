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

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import okhttp3.internal.commonBuild
import okhttp3.internal.commonClampToInt
import okhttp3.internal.commonForceCache
import okhttp3.internal.commonForceNetwork
import okhttp3.internal.commonImmutable
import okhttp3.internal.commonNoCache
import okhttp3.internal.commonNoStore
import okhttp3.internal.commonNoTransform
import okhttp3.internal.commonOnlyIfCached
import okhttp3.internal.commonParse
import okhttp3.internal.commonToString

/**
 * A Cache-Control header with cache directives from a server or client. These directives set policy
 * on what responses can be stored, and which requests can be satisfied by those stored responses.
 *
 * See [RFC 7234, 5.2](https://tools.ietf.org/html/rfc7234#section-5.2).
 */
class CacheControl internal constructor(
  /**
   * In a response, this field's name "no-cache" is misleading. It doesn't prevent us from caching
   * the response; it only means we have to validate the response with the origin server before
   * returning it. We can do this with a conditional GET.
   *
   * In a request, it means do not use a cache to satisfy the request.
   */
  @get:JvmName("noCache") val noCache: Boolean,
  /** If true, this response should not be cached. */
  @get:JvmName("noStore") val noStore: Boolean,
  /** The duration past the response's served date that it can be served without validation. */
  @get:JvmName("maxAgeSeconds") val maxAgeSeconds: Int,
  /**
   * The "s-maxage" directive is the max age for shared caches. Not to be confused with "max-age"
   * for non-shared caches, As in Firefox and Chrome, this directive is not honored by this cache.
   */
  @get:JvmName("sMaxAgeSeconds") val sMaxAgeSeconds: Int,
  val isPrivate: Boolean,
  val isPublic: Boolean,
  @get:JvmName("mustRevalidate") val mustRevalidate: Boolean,
  @get:JvmName("maxStaleSeconds") val maxStaleSeconds: Int,
  @get:JvmName("minFreshSeconds") val minFreshSeconds: Int,
  /**
   * This field's name "only-if-cached" is misleading. It actually means "do not use the network".
   * It is set by a client who only wants to make a request if it can be fully satisfied by the
   * cache. Cached responses that would require validation (ie. conditional gets) are not permitted
   * if this header is set.
   */
  @get:JvmName("onlyIfCached") val onlyIfCached: Boolean,
  @get:JvmName("noTransform") val noTransform: Boolean,
  @get:JvmName("immutable") val immutable: Boolean,
  internal var headerValue: String?,
) {
  @JvmName("-deprecated_noCache")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "noCache"),
    level = DeprecationLevel.ERROR,
  )
  fun noCache(): Boolean = noCache

  @JvmName("-deprecated_noStore")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "noStore"),
    level = DeprecationLevel.ERROR,
  )
  fun noStore(): Boolean = noStore

  @JvmName("-deprecated_maxAgeSeconds")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "maxAgeSeconds"),
    level = DeprecationLevel.ERROR,
  )
  fun maxAgeSeconds(): Int = maxAgeSeconds

  @JvmName("-deprecated_sMaxAgeSeconds")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "sMaxAgeSeconds"),
    level = DeprecationLevel.ERROR,
  )
  fun sMaxAgeSeconds(): Int = sMaxAgeSeconds

  @JvmName("-deprecated_mustRevalidate")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "mustRevalidate"),
    level = DeprecationLevel.ERROR,
  )
  fun mustRevalidate(): Boolean = mustRevalidate

  @JvmName("-deprecated_maxStaleSeconds")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "maxStaleSeconds"),
    level = DeprecationLevel.ERROR,
  )
  fun maxStaleSeconds(): Int = maxStaleSeconds

  @JvmName("-deprecated_minFreshSeconds")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "minFreshSeconds"),
    level = DeprecationLevel.ERROR,
  )
  fun minFreshSeconds(): Int = minFreshSeconds

  @JvmName("-deprecated_onlyIfCached")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "onlyIfCached"),
    level = DeprecationLevel.ERROR,
  )
  fun onlyIfCached(): Boolean = onlyIfCached

  @JvmName("-deprecated_noTransform")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "noTransform"),
    level = DeprecationLevel.ERROR,
  )
  fun noTransform(): Boolean = noTransform

  @JvmName("-deprecated_immutable")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "immutable"),
    level = DeprecationLevel.ERROR,
  )
  fun immutable(): Boolean = immutable

  override fun toString(): String = commonToString()

  /** Builds a `Cache-Control` request header. */
  class Builder {
    internal var noCache: Boolean = false
    internal var noStore: Boolean = false
    internal var maxAgeSeconds = -1
    internal var maxStaleSeconds = -1
    internal var minFreshSeconds = -1
    internal var onlyIfCached: Boolean = false
    internal var noTransform: Boolean = false
    internal var immutable: Boolean = false

    /** Don't accept an unvalidated cached response. */
    fun noCache() = commonNoCache()

    /** Don't store the server's response in any cache. */
    fun noStore() = commonNoStore()

    /**
     * Only accept the response if it is in the cache. If the response isn't cached, a `504
     * Unsatisfiable Request` response will be returned.
     */
    fun onlyIfCached() = commonOnlyIfCached()

    /** Don't accept a transformed response. */
    fun noTransform() = commonNoTransform()

    fun immutable() = commonImmutable()

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds [maxAge], it
     * will not be used and a network request will be made.
     *
     * @param maxAge a non-negative duration. This is stored and transmitted with [TimeUnit.SECONDS]
     *     precision; finer precision will be lost.
     */
    fun maxAge(maxAge: Duration) =
      apply {
        val maxAgeSeconds = maxAge.inWholeSeconds
        require(maxAgeSeconds >= 0) { "maxAge < 0: $maxAgeSeconds" }
        this.maxAgeSeconds = maxAgeSeconds.commonClampToInt()
      }

    fun maxStale(maxStale: Duration) =
      apply {
        val maxStaleSeconds = maxStale.inWholeSeconds
        require(maxStaleSeconds >= 0) { "maxStale < 0: $maxStaleSeconds" }
        this.maxStaleSeconds = maxStaleSeconds.commonClampToInt()
      }

    fun minFresh(minFresh: Duration) =
      apply {
        val minFreshSeconds = minFresh.inWholeSeconds
        require(minFreshSeconds >= 0) { "minFresh < 0: $minFreshSeconds" }
        this.minFreshSeconds = minFreshSeconds.commonClampToInt()
      }

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds [maxAge], it
     * will not be used and a network request will be made.
     *
     * @param maxAge a non-negative integer. This is stored and transmitted with [TimeUnit.SECONDS]
     *     precision; finer precision will be lost.
     */
    fun maxAge(
      maxAge: Int,
      timeUnit: TimeUnit,
    ) = apply {
      require(maxAge >= 0) { "maxAge < 0: $maxAge" }
      val maxAgeSecondsLong = timeUnit.toSeconds(maxAge.toLong())
      this.maxAgeSeconds = maxAgeSecondsLong.commonClampToInt()
    }

    /**
     * Accept cached responses that have exceeded their freshness lifetime by up to `maxStale`. If
     * unspecified, stale cache responses will not be used.
     *
     * @param maxStale a non-negative integer. This is stored and transmitted with
     *     [TimeUnit.SECONDS] precision; finer precision will be lost.
     */
    fun maxStale(
      maxStale: Int,
      timeUnit: TimeUnit,
    ) = apply {
      require(maxStale >= 0) { "maxStale < 0: $maxStale" }
      val maxStaleSecondsLong = timeUnit.toSeconds(maxStale.toLong())
      this.maxStaleSeconds = maxStaleSecondsLong.commonClampToInt()
    }

    /**
     * Sets the minimum number of seconds that a response will continue to be fresh for. If the
     * response will be stale when [minFresh] have elapsed, the cached response will not be used and
     * a network request will be made.
     *
     * @param minFresh a non-negative integer. This is stored and transmitted with
     *     [TimeUnit.SECONDS] precision; finer precision will be lost.
     */
    fun minFresh(
      minFresh: Int,
      timeUnit: TimeUnit,
    ) = apply {
      require(minFresh >= 0) { "minFresh < 0: $minFresh" }
      val minFreshSecondsLong = timeUnit.toSeconds(minFresh.toLong())
      this.minFreshSeconds = minFreshSecondsLong.commonClampToInt()
    }

    fun build(): CacheControl = commonBuild()
  }

  companion object {
    /**
     * Cache control request directives that require network validation of responses. Note that such
     * requests may be assisted by the cache via conditional GET requests.
     */
    @JvmField
    val FORCE_NETWORK = commonForceNetwork()

    /**
     * Cache control request directives that uses the cache only, even if the cached response is
     * stale. If the response isn't available in the cache or requires server validation, the call
     * will fail with a `504 Unsatisfiable Request`.
     */
    @JvmField
    val FORCE_CACHE = commonForceCache()

    /**
     * Returns the cache directives of [headers]. This honors both Cache-Control and Pragma headers
     * if they are present.
     */
    @JvmStatic
    fun parse(headers: Headers): CacheControl = commonParse(headers)
  }
}
