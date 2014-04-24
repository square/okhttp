package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Challenge;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkAuthenticator;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.Platform;
import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.squareup.okhttp.internal.Util.equal;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;

/** Headers and utilities for internal use by OkHttp. */
public final class OkHeaders {
  private static final Comparator<String> FIELD_NAME_COMPARATOR = new Comparator<String>() {
    // @FindBugsSuppressWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
    @Override public int compare(String a, String b) {
      if (a == b) {
        return 0;
      } else if (a == null) {
        return -1;
      } else if (b == null) {
        return 1;
      } else {
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
      }
    }
  };

  static final String PREFIX = Platform.get().getPrefix();

  /**
   * Synthetic response header: the local time when the request was sent.
   */
  public static final String SENT_MILLIS = PREFIX + "-Sent-Millis";

  /**
   * Synthetic response header: the local time when the response was received.
   */
  public static final String RECEIVED_MILLIS = PREFIX + "-Received-Millis";

  private OkHeaders() {
  }

  public static long contentLength(Request request) {
    return contentLength(request.headers());
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
   * Returns an immutable map containing each field to its list of values.
   *
   * @param valueForNullKey the request line for requests, or the status line
   *     for responses. If non-null, this value is mapped to the null key.
   */
  public static Map<String, List<String>> toMultimap(Headers headers, String valueForNullKey) {
    Map<String, List<String>> result = new TreeMap<String, List<String>>(FIELD_NAME_COMPARATOR);
    for (int i = 0; i < headers.size(); i++) {
      String fieldName = headers.name(i);
      String value = headers.value(i);

      List<String> allValues = new ArrayList<String>();
      List<String> otherValues = result.get(fieldName);
      if (otherValues != null) {
        allValues.addAll(otherValues);
      }
      allValues.add(value);
      result.put(fieldName, Collections.unmodifiableList(allValues));
    }
    if (valueForNullKey != null) {
      result.put(null, Collections.unmodifiableList(Collections.singletonList(valueForNullKey)));
    }
    return Collections.unmodifiableMap(result);
  }

  public static void addCookies(Request.Builder builder, Map<String, List<String>> cookieHeaders) {
    for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
      String key = entry.getKey();
      if (("Cookie".equalsIgnoreCase(key) || "Cookie2".equalsIgnoreCase(key))
          && !entry.getValue().isEmpty()) {
        builder.addHeader(key, buildCookieHeader(entry.getValue()));
      }
    }
  }

  /**
   * Send all cookies in one big header, as recommended by
   * <a href="http://tools.ietf.org/html/rfc6265#section-4.2.1">RFC 6265</a>.
   */
  private static String buildCookieHeader(List<String> cookies) {
    if (cookies.size() == 1) return cookies.get(0);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cookies.size(); i++) {
      if (i > 0) sb.append("; ");
      sb.append(cookies.get(i));
    }
    return sb.toString();
  }

  /**
   * Returns true if none of the Vary headers have changed between {@code
   * cachedRequest} and {@code newRequest}.
   */
  public static boolean varyMatches(
      Response cachedResponse, Headers cachedRequest, Request newRequest) {
    for (String field : varyFields(cachedResponse)) {
      if (!equal(cachedRequest.values(field), newRequest.headers(field))) return false;
    }
    return true;
  }

  /**
   * Returns true if a Vary header contains an asterisk. Such responses cannot
   * be cached.
   */
  public static boolean hasVaryAll(Response response) {
    return varyFields(response).contains("*");
  }

  private static Set<String> varyFields(Response response) {
    Set<String> result = Collections.emptySet();
    Headers headers = response.headers();
    for (int i = 0; i < headers.size(); i++) {
      if (!"Vary".equalsIgnoreCase(headers.name(i))) continue;

      String value = headers.value(i);
      if (result.isEmpty()) {
        result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
      }
      for (String varyField : value.split(",")) {
        result.add(varyField.trim());
      }
    }
    return result;
  }

  /**
   * Returns the subset of the headers in {@code response}'s request that
   * impact the content of response's body.
   */
  public static Headers varyHeaders(Response response) {
    Set<String> varyFields = varyFields(response);
    if (varyFields.isEmpty()) return new Headers.Builder().build();

    Headers.Builder result = new Headers.Builder();
    Headers requestHeaders = response.request().headers();
    for (int i = 0; i < requestHeaders.size(); i++) {
      String fieldName = requestHeaders.name(i);
      if (varyFields.contains(fieldName)) {
        result.add(fieldName, requestHeaders.value(i));
      }
    }
    return result.build();
  }

  /**
   * Returns true if {@code fieldName} is an end-to-end HTTP header, as
   * defined by RFC 2616, 13.5.1.
   */
  static boolean isEndToEnd(String fieldName) {
    return !"Connection".equalsIgnoreCase(fieldName)
        && !"Keep-Alive".equalsIgnoreCase(fieldName)
        && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
        && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
        && !"TE".equalsIgnoreCase(fieldName)
        && !"Trailers".equalsIgnoreCase(fieldName)
        && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
        && !"Upgrade".equalsIgnoreCase(fieldName);
  }

  /**
   * Parse RFC 2617 challenges. This API is only interested in the scheme
   * name and realm.
   */
  public static List<Challenge> parseChallenges(Headers responseHeaders, String challengeHeader) {
    // auth-scheme = token
    // auth-param  = token "=" ( token | quoted-string )
    // challenge   = auth-scheme 1*SP 1#auth-param
    // realm       = "realm" "=" realm-value
    // realm-value = quoted-string
    List<Challenge> result = new ArrayList<Challenge>();
    for (int h = 0; h < responseHeaders.size(); h++) {
      if (!challengeHeader.equalsIgnoreCase(responseHeaders.name(h))) {
        continue;
      }
      String value = responseHeaders.value(h);
      int pos = 0;
      while (pos < value.length()) {
        int tokenStart = pos;
        pos = HeaderParser.skipUntil(value, pos, " ");

        String scheme = value.substring(tokenStart, pos).trim();
        pos = HeaderParser.skipWhitespace(value, pos);

        // TODO: This currently only handles schemes with a 'realm' parameter;
        //       It needs to be fixed to handle any scheme and any parameters
        //       http://code.google.com/p/android/issues/detail?id=11140

        if (!value.regionMatches(true, pos, "realm=\"", 0, "realm=\"".length())) {
          break; // Unexpected challenge parameter; give up!
        }

        pos += "realm=\"".length();
        int realmStart = pos;
        pos = HeaderParser.skipUntil(value, pos, "\"");
        String realm = value.substring(realmStart, pos);
        pos++; // Consume '"' close quote.
        pos = HeaderParser.skipUntil(value, pos, ",");
        pos++; // Consume ',' comma.
        pos = HeaderParser.skipWhitespace(value, pos);
        result.add(new Challenge(scheme, realm));
      }
    }
    return result;
  }

  /**
   * React to a failed authorization response by looking up new credentials.
   * Returns a request for a subsequent attempt, or null if no further attempts
   * should be made.
   */
  public static Request processAuthHeader(OkAuthenticator authenticator, Response response,
      Proxy proxy) throws IOException {
    return response.code() == HTTP_PROXY_AUTH
        ? authenticator.authenticateProxy(proxy, response)
        : authenticator.authenticate(proxy, response);
  }
}
