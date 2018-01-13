/*
 * Copyright (C) 2018 Square, Inc.
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HttpMethodBehaviorTest {
  @Test public void customBehavior() {
    HttpMethodBehavior behavior = new HttpMethodBehavior.Builder()
        .invalidatesCache(true)
        .requiresRequestBody(true)
        .permitsRequestBody(true)
        .redirectsWithBody(true)
        .redirectsToGet(false)
        .build();

    assertEquals(true, behavior.invalidatesCache());
    assertEquals(true, behavior.requiresRequestBody());
    assertEquals(true, behavior.permitsRequestBody());
    assertEquals(true, behavior.redirectsWithBody());
    assertEquals(false, behavior.redirectsToGet());
  }

  @Test public void newBuilder() {
    HttpMethodBehavior behavior = new HttpMethodBehavior.Builder()
        .invalidatesCache(true)
        .requiresRequestBody(true)
        .permitsRequestBody(true)
        .redirectsWithBody(true)
        .redirectsToGet(false)
        .build();
    HttpMethodBehavior behavior2 = behavior.newBuilder()
        .invalidatesCache(false)
        .build();

    assertEquals(false, behavior2.invalidatesCache());
    assertEquals(behavior.requiresRequestBody(), behavior2.requiresRequestBody());
    assertEquals(behavior.permitsRequestBody(), behavior2.permitsRequestBody());
    assertEquals(behavior.redirectsWithBody(), behavior2.redirectsWithBody());
    assertEquals(behavior.redirectsToGet(), behavior2.redirectsToGet());
  }

  @Test public void requiresRequestBodyButNotPermitsRequestBodyForbidden() {
    try {
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(true)
          .permitsRequestBody(false)
          .redirectsWithBody(false)
          .redirectsToGet(false)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void redirectsWithBodyButNotPermitsRequestBodyForbidden() {
    try {
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(false)
          .permitsRequestBody(false)
          .redirectsWithBody(true)
          .redirectsToGet(false)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void redirectsWithBodyAndRedirectsToGetForbidden() {
    try {
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(false)
          .permitsRequestBody(true)
          .redirectsWithBody(true)
          .redirectsToGet(true)
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
