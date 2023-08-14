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
import okhttp3.internal.commonBuild
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
  actual val noCache: Boolean,
  actual val noStore: Boolean,
  actual val maxAgeSeconds: Int,
  actual val sMaxAgeSeconds: Int,
  actual val isPrivate: Boolean,
  actual val isPublic: Boolean,
  actual val mustRevalidate: Boolean,
  actual val maxStaleSeconds: Int,
  actual val minFreshSeconds: Int,
  actual val onlyIfCached: Boolean,
  actual val noTransform: Boolean,
  actual val immutable: Boolean,
  internal actual var headerValue: String?
) {
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

    actual fun build(): CacheControl = commonBuild()
  }

  actual companion object {
    actual val FORCE_NETWORK = commonForceNetwork()

    actual val FORCE_CACHE = commonForceCache()

    actual fun parse(headers: Headers): CacheControl = commonParse(headers)
  }
}
