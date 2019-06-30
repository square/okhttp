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
import java.util.Collection;
import java.util.Collections;
import okhttp3.UrlComponentEncodingTester.Component;
import okhttp3.UrlComponentEncodingTester.Encoding;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public final class HttpUrlTest {
  @Parameterized.Parameters(name = "Use get = {0}")
  public static Collection<Object[]> parameters() {
    return asList(
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
    // Leading.
    assertThat(parse("http://host/\f\n\t \r")).isEqualTo(expected);
    // Trailing.
    assertThat(parse("\r\n\f \thttp://host/")).isEqualTo(expected);
    // Both.
    assertThat(parse(" http://host/ ")).isEqualTo(expected);
    // Both.
    assertThat(parse("    http://host/    ")).isEqualTo(expected);
    assertThat(parse("http://host/").resolve("   ")).isEqualTo(expected);
    assertThat(parse("http://host/").resolve("  .  ")).isEqualTo(expected);
  }

  @Test public void parseHostAsciiNonPrintable() throws Exception {
    String host = "host\u0001";
    assertInvalid("http://" + host + "/", "Invalid URL host: \"host\u0001\"");
    // TODO make exception message escape non-printable characters
  }

  @Test public void parseDoesNotTrimOtherWhitespaceCharacters() throws Exception {
    // Whitespace characters list from Google's Guava team: http://goo.gl/IcR9RD
    // line tabulation
    assertThat(parse("http://h/\u000b").encodedPath()).isEqualTo("/%0B");
    // information separator 4
    assertThat(parse("http://h/\u001c").encodedPath()).isEqualTo("/%1C");
    // information separator 3
    assertThat(parse("http://h/\u001d").encodedPath()).isEqualTo("/%1D");
    // information separator 2
    assertThat(parse("http://h/\u001e").encodedPath()).isEqualTo("/%1E");
    // information separator 1
    assertThat(parse("http://h/\u001f").encodedPath()).isEqualTo("/%1F");
    // next line
    assertThat(parse("http://h/\u0085").encodedPath()).isEqualTo("/%C2%85");
    // non-breaking space
    assertThat(parse("http://h/\u00a0").encodedPath()).isEqualTo("/%C2%A0");
    // ogham space mark
    assertThat(parse("http://h/\u1680").encodedPath()).isEqualTo("/%E1%9A%80");
    // mongolian vowel separator
    assertThat(parse("http://h/\u180e").encodedPath()).isEqualTo("/%E1%A0%8E");
    // en quad
    assertThat(parse("http://h/\u2000").encodedPath()).isEqualTo("/%E2%80%80");
    // em quad
    assertThat(parse("http://h/\u2001").encodedPath()).isEqualTo("/%E2%80%81");
    // en space
    assertThat(parse("http://h/\u2002").encodedPath()).isEqualTo("/%E2%80%82");
    // em space
    assertThat(parse("http://h/\u2003").encodedPath()).isEqualTo("/%E2%80%83");
    // three-per-em space
    assertThat(parse("http://h/\u2004").encodedPath()).isEqualTo("/%E2%80%84");
    // four-per-em space
    assertThat(parse("http://h/\u2005").encodedPath()).isEqualTo("/%E2%80%85");
    // six-per-em space
    assertThat(parse("http://h/\u2006").encodedPath()).isEqualTo("/%E2%80%86");
    // figure space
    assertThat(parse("http://h/\u2007").encodedPath()).isEqualTo("/%E2%80%87");
    // punctuation space
    assertThat(parse("http://h/\u2008").encodedPath()).isEqualTo("/%E2%80%88");
    // thin space
    assertThat(parse("http://h/\u2009").encodedPath()).isEqualTo("/%E2%80%89");
    // hair space
    assertThat(parse("http://h/\u200a").encodedPath()).isEqualTo("/%E2%80%8A");
    // zero-width space
    assertThat(parse("http://h/\u200b").encodedPath()).isEqualTo("/%E2%80%8B");
    // zero-width non-joiner
    assertThat(parse("http://h/\u200c").encodedPath()).isEqualTo("/%E2%80%8C");
    // zero-width joiner
    assertThat(parse("http://h/\u200d").encodedPath()).isEqualTo("/%E2%80%8D");
    // left-to-right mark
    assertThat(parse("http://h/\u200e").encodedPath()).isEqualTo("/%E2%80%8E");
    // right-to-left mark
    assertThat(parse("http://h/\u200f").encodedPath()).isEqualTo("/%E2%80%8F");
    // line separator
    assertThat(parse("http://h/\u2028").encodedPath()).isEqualTo("/%E2%80%A8");
    // paragraph separator
    assertThat(parse("http://h/\u2029").encodedPath()).isEqualTo("/%E2%80%A9");
    // narrow non-breaking space
    assertThat(parse("http://h/\u202f").encodedPath()).isEqualTo("/%E2%80%AF");
    // medium mathematical space
    assertThat(parse("http://h/\u205f").encodedPath()).isEqualTo("/%E2%81%9F");
    // ideographic space
    assertThat(parse("http://h/\u3000").encodedPath()).isEqualTo("/%E3%80%80");
  }

  @Test public void scheme() throws Exception {
    assertThat(parse("http://host/")).isEqualTo(parse("http://host/"));
    assertThat(parse("Http://host/")).isEqualTo(parse("http://host/"));
    assertThat(parse("http://host/")).isEqualTo(parse("http://host/"));
    assertThat(parse("HTTP://host/")).isEqualTo(parse("http://host/"));
    assertThat(parse("https://host/")).isEqualTo(parse("https://host/"));
    assertThat(parse("HTTPS://host/")).isEqualTo(parse("https://host/"));

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
    assertThat(base.newBuilder("https://host2").build()).isEqualTo(parse("https://host2/"));
    assertThat(base.newBuilder("//host2").build()).isEqualTo(parse("http://host2/"));
    assertThat(base.newBuilder("/path").build()).isEqualTo(parse("http://host/path"));
    assertThat(base.newBuilder("path").build()).isEqualTo(parse("http://host/a/path"));
    assertThat(base.newBuilder("?query").build()).isEqualTo(parse("http://host/a/b?query"));
    assertThat(base.newBuilder("#fragment").build())
        .isEqualTo(parse("http://host/a/b#fragment"));
    assertThat(base.newBuilder("").build()).isEqualTo(parse("http://host/a/b"));
    assertThat(base.newBuilder("ftp://b")).isNull();
    assertThat(base.newBuilder("ht+tp://b")).isNull();
    assertThat(base.newBuilder("ht-tp://b")).isNull();
    assertThat(base.newBuilder("ht.tp://b")).isNull();
  }

  @Test public void redactedUrl() {
    HttpUrl baseWithPasswordAndUsername = parse("http://username:password@host/a/b#fragment");
    HttpUrl baseWithUsernameOnly = parse("http://username@host/a/b#fragment");
    HttpUrl baseWithPasswordOnly = parse("http://password@host/a/b#fragment");
    assertThat(baseWithPasswordAndUsername.redact()).isEqualTo("http://host/...");
    assertThat(baseWithUsernameOnly.redact()).isEqualTo("http://host/...");
    assertThat(baseWithPasswordOnly.redact()).isEqualTo("http://host/...");
  }

  @Test public void resolveNoScheme() throws Exception {
    HttpUrl base = parse("http://host/a/b");
    assertThat(base.resolve("//host2")).isEqualTo(parse("http://host2/"));
    assertThat(base.resolve("/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("path")).isEqualTo(parse("http://host/a/path"));
    assertThat(base.resolve("?query")).isEqualTo(parse("http://host/a/b?query"));
    assertThat(base.resolve("#fragment")).isEqualTo(parse("http://host/a/b#fragment"));
    assertThat(base.resolve("")).isEqualTo(parse("http://host/a/b"));
    assertThat(base.resolve("\\path")).isEqualTo(parse("http://host/path"));
  }

  @Test public void resolveUnsupportedScheme() throws Exception {
    HttpUrl base = parse("http://a/");
    assertThat(base.resolve("ftp://b")).isNull();
    assertThat(base.resolve("ht+tp://b")).isNull();
    assertThat(base.resolve("ht-tp://b")).isNull();
    assertThat(base.resolve("ht.tp://b")).isNull();
  }

  @Test public void resolveSchemeLikePath() throws Exception {
    HttpUrl base = parse("http://a/");
    assertThat(base.resolve("http//b/")).isEqualTo(parse("http://a/http//b/"));
    assertThat(base.resolve("ht+tp//b/")).isEqualTo(parse("http://a/ht+tp//b/"));
    assertThat(base.resolve("ht-tp//b/")).isEqualTo(parse("http://a/ht-tp//b/"));
    assertThat(base.resolve("ht.tp//b/")).isEqualTo(parse("http://a/ht.tp//b/"));
  }

  /** https://tools.ietf.org/html/rfc3986#section-5.4.1 */
  @Test public void rfc3886NormalExamples() {
    HttpUrl url = parse("http://a/b/c/d;p?q");
    // No 'g:' scheme in HttpUrl.
    assertThat(url.resolve("g:h")).isNull();
    assertThat(url.resolve("g")).isEqualTo(parse("http://a/b/c/g"));
    assertThat(url.resolve("./g")).isEqualTo(parse("http://a/b/c/g"));
    assertThat(url.resolve("g/")).isEqualTo(parse("http://a/b/c/g/"));
    assertThat(url.resolve("/g")).isEqualTo(parse("http://a/g"));
    assertThat(url.resolve("//g")).isEqualTo(parse("http://g"));
    assertThat(url.resolve("?y")).isEqualTo(parse("http://a/b/c/d;p?y"));
    assertThat(url.resolve("g?y")).isEqualTo(parse("http://a/b/c/g?y"));
    assertThat(url.resolve("#s")).isEqualTo(parse("http://a/b/c/d;p?q#s"));
    assertThat(url.resolve("g#s")).isEqualTo(parse("http://a/b/c/g#s"));
    assertThat(url.resolve("g?y#s")).isEqualTo(parse("http://a/b/c/g?y#s"));
    assertThat(url.resolve(";x")).isEqualTo(parse("http://a/b/c/;x"));
    assertThat(url.resolve("g;x")).isEqualTo(parse("http://a/b/c/g;x"));
    assertThat(url.resolve("g;x?y#s")).isEqualTo(parse("http://a/b/c/g;x?y#s"));
    assertThat(url.resolve("")).isEqualTo(parse("http://a/b/c/d;p?q"));
    assertThat(url.resolve(".")).isEqualTo(parse("http://a/b/c/"));
    assertThat(url.resolve("./")).isEqualTo(parse("http://a/b/c/"));
    assertThat(url.resolve("..")).isEqualTo(parse("http://a/b/"));
    assertThat(url.resolve("../")).isEqualTo(parse("http://a/b/"));
    assertThat(url.resolve("../g")).isEqualTo(parse("http://a/b/g"));
    assertThat(url.resolve("../..")).isEqualTo(parse("http://a/"));
    assertThat(url.resolve("../../")).isEqualTo(parse("http://a/"));
    assertThat(url.resolve("../../g")).isEqualTo(parse("http://a/g"));
  }

  /** https://tools.ietf.org/html/rfc3986#section-5.4.2 */
  @Test public void rfc3886AbnormalExamples() {
    HttpUrl url = parse("http://a/b/c/d;p?q");
    assertThat(url.resolve("../../../g")).isEqualTo(parse("http://a/g"));
    assertThat(url.resolve("../../../../g")).isEqualTo(parse("http://a/g"));
    assertThat(url.resolve("/./g")).isEqualTo(parse("http://a/g"));
    assertThat(url.resolve("/../g")).isEqualTo(parse("http://a/g"));
    assertThat(url.resolve("g.")).isEqualTo(parse("http://a/b/c/g."));
    assertThat(url.resolve(".g")).isEqualTo(parse("http://a/b/c/.g"));
    assertThat(url.resolve("g..")).isEqualTo(parse("http://a/b/c/g.."));
    assertThat(url.resolve("..g")).isEqualTo(parse("http://a/b/c/..g"));
    assertThat(url.resolve("./../g")).isEqualTo(parse("http://a/b/g"));
    assertThat(url.resolve("./g/.")).isEqualTo(parse("http://a/b/c/g/"));
    assertThat(url.resolve("g/./h")).isEqualTo(parse("http://a/b/c/g/h"));
    assertThat(url.resolve("g/../h")).isEqualTo(parse("http://a/b/c/h"));
    assertThat(url.resolve("g;x=1/./y")).isEqualTo(parse("http://a/b/c/g;x=1/y"));
    assertThat(url.resolve("g;x=1/../y")).isEqualTo(parse("http://a/b/c/y"));
    assertThat(url.resolve("g?y/./x")).isEqualTo(parse("http://a/b/c/g?y/./x"));
    assertThat(url.resolve("g?y/../x")).isEqualTo(parse("http://a/b/c/g?y/../x"));
    assertThat(url.resolve("g#s/./x")).isEqualTo(parse("http://a/b/c/g#s/./x"));
    assertThat(url.resolve("g#s/../x")).isEqualTo(parse("http://a/b/c/g#s/../x"));
    // "http:g" also okay.
    assertThat(url.resolve("http:g")).isEqualTo(parse("http://a/b/c/g"));
  }

  @Test public void parseAuthoritySlashCountDoesntMatter() throws Exception {
    assertThat(parse("http:host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http://host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:/\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:///host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:\\//host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:/\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http://\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:\\\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:/\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:\\\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http:////host/path")).isEqualTo(parse("http://host/path"));
  }

  @Test public void resolveAuthoritySlashCountDoesntMatterWithDifferentScheme() throws Exception {
    HttpUrl base = parse("https://a/b/c");
    assertThat(base.resolve("http:host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http://host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:///host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\//host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http://\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:////host/path")).isEqualTo(parse("http://host/path"));
  }

  @Test public void resolveAuthoritySlashCountMattersWithSameScheme() throws Exception {
    HttpUrl base = parse("http://a/b/c");
    assertThat(base.resolve("http:host/path")).isEqualTo(parse("http://a/b/host/path"));
    assertThat(base.resolve("http:/host/path")).isEqualTo(parse("http://a/host/path"));
    assertThat(base.resolve("http:\\host/path")).isEqualTo(parse("http://a/host/path"));
    assertThat(base.resolve("http://host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:///host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\//host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http://\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\\\/host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:/\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:\\\\\\host/path")).isEqualTo(parse("http://host/path"));
    assertThat(base.resolve("http:////host/path")).isEqualTo(parse("http://host/path"));
  }

  @Test public void username() throws Exception {
    assertThat(parse("http://@host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http://user@host/path")).isEqualTo(parse("http://user@host/path"));
  }

  /** Given multiple '@' characters, the last one is the delimiter. */
  @Test public void authorityWithMultipleAtSigns() throws Exception {
    HttpUrl httpUrl = parse("http://foo@bar@baz/path");
    assertThat(httpUrl.username()).isEqualTo("foo@bar");
    assertThat(httpUrl.password()).isEqualTo("");
    assertThat(httpUrl).isEqualTo(parse("http://foo%40bar@baz/path"));
  }

  /** Given multiple ':' characters, the first one is the delimiter. */
  @Test public void authorityWithMultipleColons() throws Exception {
    HttpUrl httpUrl = parse("http://foo:pass1@bar:pass2@baz/path");
    assertThat(httpUrl.username()).isEqualTo("foo");
    assertThat(httpUrl.password()).isEqualTo("pass1@bar:pass2");
    assertThat(httpUrl).isEqualTo(parse("http://foo:pass1%40bar%3Apass2@baz/path"));
  }

  @Test public void usernameAndPassword() throws Exception {
    assertThat(parse("http://username:password@host/path"))
        .isEqualTo(parse("http://username:password@host/path"));
    assertThat(parse("http://username:@host/path"))
        .isEqualTo(parse("http://username@host/path"));
  }

  @Test public void passwordWithEmptyUsername() throws Exception {
    // Chrome doesn't mind, but Firefox rejects URLs with empty usernames and non-empty passwords.
    assertThat(parse("http://:@host/path")).isEqualTo(parse("http://host/path"));
    assertThat(parse("http://:password@@host/path").encodedPassword())
        .isEqualTo("password%40");
  }

  @Test public void unprintableCharactersArePercentEncoded() throws Exception {
    assertThat(parse("http://host/\u0000").encodedPath()).isEqualTo("/%00");
    assertThat(parse("http://host/\u0008").encodedPath()).isEqualTo("/%08");
    assertThat(parse("http://host/\ufffd").encodedPath()).isEqualTo("/%EF%BF%BD");
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
    assertThat(parse("http://abcd").host()).isEqualTo("abcd");
    assertThat(parse("http://σ").host()).isEqualTo("xn--4xa");
  }

  @Test public void hostnameUppercaseCharactersConvertedToLowercase() throws Exception {
    assertThat(parse("http://ABCD").host()).isEqualTo("abcd");
    assertThat(parse("http://Σ").host()).isEqualTo("xn--4xa");
  }

  @Test public void hostnameIgnoredCharacters() throws Exception {
    // The soft hyphen (­) should be ignored.
    assertThat(parse("http://AB\u00adCD").host()).isEqualTo("abcd");
  }

  @Test public void hostnameMultipleCharacterMapping() throws Exception {
    // Map the single character telephone symbol (℡) to the string "tel".
    assertThat(parse("http://\u2121").host()).isEqualTo("tel");
  }

  @Test public void hostnameMappingLastMappedCodePoint() throws Exception {
    assertThat(parse("http://\uD87E\uDE1D").host()).isEqualTo("xn--pu5l");
  }

  @Ignore("The java.net.IDN implementation doesn't ignore characters that it should.")
  @Test public void hostnameMappingLastIgnoredCodePoint() throws Exception {
    assertThat(parse("http://ab\uDB40\uDDEFcd").host()).isEqualTo("abcd");
  }

  @Test public void hostnameMappingLastDisallowedCodePoint() throws Exception {
    assertInvalid("http://\uDBFF\uDFFF", "Invalid URL host: \"\uDBFF\uDFFF\"");
  }

  @Test public void hostIpv6() throws Exception {
    // Square braces are absent from host()...
    assertThat(parse("http://[::1]/").host()).isEqualTo("::1");

    // ... but they're included in toString().
    assertThat(parse("http://[::1]/").toString()).isEqualTo("http://[::1]/");

    // IPv6 colons don't interfere with port numbers or passwords.
    assertThat(parse("http://[::1]:8080/").port()).isEqualTo(8080);
    assertThat(parse("http://user:password@[::1]/").password()).isEqualTo("password");
    assertThat(parse("http://user:password@[::1]:8080/").host()).isEqualTo("::1");

    // Permit the contents of IPv6 addresses to be percent-encoded...
    assertThat(parse("http://[%3A%3A%31]/").host()).isEqualTo("::1");

    // Including the Square braces themselves! (This is what Chrome does.)
    assertThat(parse("http://%5B%3A%3A1%5D/").host()).isEqualTo("::1");
  }

  @Test public void hostIpv6AddressDifferentFormats() throws Exception {
    // Multiple representations of the same address; see http://tools.ietf.org/html/rfc5952.
    String a3 = "2001:db8::1:0:0:1";
    assertThat(parse("http://[2001:db8:0:0:1:0:0:1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:0db8:0:0:1:0:0:1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8::1:0:0:1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8::0:1:0:0:1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:0db8::1:0:0:1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8:0:0:1::1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8:0000:0:1::1]").host()).isEqualTo(a3);
    assertThat(parse("http://[2001:DB8:0:0:1::1]").host()).isEqualTo(a3);
  }

  @Test public void hostIpv6AddressLeadingCompression() throws Exception {
    assertThat(parse("http://[::0001]").host()).isEqualTo("::1");
    assertThat(parse("http://[0000::0001]").host()).isEqualTo("::1");
    assertThat(parse("http://[0000:0000:0000:0000:0000:0000:0000:0001]").host())
        .isEqualTo("::1");
    assertThat(parse("http://[0000:0000:0000:0000:0000:0000::0001]").host())
        .isEqualTo("::1");
  }

  @Test public void hostIpv6AddressTrailingCompression() throws Exception {
    assertThat(parse("http://[0001:0000::]").host()).isEqualTo("1::");
    assertThat(parse("http://[0001::0000]").host()).isEqualTo("1::");
    assertThat(parse("http://[0001::]").host()).isEqualTo("1::");
    assertThat(parse("http://[1::]").host()).isEqualTo("1::");
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
    assertThat(parse("http://[::1:255.255.255.255]/").host()).isEqualTo("::1:ffff:ffff");
    assertThat(parse("http://[0:0:0:0:0:1:0.0.0.0]/").host()).isEqualTo("::1:0:0");
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

  @Test public void hostIpv6Malformed() throws Exception {
    assertInvalid("http://[::g]/", "Invalid URL host: \"[::g]\"");
  }

  @Test public void hostIpv6CanonicalForm() throws Exception {
    assertThat(parse("http://[abcd:ef01:2345:6789:abcd:ef01:2345:6789]/").host())
        .isEqualTo("abcd:ef01:2345:6789:abcd:ef01:2345:6789");
    assertThat(parse("http://[a:0:0:0:b:0:0:0]/").host()).isEqualTo("a::b:0:0:0");
    assertThat(parse("http://[a:b:0:0:c:0:0:0]/").host()).isEqualTo("a:b:0:0:c::");
    assertThat(parse("http://[a:b:0:0:0:c:0:0]/").host()).isEqualTo("a:b::c:0:0");
    assertThat(parse("http://[a:0:0:0:b:0:0:0]/").host()).isEqualTo("a::b:0:0:0");
    assertThat(parse("http://[0:0:0:a:b:0:0:0]/").host()).isEqualTo("::a:b:0:0:0");
    assertThat(parse("http://[0:0:0:a:0:0:0:b]/").host()).isEqualTo("::a:0:0:0:b");
    assertThat(parse("http://[0:a:b:c:d:e:f:1]/").host()).isEqualTo("0:a:b:c:d:e:f:1");
    assertThat(parse("http://[a:b:c:d:e:f:1:0]/").host()).isEqualTo("a:b:c:d:e:f:1:0");
    assertThat(parse("http://[FF01:0:0:0:0:0:0:101]/").host()).isEqualTo("ff01::101");
    assertThat(parse("http://[2001:db8::1]/").host()).isEqualTo("2001:db8::1");
    assertThat(parse("http://[2001:db8:0:0:0:0:2:1]/").host()).isEqualTo("2001:db8::2:1");
    assertThat(parse("http://[2001:db8:0:1:1:1:1:1]/").host())
        .isEqualTo("2001:db8:0:1:1:1:1:1");
    assertThat(parse("http://[2001:db8:0:0:1:0:0:1]/").host())
        .isEqualTo("2001:db8::1:0:0:1");
    assertThat(parse("http://[2001:0:0:1:0:0:0:1]/").host()).isEqualTo("2001:0:0:1::1");
    assertThat(parse("http://[1:0:0:0:0:0:0:0]/").host()).isEqualTo("1::");
    assertThat(parse("http://[0:0:0:0:0:0:0:1]/").host()).isEqualTo("::1");
    assertThat(parse("http://[0:0:0:0:0:0:0:0]/").host()).isEqualTo("::");
    assertThat(parse("http://[::ffff:c0a8:1fe]/").host()).isEqualTo("192.168.1.254");
  }

  /** The builder permits square braces but does not require them. */
  @Test public void hostIpv6Builder() throws Exception {
    HttpUrl base = parse("http://example.com/");
    assertThat(base.newBuilder().host("[::1]").build().toString())
        .isEqualTo("http://[::1]/");
    assertThat(base.newBuilder().host("[::0001]").build().toString())
        .isEqualTo("http://[::1]/");
    assertThat(base.newBuilder().host("::1").build().toString()).isEqualTo("http://[::1]/");
    assertThat(base.newBuilder().host("::0001").build().toString())
        .isEqualTo("http://[::1]/");
  }

  @Test public void hostIpv4CanonicalForm() throws Exception {
    assertThat(parse("http://255.255.255.255/").host()).isEqualTo("255.255.255.255");
    assertThat(parse("http://1.2.3.4/").host()).isEqualTo("1.2.3.4");
    assertThat(parse("http://0.0.0.0/").host()).isEqualTo("0.0.0.0");
  }

  @Test public void hostWithTrailingDot() throws Exception {
    assertThat(parse("http://host./").host()).isEqualTo("host.");
  }

  @Test public void port() throws Exception {
    assertThat(parse("http://host:80/")).isEqualTo(parse("http://host/"));
    assertThat(parse("http://host:99/")).isEqualTo(parse("http://host:99/"));
    assertThat(parse("http://host:/")).isEqualTo(parse("http://host/"));
    assertThat(parse("http://host:65535/").port()).isEqualTo(65535);
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
    assertThat(url.toString()).isEqualTo("http://host/#Σ");
    assertThat(url.fragment()).isEqualTo("Σ");
    assertThat(url.encodedFragment()).isEqualTo("Σ");
    assertThat(url.uri().toString()).isEqualTo("http://host/#Σ");
  }

  @Test public void fragmentNonAsciiThatOffendsJavaNetUri() throws Exception {
    HttpUrl url = parse("http://host/#\u0080");
    assertThat(url.toString()).isEqualTo("http://host/#\u0080");
    assertThat(url.fragment()).isEqualTo("\u0080");
    assertThat(url.encodedFragment()).isEqualTo("\u0080");
    // Control characters may be stripped!
    assertThat(url.uri()).isEqualTo(new URI("http://host/#"));
  }

  @Test public void fragmentPercentEncodedNonAscii() throws Exception {
    HttpUrl url = parse("http://host/#%C2%80");
    assertThat(url.toString()).isEqualTo("http://host/#%C2%80");
    assertThat(url.fragment()).isEqualTo("\u0080");
    assertThat(url.encodedFragment()).isEqualTo("%C2%80");
    assertThat(url.uri().toString()).isEqualTo("http://host/#%C2%80");
  }

  @Test public void fragmentPercentEncodedPartialCodePoint() throws Exception {
    HttpUrl url = parse("http://host/#%80");
    assertThat(url.toString()).isEqualTo("http://host/#%80");
    // Unicode replacement character.
    assertThat(url.fragment()).isEqualTo("\ufffd");
    assertThat(url.encodedFragment()).isEqualTo("%80");
    assertThat(url.uri().toString()).isEqualTo("http://host/#%80");
  }

  @Test public void relativePath() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.resolve("d/e/f")).isEqualTo(parse("http://host/a/b/d/e/f"));
    assertThat(base.resolve("../../d/e/f")).isEqualTo(parse("http://host/d/e/f"));
    assertThat(base.resolve("..")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("../..")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve("../../..")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve(".")).isEqualTo(parse("http://host/a/b/"));
    assertThat(base.resolve("././..")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("c/d/../e/../")).isEqualTo(parse("http://host/a/b/c/"));
    assertThat(base.resolve("..e/")).isEqualTo(parse("http://host/a/b/..e/"));
    assertThat(base.resolve("e/f../")).isEqualTo(parse("http://host/a/b/e/f../"));
    assertThat(base.resolve("%2E.")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve(".%2E")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("%2E%2E")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("%2e.")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve(".%2e")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("%2e%2e")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("%2E")).isEqualTo(parse("http://host/a/b/"));
    assertThat(base.resolve("%2e")).isEqualTo(parse("http://host/a/b/"));
  }

  @Test public void relativePathWithTrailingSlash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c/");
    assertThat(base.resolve("..")).isEqualTo(parse("http://host/a/b/"));
    assertThat(base.resolve("../")).isEqualTo(parse("http://host/a/b/"));
    assertThat(base.resolve("../..")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("../../")).isEqualTo(parse("http://host/a/"));
    assertThat(base.resolve("../../..")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve("../../../")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve("../../../..")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve("../../../../")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve("../../../../a")).isEqualTo(parse("http://host/a"));
    assertThat(base.resolve("../../../../a/..")).isEqualTo(parse("http://host/"));
    assertThat(base.resolve("../../../../a/b/..")).isEqualTo(parse("http://host/a/"));
  }

  @Test public void pathWithBackslash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.resolve("d\\e\\f")).isEqualTo(parse("http://host/a/b/d/e/f"));
    assertThat(base.resolve("../..\\d\\e\\f")).isEqualTo(parse("http://host/d/e/f"));
    assertThat(base.resolve("..\\..")).isEqualTo(parse("http://host/"));
  }

  @Test public void relativePathWithSameScheme() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.resolve("http:d/e/f")).isEqualTo(parse("http://host/a/b/d/e/f"));
    assertThat(base.resolve("http:../../d/e/f")).isEqualTo(parse("http://host/d/e/f"));
  }

  @Test public void decodeUsername() {
    assertThat(parse("http://user@host/").username()).isEqualTo("user");
    assertThat(parse("http://%F0%9F%8D%A9@host/").username()).isEqualTo("\uD83C\uDF69");
  }

  @Test public void decodePassword() {
    assertThat(parse("http://user:password@host/").password()).isEqualTo("password");
    assertThat(parse("http://user:@host/").password()).isEqualTo("");
    assertThat(parse("http://user:%F0%9F%8D%A9@host/").password())
        .isEqualTo("\uD83C\uDF69");
  }

  @Test public void decodeSlashCharacterInDecodedPathSegment() {
    assertThat(parse("http://host/a%2Fb%2Fc").pathSegments()).containsExactly("a/b/c");
  }

  @Test public void decodeEmptyPathSegments() {
    assertThat(parse("http://host/").pathSegments()).containsExactly("");
  }

  @Test public void percentDecode() throws Exception {
    assertThat(parse("http://host/%00").pathSegments()).containsExactly("\u0000");
    assertThat(parse("http://host/a/%E2%98%83/c").pathSegments()).containsExactly("a", "\u2603", "c");
    assertThat(parse("http://host/a/%F0%9F%8D%A9/c").pathSegments()).containsExactly("a", "\uD83C\uDF69", "c");
    assertThat(parse("http://host/a/%62/c").pathSegments()).containsExactly("a", "b", "c");
    assertThat(parse("http://host/a/%7A/c").pathSegments()).containsExactly("a", "z", "c");
    assertThat(parse("http://host/a/%7a/c").pathSegments()).containsExactly("a", "z", "c");
  }

  @Test public void malformedPercentEncoding() {
    assertThat(parse("http://host/a%f/b").pathSegments()).containsExactly("a%f", "b");
    assertThat(parse("http://host/%/b").pathSegments()).containsExactly("%", "b");
    assertThat(parse("http://host/%").pathSegments()).containsExactly("%");
    assertThat(parse("http://github.com/%%30%30").pathSegments()).containsExactly("%00");
  }

  @Test public void malformedUtf8Encoding() {
    // Replace a partial UTF-8 sequence with the Unicode replacement character.
    assertThat(parse("http://host/a/%E2%98x/c").pathSegments())
        .containsExactly("a", "\ufffdx", "c");
  }

  @Test public void incompleteUrlComposition() throws Exception {
    try {
      new HttpUrl.Builder().scheme("http").build();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo("host == null");
    }
    try {
      new HttpUrl.Builder().host("host").build();
      fail();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).isEqualTo("scheme == null");
    }
  }

  @Test public void builderToString() {
    assertThat(parse("https://host.com/path").newBuilder().toString())
        .isEqualTo("https://host.com/path");
  }

  @Test public void incompleteBuilderToString() {
    assertThat(new HttpUrl.Builder().scheme("https").encodedPath("/path").toString())
        .isEqualTo("https:///path");
    assertThat(new HttpUrl.Builder().host("host.com").encodedPath("/path").toString())
        .isEqualTo("//host.com/path");
    assertThat(new HttpUrl.Builder().host("host.com").encodedPath("/path").port(8080).toString())
        .isEqualTo("//host.com:8080/path");
  }

  @Test public void minimalUrlComposition() throws Exception {
    HttpUrl url = new HttpUrl.Builder().scheme("http").host("host").build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.scheme()).isEqualTo("http");
    assertThat(url.username()).isEqualTo("");
    assertThat(url.password()).isEqualTo("");
    assertThat(url.host()).isEqualTo("host");
    assertThat(url.port()).isEqualTo(80);
    assertThat(url.encodedPath()).isEqualTo("/");
    assertThat(url.query()).isNull();
    assertThat(url.fragment()).isNull();
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
    assertThat(url.toString())
        .isEqualTo("http://username:password@host:8080/path?query#fragment");
    assertThat(url.scheme()).isEqualTo("http");
    assertThat(url.username()).isEqualTo("username");
    assertThat(url.password()).isEqualTo("password");
    assertThat(url.host()).isEqualTo("host");
    assertThat(url.port()).isEqualTo(8080);
    assertThat(url.encodedPath()).isEqualTo("/path");
    assertThat(url.query()).isEqualTo("query");
    assertThat(url.fragment()).isEqualTo("fragment");
  }

  @Test public void changingSchemeChangesDefaultPort() throws Exception {
    assertThat(parse("http://example.com")
        .newBuilder()
        .scheme("https")
        .build().port()).isEqualTo(443);

    assertThat(parse("https://example.com")
        .newBuilder()
        .scheme("http")
        .build().port()).isEqualTo(80);

    assertThat(parse("https://example.com:1234")
        .newBuilder()
        .scheme("http")
        .build().port()).isEqualTo(1234);
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
    assertThat(url.toString()).isEqualTo(("http://a%0D%0A%0C%09%20b:c%0D%0A%0C%09%20d@host"
        + "/e%0D%0A%0C%09%20f?g%0D%0A%0C%09%20h#i%0D%0A%0C%09 j"));
    assertThat(url.username()).isEqualTo("a\r\n\f\t b");
    assertThat(url.password()).isEqualTo("c\r\n\f\t d");
    assertThat(url.pathSegments().get(0)).isEqualTo("e\r\n\f\t f");
    assertThat(url.query()).isEqualTo("g\r\n\f\t h");
    assertThat(url.fragment()).isEqualTo("i\r\n\f\t j");
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
    assertThat(url.toString())
        .isEqualTo(("http://a%3A%01%40%2F%5C%3F%23%25b:c%3A%01%40%2F%5C%3F%23%25d@ef:8080/"
        + "g:%01@%2F%5C%3F%23%25h?i:%01@/\\?%23%25j#k:%01@/\\?#%25l"));
    assertThat(url.scheme()).isEqualTo("http");
    assertThat(url.username()).isEqualTo("a:\u0001@/\\?#%b");
    assertThat(url.password()).isEqualTo("c:\u0001@/\\?#%d");
    assertThat(url.pathSegments()).containsExactly("g:\u0001@/\\?#%h");
    assertThat(url.query()).isEqualTo("i:\u0001@/\\?#%j");
    assertThat(url.fragment()).isEqualTo("k:\u0001@/\\?#%l");
    assertThat(url.encodedUsername()).isEqualTo("a%3A%01%40%2F%5C%3F%23%25b");
    assertThat(url.encodedPassword()).isEqualTo("c%3A%01%40%2F%5C%3F%23%25d");
    assertThat(url.encodedPath()).isEqualTo("/g:%01@%2F%5C%3F%23%25h");
    assertThat(url.encodedQuery()).isEqualTo("i:%01@/\\?%23%25j");
    assertThat(url.encodedFragment()).isEqualTo("k:%01@/\\?#%25l");
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
    assertThat(url.toString())
        .isEqualTo(("http://a%3A%01%40%2F%5C%3F%23%25b:c%3A%01%40%2F%5C%3F%23%25d@ef:8080/"
        + "g:%01@%2F%5C%3F%23%25h?i:%01@/\\?%23%25j#k:%01@/\\?#%25l"));
    assertThat(url.scheme()).isEqualTo("http");
    assertThat(url.username()).isEqualTo("a:\u0001@/\\?#%b");
    assertThat(url.password()).isEqualTo("c:\u0001@/\\?#%d");
    assertThat(url.pathSegments()).containsExactly("g:\u0001@/\\?#%h");
    assertThat(url.query()).isEqualTo("i:\u0001@/\\?#%j");
    assertThat(url.fragment()).isEqualTo("k:\u0001@/\\?#%l");
    assertThat(url.encodedUsername()).isEqualTo("a%3A%01%40%2F%5C%3F%23%25b");
    assertThat(url.encodedPassword()).isEqualTo("c%3A%01%40%2F%5C%3F%23%25d");
    assertThat(url.encodedPath()).isEqualTo("/g:%01@%2F%5C%3F%23%25h");
    assertThat(url.encodedQuery()).isEqualTo("i:%01@/\\?%23%25j");
    assertThat(url.encodedFragment()).isEqualTo("k:%01@/\\?#%25l");
  }

  @Test public void composeWithEncodedPath() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .encodedPath("/a%2Fb/c")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/a%2Fb/c");
    assertThat(url.encodedPath()).isEqualTo("/a%2Fb/c");
    assertThat(url.pathSegments()).containsExactly("a/b", "c");
  }

  @Test public void composeMixingPathSegments() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .encodedPath("/a%2fb/c")
        .addPathSegment("d%25e")
        .addEncodedPathSegment("f%25g")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/a%2fb/c/d%2525e/f%25g");
    assertThat(url.encodedPath()).isEqualTo("/a%2fb/c/d%2525e/f%25g");
    assertThat(url.encodedPathSegments()).containsExactly("a%2fb", "c", "d%2525e", "f%25g");
    assertThat(url.pathSegments()).containsExactly("a/b", "c", "d%25e", "f%g");
  }

  @Test public void composeWithAddSegment() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addPathSegment("").build().encodedPath())
        .isEqualTo("/a/b/c/");
    assertThat(base.newBuilder().addPathSegment("").addPathSegment("d").build().encodedPath())
        .isEqualTo("/a/b/c/d");
    assertThat(base.newBuilder().addPathSegment("..").build().encodedPath())
        .isEqualTo("/a/b/");
    assertThat(base.newBuilder().addPathSegment("").addPathSegment("..").build().encodedPath())
        .isEqualTo("/a/b/");
    assertThat(base.newBuilder().addPathSegment("").addPathSegment("").build().encodedPath())
        .isEqualTo("/a/b/c/");
  }

  @Test public void pathSize() throws Exception {
    assertThat(parse("http://host/").pathSize()).isEqualTo(1);
    assertThat(parse("http://host/a/b/c").pathSize()).isEqualTo(3);
  }

  @Test public void addPathSegments() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");

    // Add a string with zero slashes: resulting URL gains one slash.
    assertThat(base.newBuilder().addPathSegments("").build().encodedPath())
        .isEqualTo("/a/b/c/");
    assertThat(base.newBuilder().addPathSegments("d").build().encodedPath())
        .isEqualTo("/a/b/c/d");

    // Add a string with one slash: resulting URL gains two slashes.
    assertThat(base.newBuilder().addPathSegments("/").build().encodedPath())
        .isEqualTo("/a/b/c//");
    assertThat(base.newBuilder().addPathSegments("d/").build().encodedPath())
        .isEqualTo("/a/b/c/d/");
    assertThat(base.newBuilder().addPathSegments("/d").build().encodedPath())
        .isEqualTo("/a/b/c//d");

    // Add a string with two slashes: resulting URL gains three slashes.
    assertThat(base.newBuilder().addPathSegments("//").build().encodedPath())
        .isEqualTo("/a/b/c///");
    assertThat(base.newBuilder().addPathSegments("/d/").build().encodedPath())
        .isEqualTo("/a/b/c//d/");
    assertThat(base.newBuilder().addPathSegments("d//").build().encodedPath())
        .isEqualTo("/a/b/c/d//");
    assertThat(base.newBuilder().addPathSegments("//d").build().encodedPath())
        .isEqualTo("/a/b/c///d");
    assertThat(base.newBuilder().addPathSegments("d/e/f").build().encodedPath())
        .isEqualTo("/a/b/c/d/e/f");
  }

  @Test public void addPathSegmentsOntoTrailingSlash() throws Exception {
    HttpUrl base = parse("http://host/a/b/c/");

    // Add a string with zero slashes: resulting URL gains zero slashes.
    assertThat(base.newBuilder().addPathSegments("").build().encodedPath())
        .isEqualTo("/a/b/c/");
    assertThat(base.newBuilder().addPathSegments("d").build().encodedPath())
        .isEqualTo("/a/b/c/d");

    // Add a string with one slash: resulting URL gains one slash.
    assertThat(base.newBuilder().addPathSegments("/").build().encodedPath())
        .isEqualTo("/a/b/c//");
    assertThat(base.newBuilder().addPathSegments("d/").build().encodedPath())
        .isEqualTo("/a/b/c/d/");
    assertThat(base.newBuilder().addPathSegments("/d").build().encodedPath())
        .isEqualTo("/a/b/c//d");

    // Add a string with two slashes: resulting URL gains two slashes.
    assertThat(base.newBuilder().addPathSegments("//").build().encodedPath())
        .isEqualTo("/a/b/c///");
    assertThat(base.newBuilder().addPathSegments("/d/").build().encodedPath())
        .isEqualTo("/a/b/c//d/");
    assertThat(base.newBuilder().addPathSegments("d//").build().encodedPath())
        .isEqualTo("/a/b/c/d//");
    assertThat(base.newBuilder().addPathSegments("//d").build().encodedPath())
        .isEqualTo("/a/b/c///d");
    assertThat(base.newBuilder().addPathSegments("d/e/f").build().encodedPath())
        .isEqualTo("/a/b/c/d/e/f");
  }

  @Test public void addPathSegmentsWithBackslash() throws Exception {
    HttpUrl base = parse("http://host/");
    assertThat(base.newBuilder().addPathSegments("d\\e").build().encodedPath())
        .isEqualTo("/d/e");
    assertThat(base.newBuilder().addEncodedPathSegments("d\\e").build().encodedPath())
        .isEqualTo("/d/e");
  }

  @Test public void addPathSegmentsWithEmptyPaths() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addPathSegments("/d/e///f").build().encodedPath())
        .isEqualTo("/a/b/c//d/e///f");
  }

  @Test public void addEncodedPathSegments() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(
        (Object) base.newBuilder().addEncodedPathSegments("d/e/%20/\n").build().encodedPath())
        .isEqualTo("/a/b/c/d/e/%20/");
  }

  @Test public void addPathSegmentDotDoesNothing() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addPathSegment(".").build().encodedPath())
        .isEqualTo("/a/b/c");
  }

  @Test public void addPathSegmentEncodes() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addPathSegment("%2e").build().encodedPath())
        .isEqualTo("/a/b/c/%252e");
    assertThat(base.newBuilder().addPathSegment("%2e%2e").build().encodedPath())
        .isEqualTo("/a/b/c/%252e%252e");
  }

  @Test public void addPathSegmentDotDotPopsDirectory() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addPathSegment("..").build().encodedPath())
        .isEqualTo("/a/b/");
  }

  @Test public void addPathSegmentDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addPathSegment(".\n").build().encodedPath())
        .isEqualTo("/a/b/c/.%0A");
  }

  @Test public void addEncodedPathSegmentDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addEncodedPathSegment(".\n").build().encodedPath())
        .isEqualTo("/a/b/c");
  }

  @Test public void addEncodedPathSegmentDotDotAndIgnoredCharacter() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().addEncodedPathSegment("..\n").build().encodedPath())
        .isEqualTo("/a/b/");
  }

  @Test public void setPathSegment() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().setPathSegment(0, "d").build().encodedPath())
        .isEqualTo("/d/b/c");
    assertThat(base.newBuilder().setPathSegment(1, "d").build().encodedPath())
        .isEqualTo("/a/d/c");
    assertThat(base.newBuilder().setPathSegment(2, "d").build().encodedPath())
        .isEqualTo("/a/b/d");
  }

  @Test public void setPathSegmentEncodes() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().setPathSegment(0, "%25").build().encodedPath())
        .isEqualTo("/%2525/b/c");
    assertThat(base.newBuilder().setPathSegment(0, ".\n").build().encodedPath())
        .isEqualTo("/.%0A/b/c");
    assertThat(base.newBuilder().setPathSegment(0, "%2e").build().encodedPath())
        .isEqualTo("/%252e/b/c");
  }

  @Test public void setPathSegmentAcceptsEmpty() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    assertThat(base.newBuilder().setPathSegment(0, "").build().encodedPath())
        .isEqualTo("//b/c");
    assertThat(base.newBuilder().setPathSegment(2, "").build().encodedPath())
        .isEqualTo("/a/b/");
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
    assertThat(url.encodedPath()).isEqualTo("/a/%2F/c");
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
    assertThat(base.newBuilder().setEncodedPathSegment(0, "%25").build().encodedPath())
        .isEqualTo("/%25/b/c");
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
    assertThat(url.encodedPath()).isEqualTo("/a/%2F/c");
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
    assertThat(url.encodedPath()).isEqualTo("/b/c");
  }

  @Test public void removePathSegmentDoesntRemovePath() throws Exception {
    HttpUrl base = parse("http://host/a/b/c");
    HttpUrl url = base.newBuilder()
        .removePathSegment(0)
        .removePathSegment(0)
        .removePathSegment(0)
        .build();
    assertThat(url.pathSegments()).containsExactly("");
    assertThat(url.encodedPath()).isEqualTo("/");
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
    assertThat(javaNetUrl.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @Test public void toUri() throws Exception {
    HttpUrl httpUrl = parse("http://username:password@host/path?query#fragment");
    URI uri = httpUrl.uri();
    assertThat(uri.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @Test public void toUriSpecialQueryCharacters() throws Exception {
    HttpUrl httpUrl = parse("http://host/?d=abc!@[]^`{}|\\");
    URI uri = httpUrl.uri();
    assertThat(uri.toString()).isEqualTo("http://host/?d=abc!@[]%5E%60%7B%7D%7C%5C");
  }

  @Test public void toUriWithUsernameNoPassword() throws Exception {
    HttpUrl httpUrl = new HttpUrl.Builder()
        .scheme("http")
        .username("user")
        .host("host")
        .build();
    assertThat(httpUrl.toString()).isEqualTo("http://user@host/");
    assertThat(httpUrl.uri().toString()).isEqualTo("http://user@host/");
  }

  @Test public void toUriUsernameSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .username("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/");
    assertThat(url.uri().toString())
        .isEqualTo("http://%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/");
  }

  @Test public void toUriPasswordSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .username("user")
        .password("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://user:%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/");
    assertThat(url.uri().toString())
        .isEqualTo("http://user:%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/");
  }

  @Test public void toUriPathSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addPathSegment("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/=[]:;%22~%7C%3F%23@%5E%2F$%25*");
    assertThat(url.uri().toString())
        .isEqualTo("http://host/=%5B%5D:;%22~%7C%3F%23@%5E%2F$%25*");
  }

  @Test public void toUriQueryParameterNameSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("=[]:;\"~|?#@^/$%*", "a")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://host/?%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*=a");
    assertThat(url.uri().toString())
        .isEqualTo("http://host/?%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*=a");
    assertThat(url.queryParameter("=[]:;\"~|?#@^/$%*")).isEqualTo("a");
  }

  @Test public void toUriQueryParameterValueSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("a", "=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://host/?a=%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*");
    assertThat(url.uri().toString())
        .isEqualTo("http://host/?a=%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*");
    assertThat(url.queryParameter("a")).isEqualTo("=[]:;\"~|?#@^/$%*");
  }

  @Test public void toUriQueryValueSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .query("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?=[]:;%22~|?%23@^/$%25*");
    assertThat(url.uri().toString()).isEqualTo("http://host/?=[]:;%22~%7C?%23@%5E/$%25*");
  }

  @Test public void queryCharactersEncodedWhenComposed() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("a", "!$(),/:;?@[]\\^`{|}~")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://host/?a=%21%24%28%29%2C%2F%3A%3B%3F%40%5B%5D%5C%5E%60%7B%7C%7D%7E");
    assertThat(url.queryParameter("a")).isEqualTo("!$(),/:;?@[]\\^`{|}~");
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
    assertThat(url.toString()).isEqualTo("http://host/?a=!$(),/:;?@[]\\^`{|}~");
    assertThat(url.queryParameter("a")).isEqualTo("!$(),/:;?@[]\\^`{|}~");
  }

  /**
   * When callers parse a URL with query components that aren't encoded, we shouldn't convert them
   * into a canonical form because doing so could be semantically different.
   */
  @Test public void queryCharactersNotReencodedWhenParsed() throws Exception {
    HttpUrl url = parse("http://host/?a=!$(),/:;?@[]\\^`{|}~");
    assertThat(url.toString()).isEqualTo("http://host/?a=!$(),/:;?@[]\\^`{|}~");
    assertThat(url.queryParameter("a")).isEqualTo("!$(),/:;?@[]\\^`{|}~");
  }

  @Test public void toUriFragmentSpecialCharacters() throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .fragment("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/#=[]:;\"~|?#@^/$%25*");
    assertThat(url.uri().toString()).isEqualTo("http://host/#=[]:;%22~%7C?%23@%5E/$%25*");
  }

  @Test public void toUriWithControlCharacters() throws Exception {
    // Percent-encoded in the path.
    assertThat(parse("http://host/a\u0000b").uri()).isEqualTo(new URI("http://host/a%00b"));
    assertThat(parse("http://host/a\u0080b").uri())
        .isEqualTo(new URI("http://host/a%C2%80b"));
    assertThat(parse("http://host/a\u009fb").uri())
        .isEqualTo(new URI("http://host/a%C2%9Fb"));
    // Percent-encoded in the query.
    assertThat(parse("http://host/?a\u0000b").uri())
        .isEqualTo(new URI("http://host/?a%00b"));
    assertThat(parse("http://host/?a\u0080b").uri())
        .isEqualTo(new URI("http://host/?a%C2%80b"));
    assertThat(parse("http://host/?a\u009fb").uri())
        .isEqualTo(new URI("http://host/?a%C2%9Fb"));
    // Stripped from the fragment.
    assertThat(parse("http://host/#a\u0000b").uri())
        .isEqualTo(new URI("http://host/#a%00b"));
    assertThat(parse("http://host/#a\u0080b").uri()).isEqualTo(new URI("http://host/#ab"));
    assertThat(parse("http://host/#a\u009fb").uri()).isEqualTo(new URI("http://host/#ab"));
  }

  @Test public void toUriWithSpaceCharacters() throws Exception {
    // Percent-encoded in the path.
    assertThat(parse("http://host/a\u000bb").uri()).isEqualTo(new URI("http://host/a%0Bb"));
    assertThat(parse("http://host/a b").uri()).isEqualTo(new URI("http://host/a%20b"));
    assertThat(parse("http://host/a\u2009b").uri())
        .isEqualTo(new URI("http://host/a%E2%80%89b"));
    assertThat(parse("http://host/a\u3000b").uri())
        .isEqualTo(new URI("http://host/a%E3%80%80b"));
    // Percent-encoded in the query.
    assertThat(parse("http://host/?a\u000bb").uri())
        .isEqualTo(new URI("http://host/?a%0Bb"));
    assertThat(parse("http://host/?a b").uri()).isEqualTo(new URI("http://host/?a%20b"));
    assertThat(parse("http://host/?a\u2009b").uri())
        .isEqualTo(new URI("http://host/?a%E2%80%89b"));
    assertThat(parse("http://host/?a\u3000b").uri())
        .isEqualTo(new URI("http://host/?a%E3%80%80b"));
    // Stripped from the fragment.
    assertThat(parse("http://host/#a\u000bb").uri())
        .isEqualTo(new URI("http://host/#a%0Bb"));
    assertThat(parse("http://host/#a b").uri()).isEqualTo(new URI("http://host/#a%20b"));
    assertThat(parse("http://host/#a\u2009b").uri()).isEqualTo(new URI("http://host/#ab"));
    assertThat(parse("http://host/#a\u3000b").uri()).isEqualTo(new URI("http://host/#ab"));
  }

  @Test public void toUriWithNonHexPercentEscape() throws Exception {
    assertThat(parse("http://host/%xx").uri()).isEqualTo(new URI("http://host/%25xx"));
  }

  @Test public void toUriWithTruncatedPercentEscape() throws Exception {
    assertThat(parse("http://host/%a").uri()).isEqualTo(new URI("http://host/%25a"));
    assertThat(parse("http://host/%").uri()).isEqualTo(new URI("http://host/%25"));
  }

  @Test public void fromJavaNetUrl() throws Exception {
    URL javaNetUrl = new URL("http://username:password@host/path?query#fragment");
    HttpUrl httpUrl = HttpUrl.get(javaNetUrl);
    assertThat(httpUrl.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @Test public void fromJavaNetUrlUnsupportedScheme() throws Exception {
    URL javaNetUrl = new URL("mailto:user@example.com");
    assertThat(HttpUrl.get(javaNetUrl)).isNull();
  }

  @Test public void fromUri() throws Exception {
    URI uri = new URI("http://username:password@host/path?query#fragment");
    HttpUrl httpUrl = HttpUrl.get(uri);
    assertThat(httpUrl.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @Test public void fromUriUnsupportedScheme() throws Exception {
    URI uri = new URI("mailto:user@example.com");
    assertThat(HttpUrl.get(uri)).isNull();
  }

  @Test public void fromUriPartial() throws Exception {
    URI uri = new URI("/path");
    assertThat(HttpUrl.get(uri)).isNull();
  }

  @Test public void composeQueryWithComponents() throws Exception {
    HttpUrl base = parse("http://host/");
    HttpUrl url = base.newBuilder().addQueryParameter("a+=& b", "c+=& d").build();
    assertThat(url.toString()).isEqualTo("http://host/?a%2B%3D%26%20b=c%2B%3D%26%20d");
    assertThat(url.queryParameterValue(0)).isEqualTo("c+=& d");
    assertThat(url.queryParameterName(0)).isEqualTo("a+=& b");
    assertThat(url.queryParameter("a+=& b")).isEqualTo("c+=& d");
    assertThat(url.queryParameterNames()).isEqualTo(Collections.singleton("a+=& b"));
    assertThat(url.queryParameterValues("a+=& b")).isEqualTo(singletonList("c+=& d"));
    assertThat(url.querySize()).isEqualTo(1);
    // Ambiguous! (Though working as designed.)
    assertThat(url.query()).isEqualTo("a+=& b=c+=& d");
    assertThat(url.encodedQuery()).isEqualTo("a%2B%3D%26%20b=c%2B%3D%26%20d");
  }

  @Test public void composeQueryWithEncodedComponents() throws Exception {
    HttpUrl base = parse("http://host/");
    HttpUrl url = base.newBuilder().addEncodedQueryParameter("a+=& b", "c+=& d").build();
    assertThat(url.toString()).isEqualTo("http://host/?a+%3D%26%20b=c+%3D%26%20d");
    assertThat(url.queryParameter("a =& b")).isEqualTo("c =& d");
  }

  @Test public void composeQueryRemoveQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .removeAllQueryParameters("a+=& b")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.queryParameter("a+=& b")).isNull();
  }

  @Test public void composeQueryRemoveEncodedQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .removeAllEncodedQueryParameters("a+=& b")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.queryParameter("a =& b")).isNull();
  }

  @Test public void composeQuerySetQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .setQueryParameter("a+=& b", "ef")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?a%2B%3D%26%20b=ef");
    assertThat(url.queryParameter("a+=& b")).isEqualTo("ef");
  }

  @Test public void composeQuerySetEncodedQueryParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .setEncodedQueryParameter("a+=& b", "ef")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?a+%3D%26%20b=ef");
    assertThat(url.queryParameter("a =& b")).isEqualTo("ef");
  }

  @Test public void composeQueryMultipleEncodedValuesForParameter() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .addQueryParameter("a+=& b", "e+=& f")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://host/?a%2B%3D%26%20b=c%2B%3D%26%20d&a%2B%3D%26%20b=e%2B%3D%26%20f");
    assertThat(url.querySize()).isEqualTo(2);
    assertThat(url.queryParameterNames()).isEqualTo(Collections.singleton("a+=& b"));
    assertThat(url.queryParameterValues("a+=& b")).containsExactly("c+=& d", "e+=& f");
  }

  @Test public void absentQueryIsZeroNameValuePairs() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query(null)
        .build();
    assertThat(url.querySize()).isEqualTo(0);
  }

  @Test public void emptyQueryIsSingleNameValuePairWithEmptyKey() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query("")
        .build();
    assertThat(url.querySize()).isEqualTo(1);
    assertThat(url.queryParameterName(0)).isEqualTo("");
    assertThat(url.queryParameterValue(0)).isNull();
  }

  @Test public void ampersandQueryIsTwoNameValuePairsWithEmptyKeys() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query("&")
        .build();
    assertThat(url.querySize()).isEqualTo(2);
    assertThat(url.queryParameterName(0)).isEqualTo("");
    assertThat(url.queryParameterValue(0)).isNull();
    assertThat(url.queryParameterName(1)).isEqualTo("");
    assertThat(url.queryParameterValue(1)).isNull();
  }

  @Test public void removeAllDoesNotRemoveQueryIfNoParametersWereRemoved() throws Exception {
    HttpUrl url = parse("http://host/").newBuilder()
        .query("")
        .removeAllQueryParameters("a")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?");
  }

  @Test public void queryParametersWithoutValues() throws Exception {
    HttpUrl url = parse("http://host/?foo&bar&baz");
    assertThat(url.querySize()).isEqualTo(3);
    assertThat(url.queryParameterNames()).containsExactly("foo", "bar", "baz");
    assertThat(url.queryParameterValue(0)).isNull();
    assertThat(url.queryParameterValue(1)).isNull();
    assertThat(url.queryParameterValue(2)).isNull();
    assertThat(url.queryParameterValues("foo")).isEqualTo(singletonList((String) null));
    assertThat(url.queryParameterValues("bar")).isEqualTo(singletonList((String) null));
    assertThat(url.queryParameterValues("baz")).isEqualTo(singletonList((String) null));
  }

  @Test public void queryParametersWithEmptyValues() throws Exception {
    HttpUrl url = parse("http://host/?foo=&bar=&baz=");
    assertThat(url.querySize()).isEqualTo(3);
    assertThat(url.queryParameterNames()).containsExactly("foo", "bar", "baz");
    assertThat(url.queryParameterValue(0)).isEqualTo("");
    assertThat(url.queryParameterValue(1)).isEqualTo("");
    assertThat(url.queryParameterValue(2)).isEqualTo("");
    assertThat(url.queryParameterValues("foo")).isEqualTo(singletonList(""));
    assertThat(url.queryParameterValues("bar")).isEqualTo(singletonList(""));
    assertThat(url.queryParameterValues("baz")).isEqualTo(singletonList(""));
  }

  @Test public void queryParametersWithRepeatedName() throws Exception {
    HttpUrl url = parse("http://host/?foo[]=1&foo[]=2&foo[]=3");
    assertThat(url.querySize()).isEqualTo(3);
    assertThat(url.queryParameterNames()).isEqualTo(Collections.singleton("foo[]"));
    assertThat(url.queryParameterValue(0)).isEqualTo("1");
    assertThat(url.queryParameterValue(1)).isEqualTo("2");
    assertThat(url.queryParameterValue(2)).isEqualTo("3");
    assertThat(url.queryParameterValues("foo[]")).containsExactly("1", "2", "3");
  }

  @Test public void queryParameterLookupWithNonCanonicalEncoding() throws Exception {
    HttpUrl url = parse("http://host/?%6d=m&+=%20");
    assertThat(url.queryParameterName(0)).isEqualTo("m");
    assertThat(url.queryParameterName(1)).isEqualTo(" ");
    assertThat(url.queryParameter("m")).isEqualTo("m");
    assertThat(url.queryParameter(" ")).isEqualTo(" ");
  }

  @Test public void parsedQueryDoesntIncludeFragment() {
    HttpUrl url = parse("http://host/?#fragment");
    assertThat(url.fragment()).isEqualTo("fragment");
    assertThat(url.query()).isEqualTo("");
    assertThat(url.encodedQuery()).isEqualTo("");
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
    assertThat(url.toString()).isEqualTo("http://%25:%25@host/%25?%25#%25");
    assertThat(url.newBuilder().build().toString())
        .isEqualTo("http://%25:%25@host/%25?%25#%25");
    assertThat(url.resolve("").toString()).isEqualTo("http://%25:%25@host/%25?%25");
  }

  /**
   * Although HttpUrl prefers percent-encodings in uppercase, it should preserve the exact structure
   * of the original encoding.
   */
  @Test public void rawEncodingRetained() throws Exception {
    String urlString = "http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D#%6d%6D";
    HttpUrl url = parse(urlString);
    assertThat(url.encodedUsername()).isEqualTo("%6d%6D");
    assertThat(url.encodedPassword()).isEqualTo("%6d%6D");
    assertThat(url.encodedPath()).isEqualTo("/%6d%6D");
    assertThat(url.encodedPathSegments()).containsExactly("%6d%6D");
    assertThat(url.encodedQuery()).isEqualTo("%6d%6D");
    assertThat(url.encodedFragment()).isEqualTo("%6d%6D");
    assertThat(url.toString()).isEqualTo(urlString);
    assertThat(url.newBuilder().build().toString()).isEqualTo(urlString);
    assertThat(url.resolve("").toString())
        .isEqualTo("http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D");
  }

  @Test public void clearFragment() throws Exception {
    HttpUrl url = parse("http://host/#fragment")
        .newBuilder()
        .fragment(null)
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.fragment()).isNull();
    assertThat(url.encodedFragment()).isNull();
  }

  @Test public void clearEncodedFragment() throws Exception {
    HttpUrl url = parse("http://host/#fragment")
        .newBuilder()
        .encodedFragment(null)
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.fragment()).isNull();
    assertThat(url.encodedFragment()).isNull();
  }

  @Test public void topPrivateDomain() {
    assertThat(parse("https://google.com").topPrivateDomain()).isEqualTo("google.com");
    assertThat(parse("https://adwords.google.co.uk").topPrivateDomain())
        .isEqualTo("google.co.uk");
    assertThat(parse("https://栃.栃木.jp").topPrivateDomain())
        .isEqualTo("xn--ewv.xn--4pvxs.jp");
    assertThat(parse("https://xn--ewv.xn--4pvxs.jp").topPrivateDomain())
        .isEqualTo("xn--ewv.xn--4pvxs.jp");

    assertThat(parse("https://co.uk").topPrivateDomain()).isNull();
    assertThat(parse("https://square").topPrivateDomain()).isNull();
    assertThat(parse("https://栃木.jp").topPrivateDomain()).isNull();
    assertThat(parse("https://xn--4pvxs.jp").topPrivateDomain()).isNull();
    assertThat(parse("https://localhost").topPrivateDomain()).isNull();
    assertThat(parse("https://127.0.0.1").topPrivateDomain()).isNull();
  }

  private void assertInvalid(String string, String exceptionMessage) {
    if (useGet) {
      try {
        parse(string);
        fail("Expected get of \"" + string + "\" to throw with: " + exceptionMessage);
      } catch (IllegalArgumentException e) {
        assertThat(e.getMessage()).isEqualTo(exceptionMessage);
      }
    } else {
      assertThat(parse(string)).overridingErrorMessage(string).isNull();
    }
  }
}
