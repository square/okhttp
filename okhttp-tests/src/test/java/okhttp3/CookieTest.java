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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpDate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CookieTest {
  HttpUrl url = HttpUrl.parse("https://example.com/");

  @Test public void simpleCookie() throws Exception {
    Cookie cookie = Cookie.parse(url, "SID=31d4d96e407aad42");
    assertEquals("SID=31d4d96e407aad42; path=/", cookie.toString());
  }

  @Test public void noEqualsSign() throws Exception {
    assertNull(Cookie.parse(url, "foo"));
    assertNull(Cookie.parse(url, "foo; Path=/"));
  }

  @Test public void emptyName() throws Exception {
    assertNull(Cookie.parse(url, "=b"));
    assertNull(Cookie.parse(url, " =b"));
    assertNull(Cookie.parse(url, "\r\t \n=b"));
  }

  @Test public void spaceInName() throws Exception {
    assertEquals("a b", Cookie.parse(url, "a b=cd").name());
  }

  @Test public void spaceInValue() throws Exception {
    assertEquals("c d", Cookie.parse(url, "ab=c d").value());
  }

  @Test public void trimLeadingAndTrailingWhitespaceFromName() throws Exception {
    assertEquals("a", Cookie.parse(url, " a=b").name());
    assertEquals("a", Cookie.parse(url, "a =b").name());
    assertEquals("a", Cookie.parse(url, "\r\t \na\n\t \n=b").name());
  }

  @Test public void emptyValue() throws Exception {
    assertEquals("", Cookie.parse(url, "a=").value());
    assertEquals("", Cookie.parse(url, "a= ").value());
    assertEquals("", Cookie.parse(url, "a=\r\t \n").value());
  }

  @Test public void trimLeadingAndTrailingWhitespaceFromValue() throws Exception {
    assertEquals("", Cookie.parse(url, "a= ").value());
    assertEquals("b", Cookie.parse(url, "a= b").value());
    assertEquals("b", Cookie.parse(url, "a=b ").value());
    assertEquals("b", Cookie.parse(url, "a=\r\t \nb\n\t \n").value());
  }

  @Test public void invalidCharacters() throws Exception {
    assertEquals(null, Cookie.parse(url, "a\u0000b=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u0000d"));
    assertEquals(null, Cookie.parse(url, "a\u0001b=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u0001d"));
    assertEquals(null, Cookie.parse(url, "a\u0009b=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u0009d"));
    assertEquals(null, Cookie.parse(url, "a\u001fb=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u001fd"));
    assertEquals(null, Cookie.parse(url, "a\u007fb=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u007fd"));
    assertEquals(null, Cookie.parse(url, "a\u0080b=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u0080d"));
    assertEquals(null, Cookie.parse(url, "a\u00ffb=cd"));
    assertEquals(null, Cookie.parse(url, "ab=c\u00ffd"));
  }

  @Test public void maxAge() throws Exception {
    assertEquals(51000L,
        Cookie.parse(50000L, url, "a=b; Max-Age=1").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(50000L, url, "a=b; Max-Age=9223372036854724").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(50000L, url, "a=b; Max-Age=9223372036854725").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(50000L, url, "a=b; Max-Age=9223372036854726").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(9223372036854773807L, url, "a=b; Max-Age=1").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(9223372036854773807L, url, "a=b; Max-Age=2").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(9223372036854773807L, url, "a=b; Max-Age=3").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(50000L, url, "a=b; Max-Age=10000000000000000000").expiresAt());
  }

  @Test public void maxAgeNonPositive() throws Exception {
    assertEquals(Long.MIN_VALUE,
        Cookie.parse(50000L, url, "a=b; Max-Age=-1").expiresAt());
    assertEquals(Long.MIN_VALUE,
        Cookie.parse(50000L, url, "a=b; Max-Age=0").expiresAt());
    assertEquals(Long.MIN_VALUE,
        Cookie.parse(50000L, url, "a=b; Max-Age=-9223372036854775808").expiresAt());
    assertEquals(Long.MIN_VALUE,
        Cookie.parse(50000L, url, "a=b; Max-Age=-9223372036854775809").expiresAt());
    assertEquals(Long.MIN_VALUE,
        Cookie.parse(50000L, url, "a=b; Max-Age=-10000000000000000000").expiresAt());
  }

  @Test public void domainAndPath() throws Exception {
    Cookie cookie = Cookie.parse(url, "SID=31d4d96e407aad42; Path=/; Domain=example.com");
    assertEquals("example.com", cookie.domain());
    assertEquals("/", cookie.path());
    assertFalse(cookie.hostOnly());
    assertEquals("SID=31d4d96e407aad42; domain=example.com; path=/", cookie.toString());
  }

  @Test public void secureAndHttpOnly() throws Exception {
    Cookie cookie = Cookie.parse(url, "SID=31d4d96e407aad42; Path=/; Secure; HttpOnly");
    assertTrue(cookie.secure());
    assertTrue(cookie.httpOnly());
    assertEquals("SID=31d4d96e407aad42; path=/; secure; httponly", cookie.toString());
  }

  @Test public void expiresDate() throws Exception {
    assertEquals(date("1970-01-01T00:00:00.000+0000"), new Date(
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT").expiresAt()));
    assertEquals(date("2021-06-09T10:18:14.000+0000"), new Date(
        Cookie.parse(url, "a=b; Expires=Wed, 09 Jun 2021 10:18:14 GMT").expiresAt()));
    assertEquals(date("1994-11-06T08:49:37.000+0000"), new Date(
        Cookie.parse(url, "a=b; Expires=Sun, 06 Nov 1994 08:49:37 GMT").expiresAt()));
  }

  @Test public void awkwardDates() throws Exception {
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 70 00:00:00 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 01 January 1970 00:00:00 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Janucember 1970 00:00:00 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 1 Jan 1970 00:00:00 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 0:00:00 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:0:00 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:0 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=00:00:00 Thu, 01 Jan 1970 GMT").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=00:00:00 1970 Jan 01").expiresAt());
    assertEquals(0L,
        Cookie.parse(url, "a=b; Expires=00:00:00 1970 Jan 1").expiresAt());
  }

  @Test public void invalidYear() throws Exception {
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1600 00:00:00 GMT").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 19999 00:00:00 GMT").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 00:00:00 GMT").expiresAt());
  }

  @Test public void invalidMonth() throws Exception {
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Foo 1970 00:00:00 GMT").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Foocember 1970 00:00:00 GMT").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 1970 00:00:00 GMT").expiresAt());
  }

  @Test public void invalidDayOfMonth() throws Exception {
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 32 Jan 1970 00:00:00 GMT").expiresAt());
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, Jan 1970 00:00:00 GMT").expiresAt());
  }

  @Test public void invalidHour() throws Exception {
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 24:00:00 GMT").expiresAt());
  }

  @Test public void invalidMinute() throws Exception {
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:60:00 GMT").expiresAt());
  }

  @Test public void invalidSecond() throws Exception {
    assertEquals(HttpDate.MAX_DATE,
        Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:60 GMT").expiresAt());
  }

  @Test public void domainMatches() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b; domain=example.com");
    assertTrue(cookie.matches(HttpUrl.parse("http://example.com")));
    assertTrue(cookie.matches(HttpUrl.parse("http://www.example.com")));
    assertFalse(cookie.matches(HttpUrl.parse("http://square.com")));
  }

  /** If no domain is present, match only the origin domain. */
  @Test public void domainMatchesNoDomain() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b");
    assertTrue(cookie.matches(HttpUrl.parse("http://example.com")));
    assertFalse(cookie.matches(HttpUrl.parse("http://www.example.com")));
    assertFalse(cookie.matches(HttpUrl.parse("http://square.com")));
  }

  /** Ignore an optional leading `.` in the domain. */
  @Test public void domainMatchesIgnoresLeadingDot() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b; domain=.example.com");
    assertTrue(cookie.matches(HttpUrl.parse("http://example.com")));
    assertTrue(cookie.matches(HttpUrl.parse("http://www.example.com")));
    assertFalse(cookie.matches(HttpUrl.parse("http://square.com")));
  }

  /** Ignore the entire attribute if the domain ends with `.`. */
  @Test public void domainIgnoredWithTrailingDot() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b; domain=example.com.");
    assertTrue(cookie.matches(HttpUrl.parse("http://example.com")));
    assertFalse(cookie.matches(HttpUrl.parse("http://www.example.com")));
    assertFalse(cookie.matches(HttpUrl.parse("http://square.com")));
  }

  @Test public void idnDomainMatches() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.parse("http://☃.net/"), "a=b; domain=☃.net");
    assertTrue(cookie.matches(HttpUrl.parse("http://☃.net/")));
    assertTrue(cookie.matches(HttpUrl.parse("http://xn--n3h.net/")));
    assertTrue(cookie.matches(HttpUrl.parse("http://www.☃.net/")));
    assertTrue(cookie.matches(HttpUrl.parse("http://www.xn--n3h.net/")));
  }

  @Test public void punycodeDomainMatches() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.parse("http://xn--n3h.net/"), "a=b; domain=xn--n3h.net");
    assertTrue(cookie.matches(HttpUrl.parse("http://☃.net/")));
    assertTrue(cookie.matches(HttpUrl.parse("http://xn--n3h.net/")));
    assertTrue(cookie.matches(HttpUrl.parse("http://www.☃.net/")));
    assertTrue(cookie.matches(HttpUrl.parse("http://www.xn--n3h.net/")));
  }

  @Test public void domainMatchesIpAddress() throws Exception {
    HttpUrl urlWithIp = HttpUrl.parse("http://123.45.234.56/");
    assertNull(Cookie.parse(urlWithIp, "a=b; domain=234.56"));
    assertEquals("123.45.234.56", Cookie.parse(urlWithIp, "a=b; domain=123.45.234.56").domain());
  }

  /**
   * These public suffixes were selected by inspecting the publicsuffix.org list. It's possible they
   * may change in the future. If this test begins to fail, please double check they are still
   * present in the public suffix list.
   */
  @Test public void domainIsPublicSuffix() {
    HttpUrl ascii = HttpUrl.parse("https://foo1.foo.bar.elb.amazonaws.com");
    assertNotNull(Cookie.parse(ascii, "a=b; domain=foo.bar.elb.amazonaws.com"));
    assertNull(Cookie.parse(ascii, "a=b; domain=bar.elb.amazonaws.com"));
    assertNull(Cookie.parse(ascii, "a=b; domain=com"));

    HttpUrl unicode = HttpUrl.parse("https://長.長.長崎.jp");
    assertNotNull(Cookie.parse(unicode, "a=b; domain=長.長崎.jp"));
    assertNull(Cookie.parse(unicode, "a=b; domain=長崎.jp"));

    HttpUrl punycode = HttpUrl.parse("https://xn--ue5a.xn--ue5a.xn--8ltr62k.jp");
    assertNotNull(Cookie.parse(punycode, "a=b; domain=xn--ue5a.xn--8ltr62k.jp"));
    assertNull(Cookie.parse(punycode, "a=b; domain=xn--8ltr62k.jp"));
  }

  @Test public void hostOnly() throws Exception {
    assertTrue(Cookie.parse(url, "a=b").hostOnly());
    assertFalse(Cookie.parse(url, "a=b; domain=example.com").hostOnly());
  }

  @Test public void defaultPath() throws Exception {
    assertEquals("/foo", Cookie.parse(HttpUrl.parse("http://example.com/foo/bar"), "a=b").path());
    assertEquals("/foo", Cookie.parse(HttpUrl.parse("http://example.com/foo/"), "a=b").path());
    assertEquals("/", Cookie.parse(HttpUrl.parse("http://example.com/foo"), "a=b").path());
    assertEquals("/", Cookie.parse(HttpUrl.parse("http://example.com/"), "a=b").path());
  }

  @Test public void defaultPathIsUsedIfPathDoesntHaveLeadingSlash() throws Exception {
    assertEquals("/foo", Cookie.parse(HttpUrl.parse("http://example.com/foo/bar"),
        "a=b; path=quux").path());
    assertEquals("/foo", Cookie.parse(HttpUrl.parse("http://example.com/foo/bar"),
        "a=b; path=").path());
  }

  @Test public void pathAttributeDoesntNeedToMatch() throws Exception {
    assertEquals("/quux", Cookie.parse(HttpUrl.parse("http://example.com/"),
        "a=b; path=/quux").path());
    assertEquals("/quux", Cookie.parse(HttpUrl.parse("http://example.com/foo/bar"),
        "a=b; path=/quux").path());
  }

  @Test public void httpOnly() throws Exception {
    assertFalse(Cookie.parse(url, "a=b").httpOnly());
    assertTrue(Cookie.parse(url, "a=b; HttpOnly").httpOnly());
  }

  @Test public void secure() throws Exception {
    assertFalse(Cookie.parse(url, "a=b").secure());
    assertTrue(Cookie.parse(url, "a=b; Secure").secure());
  }

  @Test public void maxAgeTakesPrecedenceOverExpires() throws Exception {
    // Max-Age = 1, Expires = 2. In either order.
    assertEquals(1000L, Cookie.parse(
        0L, url, "a=b; Max-Age=1; Expires=Thu, 01 Jan 1970 00:00:02 GMT").expiresAt());
    assertEquals(1000L, Cookie.parse(
        0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:02 GMT; Max-Age=1").expiresAt());
    // Max-Age = 2, Expires = 1. In either order.
    assertEquals(2000L, Cookie.parse(
        0L, url, "a=b; Max-Age=2; Expires=Thu, 01 Jan 1970 00:00:01 GMT").expiresAt());
    assertEquals(2000L, Cookie.parse(
        0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:01 GMT; Max-Age=2").expiresAt());
  }

  /** If a cookie incorrectly defines multiple 'Max-Age' attributes, the last one defined wins. */
  @Test public void lastMaxAgeWins() throws Exception {
    assertEquals(3000L, Cookie.parse(
        0L, url, "a=b; Max-Age=2; Max-Age=4; Max-Age=1; Max-Age=3").expiresAt());
  }

  /** If a cookie incorrectly defines multiple 'Expires' attributes, the last one defined wins. */
  @Test public void lastExpiresAtWins() throws Exception {
    assertEquals(3000L, Cookie.parse(0L, url, "a=b; "
        + "Expires=Thu, 01 Jan 1970 00:00:02 GMT; "
        + "Expires=Thu, 01 Jan 1970 00:00:04 GMT; "
        + "Expires=Thu, 01 Jan 1970 00:00:01 GMT; "
        + "Expires=Thu, 01 Jan 1970 00:00:03 GMT").expiresAt());
  }

  @Test public void maxAgeOrExpiresMakesCookiePersistent() throws Exception {
    assertFalse(Cookie.parse(0L, url, "a=b").persistent());
    assertTrue(Cookie.parse(0L, url, "a=b; Max-Age=1").persistent());
    assertTrue(Cookie.parse(0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:01 GMT").persistent());
  }

  @Test public void parseAll() throws Exception {
    Headers headers = new Headers.Builder()
        .add("Set-Cookie: a=b")
        .add("Set-Cookie: c=d")
        .build();
    List<Cookie> cookies = Cookie.parseAll(url, headers);
    assertEquals(2, cookies.size());
    assertEquals("a=b; path=/", cookies.get(0).toString());
    assertEquals("c=d; path=/", cookies.get(1).toString());
  }

  @Test public void builder() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .domain("example.com")
        .build();
    assertEquals("a", cookie.name());
    assertEquals("b", cookie.value());
    assertEquals(HttpDate.MAX_DATE, cookie.expiresAt());
    assertEquals("example.com", cookie.domain());
    assertEquals("/", cookie.path());
    assertFalse(cookie.secure());
    assertFalse(cookie.httpOnly());
    assertFalse(cookie.persistent());
    assertFalse(cookie.hostOnly());
  }

  @Test public void builderNameValidation() throws Exception {
    try {
      new Cookie.Builder().name(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Cookie.Builder().name(" a ");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void builderValueValidation() throws Exception {
    try {
      new Cookie.Builder().value(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Cookie.Builder().value(" b ");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void builderClampsMaxDate() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(Long.MAX_VALUE)
        .build();
    assertEquals("a=b; expires=Fri, 31 Dec 9999 23:59:59 GMT; path=/", cookie.toString());
  }

  @Test public void builderExpiresAt() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(date("1970-01-01T00:00:01.000+0000").getTime())
        .build();
    assertEquals("a=b; expires=Thu, 01 Jan 1970 00:00:01 GMT; path=/", cookie.toString());
  }

  @Test public void builderClampsMinDate() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(date("1970-01-01T00:00:00.000+0000").getTime())
        .build();
    assertEquals("a=b; max-age=0; path=/", cookie.toString());
  }

  @Test public void builderDomainValidation() throws Exception {
    try {
      new Cookie.Builder().hostOnlyDomain(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Cookie.Builder().hostOnlyDomain("a/b");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void builderDomain() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("squareup.com")
        .build();
    assertEquals("squareup.com", cookie.domain());
    assertTrue(cookie.hostOnly());
  }

  @Test public void builderPath() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .path("/foo")
        .build();
    assertEquals("/foo", cookie.path());
  }

  @Test public void builderPathValidation() throws Exception {
    try {
      new Cookie.Builder().path(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Cookie.Builder().path("foo");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void builderSecure() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .secure()
        .build();
    assertEquals(true, cookie.secure());
  }

  @Test public void builderHttpOnly() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .httpOnly()
        .build();
    assertEquals(true, cookie.httpOnly());
  }

  @Test public void equalsAndHashCode() throws Exception {
    List<String> cookieStrings = Arrays.asList(
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a= ; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a=b;          Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a=b; Path=/c;                     Max-Age=5; Secure; HttpOnly",
        "a=b; Path=/c; Domain=example.com;            Secure; HttpOnly",
        "a=b; Path=/c; Domain=example.com; Max-Age=5;         HttpOnly",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure;         "
    );
    for (String stringA : cookieStrings) {
      Cookie cookieA = Cookie.parse(0, url, stringA);
      for (String stringB : cookieStrings) {
        Cookie cookieB = Cookie.parse(0, url, stringB);
        if (Objects.equals(stringA, stringB)) {
          assertEquals(cookieA.hashCode(), cookieB.hashCode());
          assertEquals(cookieA, cookieB);
        } else {
          assertFalse(cookieA.hashCode() == cookieB.hashCode());
          assertFalse(cookieA.equals(cookieB));
        }
      }
      assertFalse(cookieA.equals(null));
    }
  }

  private Date date(String s) throws ParseException {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    format.setTimeZone(Util.UTC);
    return format.parse(s);
  }
}
