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
  private var headerValue: String?
) {
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
        deleteRange(length - 2, length)
      }
      headerValue = result
    }
    return result
  }

  actual class Builder {
    internal actual var noCache: Boolean = false
    internal actual var noStore: Boolean = false
    internal actual var maxAgeSeconds = -1
    internal actual var maxStaleSeconds = -1
    internal actual var minFreshSeconds = -1
    internal actual var onlyIfCached: Boolean = false
    internal actual var noTransform: Boolean = false
    internal actual var immutable: Boolean = false

    actual fun noCache() = apply {
      this.noCache = true
    }

    actual fun noStore() = apply {
      this.noStore = true
    }

    actual fun onlyIfCached() = apply {
      this.onlyIfCached = true
    }

    actual fun noTransform() = apply {
      this.noTransform = true
    }

    actual fun immutable() = apply {
      this.immutable = true
    }

    actual fun build(): CacheControl {
      return CacheControl(noCache, noStore, maxAgeSeconds, -1, false, false, false, maxStaleSeconds,
          minFreshSeconds, onlyIfCached, noTransform, immutable, null)
    }
  }

  actual companion object {
    actual val FORCE_NETWORK = Builder()
        .noCache()
        .build()
  }
}
