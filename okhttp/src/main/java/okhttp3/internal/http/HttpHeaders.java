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

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import okhttp3.Challenge;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import okio.ByteString;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static okhttp3.internal.Util.EMPTY_HEADERS;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/** Headers and utilities for internal use by OkHttp. */
public final class HttpHeaders {
  private static final ByteString QUOTED_STRING_DELIMITERS = ByteString.encodeUtf8("\"\\");
  private static final ByteString TOKEN_DELIMITERS = ByteString.encodeUtf8("\t ,=");

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
      if (!Objects.equals(cachedRequest.values(field), newRequest.headers(field))) return false;
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
    if (varyFields.isEmpty()) return EMPTY_HEADERS;

    Headers.Builder result = new Headers.Builder();
    for (int i = 0, size = requestHeaders.size(); i < size; i++) {
      String fieldName = requestHeaders.name(i);
      if (varyFields.contains(fieldName)) {
        result.add(fieldName, requestHeaders.value(i));
      }
    }
    return result.build();
  }

  /**
   * Parse RFC 7235 challenges. This is awkward because we need to look ahead to know how to
   * interpret a token.
   *
   * <p>For example, the first line has a parameter name/value pair and the second line has a single
   * token68:
   *
   * <pre>   {@code
   *
   *   WWW-Authenticate: Digest foo=bar
   *   WWW-Authenticate: Digest foo=
   * }</pre>
   *
   * <p>Similarly, the first line has one challenge and the second line has two challenges:
   *
   * <pre>   {@code
   *
   *   WWW-Authenticate: Digest ,foo=bar
   *   WWW-Authenticate: Digest ,foo
   * }</pre>
   */
  public static List<Challenge> parseChallenges(Headers responseHeaders, String headerName) {
    List<Challenge> result = new ArrayList<>();
    for (int h = 0; h < responseHeaders.size(); h++) {
      if (headerName.equalsIgnoreCase(responseHeaders.name(h))) {
        Buffer header = new Buffer().writeUtf8(responseHeaders.value(h));
        parseChallengeHeader(result, header);
      }
    }
    return result;
  }

  private static void parseChallengeHeader(List<Challenge> result, Buffer header) {
    String peek = null;

    while (true) {
      // Read a scheme name for this challenge if we don't have one already.
      if (peek == null) {
        skipWhitespaceAndCommas(header);
        peek = readToken(header);
        if (peek == null) return;
      }

      String schemeName = peek;

      // Read a token68, a sequence of parameters, or nothing.
      boolean commaPrefixed = skipWhitespaceAndCommas(header);
      peek = readToken(header);
      if (peek == null) {
        if (!header.exhausted()) return; // Expected a token; got something else.
        result.add(new Challenge(schemeName, Collections.emptyMap()));
        return;
      }

      int eqCount = skipAll(header, (byte) '=');
      boolean commaSuffixed = skipWhitespaceAndCommas(header);

      // It's a token68 because there isn't a value after it.
      if (!commaPrefixed && (commaSuffixed || header.exhausted())) {
        result.add(new Challenge(schemeName, Collections.singletonMap(
            null, peek + repeat('=', eqCount))));
        peek = null;
        continue;
      }

      // It's a series of parameter names and values.
      Map<String, String> parameters = new LinkedHashMap<>();
      eqCount += skipAll(header, (byte) '=');
      while (true) {
        if (peek == null) {
          peek = readToken(header);
          if (skipWhitespaceAndCommas(header)) break; // We peeked a scheme name followed by ','.
          eqCount = skipAll(header, (byte) '=');
        }
        if (eqCount == 0) break; // We peeked a scheme name.
        if (eqCount > 1) return; // Unexpected '=' characters.
        if (skipWhitespaceAndCommas(header)) return; // Unexpected ','.

        String parameterValue = !header.exhausted() && header.getByte(0) == '"'
            ? readQuotedString(header)
            : readToken(header);
        if (parameterValue == null) return; // Expected a value.
        String replaced = parameters.put(peek, parameterValue);
        peek = null;
        if (replaced != null) return; // Unexpected duplicate parameter.
        if (!skipWhitespaceAndCommas(header) && !header.exhausted()) return; // Expected ',' or EOF.
      }
      result.add(new Challenge(schemeName, parameters));
    }
  }

  /** Returns true if any commas were skipped. */
  private static boolean skipWhitespaceAndCommas(Buffer buffer) {
    boolean commaFound = false;
    while (!buffer.exhausted()) {
      byte b = buffer.getByte(0);
      if (b == ',') {
        buffer.readByte(); // Consume ','.
        commaFound = true;
      } else if (b == ' ' || b == '\t') {
        buffer.readByte(); // Consume space or tab.
      } else {
        break;
      }
    }
    return commaFound;
  }

  private static int skipAll(Buffer buffer, byte b) {
    int count = 0;
    while (!buffer.exhausted() && buffer.getByte(0) == b) {
      count++;
      buffer.readByte();
    }
    return count;
  }

  /**
   * Reads a double-quoted string, unescaping quoted pairs like {@code \"} to the 2nd character in
   * each sequence. Returns the unescaped string, or null if the buffer isn't prefixed with a
   * double-quoted string.
   */
  private static String readQuotedString(Buffer buffer) {
    if (buffer.readByte() != '\"') throw new IllegalArgumentException();
    Buffer result = new Buffer();
    while (true) {
      long i = buffer.indexOfElement(QUOTED_STRING_DELIMITERS);
      if (i == -1L) return null; // Unterminated quoted string.

      if (buffer.getByte(i) == '"') {
        result.write(buffer, i);
        buffer.readByte(); // Consume '"'.
        return result.readUtf8();
      }

      if (buffer.size() == i + 1L) return null; // Dangling escape.
      result.write(buffer, i);
      buffer.readByte(); // Consume '\'.
      result.write(buffer, 1L); // The escaped character.
    }
  }

  /**
   * Consumes and returns a non-empty token, terminating at special characters in {@link
   * #TOKEN_DELIMITERS}. Returns null if the buffer is empty or prefixed with a delimiter.
   */
  private static String readToken(Buffer buffer) {
    try {
      long tokenSize = buffer.indexOfElement(TOKEN_DELIMITERS);
      if (tokenSize == -1L) tokenSize = buffer.size();

      return tokenSize != 0L
          ? buffer.readUtf8(tokenSize)
          : null;
    } catch (EOFException e) {
      throw new AssertionError();
    }
  }

  private static String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
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
