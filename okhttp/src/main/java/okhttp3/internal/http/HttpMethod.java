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
package okhttp3.internal.http;

public final class HttpMethod {
  public static boolean invalidatesCache(String method) {
    return method.equals("POST")
        || method.equals("PATCH")
        || method.equals("PUT")
        || method.equals("DELETE")
        || method.equals("MOVE");     // WebDAV
  }

  public static boolean requiresRequestBody(String method) {
    return method.equals("POST")
        || method.equals("PUT")
        || method.equals("PATCH")
        || method.equals("PROPPATCH") // WebDAV
        || method.equals("REPORT");   // CalDAV/CardDAV (defined in WebDAV Versioning)
  }

  public static boolean permitsRequestBody(String method) {
    return requiresRequestBody(method)
        || method.equals("OPTIONS")
        || method.equals("DELETE")    // Permitted as spec is ambiguous.
        || method.equals("PROPFIND")  // (WebDAV) without body: request <allprop/>
        || method.equals("MKCOL")     // (WebDAV) may contain a body, but behaviour is unspecified
        || method.equals("LOCK");     // (WebDAV) body: create lock, without body: refresh lock
  }

  public static boolean redirectsWithBody(String method) {
    return method.equals("PROPFIND"); // (WebDAV) redirects should also maintain the request body
  }

  public static boolean redirectsToGet(String method) {
    // All requests but PROPFIND should redirect to a GET request.
    return !method.equals("PROPFIND");
  }

  private HttpMethod() {
  }
}
