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
package com.squareup.okhttp;

import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

@Ignore // HttpUrl isn't implemented yet.
public final class HttpUrlTest {
  @Test public void parseTrimsAsciiWhitespace() throws Exception {
    HttpUrl expected = HttpUrl.parse("http://host/");
    assertEquals(expected, HttpUrl.parse("http://host/\f\n\t \r")); // Leading.
    assertEquals(expected, HttpUrl.parse("\r\n\f \thttp://host/")); // Trailing.
    assertEquals(expected, HttpUrl.parse(" http://host/ ")); // Both.
  }

  @Ignore // Need to define expected results.
  @Test public void parseDoesNotTrimOtherWhitespaceCharacters() throws Exception {
    // Whitespace characters list from Google's Guava team: http://goo.gl/IcR9RD
    assertEquals(null, HttpUrl.parse("http://host/\u000b")); // line tabulation
    assertEquals(null, HttpUrl.parse("http://host/\u001c")); // information separator 4
    assertEquals(null, HttpUrl.parse("http://host/\u001d")); // information separator 3
    assertEquals(null, HttpUrl.parse("http://host/\u001e")); // information separator 2
    assertEquals(null, HttpUrl.parse("http://host/\u001f")); // information separator 1
    assertEquals(null, HttpUrl.parse("http://host/\u0085")); // next line
    assertEquals(null, HttpUrl.parse("http://host/\u00a0")); // non-breaking space
    assertEquals(null, HttpUrl.parse("http://host/\u1680")); // ogham space mark
    assertEquals(null, HttpUrl.parse("http://host/\u180e")); // mongolian vowel separator
    assertEquals(null, HttpUrl.parse("http://host/\u2000")); // en quad
    assertEquals(null, HttpUrl.parse("http://host/\u2001")); // em quad
    assertEquals(null, HttpUrl.parse("http://host/\u2002")); // en space
    assertEquals(null, HttpUrl.parse("http://host/\u2003")); // em space
    assertEquals(null, HttpUrl.parse("http://host/\u2004")); // three-per-em space
    assertEquals(null, HttpUrl.parse("http://host/\u2005")); // four-per-em space
    assertEquals(null, HttpUrl.parse("http://host/\u2006")); // six-per-em space
    assertEquals(null, HttpUrl.parse("http://host/\u2007")); // figure space
    assertEquals(null, HttpUrl.parse("http://host/\u2008")); // punctuation space
    assertEquals(null, HttpUrl.parse("http://host/\u2009")); // thin space
    assertEquals(null, HttpUrl.parse("http://host/\u200a")); // hair space
    assertEquals(null, HttpUrl.parse("http://host/\u200b")); // zero-width space
    assertEquals(null, HttpUrl.parse("http://host/\u200c")); // zero-width non-joiner
    assertEquals(null, HttpUrl.parse("http://host/\u200d")); // zero-width joiner
    assertEquals(null, HttpUrl.parse("http://host/\u200e")); // left-to-right mark
    assertEquals(null, HttpUrl.parse("http://host/\u200f")); // right-to-left mark
    assertEquals(null, HttpUrl.parse("http://host/\u2028")); // line separator
    assertEquals(null, HttpUrl.parse("http://host/\u2029")); // paragraph separator
    assertEquals(null, HttpUrl.parse("http://host/\u202f")); // narrow non-breaking space
    assertEquals(null, HttpUrl.parse("http://host/\u205f")); // medium mathematical space
    assertEquals(null, HttpUrl.parse("http://host/\u3000")); // ideographic space
  }

  @Test public void scheme() throws Exception {
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("http://host/"));
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("Http://host/"));
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("http://host/"));
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("HTTP://host/"));
    assertEquals(HttpUrl.parse("https://host/"), HttpUrl.parse("https://host/"));
    assertEquals(HttpUrl.parse("https://host/"), HttpUrl.parse("HTTPS://host/"));
    assertEquals(null, HttpUrl.parse("httpp://host/"));
    assertEquals(null, HttpUrl.parse("0ttp://host/"));
    assertEquals(null, HttpUrl.parse("ht+tp://host/"));
    assertEquals(null, HttpUrl.parse("ht.tp://host/"));
    assertEquals(null, HttpUrl.parse("ht-tp://host/"));
    assertEquals(null, HttpUrl.parse("ht1tp://host/"));
    assertEquals(null, HttpUrl.parse("httpss://host/"));
  }

  @Test public void parseNoScheme() throws Exception {
    assertEquals(null, HttpUrl.parse("//host"));
    assertEquals(null, HttpUrl.parse("/path"));
    assertEquals(null, HttpUrl.parse("path"));
    assertEquals(null, HttpUrl.parse("?query"));
    assertEquals(null, HttpUrl.parse("#fragment"));
  }

  @Test public void resolveNoScheme() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b");
    assertEquals(HttpUrl.parse("http://host2/"), base.resolve("//host2"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("/path"));
    assertEquals(HttpUrl.parse("http://host/a/path"), base.resolve("path"));
    assertEquals(HttpUrl.parse("http://host/a/b?query"), base.resolve("?query"));
    assertEquals(HttpUrl.parse("http://host/a/b#fragment"), base.resolve("#fragment"));
    assertEquals(HttpUrl.parse("http://host/a/b"), base.resolve(""));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("\\path"));
  }

  @Test public void resolveUnsupportedScheme() throws Exception {
    HttpUrl base = HttpUrl.parse("http://a/");
    assertEquals(null, base.resolve("ftp://b"));
    assertEquals(null, base.resolve("ht+tp://b"));
    assertEquals(null, base.resolve("ht-tp://b"));
    assertEquals(null, base.resolve("ht.tp://b"));
  }

  @Test public void resolveSchemeLikePath() throws Exception {
    HttpUrl base = HttpUrl.parse("http://a/");
    assertEquals(HttpUrl.parse("http://a/http//b/"), base.resolve("http//b/"));
    assertEquals(HttpUrl.parse("http://a/ht+tp//b/"), base.resolve("ht+tp//b/"));
    assertEquals(HttpUrl.parse("http://a/ht-tp//b/"), base.resolve("ht-tp//b/"));
    assertEquals(HttpUrl.parse("http://a/ht.tp//b/"), base.resolve("ht.tp//b/"));
  }

  @Test public void parseAuthoritySlashCountDoesntMatter() throws Exception {
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http://host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:/\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:///host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:\\//host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:/\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http://\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:\\\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:/\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:\\\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http:////host/path"));
  }

  @Test public void resolveAuthoritySlashCountDoesntMatterWithDifferentScheme() throws Exception {
    HttpUrl base = HttpUrl.parse("https://a/b/c");
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http://host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:///host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\//host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http://\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:////host/path"));
  }

  @Test public void resolveAuthoritySlashCountMattersWithSameScheme() throws Exception {
    HttpUrl base = HttpUrl.parse("http://a/b/c");
    assertEquals(HttpUrl.parse("http://a/b/host/path"), base.resolve("http:host/path"));
    assertEquals(HttpUrl.parse("http://a/host/path"), base.resolve("http:/host/path"));
    assertEquals(HttpUrl.parse("http://a/host/path"), base.resolve("http:\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http://host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:///host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\//host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http://\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\\\/host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:/\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:\\\\\\host/path"));
    assertEquals(HttpUrl.parse("http://host/path"), base.resolve("http:////host/path"));
  }

  @Test public void username() throws Exception {
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http://@host/path"));
    assertEquals(HttpUrl.parse("http://user@host/path"), HttpUrl.parse("http://user@host/path"));
  }

  @Ignore // Different from Firefox and Chrome.
  @Test public void authorityWithMultipleAtSigns() throws Exception {
    assertEquals(HttpUrl.parse("http://foo%40bar@baz/path"),
        HttpUrl.parse("http://foo@bar@baz/path"));
    assertEquals(HttpUrl.parse("http://foo:pass1%40bar%3Apass2@baz/path"),
        HttpUrl.parse("http://foo:pass1@bar:pass2@baz/path"));
  }

  @Test public void usernameAndPassword() throws Exception {
    assertEquals(HttpUrl.parse("http://username:password@host/path"),
        HttpUrl.parse("http://username:password@host/path"));
    assertEquals(HttpUrl.parse("http://username@host/path"),
        HttpUrl.parse("http://username:@host/path"));
  }

  @Ignore // Different from Firefox and Chrome.
  @Test public void passwordWithEmptyUsername() throws Exception {
    // Chrome doesn't mind, but Firefox rejects URLs with empty usernames and non-empty passwords.
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http://:@host/path"));
    assertEquals(HttpUrl.parse("http://:password@@host/path"),
        HttpUrl.parse("http://:password@host/path"));
  }

  @Ignore // Different from Firefox and Chrome.
  @Test public void unprintableCharactersArePercentEncoded() throws Exception {
    assertEquals("%00", HttpUrl.parse("http://host/\u0000").pathSegments().get(0));
    assertEquals("%08", HttpUrl.parse("http://host/\u0008").pathSegments().get(0));
    assertEquals("%EF%BF%BD", HttpUrl.parse("http://host/\ufffd").pathSegments().get(0));
  }

  @Test public void usernameCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(UrlComponentEncodingTester.Encoding.PERCENT, '[', ']', '{', '}', '|', '^')
        .override(UrlComponentEncodingTester.Encoding.SKIP, ':', '@', '/', '\\')
        .test(UrlComponentEncodingTester.Component.USER);
  }

  @Test public void passwordCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(UrlComponentEncodingTester.Encoding.PERCENT, '[', ']', '{', '}', '|', '^')
        .override(UrlComponentEncodingTester.Encoding.SKIP, '@', '/', '\\')
        .test(UrlComponentEncodingTester.Component.PASSWORD);
  }

  @Test public void pathCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(UrlComponentEncodingTester.Encoding.SKIP, '.', '/', '\\')
        .test(UrlComponentEncodingTester.Component.PATH);
  }
}
