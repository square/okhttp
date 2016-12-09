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

import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

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

  public static boolean redirectsWithBody(String method) {
    return method.equals("PROPFIND"); // (WebDAV) redirects should also maintain the request body
  }

  public static boolean redirectsToGet(String method, int statusCode) {
    //The POST requests combined with either 307 or 308 status codes should not redirect to GET.
    if (method.equals("POST")) {
      return statusCode != HTTP_TEMP_REDIRECT && statusCode != HTTP_PERM_REDIRECT;
    }
    return false;
  }

  private HttpMethod() {
  }
}
