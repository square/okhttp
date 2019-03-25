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

import okhttp3.internal.http.HttpHeaders
import java.util.concurrent.TimeUnit

/**
 * A Cache-Control header with cache directives from a server or client. These directives set policy
 * on what responses can be stored, and which requests can be satisfied by those stored responses.
 *
 * See [RFC 7234, 5.2](https://tools.ietf.org/html/rfc7234#section-5.2).
 */
class CacheControl private constructor(
  private val noCache: Boolean,
  private val noStore: Boolean,
  private val maxAgeSeconds: Int,
  private val sMaxAgeSeconds: Int,
  val isPrivate: Boolean,
  val isPublic: Boolean,
  private val mustRevalidate: Boolean,
  private val maxStaleSeconds: Int,
  private val minFreshSeconds: Int,
  private val onlyIfCached: Boolean,
  private val noTransform: Boolean,
  private val immutable: Boolean,
  private var headerValue: String?
) {
  /**
   * In a response, this field's name "no-cache" is misleading. It doesn't prevent us from caching
   * the response; it only means we have to validate the response with the origin server before
   * returning it. We can do this with a conditional GET.
   *
   * In a request, it means do not use a cache to satisfy the request.
   */
  fun noCache() = noCache

  /** If true, this response should not be cached.  */
  fun noStore() = noStore

  /** The duration past the response's served date that it can be served without validation. */
  fun maxAgeSeconds() = maxAgeSeconds

  /**
   * The "s-maxage" directive is the max age for shared caches. Not to be confused with "max-age"
   * for non-shared caches, As in Firefox and Chrome, this directive is not honored by this cache.
   */
  fun sMaxAgeSeconds() = sMaxAgeSeconds

  fun mustRevalidate() = mustRevalidate

  fun maxStaleSeconds() = maxStaleSeconds

  fun minFreshSeconds() = minFreshSeconds

  /**
   * This field's name "only-if-cached" is misleading. It actually means "do not use the network".
   * It is set by a client who only wants to make a request if it can be fully satisfied by the
   * cache. Cached responses that would require validation (ie. conditional gets) are not permitted
   * if this header is set.
   */
  fun onlyIfCached() = onlyIfCached

  fun noTransform() = noTransform

  fun immutable() = immutable

  override fun toString(): String {
    var result = headerValue
    if (result == null) {
      result = buildString {
        if (noCache) append("no-cache, ")
        if (noStore) append("no-store, ")
        if (maxAgeSeconds != -1) append("max-age=").append(maxAgeSeconds).append(", ")
        if (sMaxAgeSeconds != -1) append("s-maxage=").append(sMaxAgeSeconds).append(", ")
        if (isPrivate) append("private, ")
        if (isPublic) append("public, ")
        if (mustRevalidate) append("must-revalidate, ")
        if (maxStaleSeconds != -1) append("max-stale=").append(maxStaleSeconds).append(", ")
        if (minFreshSeconds != -1) append("min-fresh=").append(minFreshSeconds).append(", ")
        if (onlyIfCached) append("only-if-cached, ")
        if (noTransform) append("no-transform, ")
        if (immutable) append("immutable, ")
        if (isEmpty()) return ""
        delete(length - 2, length)
      }
      headerValue = result
    }
    return result
  }

  /** Builds a `Cache-Control` request header.  */
  class Builder {
    private var noCache: Boolean = false
    private var noStore: Boolean = false
    private var maxAgeSeconds = -1
    private var maxStaleSeconds = -1
    private var minFreshSeconds = -1
    private var onlyIfCached: Boolean = false
    private var noTransform: Boolean = false
    private var immutable: Boolean = false

    /** Don't accept an unvalidated cached response.  */
    fun noCache(): Builder {
      this.noCache = true
      return this
    }

    /** Don't store the server's response in any cache.  */
    fun noStore(): Builder {
      this.noStore = true
      return this
    }

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds `maxAge`, it
     * will not be used and a network request will be made.
     *
     * @param maxAge a non-negative integer. This is stored and transmitted with [TimeUnit.SECONDS]
     *     precision; finer precision will be lost.
     */
    fun maxAge(maxAge: Int, timeUnit: TimeUnit): Builder {
      if (maxAge < 0) throw IllegalArgumentException("maxAge < 0: $maxAge")
      val maxAgeSecondsLong = timeUnit.toSeconds(maxAge.toLong())
      this.maxAgeSeconds = maxAgeSecondsLong.clampToInt()
      return this
    }

    /**
     * Accept cached responses that have exceeded their freshness lifetime by up to `maxStale`. If
     * unspecified, stale cache responses will not be used.
     *
     * @param maxStale a non-negative integer. This is stored and transmitted with
     *     [TimeUnit.SECONDS] precision; finer precision will be lost.
     */
    fun maxStale(maxStale: Int, timeUnit: TimeUnit): Builder {
      if (maxStale < 0) throw IllegalArgumentException("maxStale < 0: $maxStale")
      val maxStaleSecondsLong = timeUnit.toSeconds(maxStale.toLong())
      this.maxStaleSeconds = maxStaleSecondsLong.clampToInt()
      return this
    }

    /**
     * Sets the minimum number of seconds that a response will continue to be fresh for. If the
     * response will be stale when `minFresh` have elapsed, the cached response will not be used and
     * a network request will be made.
     *
     * @param minFresh a non-negative integer. This is stored and transmitted with
     *     [TimeUnit.SECONDS] precision; finer precision will be lost.
     */
    fun minFresh(minFresh: Int, timeUnit: TimeUnit): Builder {
      if (minFresh < 0) throw IllegalArgumentException("minFresh < 0: $minFresh")
      val minFreshSecondsLong = timeUnit.toSeconds(minFresh.toLong())
      this.minFreshSeconds = minFreshSecondsLong.clampToInt()
      return this
    }

    /**
     * Only accept the response if it is in the cache. If the response isn't cached, a `504
     * Unsatisfiable Request` response will be returned.
     */
    fun onlyIfCached(): Builder {
      this.onlyIfCached = true
      return this
    }

    /** Don't accept a transformed response.  */
    fun noTransform(): Builder {
      this.noTransform = true
      return this
    }

    fun immutable(): Builder {
      this.immutable = true
      return this
    }

    private fun Long.clampToInt(): Int {
      return when {
        this > Integer.MAX_VALUE -> Integer.MAX_VALUE
        else -> toInt()
      }
    }

    fun build(): CacheControl {
      return CacheControl(noCache, noStore, maxAgeSeconds, -1, false, false, false, maxStaleSeconds,
          minFreshSeconds, onlyIfCached, noTransform, immutable, null)
    }
  }

  companion object {
    /**
     * Cache control request directives that require network validation of responses. Note that such
     * requests may be assisted by the cache via conditional GET requests.
     */
    @JvmField
    val FORCE_NETWORK = Builder()
        .noCache()
        .build()

    /**
     * Cache control request directives that uses the cache only, even if the cached response is
     * stale. If the response isn't available in the cache or requires server validation, the call
     * will fail with a `504 Unsatisfiable Request`.
     */
    @JvmField
    val FORCE_CACHE = Builder()
        .onlyIfCached()
        .maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the cache directives of `headers`. This honors both Cache-Control and Pragma headers
     * if they are present.
     */
    @JvmStatic
    fun parse(headers: Headers): CacheControl {
      var noCache = false
      var noStore = false
      var maxAgeSeconds = -1
      var sMaxAgeSeconds = -1
      var isPrivate = false
      var isPublic = false
      var mustRevalidate = false
      var maxStaleSeconds = -1
      var minFreshSeconds = -1
      var onlyIfCached = false
      var noTransform = false
      var immutable = false

      var canUseHeaderValue = true
      var headerValue: String? = null

      loop@ for (i in 0 until headers.size()) {
        val name = headers.name(i)
        val value = headers.value(i)

        when {
          name.equals("Cache-Control", ignoreCase = true) -> {
            if (headerValue != null) {
              // Multiple cache-control headers means we can't use the raw value.
              canUseHeaderValue = false
            } else {
              headerValue = value
            }
          }
          name.equals("Pragma", ignoreCase = true) -> {
            // Might specify additional cache-control params. We invalidate just in case.
            canUseHeaderValue = false
          }
          else -> {
            continue@loop
          }
        }

        var pos = 0
        while (pos < value.length) {
          val tokenStart = pos
          pos = HttpHeaders.skipUntil(value, pos, "=,;")
          val directive = value.substring(tokenStart, pos).trim { it <= ' ' }
          val parameter: String?

          if (pos == value.length || value[pos] == ',' || value[pos] == ';') {
            pos++ // Consume ',' or ';' (if necessary).
            parameter = null
          } else {
            pos++ // Consume '='.
            pos = HttpHeaders.skipWhitespace(value, pos)

            if (pos < value.length && value[pos] == '\"') {
              // Quoted string.
              pos++ // Consume '"' open quote.
              val parameterStart = pos
              pos = HttpHeaders.skipUntil(value, pos, "\"")
              parameter = value.substring(parameterStart, pos)
              pos++ // Consume '"' close quote (if necessary).

            } else {
              // Unquoted string.
              val parameterStart = pos
              pos = HttpHeaders.skipUntil(value, pos, ",;")
              parameter = value.substring(parameterStart, pos).trim { it <= ' ' }
            }
          }

          when {
            "no-cache".equals(directive, ignoreCase = true) -> {
              noCache = true
            }
            "no-store".equals(directive, ignoreCase = true) -> {
              noStore = true
            }
            "max-age".equals(directive, ignoreCase = true) -> {
              maxAgeSeconds = HttpHeaders.parseSeconds(parameter, -1)
            }
            "s-maxage".equals(directive, ignoreCase = true) -> {
              sMaxAgeSeconds = HttpHeaders.parseSeconds(parameter, -1)
            }
            "private".equals(directive, ignoreCase = true) -> {
              isPrivate = true
            }
            "public".equals(directive, ignoreCase = true) -> {
              isPublic = true
            }
            "must-revalidate".equals(directive, ignoreCase = true) -> {
              mustRevalidate = true
            }
            "max-stale".equals(directive, ignoreCase = true) -> {
              maxStaleSeconds = HttpHeaders.parseSeconds(parameter, Integer.MAX_VALUE)
            }
            "min-fresh".equals(directive, ignoreCase = true) -> {
              minFreshSeconds = HttpHeaders.parseSeconds(parameter, -1)
            }
            "only-if-cached".equals(directive, ignoreCase = true) -> {
              onlyIfCached = true
            }
            "no-transform".equals(directive, ignoreCase = true) -> {
              noTransform = true
            }
            "immutable".equals(directive, ignoreCase = true) -> {
              immutable = true
            }
          }
        }
      }

      if (!canUseHeaderValue) {
        headerValue = null
      }

      return CacheControl(noCache, noStore, maxAgeSeconds, sMaxAgeSeconds, isPrivate, isPublic,
          mustRevalidate, maxStaleSeconds, minFreshSeconds, onlyIfCached, noTransform, immutable,
          headerValue)
    }
  }
}
