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
import java.util.Date;
import java.util.List;
import java.util.Objects;
import okhttp3.internal.Util;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.internal.Internal.parseCookie;
import static okhttp3.internal.http.DatesKt.MAX_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class CookieTest {
  HttpUrl url = HttpUrl.get("https://example.com/");

  @Test public void simpleCookie() throws Exception {
    Cookie cookie = Cookie.parse(url, "SID=31d4d96e407aad42");
    assertThat(cookie.toString()).isEqualTo("SID=31d4d96e407aad42; path=/");
  }

  @Test public void noEqualsSign() throws Exception {
    assertThat(Cookie.parse(url, "foo")).isNull();
    assertThat(Cookie.parse(url, "foo; Path=/")).isNull();
  }

  @Test public void emptyName() throws Exception {
    assertThat(Cookie.parse(url, "=b")).isNull();
    assertThat(Cookie.parse(url, " =b")).isNull();
    assertThat(Cookie.parse(url, "\r\t \n=b")).isNull();
  }

  @Test public void spaceInName() throws Exception {
    assertThat(Cookie.parse(url, "a b=cd").name()).isEqualTo("a b");
  }

  @Test public void spaceInValue() throws Exception {
    assertThat(Cookie.parse(url, "ab=c d").value()).isEqualTo("c d");
  }

  @Test public void trimLeadingAndTrailingWhitespaceFromName() throws Exception {
    assertThat(Cookie.parse(url, " a=b").name()).isEqualTo("a");
    assertThat(Cookie.parse(url, "a =b").name()).isEqualTo("a");
    assertThat(Cookie.parse(url, "\r\t \na\n\t \n=b").name()).isEqualTo("a");
  }

  @Test public void emptyValue() throws Exception {
    assertThat(Cookie.parse(url, "a=").value()).isEqualTo("");
    assertThat(Cookie.parse(url, "a= ").value()).isEqualTo("");
    assertThat(Cookie.parse(url, "a=\r\t \n").value()).isEqualTo("");
  }

  @Test public void trimLeadingAndTrailingWhitespaceFromValue() throws Exception {
    assertThat(Cookie.parse(url, "a= ").value()).isEqualTo("");
    assertThat(Cookie.parse(url, "a= b").value()).isEqualTo("b");
    assertThat(Cookie.parse(url, "a=b ").value()).isEqualTo("b");
    assertThat(Cookie.parse(url, "a=\r\t \nb\n\t \n").value()).isEqualTo("b");
  }

  @Test public void invalidCharacters() throws Exception {
    assertThat(Cookie.parse(url, "a\u0000b=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u0000d")).isNull();
    assertThat(Cookie.parse(url, "a\u0001b=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u0001d")).isNull();
    assertThat(Cookie.parse(url, "a\u0009b=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u0009d")).isNull();
    assertThat(Cookie.parse(url, "a\u001fb=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u001fd")).isNull();
    assertThat(Cookie.parse(url, "a\u007fb=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u007fd")).isNull();
    assertThat(Cookie.parse(url, "a\u0080b=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u0080d")).isNull();
    assertThat(Cookie.parse(url, "a\u00ffb=cd")).isNull();
    assertThat(Cookie.parse(url, "ab=c\u00ffd")).isNull();
  }

  @Test public void maxAge() throws Exception {
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=1").expiresAt())
        .isEqualTo(51000L);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=9223372036854724").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=9223372036854725").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=9223372036854726").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(parseCookie(9223372036854773807L, url, "a=b; Max-Age=1").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(parseCookie(9223372036854773807L, url, "a=b; Max-Age=2").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(parseCookie(9223372036854773807L, url, "a=b; Max-Age=3").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=10000000000000000000").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void maxAgeNonPositive() throws Exception {
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-1").expiresAt())
        .isEqualTo(Long.MIN_VALUE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=0").expiresAt())
        .isEqualTo(Long.MIN_VALUE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-9223372036854775808").expiresAt())
        .isEqualTo(Long.MIN_VALUE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-9223372036854775809").expiresAt())
        .isEqualTo(Long.MIN_VALUE);
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-10000000000000000000").expiresAt())
        .isEqualTo(Long.MIN_VALUE);
  }

  @Test public void domainAndPath() throws Exception {
    Cookie cookie = Cookie.parse(url, "SID=31d4d96e407aad42; Path=/; Domain=example.com");
    assertThat(cookie.domain()).isEqualTo("example.com");
    assertThat(cookie.path()).isEqualTo("/");
    assertThat(cookie.hostOnly()).isFalse();
    assertThat(cookie.toString()).isEqualTo(
        "SID=31d4d96e407aad42; domain=example.com; path=/");
  }

  @Test public void secureAndHttpOnly() throws Exception {
    Cookie cookie = Cookie.parse(url, "SID=31d4d96e407aad42; Path=/; Secure; HttpOnly");
    assertThat(cookie.secure()).isTrue();
    assertThat(cookie.httpOnly()).isTrue();
    assertThat(cookie.toString()).isEqualTo(
        "SID=31d4d96e407aad42; path=/; secure; httponly");
  }

  @Test public void expiresDate() throws Exception {
    assertThat(new Date(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        .expiresAt())).isEqualTo(date("1970-01-01T00:00:00.000+0000"));
    assertThat(new Date(Cookie.parse(url, "a=b; Expires=Wed, 09 Jun 2021 10:18:14 GMT")
        .expiresAt())).isEqualTo(date("2021-06-09T10:18:14.000+0000"));
    assertThat(new Date(Cookie.parse(url, "a=b; Expires=Sun, 06 Nov 1994 08:49:37 GMT")
        .expiresAt())).isEqualTo(date("1994-11-06T08:49:37.000+0000"));
  }

  @Test public void awkwardDates() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 70 00:00:00 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 January 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Janucember 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 1 Jan 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 0:00:00 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:0:00 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:0 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=00:00:00 Thu, 01 Jan 1970 GMT").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=00:00:00 1970 Jan 01").expiresAt())
        .isEqualTo(0L);
    assertThat(Cookie.parse(url, "a=b; Expires=00:00:00 1970 Jan 1").expiresAt())
        .isEqualTo(0L);
  }

  @Test public void invalidYear() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1600 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 19999 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void invalidMonth() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Foo 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Foocember 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void invalidDayOfMonth() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 32 Jan 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, Jan 1970 00:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void invalidHour() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 24:00:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void invalidMinute() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:60:00 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void invalidSecond() throws Exception {
    assertThat(Cookie.parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:60 GMT").expiresAt())
        .isEqualTo(MAX_DATE);
  }

  @Test public void domainMatches() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b; domain=example.com");
    assertThat(cookie.matches(HttpUrl.get("http://example.com"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.example.com"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://square.com"))).isFalse();
  }

  /** If no domain is present, match only the origin domain. */
  @Test public void domainMatchesNoDomain() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b");
    assertThat(cookie.matches(HttpUrl.get("http://example.com"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.example.com"))).isFalse();
    assertThat(cookie.matches(HttpUrl.get("http://square.com"))).isFalse();
  }

  /** Ignore an optional leading `.` in the domain. */
  @Test public void domainMatchesIgnoresLeadingDot() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b; domain=.example.com");
    assertThat(cookie.matches(HttpUrl.get("http://example.com"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.example.com"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://square.com"))).isFalse();
  }

  /** Ignore the entire attribute if the domain ends with `.`. */
  @Test public void domainIgnoredWithTrailingDot() throws Exception {
    Cookie cookie = Cookie.parse(url, "a=b; domain=example.com.");
    assertThat(cookie.matches(HttpUrl.get("http://example.com"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.example.com"))).isFalse();
    assertThat(cookie.matches(HttpUrl.get("http://square.com"))).isFalse();
  }

  @Test public void idnDomainMatches() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.get("http://☃.net/"), "a=b; domain=☃.net");
    assertThat(cookie.matches(HttpUrl.get("http://☃.net/"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://xn--n3h.net/"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.☃.net/"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.xn--n3h.net/"))).isTrue();
  }

  @Test public void punycodeDomainMatches() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.get("http://xn--n3h.net/"), "a=b; domain=xn--n3h.net");
    assertThat(cookie.matches(HttpUrl.get("http://☃.net/"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://xn--n3h.net/"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.☃.net/"))).isTrue();
    assertThat(cookie.matches(HttpUrl.get("http://www.xn--n3h.net/"))).isTrue();
  }

  @Test public void domainMatchesIpAddress() throws Exception {
    HttpUrl urlWithIp = HttpUrl.get("http://123.45.234.56/");
    assertThat(Cookie.parse(urlWithIp, "a=b; domain=234.56")).isNull();
    assertThat(Cookie.parse(urlWithIp, "a=b; domain=123.45.234.56").domain()).isEqualTo(
        "123.45.234.56");
  }

  @Test public void domainMatchesIpv6Address() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.get("http://[::1]/"), "a=b; domain=::1");
    assertThat(cookie.domain()).isEqualTo("::1");
    assertThat(cookie.matches(HttpUrl.get("http://[::1]/"))).isTrue();
  }

  @Test public void domainMatchesIpv6AddressWithCompression() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.get("http://[0001:0000::]/"), "a=b; domain=0001:0000::");
    assertThat(cookie.domain()).isEqualTo("1::");
    assertThat(cookie.matches(HttpUrl.get("http://[1::]/"))).isTrue();
  }

  @Test public void domainMatchesIpv6AddressWithIpv4Suffix() throws Exception {
    Cookie cookie = Cookie.parse(
        HttpUrl.get("http://[::1:ffff:ffff]/"), "a=b; domain=::1:255.255.255.255");
    assertThat(cookie.domain()).isEqualTo("::1:ffff:ffff");
    assertThat(cookie.matches(HttpUrl.get("http://[::1:ffff:ffff]/"))).isTrue();
  }

  @Test public void ipv6AddressDoesntMatch() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.get("http://[::1]/"), "a=b; domain=::2");
    assertThat(cookie).isNull();
  }

  @Test public void ipv6AddressMalformed() throws Exception {
    Cookie cookie = Cookie.parse(HttpUrl.get("http://[::1]/"), "a=b; domain=::2::2");
    assertThat(cookie.domain()).isEqualTo("::1");
  }

  /**
   * These public suffixes were selected by inspecting the publicsuffix.org list. It's possible they
   * may change in the future. If this test begins to fail, please double check they are still
   * present in the public suffix list.
   */
  @Test public void domainIsPublicSuffix() {
    HttpUrl ascii = HttpUrl.get("https://foo1.foo.bar.elb.amazonaws.com");
    assertThat(Cookie.parse(ascii, "a=b; domain=foo.bar.elb.amazonaws.com")).isNotNull();
    assertThat(Cookie.parse(ascii, "a=b; domain=bar.elb.amazonaws.com")).isNull();
    assertThat(Cookie.parse(ascii, "a=b; domain=com")).isNull();

    HttpUrl unicode = HttpUrl.get("https://長.長.長崎.jp");
    assertThat(Cookie.parse(unicode, "a=b; domain=長.長崎.jp")).isNotNull();
    assertThat(Cookie.parse(unicode, "a=b; domain=長崎.jp")).isNull();

    HttpUrl punycode = HttpUrl.get("https://xn--ue5a.xn--ue5a.xn--8ltr62k.jp");
    assertThat(Cookie.parse(punycode, "a=b; domain=xn--ue5a.xn--8ltr62k.jp")).isNotNull();
    assertThat(Cookie.parse(punycode, "a=b; domain=xn--8ltr62k.jp")).isNull();
  }

  @Test public void hostOnly() throws Exception {
    assertThat(Cookie.parse(url, "a=b").hostOnly()).isTrue();
    assertThat(Cookie.parse(url, "a=b; domain=example.com").hostOnly()).isFalse();
  }

  @Test public void defaultPath() throws Exception {
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/foo/bar"), "a=b").path()).isEqualTo(
        "/foo");
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/foo/"), "a=b").path()).isEqualTo(
        "/foo");
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/foo"), "a=b").path()).isEqualTo(
        "/");
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/"), "a=b").path()).isEqualTo(
        "/");
  }

  @Test public void defaultPathIsUsedIfPathDoesntHaveLeadingSlash() throws Exception {
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/foo/bar"),
        "a=b; path=quux").path()).isEqualTo("/foo");
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/foo/bar"),
        "a=b; path=").path()).isEqualTo("/foo");
  }

  @Test public void pathAttributeDoesntNeedToMatch() throws Exception {
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/"),
        "a=b; path=/quux").path()).isEqualTo("/quux");
    assertThat(Cookie.parse(HttpUrl.get("http://example.com/foo/bar"),
        "a=b; path=/quux").path()).isEqualTo("/quux");
  }

  @Test public void httpOnly() throws Exception {
    assertThat(Cookie.parse(url, "a=b").httpOnly()).isFalse();
    assertThat(Cookie.parse(url, "a=b; HttpOnly").httpOnly()).isTrue();
  }

  @Test public void secure() throws Exception {
    assertThat(Cookie.parse(url, "a=b").secure()).isFalse();
    assertThat(Cookie.parse(url, "a=b; Secure").secure()).isTrue();
  }

  @Test public void maxAgeTakesPrecedenceOverExpires() throws Exception {
    // Max-Age = 1, Expires = 2. In either order.
    assertThat(parseCookie(
        0L, url, "a=b; Max-Age=1; Expires=Thu, 01 Jan 1970 00:00:02 GMT").expiresAt()).isEqualTo(
        1000L);
    assertThat(parseCookie(
        0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:02 GMT; Max-Age=1").expiresAt()).isEqualTo(
        1000L);
    // Max-Age = 2, Expires = 1. In either order.
    assertThat(parseCookie(
        0L, url, "a=b; Max-Age=2; Expires=Thu, 01 Jan 1970 00:00:01 GMT").expiresAt()).isEqualTo(
        2000L);
    assertThat(parseCookie(
        0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:01 GMT; Max-Age=2").expiresAt()).isEqualTo(
        2000L);
  }

  /** If a cookie incorrectly defines multiple 'Max-Age' attributes, the last one defined wins. */
  @Test public void lastMaxAgeWins() throws Exception {
    assertThat(parseCookie(
        0L, url, "a=b; Max-Age=2; Max-Age=4; Max-Age=1; Max-Age=3").expiresAt()).isEqualTo(3000L);
  }

  /** If a cookie incorrectly defines multiple 'Expires' attributes, the last one defined wins. */
  @Test public void lastExpiresAtWins() throws Exception {
    assertThat(parseCookie(0L, url, "a=b; "
        + "Expires=Thu, 01 Jan 1970 00:00:02 GMT; "
        + "Expires=Thu, 01 Jan 1970 00:00:04 GMT; "
        + "Expires=Thu, 01 Jan 1970 00:00:01 GMT; "
        + "Expires=Thu, 01 Jan 1970 00:00:03 GMT").expiresAt()).isEqualTo(3000L);
  }

  @Test public void maxAgeOrExpiresMakesCookiePersistent() throws Exception {
    assertThat(parseCookie(0L, url, "a=b").persistent()).isFalse();
    assertThat(parseCookie(0L, url, "a=b; Max-Age=1").persistent()).isTrue();
    assertThat(parseCookie(0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:01 GMT").persistent())
        .isTrue();
  }

  @Test public void parseAll() throws Exception {
    Headers headers = new Headers.Builder()
        .add("Set-Cookie: a=b")
        .add("Set-Cookie: c=d")
        .build();
    List<Cookie> cookies = Cookie.parseAll(url, headers);
    assertThat(cookies.size()).isEqualTo(2);
    assertThat(cookies.get(0).toString()).isEqualTo("a=b; path=/");
    assertThat(cookies.get(1).toString()).isEqualTo("c=d; path=/");
  }

  @Test public void builder() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .domain("example.com")
        .build();
    assertThat(cookie.name()).isEqualTo("a");
    assertThat(cookie.value()).isEqualTo("b");
    assertThat(cookie.expiresAt()).isEqualTo(MAX_DATE);
    assertThat(cookie.domain()).isEqualTo("example.com");
    assertThat(cookie.path()).isEqualTo("/");
    assertThat(cookie.secure()).isFalse();
    assertThat(cookie.httpOnly()).isFalse();
    assertThat(cookie.persistent()).isFalse();
    assertThat(cookie.hostOnly()).isFalse();
  }

  @Test public void builderNameValidation() throws Exception {
    try {
      new Cookie.Builder().name(null);
      fail();
    } catch (IllegalArgumentException expected) {
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
    } catch (IllegalArgumentException expected) {
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
    assertThat(cookie.toString()).isEqualTo(
        "a=b; expires=Fri, 31 Dec 9999 23:59:59 GMT; path=/");
  }

  @Test public void builderExpiresAt() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(date("1970-01-01T00:00:01.000+0000").getTime())
        .build();
    assertThat(cookie.toString()).isEqualTo(
        "a=b; expires=Thu, 01 Jan 1970 00:00:01 GMT; path=/");
  }

  @Test public void builderClampsMinDate() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(date("1970-01-01T00:00:00.000+0000").getTime())
        .build();
    assertThat(cookie.toString()).isEqualTo("a=b; max-age=0; path=/");
  }

  @Test public void builderDomainValidation() throws Exception {
    try {
      new Cookie.Builder().hostOnlyDomain(null);
      fail();
    } catch (IllegalArgumentException expected) {
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
    assertThat(cookie.domain()).isEqualTo("squareup.com");
    assertThat(cookie.hostOnly()).isTrue();
  }

  @Test public void builderPath() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .path("/foo")
        .build();
    assertThat(cookie.path()).isEqualTo("/foo");
  }

  @Test public void builderPathValidation() throws Exception {
    try {
      new Cookie.Builder().path(null);
      fail();
    } catch (IllegalArgumentException expected) {
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
    assertThat(cookie.secure()).isTrue();
  }

  @Test public void builderHttpOnly() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .httpOnly()
        .build();
    assertThat(cookie.httpOnly()).isTrue();
  }

  @Test public void builderIpv6() throws Exception {
    Cookie cookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .domain("0:0:0:0:0:0:0:1")
        .build();
    assertThat(cookie.domain()).isEqualTo("::1");
  }

  @Test public void equalsAndHashCode() throws Exception {
    List<String> cookieStrings = asList(
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a= ; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a=b;          Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a=b; Path=/c;                     Max-Age=5; Secure; HttpOnly",
        "a=b; Path=/c; Domain=example.com;            Secure; HttpOnly",
        "a=b; Path=/c; Domain=example.com; Max-Age=5;         HttpOnly",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure;         "
    );
    for (String stringA : cookieStrings) {
      Cookie cookieA = parseCookie(0, url, stringA);
      for (String stringB : cookieStrings) {
        Cookie cookieB = parseCookie(0, url, stringB);
        if (Objects.equals(stringA, stringB)) {
          assertThat(cookieB.hashCode()).isEqualTo(cookieA.hashCode());
          assertThat(cookieB).isEqualTo(cookieA);
        } else {
          assertThat(cookieB.hashCode()).isNotEqualTo((long) cookieA.hashCode());
          assertThat(cookieB).isNotEqualTo(cookieA);
        }
      }
      assertThat(cookieA).isNotEqualTo(null);
    }
  }

  private Date date(String s) throws ParseException {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    format.setTimeZone(Util.UTC);
    return format.parse(s);
  }
}
