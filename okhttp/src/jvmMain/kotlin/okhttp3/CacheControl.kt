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
import okhttp3.internal.commonBuild
import okhttp3.internal.commonForceNetwork
import okhttp3.internal.commonImmutable
import okhttp3.internal.commonNoCache
import okhttp3.internal.commonNoStore
import okhttp3.internal.commonNoTransform
import okhttp3.internal.commonOnlyIfCached
import okhttp3.internal.commonToString
import okhttp3.internal.indexOfNonWhitespace
import okhttp3.internal.toNonNegativeInt

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

    fun maxAge(maxAge: Int, timeUnit: TimeUnit) = apply {
      require(maxAge >= 0) { "maxAge < 0: $maxAge" }
      val maxAgeSecondsLong = timeUnit.toSeconds(maxAge.toLong())
      this.maxAgeSeconds = maxAgeSecondsLong.clampToInt()
    }

    fun maxStale(maxStale: Int, timeUnit: TimeUnit) = apply {
      require(maxStale >= 0) { "maxStale < 0: $maxStale" }
      val maxStaleSecondsLong = timeUnit.toSeconds(maxStale.toLong())
      this.maxStaleSeconds = maxStaleSecondsLong.clampToInt()
    }

    fun minFresh(minFresh: Int, timeUnit: TimeUnit) = apply {
      require(minFresh >= 0) { "minFresh < 0: $minFresh" }
      val minFreshSecondsLong = timeUnit.toSeconds(minFresh.toLong())
      this.minFreshSeconds = minFreshSecondsLong.clampToInt()
    }

    private fun Long.clampToInt(): Int {
      return when {
        this > Integer.MAX_VALUE -> Integer.MAX_VALUE
        else -> toInt()
      }
    }

    actual fun build(): CacheControl = commonBuild()
  }

  actual companion object {
    @JvmField
    actual val FORCE_NETWORK = commonForceNetwork()

    @JvmField
    val FORCE_CACHE = Builder()
        .onlyIfCached()
        .maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
        .build()

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

      loop@ for (i in 0 until headers.size) {
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
          pos = value.indexOfElement("=,;", pos)
          val directive = value.substring(tokenStart, pos).trim()
          val parameter: String?

          if (pos == value.length || value[pos] == ',' || value[pos] == ';') {
            pos++ // Consume ',' or ';' (if necessary).
            parameter = null
          } else {
            pos++ // Consume '='.
            pos = value.indexOfNonWhitespace(pos)

            if (pos < value.length && value[pos] == '\"') {
              // Quoted string.
              pos++ // Consume '"' open quote.
              val parameterStart = pos
              pos = value.indexOf('"', pos)
              parameter = value.substring(parameterStart, pos)
              pos++ // Consume '"' close quote (if necessary).
            } else {
              // Unquoted string.
              val parameterStart = pos
              pos = value.indexOfElement(",;", pos)
              parameter = value.substring(parameterStart, pos).trim()
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
              maxAgeSeconds = parameter.toNonNegativeInt(-1)
            }
            "s-maxage".equals(directive, ignoreCase = true) -> {
              sMaxAgeSeconds = parameter.toNonNegativeInt(-1)
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
              maxStaleSeconds = parameter.toNonNegativeInt(Integer.MAX_VALUE)
            }
            "min-fresh".equals(directive, ignoreCase = true) -> {
              minFreshSeconds = parameter.toNonNegativeInt(-1)
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

    /**
     * Returns the next index in this at or after [startIndex] that is a character from
     * [characters]. Returns the input length if none of the requested characters can be found.
     */
    private fun String.indexOfElement(characters: String, startIndex: Int = 0): Int {
      for (i in startIndex until length) {
        if (this[i] in characters) {
          return i
        }
      }
      return length
    }
  }
}
