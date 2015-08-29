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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import org.junit.Ignore;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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
    assertEquals("/%0B", HttpUrl.parse("http://h/\u000b").encodedPath()); // line tabulation
    assertEquals("/%1C", HttpUrl.parse("http://h/\u001c").encodedPath()); // information separator 4
    assertEquals("/%1D", HttpUrl.parse("http://h/\u001d").encodedPath()); // information separator 3
    assertEquals("/%1E", HttpUrl.parse("http://h/\u001e").encodedPath()); // information separator 2
    assertEquals("/%1F", HttpUrl.parse("http://h/\u001f").encodedPath()); // information separator 1
    assertEquals("/%C2%85", HttpUrl.parse("http://h/\u0085").encodedPath()); // next line
    assertEquals("/%C2%A0", HttpUrl.parse("http://h/\u00a0").encodedPath()); // non-breaking space
    assertEquals("/%E1%9A%80", HttpUrl.parse("http://h/\u1680").encodedPath()); // ogham space mark
    assertEquals("/%E1%A0%8E", HttpUrl.parse("http://h/\u180e").encodedPath()); // mongolian vowel separator
    assertEquals("/%E2%80%80", HttpUrl.parse("http://h/\u2000").encodedPath()); // en quad
    assertEquals("/%E2%80%81", HttpUrl.parse("http://h/\u2001").encodedPath()); // em quad
    assertEquals("/%E2%80%82", HttpUrl.parse("http://h/\u2002").encodedPath()); // en space
    assertEquals("/%E2%80%83", HttpUrl.parse("http://h/\u2003").encodedPath()); // em space
    assertEquals("/%E2%80%84", HttpUrl.parse("http://h/\u2004").encodedPath()); // three-per-em space
    assertEquals("/%E2%80%85", HttpUrl.parse("http://h/\u2005").encodedPath()); // four-per-em space
    assertEquals("/%E2%80%86", HttpUrl.parse("http://h/\u2006").encodedPath()); // six-per-em space
    assertEquals("/%E2%80%87", HttpUrl.parse("http://h/\u2007").encodedPath()); // figure space
    assertEquals("/%E2%80%88", HttpUrl.parse("http://h/\u2008").encodedPath()); // punctuation space
    assertEquals("/%E2%80%89", HttpUrl.parse("http://h/\u2009").encodedPath()); // thin space
    assertEquals("/%E2%80%8A", HttpUrl.parse("http://h/\u200a").encodedPath()); // hair space
    assertEquals("/%E2%80%8B", HttpUrl.parse("http://h/\u200b").encodedPath()); // zero-width space
    assertEquals("/%E2%80%8C", HttpUrl.parse("http://h/\u200c").encodedPath()); // zero-width non-joiner
    assertEquals("/%E2%80%8D", HttpUrl.parse("http://h/\u200d").encodedPath()); // zero-width joiner
    assertEquals("/%E2%80%8E", HttpUrl.parse("http://h/\u200e").encodedPath()); // left-to-right mark
    assertEquals("/%E2%80%8F", HttpUrl.parse("http://h/\u200f").encodedPath()); // right-to-left mark
    assertEquals("/%E2%80%A8", HttpUrl.parse("http://h/\u2028").encodedPath()); // line separator
    assertEquals("/%E2%80%A9", HttpUrl.parse("http://h/\u2029").encodedPath()); // paragraph separator
    assertEquals("/%E2%80%AF", HttpUrl.parse("http://h/\u202f").encodedPath()); // narrow non-breaking space
    assertEquals("/%E2%81%9F", HttpUrl.parse("http://h/\u205f").encodedPath()); // medium mathematical space
    assertEquals("/%E3%80%80", HttpUrl.parse("http://h/\u3000").encodedPath()); // ideographic space
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
    assertEquals("password%40", HttpUrl.parse("http://:password@@host/path").encodedPassword());
  }

  @Test public void unprintableCharactersArePercentEncoded() throws Exception {
    assertEquals("/%00", HttpUrl.parse("http://host/\u0000").encodedPath());
    assertEquals("/%08", HttpUrl.parse("http://host/\u0008").encodedPath());
    assertEquals("/%EF%BF%BD", HttpUrl.parse("http://host/\ufffd").encodedPath());
  }

  @Test public void usernameCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.PERCENT, '[', ']', '{', '}', '|', '^', '\'', ';', '=', '@')
        .override(Encoding.SKIP, ':', '/', '\\', '?', '#')
        .skipForUri('%')
        .test(Component.USER);
  }

  @Test public void passwordCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.PERCENT, '[', ']', '{', '}', '|', '^', '\'', ':', ';', '=', '@')
        .override(Encoding.SKIP, '/', '\\', '?', '#')
        .skipForUri('%')
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

  @Ignore("The java.net.IDN implementation doesn't ignore characters that it should.")
  @Test public void hostnameMappingLastIgnoredCodePoint() throws Exception {
    assertEquals("abcd", HttpUrl.parse("http://ab\uDB40\uDDEFcd").host());
  }

  @Test public void hostnameMappingLastDisallowedCodePoint() throws Exception {
    assertEquals(null, HttpUrl.parse("http://\uDBFF\uDFFF"));
  }

  @Test public void hostIpv6() throws Exception {
    // Square braces are absent from host()...
    assertEquals("::1", HttpUrl.parse("http://[::1]/").host());

    // ... but they're included in toString().
    assertEquals("http://[::1]/", HttpUrl.parse("http://[::1]/").toString());

    // IPv6 colons don't interfere with port numbers or passwords.
    assertEquals(8080, HttpUrl.parse("http://[::1]:8080/").port());
    assertEquals("password", HttpUrl.parse("http://user:password@[::1]/").password());
    assertEquals("::1", HttpUrl.parse("http://user:password@[::1]:8080/").host());

    // Permit the contents of IPv6 addresses to be percent-encoded...
    assertEquals("::1", HttpUrl.parse("http://[%3A%3A%31]/").host());

    // Including the Square braces themselves! (This is what Chrome does.)
    assertEquals("::1", HttpUrl.parse("http://%5B%3A%3A1%5D/").host());
  }

  @Test public void hostIpv6AddressDifferentFormats() throws Exception {
    // Multiple representations of the same address; see http://tools.ietf.org/html/rfc5952.
    String a3 = "2001:db8::1:0:0:1";
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
    assertEquals("::1", HttpUrl.parse("http://[::0001]").host());
    assertEquals("::1", HttpUrl.parse("http://[0000::0001]").host());
    assertEquals("::1", HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000:0000:0001]").host());
    assertEquals("::1", HttpUrl.parse("http://[0000:0000:0000:0000:0000:0000::0001]").host());
  }

  @Test public void hostIpv6AddressTrailingCompression() throws Exception {
    assertEquals("1::", HttpUrl.parse("http://[0001:0000::]").host());
    assertEquals("1::", HttpUrl.parse("http://[0001::0000]").host());
    assertEquals("1::", HttpUrl.parse("http://[0001::]").host());
    assertEquals("1::", HttpUrl.parse("http://[1::]").host());
  }

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

  @Test public void hostIpv6ScopedAddress() throws Exception {
    // java.net.InetAddress parses scoped addresses. These aren't valid in URLs.
    assertEquals(null, HttpUrl.parse("http://[::1%2544]"));
  }

  @Test public void hostIpv6WithIpv4Suffix() throws Exception {
    assertEquals("::1:ffff:ffff", HttpUrl.parse("http://[::1:255.255.255.255]/").host());
    assertEquals("::1:0:0", HttpUrl.parse("http://[0:0:0:0:0:1:0.0.0.0]/").host());
  }

  @Test public void hostIpv6WithIpv4SuffixWithOctalPrefix() throws Exception {
    // Chrome interprets a leading '0' as octal; Firefox rejects them. (We reject them.)
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:0.0.0.000000]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:0.010.0.010]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:0.0.0.000001]/"));
  }

  @Test public void hostIpv6WithIpv4SuffixWithHexadecimalPrefix() throws Exception {
    // Chrome interprets a leading '0x' as hexadecimal; Firefox rejects them. (We reject them.)
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:0.0x10.0.0x10]/"));
  }

  @Test public void hostIpv6WithMalformedIpv4Suffix() throws Exception {
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:0.0:0.0]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:0.0-0.0]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:.255.255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:255..255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:255.255..255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:0:1:255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:256.255.255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:ff.255.255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:0:1:255.255.255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:1:255.255.255.255]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:1:0.0.0.0:1]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0.0.0.0:1:0:0:0:0:1]/"));
    assertEquals(null, HttpUrl.parse("http://[0.0.0.0:0:0:0:0:0:1]/"));
  }

  @Test public void hostIpv6WithIncompleteIpv4Suffix() throws Exception {
    // To Chrome & Safari these are well-formed; Firefox disagrees. (We're consistent with Firefox).
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:255.255.255.]/"));
    assertEquals(null, HttpUrl.parse("http://[0:0:0:0:0:1:255.255.255]/"));
  }

  @Test public void hostIpv6CanonicalForm() throws Exception {
    assertEquals("abcd:ef01:2345:6789:abcd:ef01:2345:6789",
        HttpUrl.parse("http://[abcd:ef01:2345:6789:abcd:ef01:2345:6789]/").host());
    assertEquals("a::b:0:0:0", HttpUrl.parse("http://[a:0:0:0:b:0:0:0]/").host());
    assertEquals("a:b:0:0:c::", HttpUrl.parse("http://[a:b:0:0:c:0:0:0]/").host());
    assertEquals("a:b::c:0:0", HttpUrl.parse("http://[a:b:0:0:0:c:0:0]/").host());
    assertEquals("a::b:0:0:0", HttpUrl.parse("http://[a:0:0:0:b:0:0:0]/").host());
    assertEquals("::a:b:0:0:0", HttpUrl.parse("http://[0:0:0:a:b:0:0:0]/").host());
    assertEquals("::a:0:0:0:b", HttpUrl.parse("http://[0:0:0:a:0:0:0:b]/").host());
    assertEquals("::a:b:c:d:e:f:1", HttpUrl.parse("http://[0:a:b:c:d:e:f:1]/").host());
    assertEquals("a:b:c:d:e:f:1::", HttpUrl.parse("http://[a:b:c:d:e:f:1:0]/").host());
    assertEquals("ff01::101", HttpUrl.parse("http://[FF01:0:0:0:0:0:0:101]/").host());
    assertEquals("1::", HttpUrl.parse("http://[1:0:0:0:0:0:0:0]/").host());
    assertEquals("::1", HttpUrl.parse("http://[0:0:0:0:0:0:0:1]/").host());
    assertEquals("::", HttpUrl.parse("http://[0:0:0:0:0:0:0:0]/").host());
  }

  @Test public void hostIpv4CanonicalForm() throws Exception {
    assertEquals("255.255.255.255", HttpUrl.parse("http://255.255.255.255/").host());
    assertEquals("1.2.3.4", HttpUrl.parse("http://1.2.3.4/").host());
    assertEquals("0.0.0.0", HttpUrl.parse("http://0.0.0.0/").host());
  }

  @Ignore("java.net.IDN strips trailing trailing dots on Java 7, but not on Java 8.")
  @Test public void hostWithTrailingDot() throws Exception {
    assertEquals("host.", HttpUrl.parse("http://host./").host());
  }

  @Test public void port() throws Exception {
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("http://host:80/"));
    assertEquals(HttpUrl.parse("http://host:99/"), HttpUrl.parse("http://host:99/"));
    assertEquals(HttpUrl.parse("http://host/"), HttpUrl.parse("http://host:/"));
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
        .skipForUri('%', '[', ']')
        .test(Component.PATH);
  }

  @Test public void queryCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.IDENTITY, '?', '`')
        .override(Encoding.PERCENT, '\'')
        .override(Encoding.SKIP, '#', '+')
        .skipForUri('%', '\\', '^', '`', '{', '|', '}')
        .test(Component.QUERY);
  }

  @Test public void fragmentCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.IDENTITY, ' ', '"', '#', '<', '>', '?', '`')
        .skipForUri('%', ' ', '"', '#', '<', '>', '\\', '^', '`', '{', '|', '}')
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

  @Test public void relativePathWithTrailingSlash() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c/");
    assertEquals(HttpUrl.parse("http://host/a/b/"), base.resolve(".."));
    assertEquals(HttpUrl.parse("http://host/a/b/"), base.resolve("../"));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("../.."));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("../../"));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../../.."));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../../../"));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../../../.."));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../../../../"));
    assertEquals(HttpUrl.parse("http://host/a"), base.resolve("../../../../a"));
    assertEquals(HttpUrl.parse("http://host/"), base.resolve("../../../../a/.."));
    assertEquals(HttpUrl.parse("http://host/a/"), base.resolve("../../../../a/b/.."));
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
    assertEquals("user", HttpUrl.parse("http://user@host/").username());
    assertEquals("\uD83C\uDF69", HttpUrl.parse("http://%F0%9F%8D%A9@host/").username());
  }

  @Test public void decodePassword() {
    assertEquals("password", HttpUrl.parse("http://user:password@host/").password());
    assertEquals("", HttpUrl.parse("http://user:@host/").password());
    assertEquals("\uD83C\uDF69", HttpUrl.parse("http://user:%F0%9F%8D%A9@host/").password());
  }

  @Test public void decodeSlashCharacterInDecodedPathSegment() {
    assertEquals(Arrays.asList("a/b/c"),
        HttpUrl.parse("http://host/a%2Fb%2Fc").pathSegments());
  }

  @Test public void decodeEmptyPathSegments() {
    assertEquals(Arrays.asList(""),
        HttpUrl.parse("http://host/").pathSegments());
  }

  @Test public void percentDecode() throws Exception {
    assertEquals(Arrays.asList("\u0000"),
        HttpUrl.parse("http://host/%00").pathSegments());
    assertEquals(Arrays.asList("a", "\u2603", "c"),
        HttpUrl.parse("http://host/a/%E2%98%83/c").pathSegments());
    assertEquals(Arrays.asList("a", "\uD83C\uDF69", "c"),
        HttpUrl.parse("http://host/a/%F0%9F%8D%A9/c").pathSegments());
    assertEquals(Arrays.asList("a", "b", "c"),
        HttpUrl.parse("http://host/a/%62/c").pathSegments());
    assertEquals(Arrays.asList("a", "z", "c"),
        HttpUrl.parse("http://host/a/%7A/c").pathSegments());
    assertEquals(Arrays.asList("a", "z", "c"),
        HttpUrl.parse("http://host/a/%7a/c").pathSegments());
  }

  @Test public void malformedPercentEncoding() {
    assertEquals(Arrays.asList("a%f", "b"),
        HttpUrl.parse("http://host/a%f/b").pathSegments());
    assertEquals(Arrays.asList("%", "b"),
        HttpUrl.parse("http://host/%/b").pathSegments());
    assertEquals(Arrays.asList("%"),
        HttpUrl.parse("http://host/%").pathSegments());
  }

  @Test public void malformedUtf8Encoding() {
    // Replace a partial UTF-8 sequence with the Unicode replacement character.
    assertEquals(Arrays.asList("a", "\ufffdx", "c"),
        HttpUrl.parse("http://host/a/%E2%98x/c").pathSegments());
  }

  @Test public void incompleteUrlComposition() throws Exception {
    try {
      new HttpUrl.Builder().scheme("http").build();
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("host == null", expected.getMessage());
    }
    try {
      new HttpUrl.Builder().host("host").build();
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("scheme == null", expected.getMessage());
    }
  }

  @Test public void minimalUrlComposition() throws Exception {
    HttpUrl url = new HttpUrl.Builder().scheme("http").host("host").build();
    assertEquals("http://host/", url.toString());
    assertEquals("http", url.scheme());
    assertEquals("", url.username());
    assertEquals("", url.password());
    assertEquals("host", url.host());
    assertEquals(80, url.port());
    assertEquals("/", url.encodedPath());
    assertEquals(null, url.query());
    assertEquals(null, url.fragment());
  }

  @Test public void fullUrlComposition() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .username("username")
        .password("password")
        .host("host")
        .port(8080)
        .addPathSegment("path")
        .query("query")
        .fragment("fragment")
        .build();
    assertEquals("http://username:password@host:8080/path?query#fragment", url.toString());
    assertEquals("http", url.scheme());
    assertEquals("username", url.username());
    assertEquals("password", url.password());
    assertEquals("host", url.host());
    assertEquals(8080, url.port());
    assertEquals("/path", url.encodedPath());
    assertEquals("query", url.query());
    assertEquals("fragment", url.fragment());
  }

  @Test public void changingSchemeChangesDefaultPort() throws Exception {
    assertEquals(443, HttpUrl.parse("http://example.com")
        .newBuilder()
        .scheme("https")
        .build().port());

    assertEquals(80, HttpUrl.parse("https://example.com")
        .newBuilder()
        .scheme("http")
        .build().port());

    assertEquals(1234, HttpUrl.parse("https://example.com:1234")
        .newBuilder()
        .scheme("http")
        .build().port());
  }

  @Test public void composeEncodesWhitespace() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .username("a\r\n\f\t b")
        .password("c\r\n\f\t d")
        .host("host")
        .addPathSegment("e\r\n\f\t f")
        .query("g\r\n\f\t h")
        .fragment("i\r\n\f\t j")
        .build();
    assertEquals("http://a%0D%0A%0C%09%20b:c%0D%0A%0C%09%20d@host"
        + "/e%0D%0A%0C%09%20f?g%0D%0A%0C%09%20h#i%0D%0A%0C%09 j", url.toString());
    assertEquals("a\r\n\f\t b", url.username());
    assertEquals("c\r\n\f\t d", url.password());
    assertEquals("e\r\n\f\t f", url.pathSegments().get(0));
    assertEquals("g\r\n\f\t h", url.query());
    assertEquals("i\r\n\f\t j", url.fragment());
  }

  @Test public void composeFromUnencodedComponents() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .username("a:\u0001@/\\?#%b")
        .password("c:\u0001@/\\?#%d")
        .host("ef")
        .port(8080)
        .addPathSegment("g:\u0001@/\\?#%h")
        .query("i:\u0001@/\\?#%j")
        .fragment("k:\u0001@/\\?#%l")
        .build();
    assertEquals("http://a%3A%01%40%2F%5C%3F%23%25b:c%3A%01%40%2F%5C%3F%23%25d@ef:8080/"
        + "g:%01@%2F%5C%3F%23%25h?i:%01@/\\?%23%25j#k:%01@/\\?#%25l", url.toString());
    assertEquals("http", url.scheme());
    assertEquals("a:\u0001@/\\?#%b", url.username());
    assertEquals("c:\u0001@/\\?#%d", url.password());
    assertEquals(Arrays.asList("g:\u0001@/\\?#%h"), url.pathSegments());
    assertEquals("i:\u0001@/\\?#%j", url.query());
    assertEquals("k:\u0001@/\\?#%l", url.fragment());
    assertEquals("a%3A%01%40%2F%5C%3F%23%25b", url.encodedUsername());
    assertEquals("c%3A%01%40%2F%5C%3F%23%25d", url.encodedPassword());
    assertEquals("/g:%01@%2F%5C%3F%23%25h", url.encodedPath());
    assertEquals("i:%01@/\\?%23%25j", url.encodedQuery());
    assertEquals("k:%01@/\\?#%25l", url.encodedFragment());
  }

  @Test public void composeFromEncodedComponents() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .encodedUsername("a:\u0001@/\\?#%25b")
        .encodedPassword("c:\u0001@/\\?#%25d")
        .host("ef")
        .port(8080)
        .addEncodedPathSegment("g:\u0001@/\\?#%25h")
        .encodedQuery("i:\u0001@/\\?#%25j")
        .encodedFragment("k:\u0001@/\\?#%25l")
        .build();
    assertEquals("http://a%3A%01%40%2F%5C%3F%23%25b:c%3A%01%40%2F%5C%3F%23%25d@ef:8080/"
        + "g:%01@%2F%5C%3F%23%25h?i:%01@/\\?%23%25j#k:%01@/\\?#%25l", url.toString());
    assertEquals("http", url.scheme());
    assertEquals("a:\u0001@/\\?#%b", url.username());
    assertEquals("c:\u0001@/\\?#%d", url.password());
    assertEquals(Arrays.asList("g:\u0001@/\\?#%h"), url.pathSegments());
    assertEquals("i:\u0001@/\\?#%j", url.query());
    assertEquals("k:\u0001@/\\?#%l", url.fragment());
    assertEquals("a%3A%01%40%2F%5C%3F%23%25b", url.encodedUsername());
    assertEquals("c%3A%01%40%2F%5C%3F%23%25d", url.encodedPassword());
    assertEquals("/g:%01@%2F%5C%3F%23%25h", url.encodedPath());
    assertEquals("i:%01@/\\?%23%25j", url.encodedQuery());
    assertEquals("k:%01@/\\?#%25l", url.encodedFragment());
  }

  @Test public void composeWithEncodedPath() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .encodedPath("/a%2Fb/c")
        .build();
    assertEquals("http://host/a%2Fb/c", url.toString());
    assertEquals("/a%2Fb/c", url.encodedPath());
    assertEquals(Arrays.asList("a/b", "c"), url.pathSegments());
  }

  @Test public void composeMixingPathSegments() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .encodedPath("/a%2fb/c")
        .addPathSegment("d%25e")
        .addEncodedPathSegment("f%25g")
        .build();
    assertEquals("http://host/a%2fb/c/d%2525e/f%25g", url.toString());
    assertEquals("/a%2fb/c/d%2525e/f%25g", url.encodedPath());
    assertEquals(Arrays.asList("a%2fb", "c", "d%2525e", "f%25g"), url.encodedPathSegments());
    assertEquals(Arrays.asList("a/b", "c", "d%25e", "f%g"), url.pathSegments());
  }

  @Test public void composeWithAddSegment() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/c/", base.newBuilder().addPathSegment("").build().encodedPath());
    assertEquals("/a/b/c/d",
        base.newBuilder().addPathSegment("").addPathSegment("d").build().encodedPath());
    assertEquals("/a/b/", base.newBuilder().addPathSegment("..").build().encodedPath());
    assertEquals("/a/b/", base.newBuilder().addPathSegment("").addPathSegment("..").build()
        .encodedPath());
    assertEquals("/a/b/c/", base.newBuilder().addPathSegment("").addPathSegment("").build()
        .encodedPath());
  }

  @Test public void pathSize() throws Exception {
    assertEquals(1, HttpUrl.parse("http://host/").pathSize());
    assertEquals(3, HttpUrl.parse("http://host/a/b/c").pathSize());
  }

  @Test public void addPathSegmentDotDoesNothing() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/c", base.newBuilder().addPathSegment(".").build().encodedPath());
  }

  @Test public void addPathSegmentEncodes() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/c/%252e",
        base.newBuilder().addPathSegment("%2e").build().encodedPath());
    assertEquals("/a/b/c/%252e%252e",
        base.newBuilder().addPathSegment("%2e%2e").build().encodedPath());
  }

  @Test public void addPathSegmentDotDotPopsDirectory() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/", base.newBuilder().addPathSegment("..").build().encodedPath());
  }

  @Test public void addPathSegmentDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/c/.%0A", base.newBuilder().addPathSegment(".\n").build().encodedPath());
  }

  @Test public void addEncodedPathSegmentDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/c", base.newBuilder().addEncodedPathSegment(".\n").build().encodedPath());
  }

  @Test public void addEncodedPathSegmentDotDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/a/b/", base.newBuilder().addEncodedPathSegment("..\n").build().encodedPath());
  }

  @Test public void setPathSegment() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/d/b/c", base.newBuilder().setPathSegment(0, "d").build().encodedPath());
    assertEquals("/a/d/c", base.newBuilder().setPathSegment(1, "d").build().encodedPath());
    assertEquals("/a/b/d", base.newBuilder().setPathSegment(2, "d").build().encodedPath());
  }

  @Test public void setPathSegmentEncodes() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/%2525/b/c", base.newBuilder().setPathSegment(0, "%25").build().encodedPath());
    assertEquals("/.%0A/b/c", base.newBuilder().setPathSegment(0, ".\n").build().encodedPath());
    assertEquals("/%252e/b/c", base.newBuilder().setPathSegment(0, "%2e").build().encodedPath());
  }

  @Test public void setPathSegmentAcceptsEmpty() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("//b/c", base.newBuilder().setPathSegment(0, "").build().encodedPath());
    assertEquals("/a/b/", base.newBuilder().setPathSegment(2, "").build().encodedPath());
  }

  @Test public void setPathSegmentRejectsDot() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    try {
      base.newBuilder().setPathSegment(0, ".");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setPathSegmentRejectsDotDot() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    try {
      base.newBuilder().setPathSegment(0, "..");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setPathSegmentWithSlash() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    HttpUrl url = base.newBuilder().setPathSegment(1, "/").build();
    assertEquals("/a/%2F/c", url.encodedPath());
  }

  @Test public void setPathSegmentOutOfBounds() throws Exception {
    try {
      new HttpUrl.Builder().setPathSegment(1, "a");
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void setEncodedPathSegmentEncodes() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    assertEquals("/%25/b/c",
        base.newBuilder().setEncodedPathSegment(0, "%25").build().encodedPath());
  }

  @Test public void setEncodedPathSegmentRejectsDot() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, ".");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentRejectsDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, ".\n");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentRejectsDotDot() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, "..");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentRejectsDotDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, "..\n");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentWithSlash() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    HttpUrl url = base.newBuilder().setEncodedPathSegment(1, "/").build();
    assertEquals("/a/%2F/c", url.encodedPath());
  }

  @Test public void setEncodedPathSegmentOutOfBounds() throws Exception {
    try {
      new HttpUrl.Builder().setEncodedPathSegment(1, "a");
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void removePathSegment() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    HttpUrl url = base.newBuilder()
        .removePathSegment(0)
        .build();
    assertEquals("/b/c", url.encodedPath());
  }

  @Test public void removePathSegmentDoesntRemovePath() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/a/b/c");
    HttpUrl url = base.newBuilder()
        .removePathSegment(0)
        .removePathSegment(0)
        .removePathSegment(0)
        .build();
    assertEquals(Arrays.asList(""), url.pathSegments());
    assertEquals("/", url.encodedPath());
  }

  @Test public void removePathSegmentOutOfBounds() throws Exception {
    try {
      new HttpUrl.Builder().removePathSegment(1);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void toJavaNetUrl() throws Exception {
    HttpUrl httpUrl = HttpUrl.parse("http://username:password@host/path?query#fragment");
    URL javaNetUrl = httpUrl.url();
    assertEquals("http://username:password@host/path?query#fragment", javaNetUrl.toString());
  }

  @Test public void toUri() throws Exception {
    HttpUrl httpUrl = HttpUrl.parse("http://username:password@host/path?query#fragment");
    URI uri = httpUrl.uri();
    assertEquals("http://username:password@host/path?query#fragment", uri.toString());
  }

  @Test public void toUriForbiddenCharacter() throws Exception {
    HttpUrl httpUrl = HttpUrl.parse("http://host/a[b");
    try {
      httpUrl.uri();
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("not valid as a java.net.URI: http://host/a[b", expected.getMessage());
    }
  }

  @Test public void fromJavaNetUrl() throws Exception {
    URL javaNetUrl = new URL("http://username:password@host/path?query#fragment");
    HttpUrl httpUrl = HttpUrl.get(javaNetUrl);
    assertEquals("http://username:password@host/path?query#fragment", httpUrl.toString());
  }

  @Test public void fromJavaNetUrlUnsupportedScheme() throws Exception {
    URL javaNetUrl = new URL("mailto:user@example.com");
    assertEquals(null, HttpUrl.get(javaNetUrl));
  }

  @Test public void fromUri() throws Exception {
    URI uri = new URI("http://username:password@host/path?query#fragment");
    HttpUrl httpUrl = HttpUrl.get(uri);
    assertEquals("http://username:password@host/path?query#fragment", httpUrl.toString());
  }

  @Test public void fromUriUnsupportedScheme() throws Exception {
    URI uri = new URI("mailto:user@example.com");
    assertEquals(null, HttpUrl.get(uri));
  }

  @Test public void fromUriPartial() throws Exception {
    URI uri = new URI("/path");
    assertEquals(null, HttpUrl.get(uri));
  }

  @Test public void fromJavaNetUrl_checked() throws Exception {
    HttpUrl httpUrl = HttpUrl.getChecked("http://username:password@host/path?query#fragment");
    assertEquals("http://username:password@host/path?query#fragment", httpUrl.toString());
  }

  @Test public void fromJavaNetUrlUnsupportedScheme_checked() throws Exception {
    try {
      HttpUrl.getChecked("mailto:user@example.com");
      fail();
    } catch (MalformedURLException e) {
    }
  }

  @Test public void fromJavaNetUrlBadHost_checked() throws Exception {
    try {
      HttpUrl.getChecked("http://hostw ithspace/");
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void composeQueryWithComponents() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/");
    HttpUrl url = base.newBuilder().addQueryParameter("a+=& b", "c+=& d").build();
    assertEquals("http://host/?a%2B%3D%26%20b=c%2B%3D%26%20d", url.toString());
    assertEquals("c+=& d", url.queryParameterValue(0));
    assertEquals("a+=& b", url.queryParameterName(0));
    assertEquals("c+=& d", url.queryParameter("a+=& b"));
    assertEquals(Collections.singleton("a+=& b"), url.queryParameterNames());
    assertEquals(singletonList("c+=& d"), url.queryParameterValues("a+=& b"));
    assertEquals(1, url.querySize());
    assertEquals("a+=& b=c+=& d", url.query()); // Ambiguous! (Though working as designed.)
    assertEquals("a%2B%3D%26%20b=c%2B%3D%26%20d", url.encodedQuery());
  }

  @Test public void composeQueryWithEncodedComponents() throws Exception {
    HttpUrl base = HttpUrl.parse("http://host/");
    HttpUrl url = base.newBuilder().addEncodedQueryParameter("a+=& b", "c+=& d").build();
    assertEquals("http://host/?a%20%3D%26%20b=c%20%3D%26%20d", url.toString());
    assertEquals("c =& d", url.queryParameter("a =& b"));
  }

  @Test public void composeQueryRemoveQueryParameter() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .removeAllQueryParameters("a+=& b")
        .build();
    assertEquals("http://host/", url.toString());
    assertEquals(null, url.queryParameter("a+=& b"));
  }

  @Test public void composeQueryRemoveEncodedQueryParameter() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .removeAllEncodedQueryParameters("a+=& b")
        .build();
    assertEquals("http://host/", url.toString());
    assertEquals(null, url.queryParameter("a =& b"));
  }

  @Test public void composeQuerySetQueryParameter() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .setQueryParameter("a+=& b", "ef")
        .build();
    assertEquals("http://host/?a%2B%3D%26%20b=ef", url.toString());
    assertEquals("ef", url.queryParameter("a+=& b"));
  }

  @Test public void composeQuerySetEncodedQueryParameter() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .setEncodedQueryParameter("a+=& b", "ef")
        .build();
    assertEquals("http://host/?a%20%3D%26%20b=ef", url.toString());
    assertEquals("ef", url.queryParameter("a =& b"));
  }

  @Test public void composeQueryMultipleEncodedValuesForParameter() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .addQueryParameter("a+=& b", "e+=& f")
        .build();
    assertEquals("http://host/?a%2B%3D%26%20b=c%2B%3D%26%20d&a%2B%3D%26%20b=e%2B%3D%26%20f",
        url.toString());
    assertEquals(2, url.querySize());
    assertEquals(Collections.singleton("a+=& b"), url.queryParameterNames());
    assertEquals(Arrays.asList("c+=& d", "e+=& f"), url.queryParameterValues("a+=& b"));
  }

  @Test public void absentQueryIsZeroNameValuePairs() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .query(null)
        .build();
    assertEquals(0, url.querySize());
  }

  @Test public void emptyQueryIsSingleNameValuePairWithEmptyKey() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .query("")
        .build();
    assertEquals(1, url.querySize());
    assertEquals("", url.queryParameterName(0));
    assertEquals(null, url.queryParameterValue(0));
  }

  @Test public void ampersandQueryIsTwoNameValuePairsWithEmptyKeys() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .query("&")
        .build();
    assertEquals(2, url.querySize());
    assertEquals("", url.queryParameterName(0));
    assertEquals(null, url.queryParameterValue(0));
    assertEquals("", url.queryParameterName(1));
    assertEquals(null, url.queryParameterValue(1));
  }

  @Test public void removeAllDoesNotRemoveQueryIfNoParametersWereRemoved() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/").newBuilder()
        .query("")
        .removeAllQueryParameters("a")
        .build();
    assertEquals("http://host/?", url.toString());
  }

  @Test public void queryParametersWithoutValues() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/?foo&bar&baz");
    assertEquals(3, url.querySize());
    assertEquals(new LinkedHashSet<>(Arrays.asList("foo", "bar", "baz")),
        url.queryParameterNames());
    assertEquals(null, url.queryParameterValue(0));
    assertEquals(null, url.queryParameterValue(1));
    assertEquals(null, url.queryParameterValue(2));
    assertEquals(singletonList((String) null), url.queryParameterValues("foo"));
    assertEquals(singletonList((String) null), url.queryParameterValues("bar"));
    assertEquals(singletonList((String) null), url.queryParameterValues("baz"));
  }

  @Test public void queryParametersWithEmptyValues() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/?foo=&bar=&baz=");
    assertEquals(3, url.querySize());
    assertEquals(new LinkedHashSet<>(Arrays.asList("foo", "bar", "baz")),
        url.queryParameterNames());
    assertEquals("", url.queryParameterValue(0));
    assertEquals("", url.queryParameterValue(1));
    assertEquals("", url.queryParameterValue(2));
    assertEquals(singletonList(""), url.queryParameterValues("foo"));
    assertEquals(singletonList(""), url.queryParameterValues("bar"));
    assertEquals(singletonList(""), url.queryParameterValues("baz"));
  }

  @Test public void queryParametersWithRepeatedName() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/?foo[]=1&foo[]=2&foo[]=3");
    assertEquals(3, url.querySize());
    assertEquals(Collections.singleton("foo[]"), url.queryParameterNames());
    assertEquals("1", url.queryParameterValue(0));
    assertEquals("2", url.queryParameterValue(1));
    assertEquals("3", url.queryParameterValue(2));
    assertEquals(Arrays.asList("1", "2", "3"), url.queryParameterValues("foo[]"));
  }

  @Test public void queryParameterLookupWithNonCanonicalEncoding() throws Exception {
    HttpUrl url = HttpUrl.parse("http://host/?%6d=m&+=%20");
    assertEquals("m", url.queryParameterName(0));
    assertEquals(" ", url.queryParameterName(1));
    assertEquals("m", url.queryParameter("m"));
    assertEquals(" ", url.queryParameter(" "));
  }

  @Test public void roundTripBuilder() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .username("%")
        .password("%")
        .host("host")
        .addPathSegment("%")
        .query("%")
        .fragment("%")
        .build();
    assertEquals("http://%25:%25@host/%25?%25#%25", url.toString());
    assertEquals("http://%25:%25@host/%25?%25#%25", url.newBuilder().build().toString());
    assertEquals("http://%25:%25@host/%25?%25", url.resolve("").toString());
  }

  /**
   * Although HttpUrl prefers percent-encodings in uppercase, it should preserve the exact
   * structure of the original encoding.
   */
  @Test public void rawEncodingRetained() throws Exception {
    String urlString = "http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D#%6d%6D";
    HttpUrl url = HttpUrl.parse(urlString);
    assertEquals("%6d%6D", url.encodedUsername());
    assertEquals("%6d%6D", url.encodedPassword());
    assertEquals("/%6d%6D", url.encodedPath());
    assertEquals(Arrays.asList("%6d%6D"), url.encodedPathSegments());
    assertEquals("%6d%6D", url.encodedQuery());
    assertEquals("%6d%6D", url.encodedFragment());
    assertEquals(urlString, url.toString());
    assertEquals(urlString, url.newBuilder().build().toString());
    assertEquals("http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D", url.resolve("").toString());
  }
}
