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
package com.squareup.okhttp;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        .build();
    assertEquals("no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, "
        + "no-transform", cacheControl.toString());
    assertTrue(cacheControl.noCache());
    assertTrue(cacheControl.noStore());
    assertEquals(1, cacheControl.maxAgeSeconds());
    assertEquals(2, cacheControl.maxStaleSeconds());
    assertEquals(3, cacheControl.minFreshSeconds());
    assertTrue(cacheControl.onlyIfCached());

    // These members are accessible to response headers only.
    assertEquals(-1, cacheControl.sMaxAgeSeconds());
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
    String header = "no-cache, no-store, max-age=1, s-maxage=2, public, must-revalidate, "
        + "max-stale=3, min-fresh=4, only-if-cached, no-transform";
    CacheControl cacheControl = CacheControl.parse(new Headers.Builder()
        .set("Cache-Control", header)
        .build());
    assertTrue(cacheControl.noCache());
    assertTrue(cacheControl.noStore());
    assertEquals(1, cacheControl.maxAgeSeconds());
    assertEquals(2, cacheControl.sMaxAgeSeconds());
    assertTrue(cacheControl.isPublic());
    assertTrue(cacheControl.mustRevalidate());
    assertEquals(3, cacheControl.maxStaleSeconds());
    assertEquals(4, cacheControl.minFreshSeconds());
    assertTrue(cacheControl.onlyIfCached());
    assertTrue(cacheControl.noTransform());
    assertEquals(header, cacheControl.toString());
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
}
