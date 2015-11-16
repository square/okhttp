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
package com.squareup.okhttp.internal.http;

public final class HttpMethod {
  public static boolean invalidatesCache(String method) {
    return "POST".equals(method)
        || "PATCH".equals(method)
        || "PUT".equals(method)
        || "DELETE".equals(method)
        || "MOVE".equals(method);     // WebDAV
  }

  public static boolean requiresRequestBody(String method) {
    return "POST".equals(method)
        || "PUT".equals(method)
        || "PATCH".equals(method)
        || "PROPPATCH".equals(method) // WebDAV
        || "REPORT".equals(method);   // CalDAV/CardDAV (defined in WebDAV Versioning)
  }

  public static boolean permitsRequestBody(String method) {
    return requiresRequestBody(method)
        || "DELETE".equals(method)    // Permitted as spec is ambiguous.
        || "PROPFIND".equals(method)  // (WebDAV) without body: request <allprop/>
        || "MKCOL".equals(method)     // (WebDAV) may contain a body, but behaviour is unspecified
        || "LOCK".equals(method);     // (WebDAV) body: create lock, without body: refresh lock
  }

  private HttpMethod() {
  }
}
