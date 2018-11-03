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

import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import okhttp3.UrlComponentEncodingTester.Component;
import okhttp3.UrlComponentEncodingTester.Encoding;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public final class HttpUrlTest {
  @Parameterized.Parameters(name = "Use get = {0}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[] { true },
        new Object[] { false }
    );
  }
  
  @Parameterized.Parameter
  public boolean useGet;
  
  HttpUrl parse(String url) {
    return useGet
        ? HttpUrl.get(url)
        : HttpUrl.parse(url);
  }
  
  @Test public void parseTrimsAsciiWhitespace() throws Exception {
    HttpUrl expected = parse("http://host/");
    assertEquals(expected, parse("http://host/\f\n\t \r")); // Leading.
    assertEquals(expected, parse("\r\n\f \thttp://host/")); // Trailing.
    assertEquals(expected, parse(" http://host/ ")); // Both.
    assertEquals(expected, parse("    http://host/    ")); // Both.
    assertEquals(expected, parse("http://host/").resolve("   "));
    assertEquals(expected, parse("http://host/").resolve("  .  "));
  }

  @Test public void parseHostAsciiNonPrintable() throws Exception {
    String host = "host\u0001";
    assertInvalid("http://" + host + "/", "Invalid URL host: \"host\u0001\"");
    // TODO make exception message escape non-printable characters
  }

  @Test public void parseDoesNotTrimOtherWhitespaceCharacters() throws Exception {
    // Whitespace characters list from Google's Guava team: http://goo.gl/IcR9RD
    assertEquals("/%0B", parse("http://h/\u000b").encodedPath()); // line tabulation
    assertEquals("/%1C", parse("http://h/\u001c").encodedPath()); // information separator 4
    assertEquals("/%1D", parse("http://h/\u001d").encodedPath()); // information separator 3
    assertEquals("/%1E", parse("http://h/\u001e").encodedPath()); // information separator 2
    assertEquals("/%1F", parse("http://h/\u001f").encodedPath()); // information separator 1
    assertEquals("/%C2%85", parse("http://h/\u0085").encodedPath()); // next line
    assertEquals("/%C2%A0", parse("http://h/\u00a0").encodedPath()); // non-breaking space
    assertEquals("/%E1%9A%80", parse("http://h/\u1680").encodedPath()); // ogham space mark
    assertEquals("/%E1%A0%8E", parse("http://h/\u180e").encodedPath()); // mongolian vowel separator
    assertEquals("/%E2%80%80", parse("http://h/\u2000").encodedPath()); // en quad
    assertEquals("/%E2%80%81", parse("http://h/\u2001").encodedPath()); // em quad
    assertEquals("/%E2%80%82", parse("http://h/\u2002").encodedPath()); // en space
    assertEquals("/%E2%80%83", parse("http://h/\u2003").encodedPath()); // em space
    assertEquals("/%E2%80%84", parse("http://h/\u2004").encodedPath()); // three-per-em space
    assertEquals("/%E2%80%85", parse("http://h/\u2005").encodedPath()); // four-per-em space
    assertEquals("/%E2%80%86", parse("http://h/\u2006").encodedPath()); // six-per-em space
    assertEquals("/%E2%80%87", parse("http://h/\u2007").encodedPath()); // figure space
    assertEquals("/%E2%80%88", parse("http://h/\u2008").encodedPath()); // punctuation space
    assertEquals("/%E2%80%89", parse("http://h/\u2009").encodedPath()); // thin space
    assertEquals("/%E2%80%8A", parse("http://h/\u200a").encodedPath()); // hair space
    assertEquals("/%E2%80%8B", parse("http://h/\u200b").encodedPath()); // zero-width space
    assertEquals("/%E2%80%8C", parse("http://h/\u200c").encodedPath()); // zero-width non-joiner
    assertEquals("/%E2%80%8D", parse("http://h/\u200d").encodedPath()); // zero-width joiner
    assertEquals("/%E2%80%8E", parse("http://h/\u200e").encodedPath()); // left-to-right mark
    assertEquals("/%E2%80%8F", parse("http://h/\u200f").encodedPath()); // right-to-left mark
    assertEquals("/%E2%80%A8", parse("http://h/\u2028").encodedPath()); // line separator
    assertEquals("/%E2%80%A9", parse("http://h/\u2029").encodedPath()); // paragraph separator
    assertEquals("/%E2%80%AF", parse("http://h/\u202f").encodedPath()); // narrow non-breaking space
    assertEquals("/%E2%81%9F", parse("http://h/\u205f").encodedPath()); // medium mathematical space
    assertEquals("/%E3%80%80", parse("http://h/\u3000").encodedPath()); // ideographic space
  }

  @Test public void scheme() throws Exception {
    assertEquals(parse("http://host/"), parse("http://host/"));
    assertEquals(parse("http://host/"), parse("Http://host/"));
    assertEquals(parse("http://host/"), parse("http://host/"));
    assertEquals(parse("http://host/"), parse("HTTP://host/"));
    assertEquals(parse("https://host/"), parse("https://host/"));
    assertEquals(parse("https://host/"), parse("HTTPS://host/"));

    assertInvalid("image640://480.png", "Expected URL scheme 'http' or 'https' but was 'image640'");
    assertInvalid("httpp://host/", "Expected URL scheme 'http' or 'https' but was 'httpp'");
    assertInvalid("0ttp://host/", "Expected URL scheme 'http' or 'https' but no colon was found");
    assertInvalid("ht+tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht+tp'");
    assertInvalid("ht.tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht.tp'");
    assertInvalid("ht-tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht-tp'");
    assertInvalid("ht1tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht1tp'");
    assertInvalid("httpss://host/", "Expected URL scheme 'http' or 'https' but was 'httpss'");
  }

  @Test public void parseNoScheme() throws Exception {
    assertInvalid("//host", "Expected URL scheme 'http' or 'https' but no colon was found");
    assertInvalid("/path", "Expected URL scheme 'http' or 'https' but no colon was found");
    assertInvalid("path", "Expected URL scheme 'http' or 'https' but no colon was found");
    assertInvalid("?query", "Expected URL scheme 'http' or 'https' but no colon was found");
    assertInvalid("#fragment", "Expected URL scheme 'http' or 'https' but no colon was found");
  }

  @Test public void newBuilderResolve() throws Exception {
    // Non-exhaustive tests because implementation is the same as resolve.
    HttpUrl base = parse("http://host/a/b");
    assertEquals(parse("https://host2/"), base.newBuilder("https://host2").build());
    assertEquals(parse("http://host2/"), base.newBuilder("//host2").build());
    assertEquals(parse("http://host/path"), base.newBuilder("/path").build());
    assertEquals(parse("http://host/a/path"), base.newBuilder("path").build());
    assertEquals(parse("http://host/a/b?query"), base.newBuilder("?query").build());
    assertEquals(parse("http://host/a/b#fragment"), base.newBuilder("#fragment").build());
    assertEquals(parse("http://host/a/b"), base.newBuilder("").build());
    assertEquals(null, base.newBuilder("ftp://b"));
    assertEquals(null, base.newBuilder("ht+tp://b"));
    assertEquals(null, base.newBuilder("ht-tp://b"));
    assertEquals(null, base.newBuilder("ht.tp://b"));
  }

  @Test public void redactedUrl() {
    HttpUrl baseWithPasswordAndUsername = parse("http://username:password@host/a/b#fragment");
    HttpUrl baseWithUsernameOnly = parse("http://username@host/a/b#fragment");
    HttpUrl baseWithPasswordOnly = parse("http://password@host/a/b#fragment");
    assertEquals("http://host/...", baseWithPasswordAndUsername.redact());
    assertEquals("http://host/...", baseWithUsernameOnly.redact());
    assertEquals("http://host/...", baseWithPasswordOnly.redact());
  }

  @Test public void resolveNoScheme() throws Exception {
    HttpUrl base = parse("http://host/a/b");
    assertEquals(parse("http://host2/"), base.resolve("//host2"));
    assertEquals(parse("http://host/path"), base.resolve("/path"));
    assertEquals(parse("http://host/a/path"), base.resolve("path"));
    assertEquals(parse("http://host/a/b?query"), base.resolve("?query"));
    assertEquals(parse("http://host/a/b#fragment"), base.resolve("#fragment"));
    assertEquals(parse("http://host/a/b"), base.resolve(""));
    assertEquals(parse("http://host/path"), base.resolve("\\path"));
  }

  @Test public void resolveUnsupportedScheme() throws Exception {
    HttpUrl base = parse("http://a/");
    assertEquals(null, base.resolve("ftp://b"));
    assertEquals(null, base.resolve("ht+tp://b"));
    assertEquals(null, base.resolve("ht-tp://b"));
    assertEquals(null, base.resolve("ht.tp://b"));
  }

  @Test public void resolveSchemeLikePath() throws Exception {
    HttpUrl base = parse("http://a/");
    assertEquals(parse("http://a/http//b/"), base.resolve("http//b/"));
    assertEquals(parse("http://a/ht+tp//b/"), base.resolve("ht+tp//b/"));
    assertEquals(parse("http://a/ht-tp//b/"), base.resolve("ht-tp//b/"));
    assertEquals(parse("http://a/ht.tp//b/"), base.resolve("ht.tp//b/"));
  }

  /** https://tools.ietf.org/html/rfc3986#section-5.4.1 */
  @Test public void rfc3886NormalExamples() {
    HttpUrl url = parse("http://a/b/c/d;p?q");
    assertEquals(null, url.resolve("g:h")); // No 'g:' scheme in HttpUrl.
    assertEquals(parse("http://a/b/c/g"), url.resolve("g"));
    assertEquals(parse("http://a/b/c/g"), url.resolve("./g"));
    assertEquals(parse("http://a/b/c/g/"), url.resolve("g/"));
    assertEquals(parse("http://a/g"), url.resolve("/g"));
    assertEquals(parse("http://g"), url.resolve("//g"));
    assertEquals(parse("http://a/b/c/d;p?y"), url.resolve("?y"));
    assertEquals(parse("http://a/b/c/g?y"), url.resolve("g?y"));
    assertEquals(parse("http://a/b/c/d;p?q#s"), url.resolve("#s"));
    assertEquals(parse("http://a/b/c/g#s"), url.resolve("g#s"));
    assertEquals(parse("http://a/b/c/g?y#s"), url.resolve("g?y#s"));
    assertEquals(parse("http://a/b/c/;x"), url.resolve(";x"));
    assertEquals(parse("http://a/b/c/g;x"), url.resolve("g;x"));
    assertEquals(parse("http://a/b/c/g;x?y#s"), url.resolve("g;x?y#s"));
    assertEquals(parse("http://a/b/c/d;p?q"), url.resolve(""));
    assertEquals(parse("http://a/b/c/"), url.resolve("."));
    assertEquals(parse("http://a/b/c/"), url.resolve("./"));
    assertEquals(parse("http://a/b/"), url.resolve(".."));
    assertEquals(parse("http://a/b/"), url.resolve("../"));
    assertEquals(parse("http://a/b/g"), url.resolve("../g"));
    assertEquals(parse("http://a/"), url.resolve("../.."));
    assertEquals(parse("http://a/"), url.resolve("../../"));
    assertEquals(parse("http://a/g"), url.resolve("../../g"));
  }

  /** https://tools.ietf.org/html/rfc3986#section-5.4.2 */
  @Test public void rfc3886AbnormalExamples() {
    HttpUrl url = parse("http://a/b/c/d;p?q");
    assertEquals(parse("http://a/g"), url.resolve("../../../g"));
    assertEquals(parse("http://a/g"), url.resolve("../../../../g"));
    assertEquals(parse("http://a/g"), url.resolve("/./g"));
    assertEquals(parse("http://a/g"), url.resolve("/../g"));
    assertEquals(parse("http://a/b/c/g."), url.resolve("g."));
    assertEquals(parse("http://a/b/c/.g"), url.resolve(".g"));
    assertEquals(parse("http://a/b/c/g.."), url.resolve("g.."));
    assertEquals(parse("http://a/b/c/..g"), url.resolve("..g"));
    assertEquals(parse("http://a/b/g"), url.resolve("./../g"));
    assertEquals(parse("http://a/b/c/g/"), url.resolve("./g/."));
    assertEquals(parse("http://a/b/c/g/h"), url.resolve("g/./h"));
    assertEquals(parse("http://a/b/c/h"), url.resolve("g/../h"));
    assertEquals(parse("http://a/b/c/g;x=1/y"), url.resolve("g;x=1/./y"));
    assertEquals(parse("http://a/b/c/y"), url.resolve("g;x=1/../y"));
    assertEquals(parse("http://a/b/c/g?y/./x"), url.resolve("g?y/./x"));
    assertEquals(parse("http://a/b/c/g?y/../x"), url.resolve("g?y/../x"));
    assertEquals(parse("http://a/b/c/g#s/./x"), url.resolve("g#s/./x"));
    assertEquals(parse("http://a/b/c/g#s/../x"), url.resolve("g#s/../x"));
    assertEquals(parse("http://a/b/c/g"), url.resolve("http:g")); // "http:g" also okay.
  }

  @Test public void parseAuthoritySlashCountDoesntMatter() throws Exception {
    assertEquals(parse("http://host/path"), parse("http:host/path"));
    assertEquals(parse("http://host/path"), parse("http:/host/path"));
    assertEquals(parse("http://host/path"), parse("http:\\host/path"));
    assertEquals(parse("http://host/path"), parse("http://host/path"));
    assertEquals(parse("http://host/path"), parse("http:\\/host/path"));
    assertEquals(parse("http://host/path"), parse("http:/\\host/path"));
    assertEquals(parse("http://host/path"), parse("http:\\\\host/path"));
    assertEquals(parse("http://host/path"), parse("http:///host/path"));
    assertEquals(parse("http://host/path"), parse("http:\\//host/path"));
    assertEquals(parse("http://host/path"), parse("http:/\\/host/path"));
    assertEquals(parse("http://host/path"), parse("http://\\host/path"));
    assertEquals(parse("http://host/path"), parse("http:\\\\/host/path"));
    assertEquals(parse("http://host/path"), parse("http:/\\\\host/path"));
    assertEquals(parse("http://host/path"), parse("http:\\\\\\host/path"));
    assertEquals(parse("http://host/path"), parse("http:////host/path"));
  }

  @Test public void resolveAuthoritySlashCountDoesntMatterWithDifferentScheme() throws Exception {
    HttpUrl base = parse("https://a/b/c");
    assertEquals(parse("http://host/path"), base.resolve("http:host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http://host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:///host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\//host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/\\/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http://\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\\\/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/\\\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\\\\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:////host/path"));
  }

  @Test public void resolveAuthoritySlashCountMattersWithSameScheme() throws Exception {
    HttpUrl base = parse("http://a/b/c");
    assertEquals(parse("http://a/b/host/path"), base.resolve("http:host/path"));
    assertEquals(parse("http://a/host/path"), base.resolve("http:/host/path"));
    assertEquals(parse("http://a/host/path"), base.resolve("http:\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http://host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:///host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\//host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/\\/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http://\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\\\/host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:/\\\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:\\\\\\host/path"));
    assertEquals(parse("http://host/path"), base.resolve("http:////host/path"));
  }

  @Test public void username() throws Exception {
    assertEquals(parse("http://host/path"), parse("http://@host/path"));
    assertEquals(parse("http://user@host/path"), parse("http://user@host/path"));
  }

  /** Given multiple '@' characters, the last one is the delimiter. */
  @Test public void authorityWithMultipleAtSigns() throws Exception {
    HttpUrl httpUrl = parse("http://foo@bar@baz/path");
    assertEquals("foo@bar", httpUrl.username());
    assertEquals("", httpUrl.password());
    assertEquals(parse("http://foo%40bar@baz/path"), httpUrl);
  }

  /** Given multiple ':' characters, the first one is the delimiter. */
  @Test public void authorityWithMultipleColons() throws Exception {
    HttpUrl httpUrl = parse("http://foo:pass1@bar:pass2@baz/path");
    assertEquals("foo", httpUrl.username());
    assertEquals("pass1@bar:pass2", httpUrl.password());
    assertEquals(parse("http://foo:pass1%40bar%3Apass2@baz/path"), httpUrl);
  }

  @Test public void usernameAndPassword() throws Exception {
    assertEquals(parse("http://username:password@host/path"),
        parse("http://username:password@host/path"));
    assertEquals(parse("http://username@host/path"),
        parse("http://username:@host/path"));
  }

  @Test public void passwordWithEmptyUsername() throws Exception {
    // Chrome doesn't mind, but Firefox rejects URLs with empty usernames and non-empty passwords.
    assertEquals(parse("http://host/path"), parse("http://:@host/path"));
    assertEquals("password%40", parse("http://:password@@host/path").encodedPassword());
  }

  @Test public void unprintableCharactersArePercentEncoded() throws Exception {
    assertEquals("/%00", parse("http://host/\u0000").encodedPath());
    assertEquals("/%08", parse("http://host/\u0008").encodedPath());
    assertEquals("/%EF%BF%BD", parse("http://host/\ufffd").encodedPath());
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
    assertInvalid("http://\n/", "Invalid URL host: \"\n\"");
    assertInvalid("http:// /", "Invalid URL host: \" \"");
    assertInvalid("http://%20/", "Invalid URL host: \"%20\"");
  }

  @Test public void hostnameLowercaseCharactersMappedDirectly() throws Exception {
    assertEquals("abcd", parse("http://abcd").host());
    assertEquals("xn--4xa", parse("http://σ").host());
  }

  @Test public void hostnameUppercaseCharactersConvertedToLowercase() throws Exception {
    assertEquals("abcd", parse("http://ABCD").host());
    assertEquals("xn--4xa", parse("http://Σ").host());
  }

  @Test public void hostnameIgnoredCharacters() throws Exception {
    // The soft hyphen (­) should be ignored.
    assertEquals("abcd", parse("http://AB\u00adCD").host());
  }

  @Test public void hostnameMultipleCharacterMapping() throws Exception {
    // Map the single character telephone symbol (℡) to the string "tel".
    assertEquals("tel", parse("http://\u2121").host());
  }

  @Test public void hostnameMappingLastMappedCodePoint() throws Exception {
    assertEquals("xn--pu5l", parse("http://\uD87E\uDE1D").host());
  }

  @Ignore("The java.net.IDN implementation doesn't ignore characters that it should.")
  @Test public void hostnameMappingLastIgnoredCodePoint() throws Exception {
    assertEquals("abcd", parse("http://ab\uDB40\uDDEFcd").host());
  }

  @Test public void hostnameMappingLastDisallowedCodePoint() throws Exception {
    assertInvalid("http://\uDBFF\uDFFF", "Invalid URL host: \"\uDBFF\uDFFF\"");
  }

  @Test public void hostIpv6() throws Exception {
    // Square braces are absent from host()...
    assertEquals("::1", parse("http://[::1]/").host());

    // ... but they're included in toString().
    assertEquals("http://[::1]/", parse("http://[::1]/").toString());

    // IPv6 colons don't interfere with port numbers or passwords.
    assertEquals(8080, parse("http://[::1]:8080/").port());
    assertEquals("password", parse("http://user:password@[::1]/").password());
    assertEquals("::1", parse("http://user:password@[::1]:8080/").host());

    // Permit the contents of IPv6 addresses to be percent-encoded...
    assertEquals("::1", parse("http://[%3A%3A%31]/").host());

    // Including the Square braces themselves! (This is what Chrome does.)
    assertEquals("::1", parse("http://%5B%3A%3A1%5D/").host());
  }

  @Test public void hostIpv6AddressDifferentFormats() throws Exception {
    // Multiple representations of the same address; see http://tools.ietf.org/html/rfc5952.
    String a3 = "2001:db8::1:0:0:1";
    assertEquals(a3, parse("http://[2001:db8:0:0:1:0:0:1]").host());
    assertEquals(a3, parse("http://[2001:0db8:0:0:1:0:0:1]").host());
    assertEquals(a3, parse("http://[2001:db8::1:0:0:1]").host());
    assertEquals(a3, parse("http://[2001:db8::0:1:0:0:1]").host());
    assertEquals(a3, parse("http://[2001:0db8::1:0:0:1]").host());
    assertEquals(a3, parse("http://[2001:db8:0:0:1::1]").host());
    assertEquals(a3, parse("http://[2001:db8:0000:0:1::1]").host());
    assertEquals(a3, parse("http://[2001:DB8:0:0:1::1]").host());
  }

  @Test public void hostIpv6AddressLeadingCompression() throws Exception {
    assertEquals("::1", parse("http://[::0001]").host());
    assertEquals("::1", parse("http://[0000::0001]").host());
    assertEquals("::1", parse("http://[0000:0000:0000:0000:0000:0000:0000:0001]").host());
    assertEquals("::1", parse("http://[0000:0000:0000:0000:0000:0000::0001]").host());
  }

  @Test public void hostIpv6AddressTrailingCompression() throws Exception {
    assertEquals("1::", parse("http://[0001:0000::]").host());
    assertEquals("1::", parse("http://[0001::0000]").host());
    assertEquals("1::", parse("http://[0001::]").host());
    assertEquals("1::", parse("http://[1::]").host());
  }

  @Test public void hostIpv6AddressTooManyDigitsInGroup() throws Exception {
    assertInvalid("http://[00000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[00000:0000:0000:0000:0000:0000:0000:0001]\"");
    assertInvalid("http://[::00001]", "Invalid URL host: \"[::00001]\"");
  }

  @Test public void hostIpv6AddressMisplacedColons() throws Exception {
    assertInvalid("http://[:0000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[:0000:0000:0000:0000:0000:0000:0000:0001]\"");
    assertInvalid("http://[:::0000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[:::0000:0000:0000:0000:0000:0000:0000:0001]\"");
    assertInvalid("http://[:1]", "Invalid URL host: \"[:1]\"");
    assertInvalid("http://[:::1]", "Invalid URL host: \"[:::1]\"");
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0001:]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0001:]\"");
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0001:]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001:]\"");
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0001::]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001::]\"");
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0001:::]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001:::]\"");
    assertInvalid("http://[1:]", "Invalid URL host: \"[1:]\"");
    assertInvalid("http://[1:::]", "Invalid URL host: \"[1:::]\"");
    assertInvalid("http://[1:::1]", "Invalid URL host: \"[1:::1]\"");
    assertInvalid("http://[0000:0000:0000:0000::0000:0000:0000:0001]",
        "Invalid URL host: \"[0000:0000:0000:0000::0000:0000:0000:0001]\"");
  }

  @Test public void hostIpv6AddressTooManyGroups() throws Exception {
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0000:0001]\"");
  }

  @Test public void hostIpv6AddressTooMuchCompression() throws Exception {
    assertInvalid("http://[0000::0000:0000:0000:0000::0001]",
        "Invalid URL host: \"[0000::0000:0000:0000:0000::0001]\"");
    assertInvalid("http://[::0000:0000:0000:0000::0001]",
        "Invalid URL host: \"[::0000:0000:0000:0000::0001]\"");
  }

  @Test public void hostIpv6ScopedAddress() throws Exception {
    // java.net.InetAddress parses scoped addresses. These aren't valid in URLs.
    assertInvalid("http://[::1%2544]", "Invalid URL host: \"[::1%2544]\"");
  }

  @Test public void hostIpv6AddressTooManyLeadingZeros() throws Exception {
    // Guava's been buggy on this case. https://github.com/google/guava/issues/3116
    assertInvalid("http://[2001:db8:0:0:1:0:0:00001]",
        "Invalid URL host: \"[2001:db8:0:0:1:0:0:00001]\"");
  }

  @Test public void hostIpv6WithIpv4Suffix() throws Exception {
    assertEquals("::1:ffff:ffff", parse("http://[::1:255.255.255.255]/").host());
    assertEquals("::1:0:0", parse("http://[0:0:0:0:0:1:0.0.0.0]/").host());
  }

  @Test public void hostIpv6WithIpv4SuffixWithOctalPrefix() throws Exception {
    // Chrome interprets a leading '0' as octal; Firefox rejects them. (We reject them.)
    assertInvalid("http://[0:0:0:0:0:1:0.0.0.000000]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.0.0.000000]\"");
    assertInvalid("http://[0:0:0:0:0:1:0.010.0.010]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.010.0.010]\"");
    assertInvalid("http://[0:0:0:0:0:1:0.0.0.000001]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.0.0.000001]\"");
  }

  @Test public void hostIpv6WithIpv4SuffixWithHexadecimalPrefix() throws Exception {
    // Chrome interprets a leading '0x' as hexadecimal; Firefox rejects them. (We reject them.)
    assertInvalid("http://[0:0:0:0:0:1:0.0x10.0.0x10]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.0x10.0.0x10]\"");
  }

  @Test public void hostIpv6WithMalformedIpv4Suffix() throws Exception {
    assertInvalid("http://[0:0:0:0:0:1:0.0:0.0]/", "Invalid URL host: \"[0:0:0:0:0:1:0.0:0.0]\"");
    assertInvalid("http://[0:0:0:0:0:1:0.0-0.0]/", "Invalid URL host: \"[0:0:0:0:0:1:0.0-0.0]\"");
    assertInvalid("http://[0:0:0:0:0:1:.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:.255.255.255]\"");
    assertInvalid("http://[0:0:0:0:0:1:255..255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255..255.255]\"");
    assertInvalid("http://[0:0:0:0:0:1:255.255..255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255.255..255]\"");
    assertInvalid("http://[0:0:0:0:0:0:1:255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:0:1:255.255]\"");
    assertInvalid("http://[0:0:0:0:0:1:256.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:256.255.255.255]\"");
    assertInvalid("http://[0:0:0:0:0:1:ff.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:ff.255.255.255]\"");
    assertInvalid("http://[0:0:0:0:0:0:1:255.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:0:1:255.255.255.255]\"");
    assertInvalid("http://[0:0:0:0:1:255.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:1:255.255.255.255]\"");
    assertInvalid("http://[0:0:0:0:1:0.0.0.0:1]/", "Invalid URL host: \"[0:0:0:0:1:0.0.0.0:1]\"");
    assertInvalid("http://[0:0.0.0.0:1:0:0:0:0:1]/",
        "Invalid URL host: \"[0:0.0.0.0:1:0:0:0:0:1]\"");
    assertInvalid("http://[0.0.0.0:0:0:0:0:0:1]/", "Invalid URL host: \"[0.0.0.0:0:0:0:0:0:1]\"");
  }

  @Test public void hostIpv6WithIncompleteIpv4Suffix() throws Exception {
    // To Chrome & Safari these are well-formed; Firefox disagrees. (We're consistent with Firefox).
    assertInvalid("http://[0:0:0:0:0:1:255.255.255.]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255.255.255.]\"");
    assertInvalid("http://[0:0:0:0:0:1:255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255.255.255]\"");
  }

  @Test public void hostIpv6CanonicalForm() throws Exception {
    assertEquals("abcd:ef01:2345:6789:abcd:ef01:2345:6789",
        parse("http://[abcd:ef01:2345:6789:abcd:ef01:2345:6789]/").host());
    assertEquals("a::b:0:0:0", parse("http://[a:0:0:0:b:0:0:0]/").host());
    assertEquals("a:b:0:0:c::", parse("http://[a:b:0:0:c:0:0:0]/").host());
    assertEquals("a:b::c:0:0", parse("http://[a:b:0:0:0:c:0:0]/").host());
    assertEquals("a::b:0:0:0", parse("http://[a:0:0:0:b:0:0:0]/").host());
    assertEquals("::a:b:0:0:0", parse("http://[0:0:0:a:b:0:0:0]/").host());
    assertEquals("::a:0:0:0:b", parse("http://[0:0:0:a:0:0:0:b]/").host());
    assertEquals("0:a:b:c:d:e:f:1", parse("http://[0:a:b:c:d:e:f:1]/").host());
    assertEquals("a:b:c:d:e:f:1:0", parse("http://[a:b:c:d:e:f:1:0]/").host());
    assertEquals("ff01::101", parse("http://[FF01:0:0:0:0:0:0:101]/").host());
    assertEquals("2001:db8::1", parse("http://[2001:db8::1]/").host());
    assertEquals("2001:db8::2:1", parse("http://[2001:db8:0:0:0:0:2:1]/").host());
    assertEquals("2001:db8:0:1:1:1:1:1", parse("http://[2001:db8:0:1:1:1:1:1]/").host());
    assertEquals("2001:db8::1:0:0:1", parse("http://[2001:db8:0:0:1:0:0:1]/").host());
    assertEquals("2001:0:0:1::1", parse("http://[2001:0:0:1:0:0:0:1]/").host());
    assertEquals("1::", parse("http://[1:0:0:0:0:0:0:0]/").host());
    assertEquals("::1", parse("http://[0:0:0:0:0:0:0:1]/").host());
    assertEquals("::", parse("http://[0:0:0:0:0:0:0:0]/").host());
  }

  /** The builder permits square braces but does not require them. */
  @Test public void hostIPv6Builder() throws Exception {
    HttpUrl base = parse("http://example.com/");
    assertEquals("http://[::1]/", base.newBuilder().host("[::1]").build().toString());
    assertEquals("http://[::1]/", base.newBuilder().host("[::0001]").build().toString());
    assertEquals("http://[::1]/", base.newBuilder().host("::1").build().toString());
    assertEquals("http://[::1]/", base.newBuilder().host("::0001").build().toString());
  }

  @Test public void hostIpv4CanonicalForm() throws Exception {
    assertEquals("255.255.255.255", parse("http://255.255.255.255/").host());
    assertEquals("1.2.3.4", parse("http://1.2.3.4/").host());
    assertEquals("0.0.0.0", parse("http://0.0.0.0/").host());
  }

  @Ignore("java.net.IDN strips trailing trailing dots on Java 7, but not on Java 8.")
  @Test public void hostWithTrailingDot() throws Exception {
    assertEquals("host.", parse("http://host./").host());
  }

  @Test public void port() throws Exception {
    assertEquals(parse("http://host/"), parse("http://host:80/"));
    assertEquals(parse("http://host:99/"), parse("http://host:99/"));
    assertEquals(parse("http://host/"), parse("http://host:/"));
    assertEquals(65535, parse("http://host:65535/").port());
    assertInvalid("http://host:0/", "Invalid URL port: \"0\"");
    assertInvalid("http://host:65536/", "Invalid URL port: \"65536\"");
    assertInvalid("http://host:-1/", "Invalid URL port: \"-1\"");
    assertInvalid("http://host:a/", "Invalid URL port: \"a\"");
    assertInvalid("http://host:%39%39/", "Invalid URL port: \"%39%39\"");
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

  @Test public void queryValueCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.IDENTITY, '?', '`')
        .override(Encoding.PERCENT, '\'')
        .override(Encoding.SKIP, '#', '+')
        .skipForUri('%', '\\', '^', '`', '{', '|', '}')
        .test(Component.QUERY_VALUE);
  }

  @Test public void fragmentCharacters() throws Exception {
    new UrlComponentEncodingTester()
        .override(Encoding.IDENTITY, ' ', '"', '#', '<', '>', '?', '`')
        .skipForUri('%', ' ', '"', '#', '<', '>', '\\', '^', '`', '{', '|', '}')
        .identityForNonAscii()
        .test(Component.FRAGMENT);
  }

  @Test public void fragmentNonAscii() throws Exception {
    HttpUrl url = parse("http://host/#Σ");
    assertEquals("http://host/#Σ", url.toString());
    assertEquals("Σ", url.fragment());
    assertEquals("Σ", url.encodedFragment());
    assertEquals("http://host/#Σ", url.uri().toString());
  }

  @Test public void fragmentNonAsciiThatOffendsJavaNetUri() throws Exception {
    HttpUrl url = parse("http://host/#\u0080");
    assertEquals("http://host/#\u0080", url.toString());
    assertEquals("\u0080", url.fragment());
    assertEquals("\u0080", url.encodedFragment());
    assertEquals(new URI("http://host/#"), url.uri()); // Control characters may be stripped!
  }

  @Test public void fragmentPercentEncodedNonAscii() throws Exception {
    HttpUrl url = parse("http://host/#%C2%80");
    assertEquals("http://host/#%C2%80", url.toString());
    assertEquals("\u0080", url.fragment());
    assertEquals("%C2%80", url.encodedFragment());
    assertEquals("http://host/#%C2%80", url.uri().toString());
  }

  @Test public void fragmentPercentEncodedPartialCodePoint() throws Exception {
    HttpUrl url = parse("http://host/#%80");
    assertEquals("http://host/#%80", url.toString());
    assertEquals("\ufffd", url.fragment()); // Unicode replacement character.
    assertEquals("%80", url.encodedFragment());
    assertEquals("http://host/#%80", url.uri().toString());
  }

  @Test public void relativePath() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals(parse("http://host/a/b/d/e/f"), base.resolve("d/e/f"));
    assertEquals(parse("http://host/d/e/f"), base.resolve("../../d/e/f"));
    assertEquals(parse("http://host/a/"), base.resolve(".."));
    assertEquals(parse("http://host/"), base.resolve("../.."));
    assertEquals(parse("http://host/"), base.resolve("../../.."));
    assertEquals(parse("http://host/a/b/"), base.resolve("."));
    assertEquals(parse("http://host/a/"), base.resolve("././.."));
    assertEquals(parse("http://host/a/b/c/"), base.resolve("c/d/../e/../"));
    assertEquals(parse("http://host/a/b/..e/"), base.resolve("..e/"));
    assertEquals(parse("http://host/a/b/e/f../"), base.resolve("e/f../"));
    assertEquals(parse("http://host/a/"), base.resolve("%2E."));
    assertEquals(parse("http://host/a/"), base.resolve(".%2E"));
    assertEquals(parse("http://host/a/"), base.resolve("%2E%2E"));
    assertEquals(parse("http://host/a/"), base.resolve("%2e."));
    assertEquals(parse("http://host/a/"), base.resolve(".%2e"));
    assertEquals(parse("http://host/a/"), base.resolve("%2e%2e"));
    assertEquals(parse("http://host/a/b/"), base.resolve("%2E"));
    assertEquals(parse("http://host/a/b/"), base.resolve("%2e"));
  }

  @Test public void relativePathWithTrailingSlash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c/");
    assertEquals(parse("http://host/a/b/"), base.resolve(".."));
    assertEquals(parse("http://host/a/b/"), base.resolve("../"));
    assertEquals(parse("http://host/a/"), base.resolve("../.."));
    assertEquals(parse("http://host/a/"), base.resolve("../../"));
    assertEquals(parse("http://host/"), base.resolve("../../.."));
    assertEquals(parse("http://host/"), base.resolve("../../../"));
    assertEquals(parse("http://host/"), base.resolve("../../../.."));
    assertEquals(parse("http://host/"), base.resolve("../../../../"));
    assertEquals(parse("http://host/a"), base.resolve("../../../../a"));
    assertEquals(parse("http://host/"), base.resolve("../../../../a/.."));
    assertEquals(parse("http://host/a/"), base.resolve("../../../../a/b/.."));
  }

  @Test public void pathWithBackslash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals(parse("http://host/a/b/d/e/f"), base.resolve("d\\e\\f"));
    assertEquals(parse("http://host/d/e/f"), base.resolve("../..\\d\\e\\f"));
    assertEquals(parse("http://host/"), base.resolve("..\\.."));
  }

  @Test public void relativePathWithSameScheme() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals(parse("http://host/a/b/d/e/f"), base.resolve("http:d/e/f"));
    assertEquals(parse("http://host/d/e/f"), base.resolve("http:../../d/e/f"));
  }

  @Test public void decodeUsername() {
    assertEquals("user", parse("http://user@host/").username());
    assertEquals("\uD83C\uDF69", parse("http://%F0%9F%8D%A9@host/").username());
  }

  @Test public void decodePassword() {
    assertEquals("password", parse("http://user:password@host/").password());
    assertEquals("", parse("http://user:@host/").password());
    assertEquals("\uD83C\uDF69", parse("http://user:%F0%9F%8D%A9@host/").password());
  }

  @Test public void decodeSlashCharacterInDecodedPathSegment() {
    assertEquals(Arrays.asList("a/b/c"),
        parse("http://host/a%2Fb%2Fc").pathSegments());
  }

  @Test public void decodeEmptyPathSegments() {
    assertEquals(Arrays.asList(""),
        parse("http://host/").pathSegments());
  }

  @Test public void percentDecode() throws Exception {
    assertEquals(Arrays.asList("\u0000"),
        parse("http://host/%00").pathSegments());
    assertEquals(Arrays.asList("a", "\u2603", "c"),
        parse("http://host/a/%E2%98%83/c").pathSegments());
    assertEquals(Arrays.asList("a", "\uD83C\uDF69", "c"),
        parse("http://host/a/%F0%9F%8D%A9/c").pathSegments());
    assertEquals(Arrays.asList("a", "b", "c"),
        parse("http://host/a/%62/c").pathSegments());
    assertEquals(Arrays.asList("a", "z", "c"),
        parse("http://host/a/%7A/c").pathSegments());
    assertEquals(Arrays.asList("a", "z", "c"),
        parse("http://host/a/%7a/c").pathSegments());
  }

  @Test public void malformedPercentEncoding() {
    assertEquals(Arrays.asList("a%f", "b"),
        parse("http://host/a%f/b").pathSegments());
    assertEquals(Arrays.asList("%", "b"),
        parse("http://host/%/b").pathSegments());
    assertEquals(Arrays.asList("%"),
        parse("http://host/%").pathSegments());
    assertEquals(Arrays.asList("%00"),
        parse("http://github.com/%%30%30").pathSegments());
  }

  @Test public void malformedUtf8Encoding() {
    // Replace a partial UTF-8 sequence with the Unicode replacement character.
    assertEquals(Arrays.asList("a", "\ufffdx", "c"),
        parse("http://host/a/%E2%98x/c").pathSegments());
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

  @Test public void builderToString() {
    assertEquals("https://host.com/path", parse("https://host.com/path").newBuilder().toString());
  }

  @Test public void incompleteBuilderToString() {
    assertEquals("https:///path",
        new HttpUrl.Builder().scheme("https").encodedPath("/path").toString());
    assertEquals("//host.com/path",
        new HttpUrl.Builder().host("host.com").encodedPath("/path").toString());
    assertEquals("//host.com:8080/path",
        new HttpUrl.Builder().host("host.com").encodedPath("/path").port(8080).toString());
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
    assertEquals(443, parse("http://example.com")
        .newBuilder()
        .scheme("https")
        .build().port());

    assertEquals(80, parse("https://example.com")
        .newBuilder()
        .scheme("http")
        .build().port());

    assertEquals(1234, parse("https://example.com:1234")
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
    HttpUrl base = parse("http://host/a/b/c");
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
    assertEquals(1, parse("http://host/").pathSize());
    assertEquals(3, parse("http://host/a/b/c").pathSize());
  }

  @Test public void addPathSegments() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");

    // Add a string with zero slashes: resulting URL gains one slash.
    assertEquals("/a/b/c/", base.newBuilder().addPathSegments("").build().encodedPath());
    assertEquals("/a/b/c/d", base.newBuilder().addPathSegments("d").build().encodedPath());

    // Add a string with one slash: resulting URL gains two slashes.
    assertEquals("/a/b/c//", base.newBuilder().addPathSegments("/").build().encodedPath());
    assertEquals("/a/b/c/d/", base.newBuilder().addPathSegments("d/").build().encodedPath());
    assertEquals("/a/b/c//d", base.newBuilder().addPathSegments("/d").build().encodedPath());

    // Add a string with two slashes: resulting URL gains three slashes.
    assertEquals("/a/b/c///", base.newBuilder().addPathSegments("//").build().encodedPath());
    assertEquals("/a/b/c//d/", base.newBuilder().addPathSegments("/d/").build().encodedPath());
    assertEquals("/a/b/c/d//", base.newBuilder().addPathSegments("d//").build().encodedPath());
    assertEquals("/a/b/c///d", base.newBuilder().addPathSegments("//d").build().encodedPath());
    assertEquals("/a/b/c/d/e/f", base.newBuilder().addPathSegments("d/e/f").build().encodedPath());
  }

  @Test public void addPathSegmentsOntoTrailingSlash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c/");

    // Add a string with zero slashes: resulting URL gains zero slashes.
    assertEquals("/a/b/c/", base.newBuilder().addPathSegments("").build().encodedPath());
    assertEquals("/a/b/c/d", base.newBuilder().addPathSegments("d").build().encodedPath());

    // Add a string with one slash: resulting URL gains one slash.
    assertEquals("/a/b/c//", base.newBuilder().addPathSegments("/").build().encodedPath());
    assertEquals("/a/b/c/d/", base.newBuilder().addPathSegments("d/").build().encodedPath());
    assertEquals("/a/b/c//d", base.newBuilder().addPathSegments("/d").build().encodedPath());

    // Add a string with two slashes: resulting URL gains two slashes.
    assertEquals("/a/b/c///", base.newBuilder().addPathSegments("//").build().encodedPath());
    assertEquals("/a/b/c//d/", base.newBuilder().addPathSegments("/d/").build().encodedPath());
    assertEquals("/a/b/c/d//", base.newBuilder().addPathSegments("d//").build().encodedPath());
    assertEquals("/a/b/c///d", base.newBuilder().addPathSegments("//d").build().encodedPath());
    assertEquals("/a/b/c/d/e/f", base.newBuilder().addPathSegments("d/e/f").build().encodedPath());
  }

  @Test public void addPathSegmentsWithBackslash() throws Exception {
    HttpUrl base = parse("http://host/");
    assertEquals("/d/e", base.newBuilder().addPathSegments("d\\e").build().encodedPath());
    assertEquals("/d/e", base.newBuilder().addEncodedPathSegments("d\\e").build().encodedPath());
  }

  @Test public void addPathSegmentsWithEmptyPaths() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/c//d/e///f",
        base.newBuilder().addPathSegments("/d/e///f").build().encodedPath());
  }

  @Test public void addEncodedPathSegments() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/c/d/e/%20/",
        base.newBuilder().addEncodedPathSegments("d/e/%20/\n").build().encodedPath());
  }

  @Test public void addPathSegmentDotDoesNothing() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/c", base.newBuilder().addPathSegment(".").build().encodedPath());
  }

  @Test public void addPathSegmentEncodes() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/c/%252e",
        base.newBuilder().addPathSegment("%2e").build().encodedPath());
    assertEquals("/a/b/c/%252e%252e",
        base.newBuilder().addPathSegment("%2e%2e").build().encodedPath());
  }

  @Test public void addPathSegmentDotDotPopsDirectory() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/", base.newBuilder().addPathSegment("..").build().encodedPath());
  }

  @Test public void addPathSegmentDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/c/.%0A", base.newBuilder().addPathSegment(".\n").build().encodedPath());
  }

  @Test public void addEncodedPathSegmentDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/c", base.newBuilder().addEncodedPathSegment(".\n").build().encodedPath());
  }

  @Test public void addEncodedPathSegmentDotDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/a/b/", base.newBuilder().addEncodedPathSegment("..\n").build().encodedPath());
  }

  @Test public void setPathSegment() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/d/b/c", base.newBuilder().setPathSegment(0, "d").build().encodedPath());
    assertEquals("/a/d/c", base.newBuilder().setPathSegment(1, "d").build().encodedPath());
    assertEquals("/a/b/d", base.newBuilder().setPathSegment(2, "d").build().encodedPath());
  }

  @Test public void setPathSegmentEncodes() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/%2525/b/c", base.newBuilder().setPathSegment(0, "%25").build().encodedPath());
    assertEquals("/.%0A/b/c", base.newBuilder().setPathSegment(0, ".\n").build().encodedPath());
    assertEquals("/%252e/b/c", base.newBuilder().setPathSegment(0, "%2e").build().encodedPath());
  }

  @Test public void setPathSegmentAcceptsEmpty() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("//b/c", base.newBuilder().setPathSegment(0, "").build().encodedPath());
    assertEquals("/a/b/", base.newBuilder().setPathSegment(2, "").build().encodedPath());
  }

  @Test public void setPathSegmentRejectsDot() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    try {
      base.newBuilder().setPathSegment(0, ".");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setPathSegmentRejectsDotDot() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    try {
      base.newBuilder().setPathSegment(0, "..");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setPathSegmentWithSlash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
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
    HttpUrl base = parse("http://host/a/b/c");
    assertEquals("/%25/b/c",
        base.newBuilder().setEncodedPathSegment(0, "%25").build().encodedPath());
  }

  @Test public void setEncodedPathSegmentRejectsDot() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, ".");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentRejectsDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, ".\n");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentRejectsDotDot() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, "..");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentRejectsDotDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    try {
      base.newBuilder().setEncodedPathSegment(0, "..\n");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setEncodedPathSegmentWithSlash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
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
    HttpUrl base = parse("http://host/a/b/c");
    HttpUrl url = base.newBuilder()
        .removePathSegment(0)
        .build();
    assertEquals("/b/c", url.encodedPath());
  }

  @Test public void removePathSegmentDoesntRemovePath() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
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
    HttpUrl httpUrl = parse("http://username:password@host/path?query#fragment");
    URL javaNetUrl = httpUrl.url();
    assertEquals("http://username:password@host/path?query#fragment", javaNetUrl.toString());
  }

  @Test public void toUri() throws Exception {
    HttpUrl httpUrl = parse("http://username:password@host/path?query#fragment");
    URI uri = httpUrl.uri();
    assertEquals("http://username:password@host/path?query#fragment", uri.toString());
  }

  @Test public void toUriSpecialQueryCharacters() throws Exception {
    HttpUrl httpUrl = parse("http://host/?d=abc!@[]^`{}|\\");
    URI uri = httpUrl.uri();
    assertEquals("http://host/?d=abc!@[]%5E%60%7B%7D%7C%5C", uri.toString());
  }

  @Test public void toUriWithUsernameNoPassword() throws Exception {
    HttpUrl httpUrl = new HttpUrl.Builder()
        .scheme("http")
        .username("user")
        .host("host")
        .build();
    assertEquals("http://user@host/", httpUrl.toString());
    assertEquals("http://user@host/", httpUrl.uri().toString());
  }

  @Test public void toUriUsernameSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .username("=[]:;\"~|?#@^/$%*")
        .build();
    assertEquals("http://%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/", url.toString());
    assertEquals("http://%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/", url.uri().toString());
  }

  @Test public void toUriPasswordSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .username("user")
        .password("=[]:;\"~|?#@^/$%*")
        .build();
    assertEquals("http://user:%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/", url.toString());
    assertEquals("http://user:%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/",
        url.uri().toString());
  }

  @Test public void toUriPathSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addPathSegment("=[]:;\"~|?#@^/$%*")
        .build();
    assertEquals("http://host/=[]:;%22~%7C%3F%23@%5E%2F$%25*", url.toString());
    assertEquals("http://host/=%5B%5D:;%22~%7C%3F%23@%5E%2F$%25*", url.uri().toString());
  }

  @Test public void toUriQueryParameterNameSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("=[]:;\"~|?#@^/$%*", "a")
        .build();
    assertEquals("http://host/?%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*=a",
        url.toString());
    assertEquals("http://host/?%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*=a",
        url.uri().toString());
    assertEquals("a", url.queryParameter("=[]:;\"~|?#@^/$%*"));
  }

  @Test public void toUriQueryParameterValueSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("a", "=[]:;\"~|?#@^/$%*")
        .build();
    assertEquals("http://host/?a=%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*",
        url.toString());
    assertEquals("http://host/?a=%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*",
        url.uri().toString());
    assertEquals("=[]:;\"~|?#@^/$%*", url.queryParameter("a"));
  }

  @Test public void toUriQueryValueSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .query("=[]:;\"~|?#@^/$%*")
        .build();
    assertEquals("http://host/?=[]:;%22~|?%23@^/$%25*", url.toString());
    assertEquals("http://host/?=[]:;%22~%7C?%23@%5E/$%25*", url.uri().toString());
  }

  @Test public void queryCharactersEncodedWhenComposed() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("a", "!$(),/:;?@[]\\^`{|}~")
        .build();
    assertEquals("http://host/?a=%21%24%28%29%2C%2F%3A%3B%3F%40%5B%5D%5C%5E%60%7B%7C%7D%7E",
        url.toString());
    assertEquals("!$(),/:;?@[]\\^`{|}~", url.queryParameter("a"));
  }

  /**
   * When callers use {@code addEncodedQueryParameter()} we only encode what's strictly required.
   * We retain the encoded (or non-encoded) state of the input.
   */
  @Test public void queryCharactersNotReencodedWhenComposedWithAddEncoded() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addEncodedQueryParameter("a", "!$(),/:;?@[]\\^`{|}~")
        .build();
    assertEquals("http://host/?a=!$(),/:;?@[]\\^`{|}~",
        url.toString());
    assertEquals("!$(),/:;?@[]\\^`{|}~", url.queryParameter("a"));
  }

  /**
   * When callers parse a URL with query components that aren't encoded, we shouldn't convert them
   * into a canonical form because doing so could be semantically different.
   */
  @Test public void queryCharactersNotReencodedWhenParsed() throws Exception {
    HttpUrl url = parse("http://host/?a=!$(),/:;?@[]\\^`{|}~");
    assertEquals("http://host/?a=!$(),/:;?@[]\\^`{|}~", url.toString());
    assertEquals("!$(),/:;?@[]\\^`{|}~", url.queryParameter("a"));
  }

  @Test public void toUriFragmentSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .fragment("=[]:;\"~|?#@^/$%*")
        .build();
    assertEquals("http://host/#=[]:;\"~|?#@^/$%25*", url.toString());
    assertEquals("http://host/#=[]:;%22~%7C?%23@%5E/$%25*", url.uri().toString());
  }

  @Test public void toUriWithControlCharacters() throws Exception {
    // Percent-encoded in the path.
    assertEquals(new URI("http://host/a%00b"), parse("http://host/a\u0000b").uri());
    assertEquals(new URI("http://host/a%C2%80b"), parse("http://host/a\u0080b").uri());
    assertEquals(new URI("http://host/a%C2%9Fb"), parse("http://host/a\u009fb").uri());
    // Percent-encoded in the query.
    assertEquals(new URI("http://host/?a%00b"), parse("http://host/?a\u0000b").uri());
    assertEquals(new URI("http://host/?a%C2%80b"), parse("http://host/?a\u0080b").uri());
    assertEquals(new URI("http://host/?a%C2%9Fb"), parse("http://host/?a\u009fb").uri());
    // Stripped from the fragment.
    assertEquals(new URI("http://host/#a%00b"), parse("http://host/#a\u0000b").uri());
    assertEquals(new URI("http://host/#ab"), parse("http://host/#a\u0080b").uri());
    assertEquals(new URI("http://host/#ab"), parse("http://host/#a\u009fb").uri());
  }

  @Test public void toUriWithSpaceCharacters() throws Exception {
    // Percent-encoded in the path.
    assertEquals(new URI("http://host/a%0Bb"), parse("http://host/a\u000bb").uri());
    assertEquals(new URI("http://host/a%20b"), parse("http://host/a b").uri());
    assertEquals(new URI("http://host/a%E2%80%89b"), parse("http://host/a\u2009b").uri());
    assertEquals(new URI("http://host/a%E3%80%80b"), parse("http://host/a\u3000b").uri());
    // Percent-encoded in the query.
    assertEquals(new URI("http://host/?a%0Bb"), parse("http://host/?a\u000bb").uri());
    assertEquals(new URI("http://host/?a%20b"), parse("http://host/?a b").uri());
    assertEquals(new URI("http://host/?a%E2%80%89b"), parse("http://host/?a\u2009b").uri());
    assertEquals(new URI("http://host/?a%E3%80%80b"), parse("http://host/?a\u3000b").uri());
    // Stripped from the fragment.
    assertEquals(new URI("http://host/#a%0Bb"), parse("http://host/#a\u000bb").uri());
    assertEquals(new URI("http://host/#a%20b"), parse("http://host/#a b").uri());
    assertEquals(new URI("http://host/#ab"), parse("http://host/#a\u2009b").uri());
    assertEquals(new URI("http://host/#ab"), parse("http://host/#a\u3000b").uri());
  }

  @Test public void toUriWithNonHexPercentEscape() throws Exception {
    assertEquals(new URI("http://host/%25xx"), parse("http://host/%xx").uri());
  }

  @Test public void toUriWithTruncatedPercentEscape() throws Exception {
    assertEquals(new URI("http://host/%25a"), parse("http://host/%a").uri());
    assertEquals(new URI("http://host/%25"), parse("http://host/%").uri());
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

  @Test public void composeQueryWithComponents() throws Exception {
    HttpUrl base = parse("http://host/");
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
    HttpUrl base = parse("http://host/");
    HttpUrl url = base.newBuilder().addEncodedQueryParameter("a+=& b", "c+=& d").build();
    assertEquals("http://host/?a+%3D%26%20b=c+%3D%26%20d", url.toString());
    assertEquals("c =& d", url.queryParameter("a =& b"));
  }

  @Test public void composeQueryRemoveQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .removeAllQueryParameters("a+=& b")
        .build();
    assertEquals("http://host/", url.toString());
    assertEquals(null, url.queryParameter("a+=& b"));
  }

  @Test public void composeQueryRemoveEncodedQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .removeAllEncodedQueryParameters("a+=& b")
        .build();
    assertEquals("http://host/", url.toString());
    assertEquals(null, url.queryParameter("a =& b"));
  }

  @Test public void composeQuerySetQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .setQueryParameter("a+=& b", "ef")
        .build();
    assertEquals("http://host/?a%2B%3D%26%20b=ef", url.toString());
    assertEquals("ef", url.queryParameter("a+=& b"));
  }

  @Test public void composeQuerySetEncodedQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .setEncodedQueryParameter("a+=& b", "ef")
        .build();
    assertEquals("http://host/?a+%3D%26%20b=ef", url.toString());
    assertEquals("ef", url.queryParameter("a =& b"));
  }

  @Test public void composeQueryMultipleEncodedValuesForParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
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
    HttpUrl url = parse("http://host/").newBuilder()
        .query(null)
        .build();
    assertEquals(0, url.querySize());
  }

  @Test public void emptyQueryIsSingleNameValuePairWithEmptyKey() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query("")
        .build();
    assertEquals(1, url.querySize());
    assertEquals("", url.queryParameterName(0));
    assertEquals(null, url.queryParameterValue(0));
  }

  @Test public void ampersandQueryIsTwoNameValuePairsWithEmptyKeys() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query("&")
        .build();
    assertEquals(2, url.querySize());
    assertEquals("", url.queryParameterName(0));
    assertEquals(null, url.queryParameterValue(0));
    assertEquals("", url.queryParameterName(1));
    assertEquals(null, url.queryParameterValue(1));
  }

  @Test public void removeAllDoesNotRemoveQueryIfNoParametersWereRemoved() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query("")
        .removeAllQueryParameters("a")
        .build();
    assertEquals("http://host/?", url.toString());
  }

  @Test public void queryParametersWithoutValues() throws Exception {
    HttpUrl url = parse("http://host/?foo&bar&baz");
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
    HttpUrl url = parse("http://host/?foo=&bar=&baz=");
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
    HttpUrl url = parse("http://host/?foo[]=1&foo[]=2&foo[]=3");
    assertEquals(3, url.querySize());
    assertEquals(Collections.singleton("foo[]"), url.queryParameterNames());
    assertEquals("1", url.queryParameterValue(0));
    assertEquals("2", url.queryParameterValue(1));
    assertEquals("3", url.queryParameterValue(2));
    assertEquals(Arrays.asList("1", "2", "3"), url.queryParameterValues("foo[]"));
  }

  @Test public void queryParameterLookupWithNonCanonicalEncoding() throws Exception {
    HttpUrl url = parse("http://host/?%6d=m&+=%20");
    assertEquals("m", url.queryParameterName(0));
    assertEquals(" ", url.queryParameterName(1));
    assertEquals("m", url.queryParameter("m"));
    assertEquals(" ", url.queryParameter(" "));
  }

  @Test public void parsedQueryDoesntIncludeFragment() {
    HttpUrl url = parse("http://host/?#fragment");
    assertEquals("fragment", url.fragment());
    assertEquals("", url.query());
    assertEquals("", url.encodedQuery());
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
   * Although HttpUrl prefers percent-encodings in uppercase, it should preserve the exact structure
   * of the original encoding.
   */
  @Test public void rawEncodingRetained() throws Exception {
    String urlString = "http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D#%6d%6D";
    HttpUrl url = parse(urlString);
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

  @Test public void clearFragment() throws Exception {
    HttpUrl url = parse("http://host/#fragment")
        .newBuilder()
        .fragment(null)
        .build();
    assertEquals("http://host/", url.toString());
    assertEquals(null, url.fragment());
    assertEquals(null, url.encodedFragment());
  }

  @Test public void clearEncodedFragment() throws Exception {
    HttpUrl url = parse("http://host/#fragment")
        .newBuilder()
        .encodedFragment(null)
        .build();
    assertEquals("http://host/", url.toString());
    assertEquals(null, url.fragment());
    assertEquals(null, url.encodedFragment());
  }

  @Test public void topPrivateDomain() {
    assertEquals("google.com", parse("https://google.com").topPrivateDomain());
    assertEquals("google.co.uk", parse("https://adwords.google.co.uk").topPrivateDomain());
    assertEquals("xn--ewv.xn--4pvxs.jp", parse("https://栃.栃木.jp").topPrivateDomain());
    assertEquals("xn--ewv.xn--4pvxs.jp",
        parse("https://xn--ewv.xn--4pvxs.jp").topPrivateDomain());

    assertNull(parse("https://co.uk").topPrivateDomain());
    assertNull(parse("https://square").topPrivateDomain());
    assertNull(parse("https://栃木.jp").topPrivateDomain());
    assertNull(parse("https://xn--4pvxs.jp").topPrivateDomain());
    assertNull(parse("https://localhost").topPrivateDomain());
    assertNull(parse("https://127.0.0.1").topPrivateDomain());
  }

  private void assertInvalid(String string, String exceptionMessage) {
    if (useGet) {
      try {
        parse(string);
        fail("Expected get of \"" + string + "\" to throw with: " + exceptionMessage);
      } catch (IllegalArgumentException e) {
        assertEquals(exceptionMessage, e.getMessage());
      }
    } else {
      assertNull(string, parse(string));
    }
  }
}
