/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal;

import java.io.IOException;
import java.net.CookieHandler;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;

/** A cookie jar that delegates to a {@link java.net.CookieHandler}. */
public final class JavaNetCookieJar implements CookieJar {
  private final CookieHandler cookieHandler;

  public JavaNetCookieJar(CookieHandler cookieHandler) {
    this.cookieHandler = cookieHandler;
  }

  @Override public void saveFromResponse(HttpUrl url, Headers headers) {
    if (cookieHandler != null) {
      try {
        cookieHandler.put(url.uri(), JavaNetHeaders.toMultimap(headers, null));
      } catch (IOException e) {
        Internal.logger.log(Level.WARNING, "Saving cookies failed for " + url.resolve("/..."), e);
      }
    }
  }

  @Override public Headers loadForRequest(HttpUrl url, Headers headers) {
    try {
      // Capture the request headers added so far so that they can be offered to the CookieHandler.
      // This is mostly to stay close to the RI; it is unlikely any of the headers above would
      // affect cookie choice besides "Host".
      Map<String, List<String>> headersMultimap = JavaNetHeaders.toMultimap(headers, null);
      Map<String, List<String>> cookies = cookieHandler.get(url.uri(), headersMultimap);

      // Add any new cookies to the request.
      Headers.Builder headersBuilder = headers.newBuilder();
      addCookies(headersBuilder, cookies);
      return headersBuilder.build();
    } catch (IOException e) {
      Internal.logger.log(Level.WARNING, "Loading cookies failed for " + url.resolve("/..."), e);
      return headers;
    }
  }

  public static void addCookies(Headers.Builder builder, Map<String, List<String>> cookieHeaders) {
    for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
      String key = entry.getKey();
      if (("Cookie".equalsIgnoreCase(key) || "Cookie2".equalsIgnoreCase(key))
          && !entry.getValue().isEmpty()) {
        builder.add(key, buildCookieHeader(entry.getValue()));
      }
    }
  }

  /**
   * Send all cookies in one big header, as recommended by <a
   * href="http://tools.ietf.org/html/rfc6265#section-4.2.1">RFC 6265</a>.
   */
  private static String buildCookieHeader(List<String> cookies) {
    if (cookies.size() == 1) return cookies.get(0);
    StringBuilder sb = new StringBuilder();
    for (int i = 0, size = cookies.size(); i < size; i++) {
      if (i > 0) sb.append("; ");
      sb.append(cookies.get(i));
    }
    return sb.toString();
  }
}
