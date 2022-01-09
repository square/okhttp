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

import okhttp3.CacheControl.Companion.parse
import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.util.concurrent.TimeUnit

class CacheControlJvmTest {
    @Test
    @Throws(Exception::class)
    fun completeBuilder() {
        val cacheControl = CacheControl.Builder()
            .noCache()
            .noStore()
            .maxAge(1, TimeUnit.SECONDS)
            .maxStale(2, TimeUnit.SECONDS)
            .minFresh(3, TimeUnit.SECONDS)
            .onlyIfCached()
            .noTransform()
            .immutable()
            .build()
        Assertions.assertThat(cacheControl.toString()).isEqualTo(
            "no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, "
                    + "no-transform, immutable"
        )
        Assertions.assertThat(cacheControl.noCache).isTrue
        Assertions.assertThat(cacheControl.noStore).isTrue
        Assertions.assertThat(cacheControl.maxAgeSeconds).isEqualTo(1)
        Assertions.assertThat(cacheControl.maxStaleSeconds).isEqualTo(2)
        Assertions.assertThat(cacheControl.minFreshSeconds).isEqualTo(3)
        Assertions.assertThat(cacheControl.onlyIfCached).isTrue
        Assertions.assertThat(cacheControl.noTransform).isTrue
        Assertions.assertThat(cacheControl.immutable).isTrue

        // These members are accessible to response headers only.
        Assertions.assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.isPrivate).isFalse
        Assertions.assertThat(cacheControl.isPublic).isFalse
        Assertions.assertThat(cacheControl.mustRevalidate).isFalse
    }

    @Test
    @Throws(Exception::class)
    fun parseEmpty() {
        val cacheControl = parse(
            Headers.Builder().set("Cache-Control", "").build()
        )
        Assertions.assertThat(cacheControl.toString()).isEqualTo("")
        Assertions.assertThat(cacheControl.noCache).isFalse
        Assertions.assertThat(cacheControl.noStore).isFalse
        Assertions.assertThat(cacheControl.maxAgeSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.isPublic).isFalse
        Assertions.assertThat(cacheControl.mustRevalidate).isFalse
        Assertions.assertThat(cacheControl.maxStaleSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.minFreshSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.onlyIfCached).isFalse
        Assertions.assertThat(cacheControl.mustRevalidate).isFalse
    }

    @Test
    @Throws(Exception::class)
    fun parse() {
        val header = ("no-cache, no-store, max-age=1, s-maxage=2, private, public, must-revalidate, "
                + "max-stale=3, min-fresh=4, only-if-cached, no-transform")
        val cacheControl = parse(
            Headers.Builder()
                .set("Cache-Control", header)
                .build()
        )
        Assertions.assertThat(cacheControl.noCache).isTrue
        Assertions.assertThat(cacheControl.noStore).isTrue
        Assertions.assertThat(cacheControl.maxAgeSeconds).isEqualTo(1)
        Assertions.assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(2)
        Assertions.assertThat(cacheControl.isPrivate).isTrue
        Assertions.assertThat(cacheControl.isPublic).isTrue
        Assertions.assertThat(cacheControl.mustRevalidate).isTrue
        Assertions.assertThat(cacheControl.maxStaleSeconds).isEqualTo(3)
        Assertions.assertThat(cacheControl.minFreshSeconds).isEqualTo(4)
        Assertions.assertThat(cacheControl.onlyIfCached).isTrue
        Assertions.assertThat(cacheControl.noTransform).isTrue
        Assertions.assertThat(cacheControl.toString()).isEqualTo(header)
    }

    @Test
    @Throws(Exception::class)
    fun parseIgnoreCacheControlExtensions() {
        // Example from http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.6
        val header = "private, community=\"UCI\""
        val cacheControl = parse(
            Headers.Builder()
                .set("Cache-Control", header)
                .build()
        )
        Assertions.assertThat(cacheControl.noCache).isFalse
        Assertions.assertThat(cacheControl.noStore).isFalse
        Assertions.assertThat(cacheControl.maxAgeSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.isPrivate).isTrue
        Assertions.assertThat(cacheControl.isPublic).isFalse
        Assertions.assertThat(cacheControl.mustRevalidate).isFalse
        Assertions.assertThat(cacheControl.maxStaleSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.minFreshSeconds).isEqualTo(-1)
        Assertions.assertThat(cacheControl.onlyIfCached).isFalse
        Assertions.assertThat(cacheControl.noTransform).isFalse
        Assertions.assertThat(cacheControl.immutable).isFalse
        Assertions.assertThat(cacheControl.toString()).isEqualTo(header)
    }

    @Test
    fun parseCacheControlAndPragmaAreCombined() {
        val headers = headersOf("Cache-Control", "max-age=12", "Pragma", "must-revalidate", "Pragma", "public")
        val cacheControl = parse(headers)
        Assertions.assertThat(cacheControl.toString()).isEqualTo("max-age=12, public, must-revalidate")
    }

    // Testing instance equality.
    @Test
    fun parseCacheControlHeaderValueIsRetained() {
        val value = "max-age=12"
        val headers = headersOf("Cache-Control", value)
        val cacheControl = parse(headers)
        Assertions.assertThat(cacheControl.toString()).isSameAs(value)
    }

    @Test
    fun parseCacheControlHeaderValueInvalidatedByPragma() {
        val headers = headersOf(
            "Cache-Control", "max-age=12",
            "Pragma", "must-revalidate"
        )
        val cacheControl = parse(headers)
        Assertions.assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate")
    }

    @Test
    fun parseCacheControlHeaderValueInvalidatedByTwoValues() {
        val headers = headersOf(
            "Cache-Control", "max-age=12",
            "Cache-Control", "must-revalidate"
        )
        val cacheControl = parse(headers)
        Assertions.assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate")
    }

    @Test
    fun parsePragmaHeaderValueIsNotRetained() {
        val headers = headersOf("Pragma", "must-revalidate")
        val cacheControl = parse(headers)
        Assertions.assertThat(cacheControl.toString()).isEqualTo("must-revalidate")
    }

    @Test
    fun computedHeaderValueIsCached() {
        val cacheControl = CacheControl.Builder()
            .maxAge(2, TimeUnit.DAYS)
            .build()
        Assertions.assertThat(cacheControl.toString()).isEqualTo("max-age=172800")
        Assertions.assertThat(cacheControl.toString()).isSameAs(cacheControl.toString())
    }

    @Test
    @Throws(Exception::class)
    fun timeDurationTruncatedToMaxValue() {
        val cacheControl = CacheControl.Builder()
            .maxAge(365 * 100, TimeUnit.DAYS) // Longer than Integer.MAX_VALUE seconds.
            .build()
        Assertions.assertThat(cacheControl.maxAgeSeconds).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    @Throws(Exception::class)
    fun secondsMustBeNonNegative() {
        val builder = CacheControl.Builder()
        try {
            builder.maxAge(-1, TimeUnit.SECONDS)
            org.junit.jupiter.api.Assertions.fail<Any>()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    @Throws(Exception::class)
    fun timePrecisionIsTruncatedToSeconds() {
        val cacheControl = CacheControl.Builder()
            .maxAge(4999, TimeUnit.MILLISECONDS)
            .build()
        Assertions.assertThat(cacheControl.maxAgeSeconds).isEqualTo(4)
    }
}
