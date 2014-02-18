package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.Platform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

  /**
   * Synthetic response header: the response source and status code like
   * "CONDITIONAL_CACHE 304".
   */
  public static final String RESPONSE_SOURCE = PREFIX + "-Response-Source";

  /**
   * @deprecated OkHttp 2 enforces an enumeration of {@link com.squareup.okhttp.Protocol protocols}
   * that can be selected. Please use #SELECTED_PROTOCOL
   */
  @Deprecated
  public static final String SELECTED_TRANSPORT = PREFIX + "-Selected-Transport";

  /**
   * Synthetic response header: the selected
   * {@link com.squareup.okhttp.Protocol protocol} ("spdy/3.1", "http/1.1", etc).
   */
  public static final String SELECTED_PROTOCOL = PREFIX + "-Selected-Protocol";

  private OkHeaders() {
  }

  public static long contentLength(Request request) {
    return stringToLong(request.header("Content-Length"));
  }

  public static long contentLength(Response response) {
    return stringToLong(response.header("Content-Length"));
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
}
