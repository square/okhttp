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

public class HttpMethodsTest {
  private final static HttpMethodBehavior TEST_BEHAVIOR = new HttpMethodBehavior.Builder()
      .invalidatesCache(true)
      .requiresRequestBody(true)
      .permitsRequestBody(true)
      .redirectsWithBody(true)
      .redirectsToGet(false)
      .build();

  @Test public void defaultBehaviorOfWellKnownMethods() {
    HttpMethods defaultMethods = HttpMethods.defaultMethods();

    // Methods defined in RFC 2616 (HTTP 1.1) and further specified in RFC 7231
    assertEquals(false, defaultMethods.invalidatesCache("GET"));
    assertEquals(false, defaultMethods.requiresRequestBody("GET"));
    assertEquals(false, defaultMethods.permitsRequestBody("GET"));
    assertEquals(false, defaultMethods.redirectsWithBody("GET"));
    assertEquals(true, defaultMethods.redirectsToGet("GET"));

    assertEquals(false, defaultMethods.invalidatesCache("HEAD"));
    assertEquals(false, defaultMethods.requiresRequestBody("HEAD"));
    assertEquals(false, defaultMethods.permitsRequestBody("HEAD"));
    assertEquals(false, defaultMethods.redirectsWithBody("HEAD"));
    assertEquals(true, defaultMethods.redirectsToGet("HEAD"));

    assertEquals(true, defaultMethods.invalidatesCache("POST"));
    assertEquals(true, defaultMethods.requiresRequestBody("POST"));
    assertEquals(true, defaultMethods.permitsRequestBody("POST"));
    assertEquals(false, defaultMethods.redirectsWithBody("POST"));
    assertEquals(true, defaultMethods.redirectsToGet("POST"));

    assertEquals(true, defaultMethods.invalidatesCache("PUT"));
    assertEquals(true, defaultMethods.requiresRequestBody("PUT"));
    assertEquals(true, defaultMethods.permitsRequestBody("PUT"));
    assertEquals(false, defaultMethods.redirectsWithBody("PUT"));
    assertEquals(true, defaultMethods.redirectsToGet("PUT"));

    assertEquals(true, defaultMethods.invalidatesCache("DELETE"));
    assertEquals(false, defaultMethods.requiresRequestBody("DELETE"));
    assertEquals(true, defaultMethods.permitsRequestBody("DELETE"));
    assertEquals(false, defaultMethods.redirectsWithBody("DELETE"));
    assertEquals(true, defaultMethods.redirectsToGet("DELETE"));

    assertEquals(false, defaultMethods.invalidatesCache("CONNECT"));
    assertEquals(false, defaultMethods.requiresRequestBody("CONNECT"));
    assertEquals(false, defaultMethods.permitsRequestBody("CONNECT"));
    assertEquals(false, defaultMethods.redirectsWithBody("CONNECT"));
    assertEquals(true, defaultMethods.redirectsToGet("CONNECT"));

    assertEquals(false, defaultMethods.invalidatesCache("OPTIONS"));
    assertEquals(false, defaultMethods.requiresRequestBody("OPTIONS"));
    assertEquals(true, defaultMethods.permitsRequestBody("OPTIONS"));
    assertEquals(false, defaultMethods.redirectsWithBody("OPTIONS"));
    assertEquals(true, defaultMethods.redirectsToGet("OPTIONS"));

    assertEquals(false, defaultMethods.invalidatesCache("TRACE"));
    assertEquals(false, defaultMethods.requiresRequestBody("TRACE"));
    assertEquals(false, defaultMethods.permitsRequestBody("TRACE"));
    assertEquals(false, defaultMethods.redirectsWithBody("TRACE"));
    assertEquals(true, defaultMethods.redirectsToGet("TRACE"));

    // WebDAV Methods
    assertEquals(false, defaultMethods.invalidatesCache("PROPFIND"));
    assertEquals(false, defaultMethods.requiresRequestBody("PROPFIND"));
    assertEquals(true, defaultMethods.permitsRequestBody("PROPFIND"));
    assertEquals(true, defaultMethods.redirectsWithBody("PROPFIND"));
    assertEquals(false, defaultMethods.redirectsToGet("PROPFIND"));

    assertEquals(false, defaultMethods.invalidatesCache("PROPPATCH"));
    assertEquals(true, defaultMethods.requiresRequestBody("PROPPATCH"));
    assertEquals(true, defaultMethods.permitsRequestBody("PROPPATCH"));
    assertEquals(false, defaultMethods.redirectsWithBody("PROPPATCH"));
    assertEquals(true, defaultMethods.redirectsToGet("PROPPATCH"));

    assertEquals(false, defaultMethods.invalidatesCache("MKCOL"));
    assertEquals(false, defaultMethods.requiresRequestBody("MKCOL"));
    assertEquals(true, defaultMethods.permitsRequestBody("MKCOL"));
    assertEquals(false, defaultMethods.redirectsWithBody("MKCOL"));
    assertEquals(true, defaultMethods.redirectsToGet("MKCOL"));

    assertEquals(false, defaultMethods.invalidatesCache("COPY"));
    assertEquals(false, defaultMethods.requiresRequestBody("COPY"));
    assertEquals(false, defaultMethods.permitsRequestBody("COPY"));
    assertEquals(false, defaultMethods.redirectsWithBody("COPY"));
    assertEquals(true, defaultMethods.redirectsToGet("COPY"));

    assertEquals(true, defaultMethods.invalidatesCache("MOVE"));
    assertEquals(false, defaultMethods.requiresRequestBody("MOVE"));
    assertEquals(false, defaultMethods.permitsRequestBody("MOVE"));
    assertEquals(false, defaultMethods.redirectsWithBody("MOVE"));
    assertEquals(true, defaultMethods.redirectsToGet("MOVE"));

    assertEquals(false, defaultMethods.invalidatesCache("LOCK"));
    assertEquals(false, defaultMethods.requiresRequestBody("LOCK"));
    assertEquals(true, defaultMethods.permitsRequestBody("LOCK"));
    assertEquals(false, defaultMethods.redirectsWithBody("LOCK"));
    assertEquals(true, defaultMethods.redirectsToGet("LOCK"));

    assertEquals(false, defaultMethods.invalidatesCache("UNLOCK"));
    assertEquals(false, defaultMethods.requiresRequestBody("UNLOCK"));
    assertEquals(false, defaultMethods.permitsRequestBody("UNLOCK"));
    assertEquals(false, defaultMethods.redirectsWithBody("UNLOCK"));
    assertEquals(true, defaultMethods.redirectsToGet("UNLOCK"));

    // CalDAV/CardDAV (defined in WebDAV Versioning)
    assertEquals(false, defaultMethods.invalidatesCache("REPORT"));
    assertEquals(true, defaultMethods.requiresRequestBody("REPORT"));
    assertEquals(true, defaultMethods.permitsRequestBody("REPORT"));
    assertEquals(false, defaultMethods.redirectsWithBody("REPORT"));
    assertEquals(true, defaultMethods.redirectsToGet("REPORT"));
    }

  @Test public void unknownMethodGetsDefaultBehavior() {
    HttpMethods defaultMethods = HttpMethods.defaultMethods();
    assertEquals(false, defaultMethods.invalidatesCache("UNKNOWN"));
    assertEquals(false, defaultMethods.requiresRequestBody("UNKNOWN"));
    assertEquals(false, defaultMethods.permitsRequestBody("UNKNOWN"));
    assertEquals(false, defaultMethods.redirectsWithBody("UNKNOWN"));
    assertEquals(true, defaultMethods.redirectsToGet("UNKNOWN"));
  }

  @Test public void customMethod() {
    HttpMethods httpMethods = HttpMethods.defaultMethods().newBuilder()
        .addMethod("CUSTOM", TEST_BEHAVIOR)
        .build();

    assertEquals(TEST_BEHAVIOR.invalidatesCache(), httpMethods.invalidatesCache("CUSTOM"));
    assertEquals(TEST_BEHAVIOR.requiresRequestBody(), httpMethods.requiresRequestBody("CUSTOM"));
    assertEquals(TEST_BEHAVIOR.permitsRequestBody(), httpMethods.permitsRequestBody("CUSTOM"));
    assertEquals(TEST_BEHAVIOR.redirectsWithBody(), httpMethods.redirectsWithBody("CUSTOM"));
    assertEquals(TEST_BEHAVIOR.redirectsToGet(), httpMethods.redirectsToGet("CUSTOM"));
  }

  @Test public void nullMethodNameForbidden() {
    HttpMethods httpMethods = HttpMethods.defaultMethods();
    try {
      httpMethods.invalidatesCache(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      httpMethods.requiresRequestBody(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      httpMethods.permitsRequestBody(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      httpMethods.redirectsWithBody(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      httpMethods.redirectsToGet(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void emptyMethodNameForbidden() {
    HttpMethods httpMethods = HttpMethods.defaultMethods();
    try {
      httpMethods.invalidatesCache("");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      httpMethods.requiresRequestBody("");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      httpMethods.permitsRequestBody("");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      httpMethods.redirectsWithBody("");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      httpMethods.redirectsToGet("");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void nullMethodNameForbiddenInBuilder() {
    try {
      HttpMethods.defaultMethods().newBuilder()
          .addMethod(null, TEST_BEHAVIOR);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void emptyMethodNameForbiddenInBuilder() {
    try {
      HttpMethods.defaultMethods().newBuilder()
          .addMethod("", TEST_BEHAVIOR);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void nullBehaviorForbiddenInBuilder() {
    try {
      HttpMethods.defaultMethods().newBuilder()
          .addMethod("METHOD", null);
      fail();
    } catch (NullPointerException expected) {
    }
  }
}
