/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import okhttp3.Challenge;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static okhttp3.internal.Util.equal;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/** Headers and utilities for internal use by OkHttp. */
public final class HttpHeaders {
  private HttpHeaders() {
  }

  public static long contentLength(Response response) {
    return contentLength(response.headers());
  }

  public static long contentLength(Headers headers) {
    return stringToLong(headers.get("Content-Length"));
  }

  private static long stringToLong(String s) {
    if (s == null) return -1;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Returns true if none of the Vary headers have changed between {@code cachedRequest} and {@code
   * newRequest}.
   */
  public static boolean varyMatches(
      Response cachedResponse, Headers cachedRequest, Request newRequest) {
    for (String field : varyFields(cachedResponse)) {
      if (!equal(cachedRequest.values(field), newRequest.headers(field))) return false;
    }
    return true;
  }

  /**
   * Returns true if a Vary header contains an asterisk. Such responses cannot be cached.
   */
  public static boolean hasVaryAll(Response response) {
    return hasVaryAll(response.headers());
  }

  /**
   * Returns true if a Vary header contains an asterisk. Such responses cannot be cached.
   */
  public static boolean hasVaryAll(Headers responseHeaders) {
    return varyFields(responseHeaders).contains("*");
  }

  private static Set<String> varyFields(Response response) {
    return varyFields(response.headers());
  }

  /**
   * Returns the names of the request headers that need to be checked for equality when caching.
   */
  public static Set<String> varyFields(Headers responseHeaders) {
    Set<String> result = Collections.emptySet();
    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
      if (!"Vary".equalsIgnoreCase(responseHeaders.name(i))) continue;

      String value = responseHeaders.value(i);
      if (result.isEmpty()) {
        result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      }
      for (String varyField : value.split(",")) {
        result.add(varyField.trim());
      }
    }
    return result;
  }

  /**
   * Returns the subset of the headers in {@code response}'s request that impact the content of
   * response's body.
   */
  public static Headers varyHeaders(Response response) {
    // Use the request headers sent over the network, since that's what the
    // response varies on. Otherwise OkHttp-supplied headers like
    // "Accept-Encoding: gzip" may be lost.
    Headers requestHeaders = response.networkResponse().request().headers();
    Headers responseHeaders = response.headers();
    return varyHeaders(requestHeaders, responseHeaders);
  }

  /**
   * Returns the subset of the headers in {@code requestHeaders} that impact the content of
   * response's body.
   */
  public static Headers varyHeaders(Headers requestHeaders, Headers responseHeaders) {
    Set<String> varyFields = varyFields(responseHeaders);
    if (varyFields.isEmpty()) return new Headers.Builder().build();

    Headers.Builder result = new Headers.Builder();
    for (int i = 0, size = requestHeaders.size(); i < size; i++) {
      String fieldName = requestHeaders.name(i);
      if (varyFields.contains(fieldName)) {
        result.add(fieldName, requestHeaders.value(i));
      }
    }
    return result.build();
  }

  /** Parse RFC 2617 challenges. This API is only interested in the scheme name and realm. */
  public static List<Challenge> parseChallenges(Headers responseHeaders, String challengeHeader) {
    // auth-scheme = token
    // auth-param  = token "=" ( token | quoted-string )
    // challenge   = auth-scheme 1*SP 1#auth-param
    // realm       = "realm" "=" realm-value
    // realm-value = quoted-string
    List<Challenge> result = new ArrayList<>();
    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
      if (!challengeHeader.equalsIgnoreCase(responseHeaders.name(i))) {
        continue;
      }
      String value = responseHeaders.value(i);
      int pos = 0;
      while (pos < value.length()) {
        int tokenStart = pos;
        pos = skipUntil(value, pos, " ");

        String scheme = value.substring(tokenStart, pos).trim();
        pos = skipWhitespace(value, pos);

        // TODO: This currently only handles schemes with a 'realm' parameter;
        //       It needs to be fixed to handle any scheme and any parameters
        //       http://code.google.com/p/android/issues/detail?id=11140

        if (!value.regionMatches(true, pos, "realm=\"", 0, "realm=\"".length())) {
          break; // Unexpected challenge parameter; give up!
        }

        pos += "realm=\"".length();
        int realmStart = pos;
        pos = skipUntil(value, pos, "\"");
        String realm = value.substring(realmStart, pos);
        pos++; // Consume '"' close quote.
        pos = skipUntil(value, pos, ",");
        pos++; // Consume ',' comma.
        pos = skipWhitespace(value, pos);
        result.add(new Challenge(scheme, realm));
      }
    }
    return result;
  }

  public static void receiveHeaders(CookieJar cookieJar, HttpUrl url, Headers headers) {
    if (cookieJar == CookieJar.NO_COOKIES) return;

    List<Cookie> cookies = Cookie.parseAll(url, headers);
    if (cookies.isEmpty()) return;

    cookieJar.saveFromResponse(url, cookies);
  }

  /** Returns true if the response must have a (possibly 0-length) body. See RFC 7231. */
  public static boolean hasBody(Response response) {
    // HEAD requests never yield a body regardless of the response headers.
    if (response.request().method().equals("HEAD")) {
      return false;
    }

    int responseCode = response.code();
    if ((responseCode < HTTP_CONTINUE || responseCode >= 200)
        && responseCode != HTTP_NO_CONTENT
        && responseCode != HTTP_NOT_MODIFIED) {
      return true;
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
    // response is malformed. For best compatibility, we honor the headers.
    if (contentLength(response) != -1
        || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
      return true;
    }

    return false;
  }

  /**
   * Returns the next index in {@code input} at or after {@code pos} that contains a character from
   * {@code characters}. Returns the input length if none of the requested characters can be found.
   */
  public static int skipUntil(String input, int pos, String characters) {
    for (; pos < input.length(); pos++) {
      if (characters.indexOf(input.charAt(pos)) != -1) {
        break;
      }
    }
    return pos;
  }

  /**
   * Returns the next non-whitespace character in {@code input} that is white space. Result is
   * undefined if input contains newline characters.
   */
  public static int skipWhitespace(String input, int pos) {
    for (; pos < input.length(); pos++) {
      char c = input.charAt(pos);
      if (c != ' ' && c != '\t') {
        break;
      }
    }
    return pos;
  }

  /**
   * Returns {@code value} as a positive integer, or 0 if it is negative, or {@code defaultValue} if
   * it cannot be parsed.
   */
  public static int parseSeconds(String value, int defaultValue) {
    try {
      long seconds = Long.parseLong(value);
      if (seconds > Integer.MAX_VALUE) {
        return Integer.MAX_VALUE;
      } else if (seconds < 0) {
        return 0;
      } else {
        return (int) seconds;
      }
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
