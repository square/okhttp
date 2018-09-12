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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Challenge;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.util.Locale.US;
import static okhttp3.internal.Util.equal;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/** Headers and utilities for internal use by OkHttp. */
public final class HttpHeaders {
  // regexes according to RFC 7235
  private static final String TOKEN_PATTERN_PART = "[!#$%&'*+.^_`|~\\p{Alnum}-]+";
  private static final String TOKEN68_PATTERN_PART = "[\\p{Alnum}._~+/-]+=*";
  private static final String OWS_PATTERN_PART = "[ \\t]*";
  private static final String QUOTED_PAIR_PATTERN_PART = "\\\\([\\t \\p{Graph}\\x80-\\xFF])";
  private static final String QUOTED_STRING_PATTERN_PART =
          "\"(?:[\\t \\x21\\x23-\\x5B\\x5D-\\x7E\\x80-\\xFF]|" + QUOTED_PAIR_PATTERN_PART + ")*\"";
  private static final String AUTH_PARAM_PATTERN_PART = TOKEN_PATTERN_PART + OWS_PATTERN_PART + '='
          + OWS_PATTERN_PART + "(?:" + TOKEN_PATTERN_PART + '|' + QUOTED_STRING_PATTERN_PART + ')';
  private static final String CHALLENGE_PATTERN_PART = TOKEN_PATTERN_PART + "(?: +(?:"
          + TOKEN68_PATTERN_PART + "|(?:,|" + AUTH_PARAM_PATTERN_PART + ")(?:" + OWS_PATTERN_PART
          + ",(?:" + OWS_PATTERN_PART + AUTH_PARAM_PATTERN_PART + ")?)*)?)?";
  private static final String AUTHENTICATION_HEADER_VALUE_SPLIT_PATTERN_PART =
          "(?:" + OWS_PATTERN_PART + ',' + OWS_PATTERN_PART + ")+";

  private static final Pattern AUTHENTICATION_HEADER_VALUE_PATTERN = Pattern.compile("^(?:,"
          + OWS_PATTERN_PART + ")*" + CHALLENGE_PATTERN_PART + "(?:" + OWS_PATTERN_PART + ",(?:"
          + OWS_PATTERN_PART + CHALLENGE_PATTERN_PART + ")?)*$");
  private static final Pattern AUTH_SCHEME_PATTERN =
          Pattern.compile('^' + TOKEN_PATTERN_PART + '$');
  private static final Pattern AUTH_SCHEME_AND_TOKEN68_PATTERN =
          Pattern.compile('^' + TOKEN_PATTERN_PART + " +" + TOKEN68_PATTERN_PART + '$');
  private static final Pattern AUTH_SCHEME_AND_PARAM_PATTERN =
          Pattern.compile('^' + TOKEN_PATTERN_PART + " +" + AUTH_PARAM_PATTERN_PART + '$');
  private static final Pattern AUTH_PARAM_PATTERN =
          Pattern.compile('^' + AUTH_PARAM_PATTERN_PART + '$');
  private static final Pattern TOKEN_PATTERN = Pattern.compile('^' + TOKEN_PATTERN_PART + '$');
  private static final Pattern QUOTED_PAIR_PATTERN = Pattern.compile(QUOTED_PAIR_PATTERN_PART);

  private static final Pattern AUTHENTICATION_HEADER_VALUE_SPLIT_PATTERN =
          Pattern.compile(AUTHENTICATION_HEADER_VALUE_SPLIT_PATTERN_PART);
  private static final Pattern WHITESPACE_SPLIT_PATTERN = Pattern.compile(" +");
  private static final Pattern AUTH_PARAM_SPLIT_PATTERN =
          Pattern.compile(OWS_PATTERN_PART + '=' + OWS_PATTERN_PART);
  private static final Pattern QUOTED_STRING_AUTH_PARAM_AT_END_PATTERN =
          Pattern.compile(TOKEN_PATTERN_PART + OWS_PATTERN_PART + '=' + OWS_PATTERN_PART
                  + QUOTED_STRING_PATTERN_PART + '$');

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

  /**
   * Parse RFC 7235 challenges.
   */
  public static List<Challenge> parseChallenges(Headers responseHeaders, String challengeHeader) {
    List<Challenge> challenges = new ArrayList<>();
    List<String> authenticationHeaders = responseHeaders.values(challengeHeader);
headerLoop:
    for (String header : authenticationHeaders) {
      // ignore invalid header value
      if (!AUTHENTICATION_HEADER_VALUE_PATTERN.matcher(header).matches()) {
        continue;
      }

      // needed to properly abort if a header is invalid due to repeated auth param names
      List<Challenge> headerChallenges = new ArrayList<>();

      String[] challengeParts = AUTHENTICATION_HEADER_VALUE_SPLIT_PATTERN.split(header);
      String authScheme = null;
      Map<String, String> authParams = new LinkedHashMap<>();
      for (int i = 0, j = challengeParts.length; i < j; i++) {
        String challengePart = challengeParts[i];

        // skip empty parts that can occur as first and last element
        if (challengePart.isEmpty()) {
          continue;
        }

        String newAuthScheme = null;
        String authParam = null;
        if (AUTH_SCHEME_PATTERN.matcher(challengePart).matches()) {
          newAuthScheme = challengePart;
        } else if (AUTH_SCHEME_AND_TOKEN68_PATTERN.matcher(challengePart).matches()) {
          String[] authSchemeAndToken68 = WHITESPACE_SPLIT_PATTERN.split(challengePart, 2);
          newAuthScheme = authSchemeAndToken68[0];
          if (authParams.put(null, authSchemeAndToken68[1]) != null) {
            // if the regex is correct, this must not happen
            throw new AssertionError();
          }
        } else if (AUTH_SCHEME_AND_PARAM_PATTERN.matcher(challengePart).matches()) {
          String[] authSchemeAndParam = WHITESPACE_SPLIT_PATTERN.split(challengePart, 2);
          newAuthScheme = authSchemeAndParam[0];
          authParam = authSchemeAndParam[1];
        } else if (AUTH_PARAM_PATTERN.matcher(challengePart).matches()) {
          authParam = challengePart;
        } else {
          // comma in quoted string part got split wrongly
          StringBuilder patternBuilder = new StringBuilder();
          patternBuilder.append('^').append(Pattern.quote(challengeParts[0]));
          for (int i2 = 1; i2 < i; i2++) {
            patternBuilder
                    .append(AUTHENTICATION_HEADER_VALUE_SPLIT_PATTERN_PART)
                    .append(Pattern.quote(challengeParts[i2]));
          }
          // if the algorithm has a flaw, the loop will crash with an ArrayIndexOutOfBoundsException
          // if this happens, the algorithm or overall regex has to be fixed, not the array access
          Matcher quotedStringAuthParamAtEndMatcher;
          do {
            patternBuilder
                    .append(AUTHENTICATION_HEADER_VALUE_SPLIT_PATTERN_PART)
                    .append(Pattern.quote(challengeParts[i++]));
            Matcher matcher = Pattern.compile(patternBuilder.toString()).matcher(header);
            if (!matcher.find()) {
              // if the algorithm is flawless, this must not happen
              throw new AssertionError();
            }
            quotedStringAuthParamAtEndMatcher =
                    QUOTED_STRING_AUTH_PARAM_AT_END_PATTERN.matcher(matcher.group());
          } while (!quotedStringAuthParamAtEndMatcher.find());
          authParam = quotedStringAuthParamAtEndMatcher.group();
        }

        if (newAuthScheme != null) {
          if (authScheme != null) {
            headerChallenges.add(new Challenge(authScheme, authParams));
            authParams.clear();
          }
          authScheme = newAuthScheme;
        }

        if (authParam != null) {
          String[] authParamPair = AUTH_PARAM_SPLIT_PATTERN.split(authParam, 2);
          // lower-case to easily check for multiple occurrences
          String authParamKey = authParamPair[0].toLowerCase(US);
          String authParamValue = authParamPair[1];
          if (!TOKEN_PATTERN.matcher(authParamValue).matches()) {
            authParamValue = authParamValue.substring(1, authParamValue.length() - 1);
            authParamValue = QUOTED_PAIR_PATTERN.matcher(authParamValue).replaceAll("$1");
          }
          if (authParams.put(authParamKey, authParamValue) != null) {
            // ignore invalid header value
            // auth param keys must not occur multiple times within one challenge
            continue headerLoop;
          }
        }
      }
      headerChallenges.add(new Challenge(authScheme, authParams));
      challenges.addAll(headerChallenges);
    }
    return challenges;
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
