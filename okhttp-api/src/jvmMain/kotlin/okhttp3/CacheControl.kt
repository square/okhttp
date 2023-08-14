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
import kotlin.time.DurationUnit
import okhttp3.internal.commonBuild
import okhttp3.internal.commonClampToInt
import okhttp3.internal.commonForceCache
import okhttp3.internal.commonForceNetwork
import okhttp3.internal.commonImmutable
import okhttp3.internal.commonMaxAge
import okhttp3.internal.commonMaxStale
import okhttp3.internal.commonMinFresh
import okhttp3.internal.commonNoCache
import okhttp3.internal.commonNoStore
import okhttp3.internal.commonNoTransform
import okhttp3.internal.commonOnlyIfCached
import okhttp3.internal.commonParse
import okhttp3.internal.commonToString

actual class CacheControl internal actual constructor(
  @get:JvmName("noCache") actual val noCache: Boolean,

  @get:JvmName("noStore") actual val noStore: Boolean,

  @get:JvmName("maxAgeSeconds") actual val maxAgeSeconds: Int,

  @get:JvmName("sMaxAgeSeconds") actual val sMaxAgeSeconds: Int,

  actual val isPrivate: Boolean,
  actual val isPublic: Boolean,

  @get:JvmName("mustRevalidate") actual val mustRevalidate: Boolean,

  @get:JvmName("maxStaleSeconds") actual val maxStaleSeconds: Int,

  @get:JvmName("minFreshSeconds") actual val minFreshSeconds: Int,

  @get:JvmName("onlyIfCached") actual val onlyIfCached: Boolean,

  @get:JvmName("noTransform") actual val noTransform: Boolean,

  @get:JvmName("immutable") actual val immutable: Boolean,

  internal actual var headerValue: String?
) {
  @JvmName("-deprecated_noCache")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "noCache"),
      level = DeprecationLevel.ERROR)
  fun noCache(): Boolean = noCache

  @JvmName("-deprecated_noStore")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "noStore"),
      level = DeprecationLevel.ERROR)
  fun noStore(): Boolean = noStore

  @JvmName("-deprecated_maxAgeSeconds")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "maxAgeSeconds"),
      level = DeprecationLevel.ERROR)
  fun maxAgeSeconds(): Int = maxAgeSeconds

  @JvmName("-deprecated_sMaxAgeSeconds")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "sMaxAgeSeconds"),
      level = DeprecationLevel.ERROR)
  fun sMaxAgeSeconds(): Int = sMaxAgeSeconds

  @JvmName("-deprecated_mustRevalidate")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "mustRevalidate"),
      level = DeprecationLevel.ERROR)
  fun mustRevalidate(): Boolean = mustRevalidate

  @JvmName("-deprecated_maxStaleSeconds")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "maxStaleSeconds"),
      level = DeprecationLevel.ERROR)
  fun maxStaleSeconds(): Int = maxStaleSeconds

  @JvmName("-deprecated_minFreshSeconds")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "minFreshSeconds"),
      level = DeprecationLevel.ERROR)
  fun minFreshSeconds(): Int = minFreshSeconds

  @JvmName("-deprecated_onlyIfCached")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "onlyIfCached"),
      level = DeprecationLevel.ERROR)
  fun onlyIfCached(): Boolean = onlyIfCached

  @JvmName("-deprecated_noTransform")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "noTransform"),
      level = DeprecationLevel.ERROR)
  fun noTransform(): Boolean = noTransform

  @JvmName("-deprecated_immutable")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "immutable"),
      level = DeprecationLevel.ERROR)
  fun immutable(): Boolean = immutable

  actual override fun toString(): String = commonToString()

  actual class Builder {
    internal actual var noCache: Boolean = false
    internal actual var noStore: Boolean = false
    internal actual var maxAgeSeconds = -1
    internal actual var maxStaleSeconds = -1
    internal actual var minFreshSeconds = -1
    internal actual var onlyIfCached: Boolean = false
    internal actual var noTransform: Boolean = false
    internal actual var immutable: Boolean = false

    actual fun noCache() = commonNoCache()

    actual fun noStore() = commonNoStore()

    actual fun onlyIfCached() = commonOnlyIfCached()

    actual fun noTransform() = commonNoTransform()

    actual fun immutable() = commonImmutable()

    actual fun maxAge(maxAge: Int, timeUnit: DurationUnit) = commonMaxAge(maxAge, timeUnit)

    actual fun maxStale(maxStale: Int, timeUnit: DurationUnit) = commonMaxStale(maxStale, timeUnit)

    actual fun minFresh(minFresh: Int, timeUnit: DurationUnit) = commonMinFresh(minFresh, timeUnit)

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds [maxAge], it
     * will not be used and a network request will be made.
     *
     * @param maxAge a non-negative integer. This is stored and transmitted with [TimeUnit.SECONDS]
     *     precision; finer precision will be lost.
     */
    fun maxAge(maxAge: Int, timeUnit: TimeUnit) = apply {
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
    fun maxStale(maxStale: Int, timeUnit: TimeUnit) = apply {
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
    fun minFresh(minFresh: Int, timeUnit: TimeUnit) = apply {
      require(minFresh >= 0) { "minFresh < 0: $minFresh" }
      val minFreshSecondsLong = timeUnit.toSeconds(minFresh.toLong())
      this.minFreshSeconds = minFreshSecondsLong.commonClampToInt()
    }

    actual fun build(): CacheControl = commonBuild()
  }

  actual companion object {
    @JvmField
    actual val FORCE_NETWORK = commonForceNetwork()

    @JvmField
    actual val FORCE_CACHE = commonForceCache()

    @JvmStatic
    actual fun parse(headers: Headers): CacheControl = commonParse(headers)
  }
}
