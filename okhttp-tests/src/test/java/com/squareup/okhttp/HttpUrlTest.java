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

import com.squareup.okhttp.UrlComponentEncodingTester.Component;
import com.squareup.okhttp.UrlComponentEncodingTester.Encoding;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class HttpUrlTest {
  @Test public void parseTrimsAsciiWhitespace() throws Exception {
    HttpUrl expected = HttpUrl.parse("http://host/");
    assertEquals(expected, HttpUrl.parse("http://host/\f\n\t \r")); // Leading.
    assertEquals(expected, HttpUrl.parse("\r\n\f \thttp://host/")); // Trailing.
    assertEquals(expected, HttpUrl.parse(" http://host/ ")); // Both.
    assertEquals(expected, HttpUrl.parse("    http://host/    ")); // Both.
    assertEquals(expected, HttpUrl.parse("http://host/").resolve("   "));
    assertEquals(expected, HttpUrl.parse("http://host/").resolve("  .  "));
  }

  @Test public void parseDoesNotTrimOtherWhitespaceCharacters() throws Exception {
    // Whitespace characters list from Google's Guava team: http://goo.gl/IcR9RD
    assertEquals("/%0B", HttpUrl.parse("http://h/\u000b").path()); // line tabulation
    assertEquals("/%1C", HttpUrl.parse("http://h/\u001c").path()); // information separator 4
    assertEquals("/%1D", HttpUrl.parse("http://h/\u001d").path()); // information separator 3
    assertEquals("/%1E", HttpUrl.parse("http://h/\u001e").path()); // information separator 2
    assertEquals("/%1F", HttpUrl.parse("http://h/\u001f").path()); // information separator 1
    assertEquals("/%C2%85", HttpUrl.parse("http://h/\u0085").path()); // next line
    assertEquals("/%C2%A0", HttpUrl.parse("http://h/\u00a0").path()); // non-breaking space
    assertEquals("/%E1%9A%80", HttpUrl.parse("http://h/\u1680").path()); // ogham space mark
    assertEquals("/%E1%A0%8E", HttpUrl.parse("http://h/\u180e").path()); // mongolian vowel separator
    assertEquals("/%E2%80%80", HttpUrl.parse("http://h/\u2000").path()); // en quad
    assertEquals("/%E2%80%81", HttpUrl.parse("http://h/\u2001").path()); // em quad
    assertEquals("/%E2%80%82", HttpUrl.parse("http://h/\u2002").path()); // en space
    assertEquals("/%E2%80%83", HttpUrl.parse("http://h/\u2003").path()); // em space
    assertEquals("/%E2%80%84", HttpUrl.parse("http://h/\u2004").path()); // three-per-em space
    assertEquals("/%E2%80%85", HttpUrl.parse("http://h/\u2005").path()); // four-per-em space
    assertEquals("/%E2%80%86", HttpUrl.parse("http://h/\u2006").path()); // six-per-em space
    assertEquals("/%E2%80%87", HttpUrl.parse("http://h/\u2007").path()); // figure space
    assertEquals("/%E2%80%88", HttpUrl.parse("http://h/\u2008").path()); // punctuation space
    assertEquals("/%E2%80%89", HttpUrl.parse("http://h/\u2009").path()); // thin space
    assertEquals("/%E2%80%8A", HttpUrl.parse("http://h/\u200a").path()); // hair space
    assertEquals("/%E2%80%8B", HttpUrl.parse("http://h/\u200b").path()); // zero-width space
    assertEquals("/%E2%80%8C", HttpUrl.parse("http://h/\u200c").path()); // zero-width non-joiner
    assertEquals("/%E2%80%8D", HttpUrl.parse("http://h/\u200d").path()); // zero-width joiner
    assertEquals("/%E2%80%8E", HttpUrl.parse("http://h/\u200e").path()); // left-to-right mark
    assertEquals("/%E2%80%8F", HttpUrl.parse("http://h/\u200f").path()); // right-to-left mark
    assertEquals("/%E2%80%A8", HttpUrl.parse("http://h/\u2028").path()); // line separator
    assertEquals("/%E2%80%A9", HttpUrl.parse("http://h/\u2029").path()); // paragraph separator
    assertEquals("/%E2%80%AF", HttpUrl.parse("http://h/\u202f").path()); // narrow non-breaking space
    assertEquals("/%E2%81%9F", HttpUrl.parse("http://h/\u205f").path()); // medium mathematical space
    assertEquals("/%E3%80%80", HttpUrl.parse("http://h/\u3000").path()); // ideographic space
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

  @Test public void passwordWithEmptyUsername() throws Exception {
    // Chrome doesn't mind, but Firefox rejects URLs with empty usernames and non-empty passwords.
    assertEquals(HttpUrl.parse("http://host/path"), HttpUrl.parse("http://:@host/path"));
    assertEquals("password%40", HttpUrl.parse("http://:password@@host/path").password());
  }

  @Test public void unprintableCharactersArePercentEncoded() throws Exception {
    assertEquals("/%00", HttpUrl.parse("http://host/\u0000").path());
    assertEquals("/%08", HttpUrl.parse("http://host/\u0008").path());
    assertEquals("/%EF%BF%BD", HttpUrl.parse("http://host/\ufffd").path());
  }

  @Test public void usernameCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.PERCENT, '[', ']', '{', '}', '|', '^', '\'', ';', '=', '@')
        .override(Encoding.SKIP, ':', '/', '\\', '?', '#')
        .test(Component.USER);
  }

  @Test public void passwordCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.PERCENT, '[', ']', '{', '}', '|', '^', '\'', ':', ';', '=', '@')
        .override(Encoding.SKIP, '/', '\\', '?', '#')
        .test(Component.PASSWORD);
  }

  @Test public void hostContainsIllegalCharacter() throws Exception {
    assertEquals(null, HttpUrl.parse("http://\n/"));
    assertEquals(null, HttpUrl.parse("http:// /"));
    assertEquals(null, HttpUrl.parse("http://%20/"));
  }

  @Test public void hostnameLowercaseCharactersMappedDirectly() throws Exception {
    assertEquals("abcd", HttpUrl.parse("http://abcd").host());
    assertEquals("xn--4xa", HttpUrl.parse("http://σ").host());
  }

  @Test public void hostnameUppercaseCharactersConvertedToLowercase() throws Exception {
    assertEquals("abcd", HttpUrl.parse("http://ABCD").host());
    assertEquals("xn--4xa", HttpUrl.parse("http://Σ").host());
  }

  @Test public void hostnameIgnoredCharacters() throws Exception {
    // The soft hyphen (­) should be ignored.
    assertEquals("abcd", HttpUrl.parse("http://AB\u00adCD").host());
  }

  @Test public void hostnameMultipleCharacterMapping() throws Exception {
    // Map the single character telephone symbol (℡) to the string "tel".
    assertEquals("tel", HttpUrl.parse("http://\u2121").host());
  }

  @Test public void hostnameMappingLastMappedCodePoint() throws Exception {
    assertEquals("xn--pu5l", HttpUrl.parse("http://\uD87E\uDE1D").host());
  }

  @Ignore // The java.net.IDN implementation doesn't ignore characters that it should.
  @Test public void hostnameMappingLastIgnoredCodePoint() throws Exception {
    assertEquals("abcd", HttpUrl.parse("http://ab\uDB40\uDDEFcd").host());
  }

  @Test public void hostnameMappingLastDisallowedCodePoint() throws Exception {
    assertEquals(null, HttpUrl.parse("http://\uDBFF\uDFFF"));
  }

  @Test public void hostIpv6() throws Exception {
    // Square braces are absent from host()...
    String address = "0:0:0:0:0:0:0:1";
    assertEquals(address, HttpUrl.parse("http://[::1]/").host());

    // ... but they're included in toString().
    assertEquals("http://[0:0:0:0:0:0:0:1]/", HttpUrl.parse("http://[::1]/").toString());

    // IPv6 colons don't interfere with port numbers or passwords.
    assertEquals(8080, HttpUrl.parse("http://[::1]:8080/").port());
    assertEquals("password", HttpUrl.parse("http://user:password@[::1]/").password());
    assertEquals(address, HttpUrl.parse("http://user:password@[::1]:8080/").host());

    // Permit the contents of IPv6 addresses to be percent-encoded...
    assertEquals(address, HttpUrl.parse("http://[%3A%3A%31]/").host());

    // Including the Square braces themselves! (This is what Chrome does.)
    assertEquals(address, HttpUrl.parse("http://%5B%3A%3A1%5D/").host());
  }

  @Test public void hostIpv6AddressDifferentFormats() throws Exception {
    // Multiple representations of the same address; see http://tools.ietf.org/html/rfc5952.
    String a3 = "2001:db8:0:0:1:0:0:1";
    assertEquals(a3, HttpUrl.parse("http://[2001:db8:0:0:1:0:0:1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:0db8:0:0:1:0:0:1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:db8::1:0:0:1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:db8::0:1:0:0:1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:0db8::1:0:0:1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:db8:0:0:1::1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:db8:0000:0:1::1]").host());
    assertEquals(a3, HttpUrl.parse("http://[2001:DB8:0:0:1::1]").host());
  }

  @Test public void hostIpv6AddressLeadingCompression() throws Exception {
    String a1 = "0:0:0:0:0:0:0:1";
    assertEquals(a1, HttpUrl.parse("http://[::0001]").host());
    assertEquals(a1, HttpUrl.parse("http://[0000::0001]").host());
    assertEquals(a1, HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000:0000:0001]").host());
    assertEquals(a1, HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000::0001]").host());
  }

  @Test public void hostIpv6AddressTrailingCompression() throws Exception {
    String a2 = "1:0:0:0:0:0:0:0";
    assertEquals(a2, HttpUrl.parse("http://[0001:0000::]").host());
    assertEquals(a2, HttpUrl.parse("http://[0001::0000]").host());
    assertEquals(a2, HttpUrl.parse("http://[0001::]").host());
    assertEquals(a2, HttpUrl.parse("http://[1::]").host());
  }

  @Ignore // java.net.InetAddress isn't as strict as it should be.
  @Test public void hostIpv6AddressTooManyDigitsInGroup() throws Exception {
    assertEquals(null, HttpUrl.parse("http://[00000:0000:0000:0000:0000:0000:0000:0001]"));
    assertEquals(null, HttpUrl.parse("http://[::00001]"));
  }

  @Test public void hostIpv6AddressMisplacedColons() throws Exception {
    assertEquals(null, HttpUrl.parse("http://[:0000:0000:0000:0000:0000:0000:0000:0001]"));
    assertEquals(null, HttpUrl.parse("http://[:::0000:0000:0000:0000:0000:0000:0000:0001]"));
    assertEquals(null, HttpUrl.parse("http://[:1]"));
    assertEquals(null, HttpUrl.parse("http://[:::1]"));
    assertEquals(null, HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000:0001:]"));
    assertEquals(null, HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000:0000:0001:]"));
    assertEquals(null, HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000:0000:0001::]"));
    assertEquals(null, HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000:0000:0001:::]"));
    assertEquals(null, HttpUrl.parse("http://[1:]"));
    assertEquals(null, HttpUrl.parse("http://[1:::]"));
    assertEquals(null, HttpUrl.parse("http://[1:::1]"));
    assertEquals(null, HttpUrl.parse("http://[00000:0000:0000:0000::0000:0000:0000:0001]"));
  }

  @Test public void hostIpv6AddressTooManyGroups() throws Exception {
    assertEquals(null, HttpUrl.parse("http://[00000:0000:0000:0000:0000:0000:0000:0000:0001]"));
  }

  @Test public void hostIpv6AddressTooMuchCompression() throws Exception {
    assertEquals(null, HttpUrl.parse("http://[0000::0000:0000:0000:0000::0001]"));
    assertEquals(null, HttpUrl.parse("http://[::0000:0000:0000:0000::0001]"));
  }

  @Test public void port() throws Exception {
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("http://host:80/"));
    assertEquals(HttpUrl.parse("http://host:99/"), HttpUrl.parse("http://host:99/"));
    assertEquals(65535, HttpUrl.parse("http://host:65535/").port());
    assertEquals(null, HttpUrl.parse("http://host:0/"));
    assertEquals(null, HttpUrl.parse("http://host:65536/"));
    assertEquals(null, HttpUrl.parse("http://host:-1/"));
    assertEquals(null, HttpUrl.parse("http://host:a/"));
    assertEquals(null, HttpUrl.parse("http://host:%39%39/"));
  }

  @Test public void pathCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.PERCENT, '^', '{', '}', '|')
        .override(Encoding.SKIP, '\\', '?', '#')
        .test(Component.PATH);
  }

  @Test public void queryCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.IDENTITY, '?', '`')
        .override(Encoding.PERCENT, '\'')
        .override(Encoding.SKIP, '#')
        .test(Component.QUERY);
  }

  @Test public void fragmentCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.IDENTITY, ' ', '"', '#', '<', '>', '?', '`')
        .test(Component.FRAGMENT);
    // TODO(jwilson): don't percent-encode non-ASCII characters. (But do encode control characters!)
  }

  @Test public void relativePath() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals(HttpUrl.parse("http://host/a/b/d/e/f"), base.resolve("d/e/f"));
    assertEquals(HttpUrl.parse("http://host/d/e/f"), base.resolve("../../d/e/f"));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve(".."));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../.."));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../../.."));
    assertEquals(HttpUrl.parse("http://host/a/b/"), base.resolve("."));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("././.."));
    assertEquals(HttpUrl.parse("http://host/a/b/c/"), base.resolve("c/d/../e/../"));
    assertEquals(HttpUrl.parse("http://host/a/b/..e/"), base.resolve("..e/"));
    assertEquals(HttpUrl.parse("http://host/a/b/e/f../"), base.resolve("e/f../"));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("%2E."));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve(".%2E"));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("%2E%2E"));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("%2e."));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve(".%2e"));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("%2e%2e"));
    assertEquals(HttpUrl.parse("http://host/a/b/"), base.resolve("%2E"));
    assertEquals(HttpUrl.parse("http://host/a/b/"), base.resolve("%2e"));
  }

  @Test public void pathWithBackslash() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals(HttpUrl.parse("http://host/a/b/d/e/f"), base.resolve("d\\e\\f"));
    assertEquals(HttpUrl.parse("http://host/d/e/f"), base.resolve("../..\\d\\e\\f"));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("..\\.."));
  }

  @Test public void relativePathWithSameScheme() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals(HttpUrl.parse("http://host/a/b/d/e/f"), base.resolve("http:d/e/f"));
    assertEquals(HttpUrl.parse("http://host/d/e/f"), base.resolve("http:../../d/e/f"));
  }

  @Test public void decodeUsername() {
    assertEquals("user", HttpUrl.parse("http://user@host/").decodeUsername());
    assertEquals("\uD83C\uDF69", HttpUrl.parse("http://%F0%9F%8D%A9@host/").decodeUsername());
  }

  @Test public void decodePassword() {
    assertEquals("password", HttpUrl.parse("http://user:password@host/").decodePassword());
    assertEquals(null, HttpUrl.parse("http://user:@host/").decodePassword());
    assertEquals("\uD83C\uDF69", HttpUrl.parse("http://user:%F0%9F%8D%A9@host/").decodePassword());
  }

  @Test public void decodeSlashCharacterInDecodedPathSegment() {
    assertEquals(Arrays.asList("a/b/c"),
        HttpUrl.parse("http://host/a%2Fb%2Fc").decodePathSegments());
  }

  @Test public void decodeEmptyPathSegments() {
    assertEquals(Arrays.asList(""),
        HttpUrl.parse("http://host/").decodePathSegments());
  }

  @Test public void percentDecode() throws Exception {
    assertEquals(Arrays.asList("\u0000"),
        HttpUrl.parse("http://host/%00").decodePathSegments());
    assertEquals(Arrays.asList("a", "\u2603", "c"),
        HttpUrl.parse("http://host/a/%E2%98%83/c").decodePathSegments());
    assertEquals(Arrays.asList("a", "\uD83C\uDF69", "c"),
        HttpUrl.parse("http://host/a/%F0%9F%8D%A9/c").decodePathSegments());
    assertEquals(Arrays.asList("a", "b", "c"),
        HttpUrl.parse("http://host/a/%62/c").decodePathSegments());
    assertEquals(Arrays.asList("a", "z", "c"),
        HttpUrl.parse("http://host/a/%7A/c").decodePathSegments());
    assertEquals(Arrays.asList("a", "z", "c"),
        HttpUrl.parse("http://host/a/%7a/c").decodePathSegments());
  }

  @Test public void malformedPercentEncoding() {
    assertEquals(Arrays.asList("a%f", "b"),
        HttpUrl.parse("http://host/a%f/b").decodePathSegments());
    assertEquals(Arrays.asList("%", "b"),
        HttpUrl.parse("http://host/%/b").decodePathSegments());
    assertEquals(Arrays.asList("%"),
        HttpUrl.parse("http://host/%").decodePathSegments());
  }

  @Test public void malformedUtf8Encoding() {
    // Replace a partial UTF-8 sequence with the Unicode replacement character.
    assertEquals(Arrays.asList("a", "\ufffdx", "c"),
        HttpUrl.parse("http://host/a/%E2%98x/c").decodePathSegments());
  }
}
