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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CacheControlTest {
  @Test public void emptyBuilderIsEmpty() throws Exception {
    CacheControl cacheControl = new CacheControl.Builder().build();
    assertEquals("", cacheControl.toString());
    assertFalse(cacheControl.noCache());
    assertFalse(cacheControl.noStore());
    assertEquals(-1, cacheControl.maxAgeSeconds());
    assertEquals(-1, cacheControl.sMaxAgeSeconds());
    assertFalse(cacheControl.isPrivate());
    assertFalse(cacheControl.isPublic());
    assertFalse(cacheControl.mustRevalidate());
    assertEquals(-1, cacheControl.maxStaleSeconds());
    assertEquals(-1, cacheControl.minFreshSeconds());
    assertFalse(cacheControl.onlyIfCached());
    assertFalse(cacheControl.mustRevalidate());
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
    assertEquals("no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, "
        + "no-transform, immutable", cacheControl.toString());
    assertTrue(cacheControl.noCache());
    assertTrue(cacheControl.noStore());
    assertEquals(1, cacheControl.maxAgeSeconds());
    assertEquals(2, cacheControl.maxStaleSeconds());
    assertEquals(3, cacheControl.minFreshSeconds());
    assertTrue(cacheControl.onlyIfCached());
    assertTrue(cacheControl.noTransform());
    assertTrue(cacheControl.immutable());

    // These members are accessible to response headers only.
    assertEquals(-1, cacheControl.sMaxAgeSeconds());
    assertFalse(cacheControl.isPrivate());
    assertFalse(cacheControl.isPublic());
    assertFalse(cacheControl.mustRevalidate());
  }

  @Test public void parseEmpty() throws Exception {
    CacheControl cacheControl = CacheControl.parse(
        new Headers.Builder().set("Cache-Control", "").build());
    assertEquals("", cacheControl.toString());
    assertFalse(cacheControl.noCache());
    assertFalse(cacheControl.noStore());
    assertEquals(-1, cacheControl.maxAgeSeconds());
    assertEquals(-1, cacheControl.sMaxAgeSeconds());
    assertFalse(cacheControl.isPublic());
    assertFalse(cacheControl.mustRevalidate());
    assertEquals(-1, cacheControl.maxStaleSeconds());
    assertEquals(-1, cacheControl.minFreshSeconds());
    assertFalse(cacheControl.onlyIfCached());
    assertFalse(cacheControl.mustRevalidate());
  }

  @Test public void parse() throws Exception {
    String header = "no-cache, no-store, max-age=1, s-maxage=2, private, public, must-revalidate, "
        + "max-stale=3, min-fresh=4, only-if-cached, no-transform";
    CacheControl cacheControl = CacheControl.parse(new Headers.Builder()
        .set("Cache-Control", header)
        .build());
    assertTrue(cacheControl.noCache());
    assertTrue(cacheControl.noStore());
    assertEquals(1, cacheControl.maxAgeSeconds());
    assertEquals(2, cacheControl.sMaxAgeSeconds());
    assertTrue(cacheControl.isPrivate());
    assertTrue(cacheControl.isPublic());
    assertTrue(cacheControl.mustRevalidate());
    assertEquals(3, cacheControl.maxStaleSeconds());
    assertEquals(4, cacheControl.minFreshSeconds());
    assertTrue(cacheControl.onlyIfCached());
    assertTrue(cacheControl.noTransform());
    assertEquals(header, cacheControl.toString());
  }

  @Test public void parseIgnoreCacheControlExtensions() throws Exception {
    // Example from http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.6
    String header = "private, community=\"UCI\"";
    CacheControl cacheControl = CacheControl.parse(new Headers.Builder()
        .set("Cache-Control", header)
        .build());
    assertFalse(cacheControl.noCache());
    assertFalse(cacheControl.noStore());
    assertEquals(-1, cacheControl.maxAgeSeconds());
    assertEquals(-1, cacheControl.sMaxAgeSeconds());
    assertTrue(cacheControl.isPrivate());
    assertFalse(cacheControl.isPublic());
    assertFalse(cacheControl.mustRevalidate());
    assertEquals(-1, cacheControl.maxStaleSeconds());
    assertEquals(-1, cacheControl.minFreshSeconds());
    assertFalse(cacheControl.onlyIfCached());
    assertFalse(cacheControl.noTransform());
    assertFalse(cacheControl.immutable());
    assertEquals(header, cacheControl.toString());
  }

  @Test public void parseCacheControlAndPragmaAreCombined() {
    Headers headers =
        Headers.of("Cache-Control", "max-age=12", "Pragma", "must-revalidate", "Pragma", "public");
    CacheControl cacheControl = CacheControl.parse(headers);
    assertEquals("max-age=12, public, must-revalidate", cacheControl.toString());
  }

  @SuppressWarnings("RedundantStringConstructorCall") // Testing instance equality.
  @Test public void parseCacheControlHeaderValueIsRetained() {
    String value = new String("max-age=12");
    Headers headers = Headers.of("Cache-Control", value);
    CacheControl cacheControl = CacheControl.parse(headers);
    assertSame(value, cacheControl.toString());
  }

  @Test public void parseCacheControlHeaderValueInvalidatedByPragma() {
    Headers headers = Headers.of("Cache-Control", "max-age=12", "Pragma", "must-revalidate");
    CacheControl cacheControl = CacheControl.parse(headers);
    assertNull(cacheControl.headerValue);
  }

  @Test public void parseCacheControlHeaderValueInvalidatedByTwoValues() {
    Headers headers = Headers.of("Cache-Control", "max-age=12", "Cache-Control", "must-revalidate");
    CacheControl cacheControl = CacheControl.parse(headers);
    assertNull(cacheControl.headerValue);
  }

  @Test public void parsePragmaHeaderValueIsNotRetained() {
    Headers headers = Headers.of("Pragma", "must-revalidate");
    CacheControl cacheControl = CacheControl.parse(headers);
    assertNull(cacheControl.headerValue);
  }

  @Test public void computedHeaderValueIsCached() {
    CacheControl cacheControl = new CacheControl.Builder()
        .maxAge(2, TimeUnit.DAYS)
        .build();
    assertNull(cacheControl.headerValue);
    assertEquals("max-age=172800", cacheControl.toString());
    assertEquals("max-age=172800", cacheControl.headerValue);
    cacheControl.headerValue = "Hi";
    assertEquals("Hi", cacheControl.toString());
  }

  @Test public void timeDurationTruncatedToMaxValue() throws Exception {
    CacheControl cacheControl = new CacheControl.Builder()
        .maxAge(365 * 100, TimeUnit.DAYS) // Longer than Integer.MAX_VALUE seconds.
        .build();
    assertEquals(Integer.MAX_VALUE, cacheControl.maxAgeSeconds());
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
    assertEquals(4, cacheControl.maxAgeSeconds());
  }

  @Test public void longNanosecondsOkay() {
    CacheControl cacheControl = new CacheControl.Builder()
        .maxAge(100000000000L, TimeUnit.NANOSECONDS)
        .build();
    assertEquals(100, cacheControl.maxAgeSeconds());
  }
}
