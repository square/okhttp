/*
 * Copyright (C) 2014 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class CacheControlTest {
  @Test
  @Throws(Exception::class)
  fun emptyBuilderIsEmpty() {
    val cacheControl = CacheControl.Builder().build()
    assertThat(cacheControl.toString()).isEqualTo("")
    assertThat(cacheControl.noCache).isFalse()
    assertThat(cacheControl.noStore).isFalse()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.isPrivate).isFalse()
    assertThat(cacheControl.isPublic).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
    assertThat(cacheControl.maxStaleSeconds).isEqualTo(-1)
    assertThat(cacheControl.minFreshSeconds).isEqualTo(-1)
    assertThat(cacheControl.onlyIfCached).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
  }

  @Test
  @Throws(Exception::class)
  fun completeBuilder() {
    val cacheControl =
      CacheControl.Builder()
        .noCache()
        .noStore()
        .maxAge(1.seconds)
        .maxStale(2.seconds)
        .minFresh(3.seconds)
        .onlyIfCached()
        .noTransform()
        .immutable()
        .build()
    assertThat(cacheControl.toString()).isEqualTo(
      "no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, no-transform, immutable",
    )
    assertThat(cacheControl.noCache).isTrue()
    assertThat(cacheControl.noStore).isTrue()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(1)
    assertThat(cacheControl.maxStaleSeconds).isEqualTo(2)
    assertThat(cacheControl.minFreshSeconds).isEqualTo(3)
    assertThat(cacheControl.onlyIfCached).isTrue()
    assertThat(cacheControl.noTransform).isTrue()
    assertThat(cacheControl.immutable).isTrue()

    // These members are accessible to response headers only.
    assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.isPrivate).isFalse()
    assertThat(cacheControl.isPublic).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
  }
}
