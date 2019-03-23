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
package okhttp3;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class CacheControlTest {
  @Test public void emptyBuilderIsEmpty() throws Exception {
    CacheControl cacheControl = new CacheControl.Builder().build();
    assertThat(cacheControl.toString()).isEqualTo("");
    assertThat(cacheControl.noCache()).isFalse();
    assertThat(cacheControl.noStore()).isFalse();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.isPrivate()).isFalse();
    assertThat(cacheControl.isPublic()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
    assertThat(cacheControl.maxStaleSeconds()).isEqualTo(-1);
    assertThat(cacheControl.minFreshSeconds()).isEqualTo(-1);
    assertThat(cacheControl.onlyIfCached()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
  }

  @Test public void completeBuilder() throws Exception {
    CacheControl cacheControl = new CacheControl.Builder()
        .noCache()
        .noStore()
        .maxAge(1, TimeUnit.SECONDS)
        .maxStale(2, TimeUnit.SECONDS)
        .minFresh(3, TimeUnit.SECONDS)
        .onlyIfCached()
        .noTransform()
        .immutable()
        .build();
    assertThat(cacheControl.toString()).isEqualTo(
        ("no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, "
        + "no-transform, immutable"));
    assertThat(cacheControl.noCache()).isTrue();
    assertThat(cacheControl.noStore()).isTrue();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(1);
    assertThat(cacheControl.maxStaleSeconds()).isEqualTo(2);
    assertThat(cacheControl.minFreshSeconds()).isEqualTo(3);
    assertThat(cacheControl.onlyIfCached()).isTrue();
    assertThat(cacheControl.noTransform()).isTrue();
    assertThat(cacheControl.immutable()).isTrue();

    // These members are accessible to response headers only.
    assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.isPrivate()).isFalse();
    assertThat(cacheControl.isPublic()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
  }

  @Test public void parseEmpty() throws Exception {
    CacheControl cacheControl = CacheControl.parse(
        new Headers.Builder().set("Cache-Control", "").build());
    assertThat(cacheControl.toString()).isEqualTo("");
    assertThat(cacheControl.noCache()).isFalse();
    assertThat(cacheControl.noStore()).isFalse();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.isPublic()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
    assertThat(cacheControl.maxStaleSeconds()).isEqualTo(-1);
    assertThat(cacheControl.minFreshSeconds()).isEqualTo(-1);
    assertThat(cacheControl.onlyIfCached()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
  }

  @Test public void parse() throws Exception {
    String header = "no-cache, no-store, max-age=1, s-maxage=2, private, public, must-revalidate, "
        + "max-stale=3, min-fresh=4, only-if-cached, no-transform";
    CacheControl cacheControl = CacheControl.parse(new Headers.Builder()
        .set("Cache-Control", header)
        .build());
    assertThat(cacheControl.noCache()).isTrue();
    assertThat(cacheControl.noStore()).isTrue();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(1);
    assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(2);
    assertThat(cacheControl.isPrivate()).isTrue();
    assertThat(cacheControl.isPublic()).isTrue();
    assertThat(cacheControl.mustRevalidate()).isTrue();
    assertThat(cacheControl.maxStaleSeconds()).isEqualTo(3);
    assertThat(cacheControl.minFreshSeconds()).isEqualTo(4);
    assertThat(cacheControl.onlyIfCached()).isTrue();
    assertThat(cacheControl.noTransform()).isTrue();
    assertThat(cacheControl.toString()).isEqualTo(header);
  }

  @Test public void parseIgnoreCacheControlExtensions() throws Exception {
    // Example from http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.6
    String header = "private, community=\"UCI\"";
    CacheControl cacheControl = CacheControl.parse(new Headers.Builder()
        .set("Cache-Control", header)
        .build());
    assertThat(cacheControl.noCache()).isFalse();
    assertThat(cacheControl.noStore()).isFalse();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.sMaxAgeSeconds()).isEqualTo(-1);
    assertThat(cacheControl.isPrivate()).isTrue();
    assertThat(cacheControl.isPublic()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
    assertThat(cacheControl.maxStaleSeconds()).isEqualTo(-1);
    assertThat(cacheControl.minFreshSeconds()).isEqualTo(-1);
    assertThat(cacheControl.onlyIfCached()).isFalse();
    assertThat(cacheControl.noTransform()).isFalse();
    assertThat(cacheControl.immutable()).isFalse();
    assertThat(cacheControl.toString()).isEqualTo(header);
  }

  @Test public void parseCacheControlAndPragmaAreCombined() {
    Headers headers =
        Headers.of("Cache-Control", "max-age=12", "Pragma", "must-revalidate", "Pragma", "public");
    CacheControl cacheControl = CacheControl.parse(headers);
    assertThat(cacheControl.toString()).isEqualTo("max-age=12, public, must-revalidate");
  }

  @SuppressWarnings("RedundantStringConstructorCall") // Testing instance equality.
  @Test public void parseCacheControlHeaderValueIsRetained() {
    String value = new String("max-age=12");
    Headers headers = Headers.of("Cache-Control", value);
    CacheControl cacheControl = CacheControl.parse(headers);
    assertThat(cacheControl.toString()).isSameAs(value);
  }

  @Test public void parseCacheControlHeaderValueInvalidatedByPragma() {
    Headers headers = Headers.of(
        "Cache-Control", "max-age=12",
        "Pragma", "must-revalidate"
    );
    CacheControl cacheControl = CacheControl.parse(headers);
    assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate");
  }

  @Test public void parseCacheControlHeaderValueInvalidatedByTwoValues() {
    Headers headers = Headers.of(
        "Cache-Control", "max-age=12",
        "Cache-Control", "must-revalidate"
    );
    CacheControl cacheControl = CacheControl.parse(headers);
    assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate");
  }

  @Test public void parsePragmaHeaderValueIsNotRetained() {
    Headers headers = Headers.of("Pragma", "must-revalidate");
    CacheControl cacheControl = CacheControl.parse(headers);
    assertThat(cacheControl.toString()).isEqualTo("must-revalidate");
  }

  @Test public void computedHeaderValueIsCached() {
    CacheControl cacheControl = new CacheControl.Builder()
        .maxAge(2, TimeUnit.DAYS)
        .build();
    assertThat(cacheControl.toString()).isEqualTo("max-age=172800");
    assertThat(cacheControl.toString()).isSameAs(cacheControl.toString());
  }

  @Test public void timeDurationTruncatedToMaxValue() throws Exception {
    CacheControl cacheControl = new CacheControl.Builder()
        .maxAge(365 * 100, TimeUnit.DAYS) // Longer than Integer.MAX_VALUE seconds.
        .build();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test public void secondsMustBeNonNegative() throws Exception {
    CacheControl.Builder builder = new CacheControl.Builder();
    try {
      builder.maxAge(-1, TimeUnit.SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void timePrecisionIsTruncatedToSeconds() throws Exception {
    CacheControl cacheControl = new CacheControl.Builder()
        .maxAge(4999, TimeUnit.MILLISECONDS)
        .build();
    assertThat(cacheControl.maxAgeSeconds()).isEqualTo(4);
  }
}
