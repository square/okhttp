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
package okhttp3.internal

import kotlin.time.DurationUnit
import kotlin.time.toDuration
import okhttp3.CacheControl

internal fun CacheControl.commonToString(): String {
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
      deleteRange(length - 2, length)
    }
    headerValue = result
  }
  return result
}

internal fun CacheControl.Builder.commonMaxAge(maxAge: Int, timeUnit: DurationUnit) = apply {
  require(maxAge >= 0) { "maxAge < 0: $maxAge" }
  val maxAgeSecondsLong = maxAge.toDuration(timeUnit).inWholeSeconds
  this.maxAgeSeconds = maxAgeSecondsLong.commonClampToInt()
}

internal fun CacheControl.Builder.commonMaxStale(maxStale: Int, timeUnit: DurationUnit) = apply {
  require(maxStale >= 0) { "maxStale < 0: $maxStale" }
  val maxStaleSecondsLong = maxStale.toDuration(timeUnit).inWholeSeconds
  this.maxStaleSeconds = maxStaleSecondsLong.commonClampToInt()
}

internal fun CacheControl.Builder.commonMinFresh(minFresh: Int, timeUnit: DurationUnit) = apply {
  require(minFresh >= 0) { "minFresh < 0: $minFresh" }
  val minFreshSecondsLong = minFresh.toDuration(timeUnit).inWholeSeconds
  this.minFreshSeconds = minFreshSecondsLong.commonClampToInt()
}

internal fun Long.commonClampToInt(): Int {
  return when {
    this > Int.MAX_VALUE -> Int.MAX_VALUE
    else -> toInt()
  }
}

internal fun CacheControl.Companion.commonForceNetwork() = CacheControl.Builder()
  .noCache()
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
    headerValue = null
  )
}

internal fun CacheControl.Builder.commonNoCache() = apply {
  this.noCache = true
}

internal fun CacheControl.Builder.commonNoStore() = apply {
  this.noStore = true
}

internal fun CacheControl.Builder.commonOnlyIfCached() = apply {
  this.onlyIfCached = true
}

internal fun CacheControl.Builder.commonNoTransform() = apply {
  this.noTransform = true
}

internal fun CacheControl.Builder.commonImmutable() = apply {
  this.immutable = true
}
