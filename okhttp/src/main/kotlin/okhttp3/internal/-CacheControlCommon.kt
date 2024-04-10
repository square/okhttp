/*
 * Copyright (C) 2021 Square, Inc.
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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import kotlin.time.Duration.Companion.seconds
import okhttp3.CacheControl
import okhttp3.Headers

internal fun CacheControl.commonToString(): String {
  var result = headerValue
  if (result == null) {
    result =
      buildString {
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
        deleteRange(length - 2, length)
      }
    headerValue = result
  }
  return result
}

internal fun Long.commonClampToInt(): Int {
  return when {
    this > Int.MAX_VALUE -> Int.MAX_VALUE
    else -> toInt()
  }
}

internal fun CacheControl.Companion.commonForceNetwork() =
  CacheControl.Builder()
    .noCache()
    .build()

internal fun CacheControl.Companion.commonForceCache() =
  CacheControl.Builder()
    .onlyIfCached()
    .maxStale(Int.MAX_VALUE.seconds)
    .build()

internal fun CacheControl.Builder.commonBuild(): CacheControl {
  return CacheControl(
    noCache = noCache,
    noStore = noStore,
    maxAgeSeconds = maxAgeSeconds,
    sMaxAgeSeconds = -1,
    isPrivate = false,
    isPublic = false,
    mustRevalidate = false,
    maxStaleSeconds = maxStaleSeconds,
    minFreshSeconds = minFreshSeconds,
    onlyIfCached = onlyIfCached,
    noTransform = noTransform,
    immutable = immutable,
    headerValue = null,
  )
}

internal fun CacheControl.Builder.commonNoCache() =
  apply {
    this.noCache = true
  }

internal fun CacheControl.Builder.commonNoStore() =
  apply {
    this.noStore = true
  }

internal fun CacheControl.Builder.commonOnlyIfCached() =
  apply {
    this.onlyIfCached = true
  }

internal fun CacheControl.Builder.commonNoTransform() =
  apply {
    this.noTransform = true
  }

internal fun CacheControl.Builder.commonImmutable() =
  apply {
    this.immutable = true
  }

internal fun CacheControl.Companion.commonParse(headers: Headers): CacheControl {
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
          maxStaleSeconds = parameter.toNonNegativeInt(Int.MAX_VALUE)
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

  return CacheControl(
    noCache = noCache,
    noStore = noStore,
    maxAgeSeconds = maxAgeSeconds,
    sMaxAgeSeconds = sMaxAgeSeconds,
    isPrivate = isPrivate,
    isPublic = isPublic,
    mustRevalidate = mustRevalidate,
    maxStaleSeconds = maxStaleSeconds,
    minFreshSeconds = minFreshSeconds,
    onlyIfCached = onlyIfCached,
    noTransform = noTransform,
    immutable = immutable,
    headerValue = headerValue,
  )
}

/**
 * Returns the next index in this at or after [startIndex] that is a character from
 * [characters]. Returns the input length if none of the requested characters can be found.
 */
private fun String.indexOfElement(
  characters: String,
  startIndex: Int = 0,
): Int {
  for (i in startIndex until length) {
    if (this[i] in characters) {
      return i
    }
  }
  return length
}
