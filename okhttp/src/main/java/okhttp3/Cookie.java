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
package okhttp3;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static okhttp3.internal.Util.UTC;
import static okhttp3.internal.Util.delimiterOffset;
import static okhttp3.internal.Util.domainToAscii;
import static okhttp3.internal.Util.trimSubstring;
import static okhttp3.internal.Util.verifyAsIpAddress;

/**
 * An <a href="http://tools.ietf.org/html/rfc6265">RFC 6265</a> Cookie.
 *
 * <p>This class doesn't support additional attributes on cookies, like <a
 * href="https://code.google.com/p/chromium/issues/detail?id=232693">Chromium's Priority=HIGH
 * extension</a>.
 */
public final class Cookie {
  private static final Pattern YEAR_PATTERN
      = Pattern.compile("(\\d{2,4})[^\\d]*");
  private static final Pattern MONTH_PATTERN
      = Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*");
  private static final Pattern DAY_OF_MONTH_PATTERN
      = Pattern.compile("(\\d{1,2})[^\\d]*");
  private static final Pattern TIME_PATTERN
      = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[^\\d]*");

  private final String name;
  private final String value;
  private final long expiresAt;
  private final String domain;
  private final String path;
  private final boolean secure;
  private final boolean httpOnly;

  private final boolean persistent; // True if 'expires' or 'max-age' is present.
  private final boolean hostOnly; // True unless 'domain' is present.

  private Cookie(String name, String value, long expiresAt, String domain, String path,
      boolean secure, boolean httpOnly, boolean hostOnly, boolean persistent) {
    this.name = name;
    this.value = value;
    this.expiresAt = expiresAt;
    this.domain = domain;
    this.path = path;
    this.secure = secure;
    this.httpOnly = httpOnly;
    this.hostOnly = hostOnly;
    this.persistent = persistent;
  }

  /** Returns a non-empty string with this cookie's name. */
  public String name() {
    return name;
  }

  /** Returns a possibly-empty string with this cookie's value. */
  public String value() {
    return value;
  }

  /** Returns true if this cookie expires at the end of the current session. */
  public boolean persistent() {
    return persistent;
  }

  /**
   * Returns the time that this cookie expires, in the same format as {@link
   * System#currentTimeMillis()}. This is {@link Long#MAX_VALUE} if the cookie is not persistent, in
   * which case it will expire at the end of the current session.
   *
   * <p>This may return a value less than the current time, in which case the cookie is already
   * expired. Webservers may return expired cookies as a mechanism to delete previously set cookies
   * that may or may not themselves be expired.
   */
  public long expiresAt() {
    return expiresAt;
  }

  /**
   * Returns true if this cookie's domain should be interpreted as a single host name, or false if
   * it should be interpreted as a pattern. This flag will be false if its {@code Set-Cookie} header
   * included a {@code domain} attribute.
   *
   * <p>For example, suppose the cookie's domain is {@code example.com}. If this flag is true it
   * matches <strong>only</strong> {@code example.com}. If this flag is false it matches {@code
   * example.com} and all subdomains including {@code api.example.com}, {@code www.example.com}, and
   * {@code beta.api.example.com}.
   */
  public boolean hostOnly() {
    return hostOnly;
  }

  /**
   * Returns the cookie's domain. If {@link #hostOnly()} returns true this is the only domain that
   * matches this cookie; otherwise it matches this domain and all subdomains.
   */
  public String domain() {
    return domain;
  }

  /**
   * Returns this cookie's path. This cookie matches URLs prefixed with path segments that match
   * this path's segments. For example, if this path is {@code /foo} this cookie matches requests to
   * {@code /foo} and {@code /foo/bar}, but not {@code /} or {@code /football}.
   */
  public String path() {
    return path;
  }

  /**
   * Returns true if this cookie should be limited to only HTTP APIs. In web browsers this prevents
   * the cookie from being accessible to scripts.
   */
  public boolean httpOnly() {
    return httpOnly;
  }

  /** Returns true if this cookie should be limited to only HTTPS requests. */
  public boolean secure() {
    return secure;
  }

  /**
   * Returns true if this cookie should be included on a request to {@code url}. In addition to this
   * check callers should also confirm that this cookie has not expired.
   */
  public boolean matches(HttpUrl url) {
    boolean domainMatch = hostOnly
        ? url.host().equals(domain)
        : domainMatch(url, domain);
    if (!domainMatch) return false;

    if (!pathMatch(url, path)) return false;

    if (secure && !url.isHttps()) return false;

    return true;
  }

  private static boolean domainMatch(HttpUrl url, String domain) {
    String urlHost = url.host();

    if (urlHost.equals(domain)) {
      return true; // As in 'example.com' matching 'example.com'.
    }

    if (urlHost.endsWith(domain)
        && urlHost.charAt(urlHost.length() - domain.length() - 1) == '.'
        && !verifyAsIpAddress(urlHost)) {
      return true; // As in 'example.com' matching 'www.example.com'.
    }

    return false;
  }

  private static boolean pathMatch(HttpUrl url, String path) {
    String urlPath = url.encodedPath();

    if (urlPath.equals(path)) {
      return true; // As in '/foo' matching '/foo'.
    }

    if (urlPath.startsWith(path)) {
      if (path.endsWith("/")) return true; // As in '/' matching '/foo'.
      if (urlPath.charAt(path.length()) == '/') return true; // As in '/foo' matching '/foo/bar'.
    }

    return false;
  }

  /**
   * Attempt to parse a {@code Set-Cookie} HTTP header value {@code setCookie} as a cookie. Returns
   * null if {@code setCookie} is not a well-formed cookie.
   */
  public static Cookie parse(HttpUrl url, String setCookie) {
    return parse(System.currentTimeMillis(), url, setCookie);
  }

  static Cookie parse(long currentTimeMillis, HttpUrl url, String setCookie) {
    int pos = 0;
    int limit = setCookie.length();
    int cookiePairEnd = delimiterOffset(setCookie, pos, limit, ';');

    int pairEqualsSign = delimiterOffset(setCookie, pos, cookiePairEnd, '=');
    if (pairEqualsSign == cookiePairEnd) return null;

    String cookieName = trimSubstring(setCookie, pos, pairEqualsSign);
    if (cookieName.isEmpty()) return null;

    String cookieValue = trimSubstring(setCookie, pairEqualsSign + 1, cookiePairEnd);

    long expiresAt = Long.MAX_VALUE;
    long deltaSeconds = -1L;
    String domain = null;
    String path = null;
    boolean secureOnly = false;
    boolean httpOnly = false;
    boolean hostOnly = true;
    boolean persistent = false;

    pos = cookiePairEnd + 1;
    while (pos < limit) {
      int attributePairEnd = delimiterOffset(setCookie, pos, limit, ';');

      int attributeEqualsSign = delimiterOffset(setCookie, pos, attributePairEnd, '=');
      String attributeName = trimSubstring(setCookie, pos, attributeEqualsSign);
      String attributeValue = attributeEqualsSign < attributePairEnd
          ? trimSubstring(setCookie, attributeEqualsSign + 1, attributePairEnd)
          : "";

      if (attributeName.equalsIgnoreCase("expires")) {
        try {
          expiresAt = parseExpires(attributeValue, 0, attributeValue.length());
          persistent = true;
        } catch (IllegalArgumentException e) {
          // Ignore this attribute, it isn't recognizable as a date.
        }
      } else if (attributeName.equalsIgnoreCase("max-age")) {
        try {
          deltaSeconds = parseMaxAge(attributeValue);
          persistent = true;
        } catch (NumberFormatException e) {
          // Ignore this attribute, it isn't recognizable as a max age.
        }
      } else if (attributeName.equalsIgnoreCase("domain")) {
        try {
          domain = parseDomain(attributeValue);
          hostOnly = false;
        } catch (IllegalArgumentException e) {
          // Ignore this attribute, it isn't recognizable as a domain.
        }
      } else if (attributeName.equalsIgnoreCase("path")) {
        path = attributeValue;
      } else if (attributeName.equalsIgnoreCase("secure")) {
        secureOnly = true;
      } else if (attributeName.equalsIgnoreCase("HttpOnly")) {
        httpOnly = true;
      }

      pos = attributePairEnd + 1;
    }

    // If 'Max-Age' is present, it takes precedence over 'Expires', regardless of the order the two
    // attributes are declared in the cookie string.
    if (deltaSeconds == Long.MIN_VALUE) {
      expiresAt = Long.MIN_VALUE;
    } else if (deltaSeconds != -1L) {
      long deltaMilliseconds = deltaSeconds <= (Long.MAX_VALUE / 1000)
          ? deltaSeconds * 1000
          : Long.MAX_VALUE;
      expiresAt = currentTimeMillis + deltaMilliseconds;
      if (expiresAt < currentTimeMillis) expiresAt = Long.MAX_VALUE; // Clamp overflow.
    }

    // If the domain is present, it must domain match. Otherwise we have a host-only cookie.
    if (domain == null) {
      domain = url.host();
    } else if (!domainMatch(url, domain)) {
      return null; // No domain match? This is either incompetence or malice!
    }

    // If the path is absent or didn't start with '/', use the default path. It's a string like
    // '/foo/bar' for a URL like 'http://example.com/foo/bar/baz'. It always starts with '/'.
    if (path == null || !path.startsWith("/")) {
      String encodedPath = url.encodedPath();
      int lastSlash = encodedPath.lastIndexOf('/');
      path = lastSlash != 0 ? encodedPath.substring(0, lastSlash) : "/";
    }

    return new Cookie(cookieName, cookieValue, expiresAt, domain, path, secureOnly, httpOnly,
        hostOnly, persistent);
  }

  /** Parse a date as specified in RFC 6265, section 5.1.1. */
  private static long parseExpires(String s, int pos, int limit) {
    pos = dateCharacterOffset(s, pos, limit, false);

    int hour = -1;
    int minute = -1;
    int second = -1;
    int dayOfMonth = -1;
    int month = -1;
    int year = -1;
    Matcher matcher = TIME_PATTERN.matcher(s);

    while (pos < limit) {
      int end = dateCharacterOffset(s, pos + 1, limit, true);
      matcher.region(pos, end);

      if (hour == -1 && matcher.usePattern(TIME_PATTERN).matches()) {
        hour = Integer.parseInt(matcher.group(1));
        minute = Integer.parseInt(matcher.group(2));
        second = Integer.parseInt(matcher.group(3));
      } else if (dayOfMonth == -1 && matcher.usePattern(DAY_OF_MONTH_PATTERN).matches()) {
        dayOfMonth = Integer.parseInt(matcher.group(1));
      } else if (month == -1 && matcher.usePattern(MONTH_PATTERN).matches()) {
        String monthString = matcher.group(1).toLowerCase(Locale.US);
        month = MONTH_PATTERN.pattern().indexOf(monthString) / 4; // Sneaky! jan=1, dec=12.
      } else if (year == -1 && matcher.usePattern(YEAR_PATTERN).matches()) {
        year = Integer.parseInt(matcher.group(1));
      }

      pos = dateCharacterOffset(s, end + 1, limit, false);
    }

    // Convert two-digit years into four-digit years. 99 becomes 1999, 15 becomes 2015.
    if (year >= 70 && year <= 99) year += 1900;
    if (year >= 0 && year <= 69) year += 2000;

    // If any partial is omitted or out of range, return -1. The date is impossible. Note that leap
    // seconds are not supported by this syntax.
    if (year < 1601) throw new IllegalArgumentException();
    if (month == -1) throw new IllegalArgumentException();
    if (dayOfMonth < 1 || dayOfMonth > 31) throw new IllegalArgumentException();
    if (hour < 0 || hour > 23) throw new IllegalArgumentException();
    if (minute < 0 || minute > 59) throw new IllegalArgumentException();
    if (second < 0 || second > 59) throw new IllegalArgumentException();

    Calendar calendar = new GregorianCalendar(UTC);
    calendar.setLenient(false);
    calendar.set(Calendar.YEAR, year);
    calendar.set(Calendar.MONTH, month - 1);
    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
    calendar.set(Calendar.HOUR_OF_DAY, hour);
    calendar.set(Calendar.MINUTE, minute);
    calendar.set(Calendar.SECOND, second);
    calendar.set(Calendar.MILLISECOND, 0);
    return calendar.getTimeInMillis();
  }

  /**
   * Returns the index of the next date character in {@code input}, or if {@code invert} the index
   * of the next non-date character in {@code input}.
   */
  private static int dateCharacterOffset(String input, int pos, int limit, boolean invert) {
    for (int i = pos; i < limit; i++) {
      int c = input.charAt(i);
      boolean dateCharacter = (c < ' ' && c != '\t') || (c >= '\u007f')
          || (c >= '0' && c <= '9')
          || (c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c == ':');
      if (dateCharacter == !invert) return i;
    }
    return limit;
  }

  /**
   * Returns the positive value if {@code attributeValue} is positive, or {@link Long#MIN_VALUE} if
   * it is either 0 or negative. If the value is positive but out of range, this returns {@link
   * Long#MAX_VALUE}.
   *
   * @throws NumberFormatException if {@code s} is not an integer of any precision.
   */
  private static long parseMaxAge(String s) {
    try {
      long parsed = Long.parseLong(s);
      return parsed <= 0L ? Long.MIN_VALUE : parsed;
    } catch (NumberFormatException e) {
      // Check if the value is an integer (positive or negative) that's too big for a long.
      if (s.matches("-?\\d+")) {
        return s.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
      }
      throw e;
    }
  }

  /**
   * Returns a domain string like {@code example.com} for an input domain like {@code EXAMPLE.COM}
   * or {@code .example.com}.
   */
  private static String parseDomain(String s) {
    if (s.endsWith(".")) {
      throw new IllegalArgumentException();
    }
    if (s.startsWith(".")) {
      s = s.substring(1);
    }
    String canonicalDomain = domainToAscii(s);
    if (canonicalDomain == null) {
      throw new IllegalArgumentException();
    }
    return canonicalDomain;
  }

  @Override public String toString() {
    return name + '=' + value;
  }
}
