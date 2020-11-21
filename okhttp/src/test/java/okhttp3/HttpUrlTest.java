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
import java.util.Collections;
import okhttp3.UrlComponentEncodingTester.Component;
import okhttp3.UrlComponentEncodingTester.Encoding;
import okhttp3.testing.PlatformRule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public final class HttpUrlTest {
  public final PlatformRule platform = new PlatformRule();

  HttpUrl parse(String url, boolean useGet) {
    return HttpUrl.parse(url);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void parseTrimsAsciiWhitespace(boolean useGet) throws Exception {
    HttpUrl expected = parse("http://host/", useGet);
    // Leading.
    assertThat(parse("http://host/\f\n\t \r", useGet)).isEqualTo(expected);
    // Trailing.
    assertThat(parse("\r\n\f \thttp://host/", useGet)).isEqualTo(expected);
    // Both.
    assertThat(parse(" http://host/ ", useGet)).isEqualTo(expected);
    // Both.
    assertThat(parse("    http://host/    ", useGet)).isEqualTo(expected);
    assertThat(parse("http://host/", useGet).resolve("   ")).isEqualTo(expected);
    assertThat(parse("http://host/", useGet).resolve("  .  ")).isEqualTo(expected);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void parseHostAsciiNonPrintable(boolean useGet) throws Exception {
    String host = "host\u0001";
    assertInvalid("http://" + host + "/", "Invalid URL host: \"host\u0001\"", useGet);
    // TODO make exception message escape non-printable characters
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void parseDoesNotTrimOtherWhitespaceCharacters(boolean useGet) throws Exception {
    // Whitespace characters list from Google's Guava team: http://goo.gl/IcR9RD
    // line tabulation
    assertThat(parse("http://h/\u000b", useGet).encodedPath()).isEqualTo("/%0B");
    // information separator 4
    assertThat(parse("http://h/\u001c", useGet).encodedPath()).isEqualTo("/%1C");
    // information separator 3
    assertThat(parse("http://h/\u001d", useGet).encodedPath()).isEqualTo("/%1D");
    // information separator 2
    assertThat(parse("http://h/\u001e", useGet).encodedPath()).isEqualTo("/%1E");
    // information separator 1
    assertThat(parse("http://h/\u001f", useGet).encodedPath()).isEqualTo("/%1F");
    // next line
    assertThat(parse("http://h/\u0085", useGet).encodedPath()).isEqualTo("/%C2%85");
    // non-breaking space
    assertThat(parse("http://h/\u00a0", useGet).encodedPath()).isEqualTo("/%C2%A0");
    // ogham space mark
    assertThat(parse("http://h/\u1680", useGet).encodedPath()).isEqualTo("/%E1%9A%80");
    // mongolian vowel separator
    assertThat(parse("http://h/\u180e", useGet).encodedPath()).isEqualTo("/%E1%A0%8E");
    // en quad
    assertThat(parse("http://h/\u2000", useGet).encodedPath()).isEqualTo("/%E2%80%80");
    // em quad
    assertThat(parse("http://h/\u2001", useGet).encodedPath()).isEqualTo("/%E2%80%81");
    // en space
    assertThat(parse("http://h/\u2002", useGet).encodedPath()).isEqualTo("/%E2%80%82");
    // em space
    assertThat(parse("http://h/\u2003", useGet).encodedPath()).isEqualTo("/%E2%80%83");
    // three-per-em space
    assertThat(parse("http://h/\u2004", useGet).encodedPath()).isEqualTo("/%E2%80%84");
    // four-per-em space
    assertThat(parse("http://h/\u2005", useGet).encodedPath()).isEqualTo("/%E2%80%85");
    // six-per-em space
    assertThat(parse("http://h/\u2006", useGet).encodedPath()).isEqualTo("/%E2%80%86");
    // figure space
    assertThat(parse("http://h/\u2007", useGet).encodedPath()).isEqualTo("/%E2%80%87");
    // punctuation space
    assertThat(parse("http://h/\u2008", useGet).encodedPath()).isEqualTo("/%E2%80%88");
    // thin space
    assertThat(parse("http://h/\u2009", useGet).encodedPath()).isEqualTo("/%E2%80%89");
    // hair space
    assertThat(parse("http://h/\u200a", useGet).encodedPath()).isEqualTo("/%E2%80%8A");
    // zero-width space
    assertThat(parse("http://h/\u200b", useGet).encodedPath()).isEqualTo("/%E2%80%8B");
    // zero-width non-joiner
    assertThat(parse("http://h/\u200c", useGet).encodedPath()).isEqualTo("/%E2%80%8C");
    // zero-width joiner
    assertThat(parse("http://h/\u200d", useGet).encodedPath()).isEqualTo("/%E2%80%8D");
    // left-to-right mark
    assertThat(parse("http://h/\u200e", useGet).encodedPath()).isEqualTo("/%E2%80%8E");
    // right-to-left mark
    assertThat(parse("http://h/\u200f", useGet).encodedPath()).isEqualTo("/%E2%80%8F");
    // line separator
    assertThat(parse("http://h/\u2028", useGet).encodedPath()).isEqualTo("/%E2%80%A8");
    // paragraph separator
    assertThat(parse("http://h/\u2029", useGet).encodedPath()).isEqualTo("/%E2%80%A9");
    // narrow non-breaking space
    assertThat(parse("http://h/\u202f", useGet).encodedPath()).isEqualTo("/%E2%80%AF");
    // medium mathematical space
    assertThat(parse("http://h/\u205f", useGet).encodedPath()).isEqualTo("/%E2%81%9F");
    // ideographic space
    assertThat(parse("http://h/\u3000", useGet).encodedPath()).isEqualTo("/%E3%80%80");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void scheme(boolean useGet)
      throws Exception {
    assertThat(parse("http://host/", useGet)).isEqualTo(parse("http://host/", useGet));
    assertThat(parse("Http://host/", useGet)).isEqualTo(parse("http://host/", useGet));
    assertThat(parse("http://host/", useGet)).isEqualTo(parse("http://host/", useGet));
    assertThat(parse("HTTP://host/", useGet)).isEqualTo(parse("http://host/", useGet));
    assertThat(parse("https://host/", useGet)).isEqualTo(parse("https://host/", useGet));
    assertThat(parse("HTTPS://host/", useGet)).isEqualTo(parse("https://host/", useGet));

    assertInvalid("image640://480.png", "Expected URL scheme 'http' or 'https' but was 'image640'",
        useGet);
    assertInvalid("httpp://host/", "Expected URL scheme 'http' or 'https' but was 'httpp'", useGet);
    assertInvalid("0ttp://host/", "Expected URL scheme 'http' or 'https' but no colon was found",
        useGet);
    assertInvalid("ht+tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht+tp'", useGet);
    assertInvalid("ht.tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht.tp'", useGet);
    assertInvalid("ht-tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht-tp'", useGet);
    assertInvalid("ht1tp://host/", "Expected URL scheme 'http' or 'https' but was 'ht1tp'", useGet);
    assertInvalid("httpss://host/", "Expected URL scheme 'http' or 'https' but was 'httpss'",
        useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void parseNoScheme(boolean useGet) throws Exception {
    assertInvalid("//host", "Expected URL scheme 'http' or 'https' but no colon was found", useGet);
    assertInvalid("/path", "Expected URL scheme 'http' or 'https' but no colon was found", useGet);
    assertInvalid("path", "Expected URL scheme 'http' or 'https' but no colon was found", useGet);
    assertInvalid("?query", "Expected URL scheme 'http' or 'https' but no colon was found", useGet);
    assertInvalid("#fragment", "Expected URL scheme 'http' or 'https' but no colon was found",
        useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void newBuilderResolve(boolean useGet) throws Exception {
    // Non-exhaustive tests because implementation is the same as resolve.
    HttpUrl base = parse("http://host/a/b", useGet);
    assertThat(base.newBuilder("https://host2").build()).isEqualTo(
        parse("https://host2/", useGet));
    assertThat(base.newBuilder("//host2").build()).isEqualTo(parse("http://host2/", useGet));
    assertThat(base.newBuilder("/path").build()).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.newBuilder("path").build()).isEqualTo(parse("http://host/a/path", useGet));
    assertThat(base.newBuilder("?query").build()).isEqualTo(
        parse("http://host/a/b?query", useGet));
    assertThat(base.newBuilder("#fragment").build())
        .isEqualTo(parse("http://host/a/b#fragment", useGet));
    assertThat(base.newBuilder("").build()).isEqualTo(parse("http://host/a/b", useGet));
    assertThat(base.newBuilder("ftp://b")).isNull();
    assertThat(base.newBuilder("ht+tp://b")).isNull();
    assertThat(base.newBuilder("ht-tp://b")).isNull();
    assertThat(base.newBuilder("ht.tp://b")).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void redactedUrl(boolean useGet) {
    HttpUrl baseWithPasswordAndUsername =
        parse("http://username:password@host/a/b#fragment", useGet);
    HttpUrl baseWithUsernameOnly = parse("http://username@host/a/b#fragment", useGet);
    HttpUrl baseWithPasswordOnly = parse("http://password@host/a/b#fragment", useGet);
    assertThat(baseWithPasswordAndUsername.redact()).isEqualTo("http://host/...");
    assertThat(baseWithUsernameOnly.redact()).isEqualTo("http://host/...");
    assertThat(baseWithPasswordOnly.redact()).isEqualTo("http://host/...");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void resolveNoScheme(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b", useGet);
    assertThat(base.resolve("//host2")).isEqualTo(parse("http://host2/", useGet));
    assertThat(base.resolve("/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("path")).isEqualTo(parse("http://host/a/path", useGet));
    assertThat(base.resolve("?query")).isEqualTo(parse("http://host/a/b?query", useGet));
    assertThat(base.resolve("#fragment")).isEqualTo(parse("http://host/a/b#fragment", useGet));
    assertThat(base.resolve("")).isEqualTo(parse("http://host/a/b", useGet));
    assertThat(base.resolve("\\path")).isEqualTo(parse("http://host/path", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void resolveUnsupportedScheme(boolean useGet) throws Exception {
    HttpUrl base = parse("http://a/", useGet);
    assertThat(base.resolve("ftp://b")).isNull();
    assertThat(base.resolve("ht+tp://b")).isNull();
    assertThat(base.resolve("ht-tp://b")).isNull();
    assertThat(base.resolve("ht.tp://b")).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void resolveSchemeLikePath(boolean useGet) throws Exception {
    HttpUrl base = parse("http://a/", useGet);
    assertThat(base.resolve("http//b/")).isEqualTo(parse("http://a/http//b/", useGet));
    assertThat(base.resolve("ht+tp//b/")).isEqualTo(parse("http://a/ht+tp//b/", useGet));
    assertThat(base.resolve("ht-tp//b/")).isEqualTo(parse("http://a/ht-tp//b/", useGet));
    assertThat(base.resolve("ht.tp//b/")).isEqualTo(parse("http://a/ht.tp//b/", useGet));
  }

  /**
   * https://tools.ietf.org/html/rfc3986#section-5.4.1
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void rfc3886NormalExamples(
      boolean useGet) {
    HttpUrl url = parse("http://a/b/c/d;p?q", useGet);
    // No 'g:' scheme in HttpUrl.
    assertThat(url.resolve("g:h")).isNull();
    assertThat(url.resolve("g")).isEqualTo(parse("http://a/b/c/g", useGet));
    assertThat(url.resolve("./g")).isEqualTo(parse("http://a/b/c/g", useGet));
    assertThat(url.resolve("g/")).isEqualTo(parse("http://a/b/c/g/", useGet));
    assertThat(url.resolve("/g")).isEqualTo(parse("http://a/g", useGet));
    assertThat(url.resolve("//g")).isEqualTo(parse("http://g", useGet));
    assertThat(url.resolve("?y")).isEqualTo(parse("http://a/b/c/d;p?y", useGet));
    assertThat(url.resolve("g?y")).isEqualTo(parse("http://a/b/c/g?y", useGet));
    assertThat(url.resolve("#s")).isEqualTo(parse("http://a/b/c/d;p?q#s", useGet));
    assertThat(url.resolve("g#s")).isEqualTo(parse("http://a/b/c/g#s", useGet));
    assertThat(url.resolve("g?y#s")).isEqualTo(parse("http://a/b/c/g?y#s", useGet));
    assertThat(url.resolve(";x")).isEqualTo(parse("http://a/b/c/;x", useGet));
    assertThat(url.resolve("g;x")).isEqualTo(parse("http://a/b/c/g;x", useGet));
    assertThat(url.resolve("g;x?y#s")).isEqualTo(parse("http://a/b/c/g;x?y#s", useGet));
    assertThat(url.resolve("")).isEqualTo(parse("http://a/b/c/d;p?q", useGet));
    assertThat(url.resolve(".")).isEqualTo(parse("http://a/b/c/", useGet));
    assertThat(url.resolve("./")).isEqualTo(parse("http://a/b/c/", useGet));
    assertThat(url.resolve("..")).isEqualTo(parse("http://a/b/", useGet));
    assertThat(url.resolve("../")).isEqualTo(parse("http://a/b/", useGet));
    assertThat(url.resolve("../g")).isEqualTo(parse("http://a/b/g", useGet));
    assertThat(url.resolve("../..")).isEqualTo(parse("http://a/", useGet));
    assertThat(url.resolve("../../")).isEqualTo(parse("http://a/", useGet));
    assertThat(url.resolve("../../g")).isEqualTo(parse("http://a/g", useGet));
  }

  /**
   * https://tools.ietf.org/html/rfc3986#section-5.4.2
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void rfc3886AbnormalExamples(boolean useGet) {
    HttpUrl url = parse("http://a/b/c/d;p?q", useGet);
    assertThat(url.resolve("../../../g")).isEqualTo(parse("http://a/g", useGet));
    assertThat(url.resolve("../../../../g")).isEqualTo(parse("http://a/g", useGet));
    assertThat(url.resolve("/./g")).isEqualTo(parse("http://a/g", useGet));
    assertThat(url.resolve("/../g")).isEqualTo(parse("http://a/g", useGet));
    assertThat(url.resolve("g.")).isEqualTo(parse("http://a/b/c/g.", useGet));
    assertThat(url.resolve(".g")).isEqualTo(parse("http://a/b/c/.g", useGet));
    assertThat(url.resolve("g..")).isEqualTo(parse("http://a/b/c/g..", useGet));
    assertThat(url.resolve("..g")).isEqualTo(parse("http://a/b/c/..g", useGet));
    assertThat(url.resolve("./../g")).isEqualTo(parse("http://a/b/g", useGet));
    assertThat(url.resolve("./g/.")).isEqualTo(parse("http://a/b/c/g/", useGet));
    assertThat(url.resolve("g/./h")).isEqualTo(parse("http://a/b/c/g/h", useGet));
    assertThat(url.resolve("g/../h")).isEqualTo(parse("http://a/b/c/h", useGet));
    assertThat(url.resolve("g;x=1/./y")).isEqualTo(parse("http://a/b/c/g;x=1/y", useGet));
    assertThat(url.resolve("g;x=1/../y")).isEqualTo(parse("http://a/b/c/y", useGet));
    assertThat(url.resolve("g?y/./x")).isEqualTo(parse("http://a/b/c/g?y/./x", useGet));
    assertThat(url.resolve("g?y/../x")).isEqualTo(parse("http://a/b/c/g?y/../x", useGet));
    assertThat(url.resolve("g#s/./x")).isEqualTo(parse("http://a/b/c/g#s/./x", useGet));
    assertThat(url.resolve("g#s/../x")).isEqualTo(parse("http://a/b/c/g#s/../x", useGet));
    // "http:g" also okay.
    assertThat(url.resolve("http:g")).isEqualTo(parse("http://a/b/c/g", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void parseAuthoritySlashCountDoesntMatter(boolean useGet) throws Exception {
    assertThat(parse("http:host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:/host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:\\host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http://host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:\\/host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:/\\host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:\\\\host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:///host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:\\//host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:/\\/host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http://\\host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:\\\\/host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:/\\\\host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:\\\\\\host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http:////host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void resolveAuthoritySlashCountDoesntMatterWithDifferentScheme(boolean useGet)
      throws Exception {
    HttpUrl base = parse("https://a/b/c", useGet);
    assertThat(base.resolve("http:host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:/host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http://host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\/host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:/\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:///host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\//host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:/\\/host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http://\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\\\/host/path")).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(base.resolve("http:/\\\\host/path")).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\\\\\host/path")).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(base.resolve("http:////host/path")).isEqualTo(parse("http://host/path", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void resolveAuthoritySlashCountMattersWithSameScheme(boolean useGet) throws Exception {
    HttpUrl base = parse("http://a/b/c", useGet);
    assertThat(base.resolve("http:host/path")).isEqualTo(parse("http://a/b/host/path", useGet));
    assertThat(base.resolve("http:/host/path")).isEqualTo(parse("http://a/host/path", useGet));
    assertThat(base.resolve("http:\\host/path")).isEqualTo(parse("http://a/host/path", useGet));
    assertThat(base.resolve("http://host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\/host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:/\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:///host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\//host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:/\\/host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http://\\host/path")).isEqualTo(parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\\\/host/path")).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(base.resolve("http:/\\\\host/path")).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(base.resolve("http:\\\\\\host/path")).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(base.resolve("http:////host/path")).isEqualTo(parse("http://host/path", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void username(boolean useGet) throws Exception {
    assertThat(parse("http://@host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http://user@host/path", useGet)).isEqualTo(
        parse("http://user@host/path", useGet));
  }

  /**
   * Given multiple '@' characters, the last one is the delimiter.
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void authorityWithMultipleAtSigns(boolean useGet) throws Exception {
    HttpUrl httpUrl = parse("http://foo@bar@baz/path", useGet);
    assertThat(httpUrl.username()).isEqualTo("foo@bar");
    assertThat(httpUrl.password()).isEqualTo("");
    assertThat(httpUrl).isEqualTo(parse("http://foo%40bar@baz/path", useGet));
  }

  /**
   * Given multiple ':' characters, the first one is the delimiter.
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void authorityWithMultipleColons(boolean useGet) throws Exception {
    HttpUrl httpUrl = parse("http://foo:pass1@bar:pass2@baz/path", useGet);
    assertThat(httpUrl.username()).isEqualTo("foo");
    assertThat(httpUrl.password()).isEqualTo("pass1@bar:pass2");
    assertThat(httpUrl).isEqualTo(parse("http://foo:pass1%40bar%3Apass2@baz/path", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void usernameAndPassword(boolean useGet) throws Exception {
    assertThat(parse("http://username:password@host/path", useGet))
        .isEqualTo(parse("http://username:password@host/path", useGet));
    assertThat(parse("http://username:@host/path", useGet))
        .isEqualTo(parse("http://username@host/path", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void passwordWithEmptyUsername(boolean useGet) throws Exception {
    // Chrome doesn't mind, but Firefox rejects URLs with empty usernames and non-empty passwords.
    assertThat(parse("http://:@host/path", useGet)).isEqualTo(
        parse("http://host/path", useGet));
    assertThat(parse("http://:password@@host/path", useGet).encodedPassword())
        .isEqualTo("password%40");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void unprintableCharactersArePercentEncoded(boolean useGet) throws Exception {
    assertThat(parse("http://host/\u0000", useGet).encodedPath()).isEqualTo("/%00");
    assertThat(parse("http://host/\u0008", useGet).encodedPath()).isEqualTo("/%08");
    assertThat(parse("http://host/\ufffd", useGet).encodedPath()).isEqualTo("/%EF%BF%BD");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void usernameCharacters(boolean useGet) throws Exception {
    UrlComponentEncodingTester.newInstance()
        .override(Encoding.PERCENT, '[', ']', '{', '}', '|', '^', '\'', ';', '=', '@')
        .override(Encoding.SKIP, ':', '/', '\\', '?', '#')
        .escapeForUri('%')
        .test(Component.USER);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void passwordCharacters(boolean useGet) throws Exception {
    UrlComponentEncodingTester.newInstance()
        .override(Encoding.PERCENT, '[', ']', '{', '}', '|', '^', '\'', ':', ';', '=', '@')
        .override(Encoding.SKIP, '/', '\\', '?', '#')
        .escapeForUri('%')
        .test(Component.PASSWORD);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostContainsIllegalCharacter(boolean useGet) throws Exception {
    assertInvalid("http://\n/", "Invalid URL host: \"\n\"", useGet);
    assertInvalid("http:// /", "Invalid URL host: \" \"", useGet);
    assertInvalid("http://%20/", "Invalid URL host: \"%20\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameLowercaseCharactersMappedDirectly(boolean useGet) throws Exception {
    assertThat(parse("http://abcd", useGet).host()).isEqualTo("abcd");
    assertThat(parse("http://σ", useGet).host()).isEqualTo("xn--4xa");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameUppercaseCharactersConvertedToLowercase(boolean useGet) throws Exception {
    assertThat(parse("http://ABCD", useGet).host()).isEqualTo("abcd");
    assertThat(parse("http://Σ", useGet).host()).isEqualTo("xn--4xa");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameIgnoredCharacters(boolean useGet) throws Exception {
    // The soft hyphen (­) should be ignored.
    assertThat(parse("http://AB\u00adCD", useGet).host()).isEqualTo("abcd");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameMultipleCharacterMapping(boolean useGet) throws Exception {
    // Map the single character telephone symbol (℡) to the string "tel".
    assertThat(parse("http://\u2121", useGet).host()).isEqualTo("tel");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameMappingLastMappedCodePoint(boolean useGet) throws Exception {
    assertThat(parse("http://\uD87E\uDE1D", useGet).host()).isEqualTo("xn--pu5l");
  }

  @Disabled("The java.net.IDN implementation doesn't ignore characters that it should.")
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameMappingLastIgnoredCodePoint(boolean useGet) throws Exception {
    assertThat(parse("http://ab\uDB40\uDDEFcd", useGet).host()).isEqualTo("abcd");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameMappingLastDisallowedCodePoint(boolean useGet) throws Exception {
    assertInvalid("http://\uDBFF\uDFFF", "Invalid URL host: \"\uDBFF\uDFFF\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameUri(boolean useGet) throws Exception {
    // Host names are special:
    //
    //  * Several characters are forbidden and must throw exceptions if used.
    //  * They don't use percent escaping at all.
    //  * They use punycode for internationalization.
    //  * URI is much more strict that HttpUrl or URL on what's accepted.
    //
    // HttpUrl is quite lenient with what characters it accepts. In particular, characters like '{'
    // and '"' are permitted but unlikely to occur in real-world URLs. Unfortunately we can't just
    // lock it down due to URL templating: "http://{env}.{dc}.example.com".
    UrlComponentEncodingTester.newInstance()
        .nonPrintableAscii(Encoding.FORBIDDEN)
        .nonAscii(Encoding.FORBIDDEN)
        .override(Encoding.FORBIDDEN, '\t', '\n', '\f', '\r', ' ')
        .override(Encoding.FORBIDDEN, '#', '%', '/', ':', '?', '@', '[', '\\', ']')
        .override(Encoding.IDENTITY, '\"', '<', '>', '^', '`', '{', '|', '}')
        .stripForUri('\"', '<', '>', '^', '`', '{', '|', '}')
        .test(Component.HOST);
  }

  /**
   * This one's ugly: the HttpUrl's host is non-empty, but the URI's host is null.
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostContainsOnlyStrippedCharacters(boolean useGet) throws Exception {
    HttpUrl url = parse("http://>/", useGet);
    assertThat(url.host()).isEqualTo(">");
    assertThat(url.uri().getHost()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6(boolean useGet) throws Exception {
    // Square braces are absent from host()...
    assertThat(parse("http://[::1]/", useGet).host()).isEqualTo("::1");

    // ... but they're included in toString().
    assertThat(parse("http://[::1]/", useGet).toString()).isEqualTo("http://[::1]/");

    // IPv6 colons don't interfere with port numbers or passwords.
    assertThat(parse("http://[::1]:8080/", useGet).port()).isEqualTo(8080);
    assertThat(parse("http://user:password@[::1]/", useGet).password()).isEqualTo("password");
    assertThat(parse("http://user:password@[::1]:8080/", useGet).host()).isEqualTo("::1");

    // Permit the contents of IPv6 addresses to be percent-encoded...
    assertThat(parse("http://[%3A%3A%31]/", useGet).host()).isEqualTo("::1");

    // Including the Square braces themselves! (This is what Chrome does.)
    assertThat(parse("http://%5B%3A%3A1%5D/", useGet).host()).isEqualTo("::1");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressDifferentFormats(boolean useGet) throws Exception {
    // Multiple representations of the same address; see http://tools.ietf.org/html/rfc5952.
    String a3 = "2001:db8::1:0:0:1";
    assertThat(parse("http://[2001:db8:0:0:1:0:0:1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:0db8:0:0:1:0:0:1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8::1:0:0:1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8::0:1:0:0:1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:0db8::1:0:0:1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8:0:0:1::1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:db8:0000:0:1::1]", useGet).host()).isEqualTo(a3);
    assertThat(parse("http://[2001:DB8:0:0:1::1]", useGet).host()).isEqualTo(a3);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressLeadingCompression(boolean useGet) throws Exception {
    assertThat(parse("http://[::0001]", useGet).host()).isEqualTo("::1");
    assertThat(parse("http://[0000::0001]", useGet).host()).isEqualTo("::1");
    assertThat(parse("http://[0000:0000:0000:0000:0000:0000:0000:0001]", useGet).host())
        .isEqualTo("::1");
    assertThat(parse("http://[0000:0000:0000:0000:0000:0000::0001]", useGet).host())
        .isEqualTo("::1");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressTrailingCompression(boolean useGet) throws Exception {
    assertThat(parse("http://[0001:0000::]", useGet).host()).isEqualTo("1::");
    assertThat(parse("http://[0001::0000]", useGet).host()).isEqualTo("1::");
    assertThat(parse("http://[0001::]", useGet).host()).isEqualTo("1::");
    assertThat(parse("http://[1::]", useGet).host()).isEqualTo("1::");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressTooManyDigitsInGroup(boolean useGet) throws Exception {
    assertInvalid("http://[00000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[00000:0000:0000:0000:0000:0000:0000:0001]\"", useGet);
    assertInvalid("http://[::00001]", "Invalid URL host: \"[::00001]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressMisplacedColons(boolean useGet) throws Exception {
    assertInvalid("http://[:0000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[:0000:0000:0000:0000:0000:0000:0000:0001]\"", useGet);
    assertInvalid("http://[:::0000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[:::0000:0000:0000:0000:0000:0000:0000:0001]\"", useGet);
    assertInvalid("http://[:1]", "Invalid URL host: \"[:1]\"", useGet);
    assertInvalid("http://[:::1]", "Invalid URL host: \"[:::1]\"", useGet);
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0001:]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0001:]\"", useGet);
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0001:]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001:]\"", useGet);
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0001::]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001::]\"", useGet);
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0001:::]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001:::]\"", useGet);
    assertInvalid("http://[1:]", "Invalid URL host: \"[1:]\"", useGet);
    assertInvalid("http://[1:::]", "Invalid URL host: \"[1:::]\"", useGet);
    assertInvalid("http://[1:::1]", "Invalid URL host: \"[1:::1]\"", useGet);
    assertInvalid("http://[0000:0000:0000:0000::0000:0000:0000:0001]",
        "Invalid URL host: \"[0000:0000:0000:0000::0000:0000:0000:0001]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressTooManyGroups(boolean useGet) throws Exception {
    assertInvalid("http://[0000:0000:0000:0000:0000:0000:0000:0000:0001]",
        "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0000:0001]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressTooMuchCompression(boolean useGet) throws Exception {
    assertInvalid("http://[0000::0000:0000:0000:0000::0001]",
        "Invalid URL host: \"[0000::0000:0000:0000:0000::0001]\"", useGet);
    assertInvalid("http://[::0000:0000:0000:0000::0001]",
        "Invalid URL host: \"[::0000:0000:0000:0000::0001]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6ScopedAddress(boolean useGet) throws Exception {
    // java.net.InetAddress parses scoped addresses. These aren't valid in URLs.
    assertInvalid("http://[::1%2544]", "Invalid URL host: \"[::1%2544]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6AddressTooManyLeadingZeros(boolean useGet) throws Exception {
    // Guava's been buggy on this case. https://github.com/google/guava/issues/3116
    assertInvalid("http://[2001:db8:0:0:1:0:0:00001]",
        "Invalid URL host: \"[2001:db8:0:0:1:0:0:00001]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6WithIpv4Suffix(boolean useGet) throws Exception {
    assertThat(parse("http://[::1:255.255.255.255]/", useGet).host()).isEqualTo("::1:ffff:ffff");
    assertThat(parse("http://[0:0:0:0:0:1:0.0.0.0]/", useGet).host()).isEqualTo("::1:0:0");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6WithIpv4SuffixWithOctalPrefix(boolean useGet) throws Exception {
    // Chrome interprets a leading '0' as octal; Firefox rejects them. (We reject them.)
    assertInvalid("http://[0:0:0:0:0:1:0.0.0.000000]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.0.0.000000]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:0.010.0.010]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.010.0.010]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:0.0.0.000001]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.0.0.000001]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6WithIpv4SuffixWithHexadecimalPrefix(boolean useGet) throws Exception {
    // Chrome interprets a leading '0x' as hexadecimal; Firefox rejects them. (We reject them.)
    assertInvalid("http://[0:0:0:0:0:1:0.0x10.0.0x10]/",
        "Invalid URL host: \"[0:0:0:0:0:1:0.0x10.0.0x10]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6WithMalformedIpv4Suffix(boolean useGet) throws Exception {
    assertInvalid("http://[0:0:0:0:0:1:0.0:0.0]/", "Invalid URL host: \"[0:0:0:0:0:1:0.0:0.0]\"",
        useGet);
    assertInvalid("http://[0:0:0:0:0:1:0.0-0.0]/", "Invalid URL host: \"[0:0:0:0:0:1:0.0-0.0]\"",
        useGet);
    assertInvalid("http://[0:0:0:0:0:1:.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:.255.255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:255..255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255..255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:255.255..255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255.255..255]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:0:1:255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:0:1:255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:256.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:256.255.255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:ff.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:ff.255.255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:0:1:255.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:0:1:255.255.255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:1:255.255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:1:255.255.255.255]\"", useGet);
    assertInvalid("http://[0:0:0:0:1:0.0.0.0:1]/", "Invalid URL host: \"[0:0:0:0:1:0.0.0.0:1]\"",
        useGet);
    assertInvalid("http://[0:0.0.0.0:1:0:0:0:0:1]/",
        "Invalid URL host: \"[0:0.0.0.0:1:0:0:0:0:1]\"", useGet);
    assertInvalid("http://[0.0.0.0:0:0:0:0:0:1]/", "Invalid URL host: \"[0.0.0.0:0:0:0:0:0:1]\"",
        useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6WithIncompleteIpv4Suffix(boolean useGet) throws Exception {
    // To Chrome & Safari these are well-formed; Firefox disagrees. (We're consistent with Firefox).
    assertInvalid("http://[0:0:0:0:0:1:255.255.255.]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255.255.255.]\"", useGet);
    assertInvalid("http://[0:0:0:0:0:1:255.255.255]/",
        "Invalid URL host: \"[0:0:0:0:0:1:255.255.255]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6Malformed(boolean useGet) throws Exception {
    assertInvalid("http://[::g]/", "Invalid URL host: \"[::g]\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv6CanonicalForm(boolean useGet) throws Exception {
    assertThat(parse("http://[abcd:ef01:2345:6789:abcd:ef01:2345:6789]/", useGet).host())
        .isEqualTo("abcd:ef01:2345:6789:abcd:ef01:2345:6789");
    assertThat(parse("http://[a:0:0:0:b:0:0:0]/", useGet).host()).isEqualTo("a::b:0:0:0");
    assertThat(parse("http://[a:b:0:0:c:0:0:0]/", useGet).host()).isEqualTo("a:b:0:0:c::");
    assertThat(parse("http://[a:b:0:0:0:c:0:0]/", useGet).host()).isEqualTo("a:b::c:0:0");
    assertThat(parse("http://[a:0:0:0:b:0:0:0]/", useGet).host()).isEqualTo("a::b:0:0:0");
    assertThat(parse("http://[0:0:0:a:b:0:0:0]/", useGet).host()).isEqualTo("::a:b:0:0:0");
    assertThat(parse("http://[0:0:0:a:0:0:0:b]/", useGet).host()).isEqualTo("::a:0:0:0:b");
    assertThat(parse("http://[0:a:b:c:d:e:f:1]/", useGet).host()).isEqualTo("0:a:b:c:d:e:f:1");
    assertThat(parse("http://[a:b:c:d:e:f:1:0]/", useGet).host()).isEqualTo("a:b:c:d:e:f:1:0");
    assertThat(parse("http://[FF01:0:0:0:0:0:0:101]/", useGet).host()).isEqualTo("ff01::101");
    assertThat(parse("http://[2001:db8::1]/", useGet).host()).isEqualTo("2001:db8::1");
    assertThat(parse("http://[2001:db8:0:0:0:0:2:1]/", useGet).host()).isEqualTo("2001:db8::2:1");
    assertThat(parse("http://[2001:db8:0:1:1:1:1:1]/", useGet).host())
        .isEqualTo("2001:db8:0:1:1:1:1:1");
    assertThat(parse("http://[2001:db8:0:0:1:0:0:1]/", useGet).host())
        .isEqualTo("2001:db8::1:0:0:1");
    assertThat(parse("http://[2001:0:0:1:0:0:0:1]/", useGet).host()).isEqualTo("2001:0:0:1::1");
    assertThat(parse("http://[1:0:0:0:0:0:0:0]/", useGet).host()).isEqualTo("1::");
    assertThat(parse("http://[0:0:0:0:0:0:0:1]/", useGet).host()).isEqualTo("::1");
    assertThat(parse("http://[0:0:0:0:0:0:0:0]/", useGet).host()).isEqualTo("::");
    assertThat(parse("http://[::ffff:c0a8:1fe]/", useGet).host()).isEqualTo("192.168.1.254");
  }

  /**
   * The builder permits square braces but does not require them.
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void hostIpv6Builder(
      boolean useGet) throws Exception {
    HttpUrl base = parse("http://example.com/", useGet);
    assertThat(base.newBuilder().host("[::1]").build().toString())
        .isEqualTo("http://[::1]/");
    assertThat(base.newBuilder().host("[::0001]").build().toString())
        .isEqualTo("http://[::1]/");
    assertThat(base.newBuilder().host("::1").build().toString()).isEqualTo("http://[::1]/");
    assertThat(base.newBuilder().host("::0001").build().toString())
        .isEqualTo("http://[::1]/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostIpv4CanonicalForm(boolean useGet) throws Exception {
    assertThat(parse("http://255.255.255.255/", useGet).host()).isEqualTo("255.255.255.255");
    assertThat(parse("http://1.2.3.4/", useGet).host()).isEqualTo("1.2.3.4");
    assertThat(parse("http://0.0.0.0/", useGet).host()).isEqualTo("0.0.0.0");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostWithTrailingDot(boolean useGet) throws Exception {
    assertThat(parse("http://host./", useGet).host()).isEqualTo("host.");
  }

  /**
   * Strip unexpected characters when converting to URI (which is more strict).
   * https://github.com/square/okhttp/issues/5667
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostToUriStripsCharacters(boolean useGet) throws Exception {
    HttpUrl httpUrl = HttpUrl.get("http://example\".com/");
    assertThat(httpUrl.uri().toString()).isEqualTo("http://example.com/");
  }

  /**
   * Confirm that URI retains other characters. https://github.com/square/okhttp/issues/5236
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostToUriStripsCharacters2(boolean useGet) throws Exception {
    HttpUrl httpUrl = HttpUrl.get("http://${tracker}/");
    assertThat(httpUrl.uri().toString()).isEqualTo("http://$tracker/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void port(boolean useGet)
      throws Exception {
    assertThat(parse("http://host:80/", useGet)).isEqualTo(parse("http://host/", useGet));
    assertThat(parse("http://host:99/", useGet)).isEqualTo(
        parse("http://host:99/", useGet));
    assertThat(parse("http://host:/", useGet)).isEqualTo(parse("http://host/", useGet));
    assertThat(parse("http://host:65535/", useGet).port()).isEqualTo(65535);
    assertInvalid("http://host:0/", "Invalid URL port: \"0\"", useGet);
    assertInvalid("http://host:65536/", "Invalid URL port: \"65536\"", useGet);
    assertInvalid("http://host:-1/", "Invalid URL port: \"-1\"", useGet);
    assertInvalid("http://host:a/", "Invalid URL port: \"a\"", useGet);
    assertInvalid("http://host:%39%39/", "Invalid URL port: \"%39%39\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void pathCharacters(boolean useGet) throws Exception {
    UrlComponentEncodingTester.newInstance()
        .override(Encoding.PERCENT, '^', '{', '}', '|')
        .override(Encoding.SKIP, '\\', '?', '#')
        .escapeForUri('%', '[', ']')
        .test(Component.PATH);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryCharacters(boolean useGet) throws Exception {
    UrlComponentEncodingTester.newInstance()
        .override(Encoding.IDENTITY, '?', '`')
        .override(Encoding.PERCENT, '\'')
        .override(Encoding.SKIP, '#', '+')
        .escapeForUri('%', '\\', '^', '`', '{', '|', '}')
        .test(Component.QUERY);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryValueCharacters(boolean useGet) throws Exception {
    UrlComponentEncodingTester.newInstance()
        .override(Encoding.IDENTITY, '?', '`')
        .override(Encoding.PERCENT, '\'')
        .override(Encoding.SKIP, '#', '+')
        .escapeForUri('%', '\\', '^', '`', '{', '|', '}')
        .test(Component.QUERY_VALUE);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fragmentCharacters(boolean useGet) throws Exception {
    UrlComponentEncodingTester.newInstance()
        .override(Encoding.IDENTITY, ' ', '"', '#', '<', '>', '?', '`')
        .escapeForUri('%', ' ', '"', '#', '<', '>', '\\', '^', '`', '{', '|', '}')
        .nonAscii(Encoding.IDENTITY)
        .test(Component.FRAGMENT);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fragmentNonAscii(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/#Σ", useGet);
    assertThat(url.toString()).isEqualTo("http://host/#Σ");
    assertThat(url.fragment()).isEqualTo("Σ");
    assertThat(url.encodedFragment()).isEqualTo("Σ");
    assertThat(url.uri().toString()).isEqualTo("http://host/#Σ");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fragmentNonAsciiThatOffendsJavaNetUri(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/#\u0080", useGet);
    assertThat(url.toString()).isEqualTo("http://host/#\u0080");
    assertThat(url.fragment()).isEqualTo("\u0080");
    assertThat(url.encodedFragment()).isEqualTo("\u0080");
    // Control characters may be stripped!
    assertThat(url.uri()).isEqualTo(new URI("http://host/#"));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fragmentPercentEncodedNonAscii(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/#%C2%80", useGet);
    assertThat(url.toString()).isEqualTo("http://host/#%C2%80");
    assertThat(url.fragment()).isEqualTo("\u0080");
    assertThat(url.encodedFragment()).isEqualTo("%C2%80");
    assertThat(url.uri().toString()).isEqualTo("http://host/#%C2%80");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fragmentPercentEncodedPartialCodePoint(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/#%80", useGet);
    assertThat(url.toString()).isEqualTo("http://host/#%80");
    // Unicode replacement character.
    assertThat(url.fragment()).isEqualTo("\ufffd");
    assertThat(url.encodedFragment()).isEqualTo("%80");
    assertThat(url.uri().toString()).isEqualTo("http://host/#%80");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void relativePath(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.resolve("d/e/f")).isEqualTo(parse("http://host/a/b/d/e/f", useGet));
    assertThat(base.resolve("../../d/e/f")).isEqualTo(parse("http://host/d/e/f", useGet));
    assertThat(base.resolve("..")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("../..")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve("../../..")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve(".")).isEqualTo(parse("http://host/a/b/", useGet));
    assertThat(base.resolve("././..")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("c/d/../e/../")).isEqualTo(parse("http://host/a/b/c/", useGet));
    assertThat(base.resolve("..e/")).isEqualTo(parse("http://host/a/b/..e/", useGet));
    assertThat(base.resolve("e/f../")).isEqualTo(parse("http://host/a/b/e/f../", useGet));
    assertThat(base.resolve("%2E.")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve(".%2E")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("%2E%2E")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("%2e.")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve(".%2e")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("%2e%2e")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("%2E")).isEqualTo(parse("http://host/a/b/", useGet));
    assertThat(base.resolve("%2e")).isEqualTo(parse("http://host/a/b/", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void relativePathWithTrailingSlash(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c/", useGet);
    assertThat(base.resolve("..")).isEqualTo(parse("http://host/a/b/", useGet));
    assertThat(base.resolve("../")).isEqualTo(parse("http://host/a/b/", useGet));
    assertThat(base.resolve("../..")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("../../")).isEqualTo(parse("http://host/a/", useGet));
    assertThat(base.resolve("../../..")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve("../../../")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve("../../../..")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve("../../../../")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve("../../../../a")).isEqualTo(parse("http://host/a", useGet));
    assertThat(base.resolve("../../../../a/..")).isEqualTo(parse("http://host/", useGet));
    assertThat(base.resolve("../../../../a/b/..")).isEqualTo(parse("http://host/a/", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void pathWithBackslash(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.resolve("d\\e\\f")).isEqualTo(parse("http://host/a/b/d/e/f", useGet));
    assertThat(base.resolve("../..\\d\\e\\f")).isEqualTo(parse("http://host/d/e/f", useGet));
    assertThat(base.resolve("..\\..")).isEqualTo(parse("http://host/", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void relativePathWithSameScheme(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.resolve("http:d/e/f")).isEqualTo(parse("http://host/a/b/d/e/f", useGet));
    assertThat(base.resolve("http:../../d/e/f")).isEqualTo(parse("http://host/d/e/f", useGet));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void decodeUsername(boolean useGet) {
    assertThat(parse("http://user@host/", useGet).username()).isEqualTo("user");
    assertThat(parse("http://%F0%9F%8D%A9@host/", useGet).username()).isEqualTo("\uD83C\uDF69");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void decodePassword(boolean useGet) {
    assertThat(parse("http://user:password@host/", useGet).password()).isEqualTo("password");
    assertThat(parse("http://user:@host/", useGet).password()).isEqualTo("");
    assertThat(parse("http://user:%F0%9F%8D%A9@host/", useGet).password())
        .isEqualTo("\uD83C\uDF69");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void decodeSlashCharacterInDecodedPathSegment(boolean useGet) {
    assertThat(parse("http://host/a%2Fb%2Fc", useGet).pathSegments()).containsExactly("a/b/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void decodeEmptyPathSegments(boolean useGet) {
    assertThat(parse("http://host/", useGet).pathSegments()).containsExactly("");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void percentDecode(boolean useGet) throws Exception {
    assertThat(parse("http://host/%00", useGet).pathSegments()).containsExactly("\u0000");
    assertThat(parse("http://host/a/%E2%98%83/c", useGet).pathSegments()).containsExactly("a",
        "\u2603", "c");
    assertThat(parse("http://host/a/%F0%9F%8D%A9/c", useGet).pathSegments()).containsExactly("a",
        "\uD83C\uDF69", "c");
    assertThat(parse("http://host/a/%62/c", useGet).pathSegments()).containsExactly("a", "b", "c");
    assertThat(parse("http://host/a/%7A/c", useGet).pathSegments()).containsExactly("a", "z", "c");
    assertThat(parse("http://host/a/%7a/c", useGet).pathSegments()).containsExactly("a", "z", "c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void malformedPercentEncoding(boolean useGet) {
    assertThat(parse("http://host/a%f/b", useGet).pathSegments()).containsExactly("a%f", "b");
    assertThat(parse("http://host/%/b", useGet).pathSegments()).containsExactly("%", "b");
    assertThat(parse("http://host/%", useGet).pathSegments()).containsExactly("%");
    assertThat(parse("http://github.com/%%30%30", useGet).pathSegments()).containsExactly("%00");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void malformedUtf8Encoding(boolean useGet) {
    // Replace a partial UTF-8 sequence with the Unicode replacement character.
    assertThat(parse("http://host/a/%E2%98x/c", useGet).pathSegments())
        .containsExactly("a", "\ufffdx", "c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void incompleteUrlComposition(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void builderToString(boolean useGet) {
    assertThat(parse("https://host.com/path", useGet).newBuilder().toString())
        .isEqualTo("https://host.com/path");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void incompleteBuilderToString(boolean useGet) {
    assertThat(new HttpUrl.Builder().scheme("https").encodedPath("/path").toString())
        .isEqualTo("https:///path");
    assertThat(new HttpUrl.Builder().host("host.com").encodedPath("/path").toString())
        .isEqualTo("//host.com/path");
    assertThat(new HttpUrl.Builder().host("host.com").encodedPath("/path").port(8080).toString())
        .isEqualTo("//host.com:8080/path");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void minimalUrlComposition(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fullUrlComposition(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void changingSchemeChangesDefaultPort(boolean useGet) throws Exception {
    assertThat(parse("http://example.com", useGet)
        .newBuilder()
        .scheme("https")
        .build().port()).isEqualTo(443);

    assertThat(parse("https://example.com", useGet)
        .newBuilder()
        .scheme("http")
        .build().port()).isEqualTo(80);

    assertThat(parse("https://example.com:1234", useGet)
        .newBuilder()
        .scheme("http")
        .build().port()).isEqualTo(1234);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeEncodesWhitespace(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeFromUnencodedComponents(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeFromEncodedComponents(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeWithEncodedPath(boolean useGet) throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .encodedPath("/a%2Fb/c")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/a%2Fb/c");
    assertThat(url.encodedPath()).isEqualTo("/a%2Fb/c");
    assertThat(url.pathSegments()).containsExactly("a/b", "c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeMixingPathSegments(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeWithAddSegment(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void pathSize(boolean useGet) throws Exception {
    assertThat(parse("http://host/", useGet).pathSize()).isEqualTo(1);
    assertThat(parse("http://host/a/b/c", useGet).pathSize()).isEqualTo(3);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegments(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);

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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentsOntoTrailingSlash(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c/", useGet);

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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentsWithBackslash(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/", useGet);
    assertThat(base.newBuilder().addPathSegments("d\\e").build().encodedPath())
        .isEqualTo("/d/e");
    assertThat(base.newBuilder().addEncodedPathSegments("d\\e").build().encodedPath())
        .isEqualTo("/d/e");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentsWithEmptyPaths(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addPathSegments("/d/e///f").build().encodedPath())
        .isEqualTo("/a/b/c//d/e///f");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addEncodedPathSegments(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(
        (Object) base.newBuilder().addEncodedPathSegments("d/e/%20/\n").build().encodedPath())
        .isEqualTo("/a/b/c/d/e/%20/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentDotDoesNothing(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addPathSegment(".").build().encodedPath())
        .isEqualTo("/a/b/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentEncodes(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addPathSegment("%2e").build().encodedPath())
        .isEqualTo("/a/b/c/%252e");
    assertThat(base.newBuilder().addPathSegment("%2e%2e").build().encodedPath())
        .isEqualTo("/a/b/c/%252e%252e");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentDotDotPopsDirectory(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addPathSegment("..").build().encodedPath())
        .isEqualTo("/a/b/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addPathSegmentDotAndIgnoredCharacter(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addPathSegment(".\n").build().encodedPath())
        .isEqualTo("/a/b/c/.%0A");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addEncodedPathSegmentDotAndIgnoredCharacter(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addEncodedPathSegment(".\n").build().encodedPath())
        .isEqualTo("/a/b/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void addEncodedPathSegmentDotDotAndIgnoredCharacter(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().addEncodedPathSegment("..\n").build().encodedPath())
        .isEqualTo("/a/b/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegment(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().setPathSegment(0, "d").build().encodedPath())
        .isEqualTo("/d/b/c");
    assertThat(base.newBuilder().setPathSegment(1, "d").build().encodedPath())
        .isEqualTo("/a/d/c");
    assertThat(base.newBuilder().setPathSegment(2, "d").build().encodedPath())
        .isEqualTo("/a/b/d");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegmentEncodes(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().setPathSegment(0, "%25").build().encodedPath())
        .isEqualTo("/%2525/b/c");
    assertThat(base.newBuilder().setPathSegment(0, ".\n").build().encodedPath())
        .isEqualTo("/.%0A/b/c");
    assertThat(base.newBuilder().setPathSegment(0, "%2e").build().encodedPath())
        .isEqualTo("/%252e/b/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegmentAcceptsEmpty(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().setPathSegment(0, "").build().encodedPath())
        .isEqualTo("//b/c");
    assertThat(base.newBuilder().setPathSegment(2, "").build().encodedPath())
        .isEqualTo("/a/b/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegmentRejectsDot(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    try {
      base.newBuilder().setPathSegment(0, ".");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegmentRejectsDotDot(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    try {
      base.newBuilder().setPathSegment(0, "..");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegmentWithSlash(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    HttpUrl url = base.newBuilder().setPathSegment(1, "/").build();
    assertThat(url.encodedPath()).isEqualTo("/a/%2F/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setPathSegmentOutOfBounds(boolean useGet) throws Exception {
    try {
      new HttpUrl.Builder().setPathSegment(1, "a");
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentEncodes(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    assertThat(base.newBuilder().setEncodedPathSegment(0, "%25").build().encodedPath())
        .isEqualTo("/%25/b/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentRejectsDot(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    try {
      base.newBuilder().setEncodedPathSegment(0, ".");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentRejectsDotAndIgnoredCharacter(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    try {
      base.newBuilder().setEncodedPathSegment(0, ".\n");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentRejectsDotDot(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    try {
      base.newBuilder().setEncodedPathSegment(0, "..");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentRejectsDotDotAndIgnoredCharacter(boolean useGet)
      throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    try {
      base.newBuilder().setEncodedPathSegment(0, "..\n");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentWithSlash(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    HttpUrl url = base.newBuilder().setEncodedPathSegment(1, "/").build();
    assertThat(url.encodedPath()).isEqualTo("/a/%2F/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void setEncodedPathSegmentOutOfBounds(boolean useGet) throws Exception {
    try {
      new HttpUrl.Builder().setEncodedPathSegment(1, "a");
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void removePathSegment(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    HttpUrl url = base.newBuilder()
        .removePathSegment(0)
        .build();
    assertThat(url.encodedPath()).isEqualTo("/b/c");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void removePathSegmentDoesntRemovePath(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/a/b/c", useGet);
    HttpUrl url = base.newBuilder()
        .removePathSegment(0)
        .removePathSegment(0)
        .removePathSegment(0)
        .build();
    assertThat(url.pathSegments()).containsExactly("");
    assertThat(url.encodedPath()).isEqualTo("/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void removePathSegmentOutOfBounds(boolean useGet) throws Exception {
    try {
      new HttpUrl.Builder().removePathSegment(1);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toJavaNetUrl(boolean useGet) throws Exception {
    HttpUrl httpUrl = parse("http://username:password@host/path?query#fragment", useGet);
    URL javaNetUrl = httpUrl.url();
    assertThat(javaNetUrl.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void toUri(boolean useGet)
      throws Exception {
    HttpUrl httpUrl = parse("http://username:password@host/path?query#fragment", useGet);
    URI uri = httpUrl.uri();
    assertThat(uri.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriSpecialQueryCharacters(boolean useGet) throws Exception {
    HttpUrl httpUrl = parse("http://host/?d=abc!@[]^`{}|\\", useGet);
    URI uri = httpUrl.uri();
    assertThat(uri.toString()).isEqualTo("http://host/?d=abc!@[]%5E%60%7B%7D%7C%5C");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriWithUsernameNoPassword(boolean useGet) throws Exception {
    HttpUrl httpUrl = new HttpUrl.Builder()
        .scheme("http")
        .username("user")
        .host("host")
        .build();
    assertThat(httpUrl.toString()).isEqualTo("http://user@host/");
    assertThat(httpUrl.uri().toString()).isEqualTo("http://user@host/");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriUsernameSpecialCharacters(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriPasswordSpecialCharacters(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriPathSpecialCharacters(boolean useGet) throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .addPathSegment("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/=[]:;%22~%7C%3F%23@%5E%2F$%25*");
    assertThat(url.uri().toString())
        .isEqualTo("http://host/=%5B%5D:;%22~%7C%3F%23@%5E%2F$%25*");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriQueryParameterNameSpecialCharacters(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriQueryParameterValueSpecialCharacters(boolean useGet) throws Exception {
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriQueryValueSpecialCharacters(boolean useGet) throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .query("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?=[]:;%22~|?%23@^/$%25*");
    assertThat(url.uri().toString()).isEqualTo("http://host/?=[]:;%22~%7C?%23@%5E/$%25*");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryCharactersEncodedWhenComposed(boolean useGet) throws Exception {
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
   * When callers use {@code addEncodedQueryParameter()} we only encode what's strictly required. We
   * retain the encoded (or non-encoded) state of the input.
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryCharactersNotReencodedWhenComposedWithAddEncoded(boolean useGet)
      throws Exception {
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
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryCharactersNotReencodedWhenParsed(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/?a=!$(),/:;?@[]\\^`{|}~", useGet);
    assertThat(url.toString()).isEqualTo("http://host/?a=!$(),/:;?@[]\\^`{|}~");
    assertThat(url.queryParameter("a")).isEqualTo("!$(),/:;?@[]\\^`{|}~");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriFragmentSpecialCharacters(boolean useGet) throws Exception {
    HttpUrl url = new HttpUrl.Builder()
        .scheme("http")
        .host("host")
        .fragment("=[]:;\"~|?#@^/$%*")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/#=[]:;\"~|?#@^/$%25*");
    assertThat(url.uri().toString()).isEqualTo("http://host/#=[]:;%22~%7C?%23@%5E/$%25*");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriWithControlCharacters(boolean useGet) throws Exception {
    // Percent-encoded in the path.
    assertThat(parse("http://host/a\u0000b", useGet).uri()).isEqualTo(new URI("http://host/a%00b"));
    assertThat(parse("http://host/a\u0080b", useGet).uri())
        .isEqualTo(new URI("http://host/a%C2%80b"));
    assertThat(parse("http://host/a\u009fb", useGet).uri())
        .isEqualTo(new URI("http://host/a%C2%9Fb"));
    // Percent-encoded in the query.
    assertThat(parse("http://host/?a\u0000b", useGet).uri())
        .isEqualTo(new URI("http://host/?a%00b"));
    assertThat(parse("http://host/?a\u0080b", useGet).uri())
        .isEqualTo(new URI("http://host/?a%C2%80b"));
    assertThat(parse("http://host/?a\u009fb", useGet).uri())
        .isEqualTo(new URI("http://host/?a%C2%9Fb"));
    // Stripped from the fragment.
    assertThat(parse("http://host/#a\u0000b", useGet).uri())
        .isEqualTo(new URI("http://host/#a%00b"));
    assertThat(parse("http://host/#a\u0080b", useGet).uri()).isEqualTo(new URI("http://host/#ab"));
    assertThat(parse("http://host/#a\u009fb", useGet).uri()).isEqualTo(new URI("http://host/#ab"));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriWithSpaceCharacters(boolean useGet) throws Exception {
    // Percent-encoded in the path.
    assertThat(parse("http://host/a\u000bb", useGet).uri()).isEqualTo(new URI("http://host/a%0Bb"));
    assertThat(parse("http://host/a b", useGet).uri()).isEqualTo(new URI("http://host/a%20b"));
    assertThat(parse("http://host/a\u2009b", useGet).uri())
        .isEqualTo(new URI("http://host/a%E2%80%89b"));
    assertThat(parse("http://host/a\u3000b", useGet).uri())
        .isEqualTo(new URI("http://host/a%E3%80%80b"));
    // Percent-encoded in the query.
    assertThat(parse("http://host/?a\u000bb", useGet).uri())
        .isEqualTo(new URI("http://host/?a%0Bb"));
    assertThat(parse("http://host/?a b", useGet).uri()).isEqualTo(new URI("http://host/?a%20b"));
    assertThat(parse("http://host/?a\u2009b", useGet).uri())
        .isEqualTo(new URI("http://host/?a%E2%80%89b"));
    assertThat(parse("http://host/?a\u3000b", useGet).uri())
        .isEqualTo(new URI("http://host/?a%E3%80%80b"));
    // Stripped from the fragment.
    assertThat(parse("http://host/#a\u000bb", useGet).uri())
        .isEqualTo(new URI("http://host/#a%0Bb"));
    assertThat(parse("http://host/#a b", useGet).uri()).isEqualTo(new URI("http://host/#a%20b"));
    assertThat(parse("http://host/#a\u2009b", useGet).uri()).isEqualTo(new URI("http://host/#ab"));
    assertThat(parse("http://host/#a\u3000b", useGet).uri()).isEqualTo(new URI("http://host/#ab"));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriWithNonHexPercentEscape(boolean useGet) throws Exception {
    assertThat(parse("http://host/%xx", useGet).uri()).isEqualTo(new URI("http://host/%25xx"));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void toUriWithTruncatedPercentEscape(boolean useGet) throws Exception {
    assertThat(parse("http://host/%a", useGet).uri()).isEqualTo(new URI("http://host/%25a"));
    assertThat(parse("http://host/%", useGet).uri()).isEqualTo(new URI("http://host/%25"));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fromJavaNetUrl(boolean useGet) throws Exception {
    URL javaNetUrl = new URL("http://username:password@host/path?query#fragment");
    HttpUrl httpUrl = HttpUrl.get(javaNetUrl);
    assertThat(httpUrl.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fromJavaNetUrlUnsupportedScheme(boolean useGet) throws Exception {
    // java.net.MalformedURLException: unknown protocol: mailto
    platform.assumeNotAndroid();
    URL javaNetUrl = new URL("mailto:user@example.com");
    assertThat(HttpUrl.get(javaNetUrl)).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fromUri(boolean useGet) throws Exception {
    URI uri = new URI("http://username:password@host/path?query#fragment");
    HttpUrl httpUrl = HttpUrl.get(uri);
    assertThat(httpUrl.toString())
        .isEqualTo("http://username:password@host/path?query#fragment");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fromUriUnsupportedScheme(boolean useGet) throws Exception {
    URI uri = new URI("mailto:user@example.com");
    assertThat(HttpUrl.get(uri)).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void fromUriPartial(boolean useGet) throws Exception {
    URI uri = new URI("/path");
    assertThat(HttpUrl.get(uri)).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQueryWithComponents(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/", useGet);
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQueryWithEncodedComponents(boolean useGet) throws Exception {
    HttpUrl base = parse("http://host/", useGet);
    HttpUrl url = base.newBuilder().addEncodedQueryParameter("a+=& b", "c+=& d").build();
    assertThat(url.toString()).isEqualTo("http://host/?a+%3D%26%20b=c+%3D%26%20d");
    assertThat(url.queryParameter("a =& b")).isEqualTo("c =& d");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQueryRemoveQueryParameter(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .removeAllQueryParameters("a+=& b")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.queryParameter("a+=& b")).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQueryRemoveEncodedQueryParameter(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .removeAllEncodedQueryParameters("a+=& b")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.queryParameter("a =& b")).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQuerySetQueryParameter(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .setQueryParameter("a+=& b", "ef")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?a%2B%3D%26%20b=ef");
    assertThat(url.queryParameter("a+=& b")).isEqualTo("ef");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQuerySetEncodedQueryParameter(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .addEncodedQueryParameter("a+=& b", "c+=& d")
        .setEncodedQueryParameter("a+=& b", "ef")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?a+%3D%26%20b=ef");
    assertThat(url.queryParameter("a =& b")).isEqualTo("ef");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void composeQueryMultipleEncodedValuesForParameter(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .addQueryParameter("a+=& b", "c+=& d")
        .addQueryParameter("a+=& b", "e+=& f")
        .build();
    assertThat(url.toString())
        .isEqualTo("http://host/?a%2B%3D%26%20b=c%2B%3D%26%20d&a%2B%3D%26%20b=e%2B%3D%26%20f");
    assertThat(url.querySize()).isEqualTo(2);
    assertThat(url.queryParameterNames()).isEqualTo(Collections.singleton("a+=& b"));
    assertThat(url.queryParameterValues("a+=& b")).containsExactly("c+=& d", "e+=& f");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void absentQueryIsZeroNameValuePairs(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .query(null)
        .build();
    assertThat(url.querySize()).isEqualTo(0);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void emptyQueryIsSingleNameValuePairWithEmptyKey(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .query("")
        .build();
    assertThat(url.querySize()).isEqualTo(1);
    assertThat(url.queryParameterName(0)).isEqualTo("");
    assertThat(url.queryParameterValue(0)).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void ampersandQueryIsTwoNameValuePairsWithEmptyKeys(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .query("&")
        .build();
    assertThat(url.querySize()).isEqualTo(2);
    assertThat(url.queryParameterName(0)).isEqualTo("");
    assertThat(url.queryParameterValue(0)).isNull();
    assertThat(url.queryParameterName(1)).isEqualTo("");
    assertThat(url.queryParameterValue(1)).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void removeAllDoesNotRemoveQueryIfNoParametersWereRemoved(boolean useGet)
      throws Exception {
    HttpUrl url = parse("http://host/", useGet).newBuilder()
        .query("")
        .removeAllQueryParameters("a")
        .build();
    assertThat(url.toString()).isEqualTo("http://host/?");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryParametersWithoutValues(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/?foo&bar&baz", useGet);
    assertThat(url.querySize()).isEqualTo(3);
    assertThat(url.queryParameterNames()).containsExactly("foo", "bar", "baz");
    assertThat(url.queryParameterValue(0)).isNull();
    assertThat(url.queryParameterValue(1)).isNull();
    assertThat(url.queryParameterValue(2)).isNull();
    assertThat(url.queryParameterValues("foo")).isEqualTo(singletonList((String) null));
    assertThat(url.queryParameterValues("bar")).isEqualTo(singletonList((String) null));
    assertThat(url.queryParameterValues("baz")).isEqualTo(singletonList((String) null));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryParametersWithEmptyValues(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/?foo=&bar=&baz=", useGet);
    assertThat(url.querySize()).isEqualTo(3);
    assertThat(url.queryParameterNames()).containsExactly("foo", "bar", "baz");
    assertThat(url.queryParameterValue(0)).isEqualTo("");
    assertThat(url.queryParameterValue(1)).isEqualTo("");
    assertThat(url.queryParameterValue(2)).isEqualTo("");
    assertThat(url.queryParameterValues("foo")).isEqualTo(singletonList(""));
    assertThat(url.queryParameterValues("bar")).isEqualTo(singletonList(""));
    assertThat(url.queryParameterValues("baz")).isEqualTo(singletonList(""));
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryParametersWithRepeatedName(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/?foo[]=1&foo[]=2&foo[]=3", useGet);
    assertThat(url.querySize()).isEqualTo(3);
    assertThat(url.queryParameterNames()).isEqualTo(Collections.singleton("foo[]"));
    assertThat(url.queryParameterValue(0)).isEqualTo("1");
    assertThat(url.queryParameterValue(1)).isEqualTo("2");
    assertThat(url.queryParameterValue(2)).isEqualTo("3");
    assertThat(url.queryParameterValues("foo[]")).containsExactly("1", "2", "3");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void queryParameterLookupWithNonCanonicalEncoding(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/?%6d=m&+=%20", useGet);
    assertThat(url.queryParameterName(0)).isEqualTo("m");
    assertThat(url.queryParameterName(1)).isEqualTo(" ");
    assertThat(url.queryParameter("m")).isEqualTo("m");
    assertThat(url.queryParameter(" ")).isEqualTo(" ");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void parsedQueryDoesntIncludeFragment(boolean useGet) {
    HttpUrl url = parse("http://host/?#fragment", useGet);
    assertThat(url.fragment()).isEqualTo("fragment");
    assertThat(url.query()).isEqualTo("");
    assertThat(url.encodedQuery()).isEqualTo("");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void roundTripBuilder(boolean useGet) throws Exception {
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
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void rawEncodingRetained(
      boolean useGet) throws Exception {
    String urlString = "http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D#%6d%6D";
    HttpUrl url = parse(urlString, useGet);
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void clearFragment(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/#fragment", useGet)
        .newBuilder()
        .fragment(null)
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.fragment()).isNull();
    assertThat(url.encodedFragment()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void clearEncodedFragment(boolean useGet) throws Exception {
    HttpUrl url = parse("http://host/#fragment", useGet)
        .newBuilder()
        .encodedFragment(null)
        .build();
    assertThat(url.toString()).isEqualTo("http://host/");
    assertThat(url.fragment()).isNull();
    assertThat(url.encodedFragment()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void topPrivateDomain(boolean useGet) {
    assertThat(parse("https://google.com", useGet).topPrivateDomain()).isEqualTo("google.com");
    assertThat(parse("https://adwords.google.co.uk", useGet).topPrivateDomain())
        .isEqualTo("google.co.uk");
    assertThat(parse("https://栃.栃木.jp", useGet).topPrivateDomain())
        .isEqualTo("xn--ewv.xn--4pvxs.jp");
    assertThat(parse("https://xn--ewv.xn--4pvxs.jp", useGet).topPrivateDomain())
        .isEqualTo("xn--ewv.xn--4pvxs.jp");

    assertThat(parse("https://co.uk", useGet).topPrivateDomain()).isNull();
    assertThat(parse("https://square", useGet).topPrivateDomain()).isNull();
    assertThat(parse("https://栃木.jp", useGet).topPrivateDomain()).isNull();
    assertThat(parse("https://xn--4pvxs.jp", useGet).topPrivateDomain()).isNull();
    assertThat(parse("https://localhost", useGet).topPrivateDomain()).isNull();
    assertThat(parse("https://127.0.0.1", useGet).topPrivateDomain()).isNull();

    // https://github.com/square/okhttp/issues/6109
    assertThat(parse("http://a./", useGet).topPrivateDomain()).isNull();
    assertThat(parse("http://./", useGet).topPrivateDomain()).isNull();

    assertThat(parse("http://squareup.com./", useGet).topPrivateDomain()).isEqualTo("squareup.com");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void unparseableTopPrivateDomain(boolean useGet) {
    assertInvalid("http://a../", "Invalid URL host: \"a..\"", useGet);
    assertInvalid("http://..a/", "Invalid URL host: \"..a\"", useGet);
    assertInvalid("http://a..b/", "Invalid URL host: \"a..b\"", useGet);
    assertInvalid("http://.a/", "Invalid URL host: \".a\"", useGet);
    assertInvalid("http://../", "Invalid URL host: \"..\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void hostnameTelephone(boolean useGet) throws Exception {
    // https://www.gosecure.net/blog/2020/10/27/weakness-in-java-tls-host-verification/

    // Map the single character telephone symbol (℡) to the string "tel".
    assertThat(parse("http://\u2121", useGet).host()).isEqualTo("tel");

    // Map the Kelvin symbol (K) to the string "k".
    assertThat(parse("http://\u212A", useGet).host()).isEqualTo("k");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void quirks(boolean useGet) throws Exception {
    assertThat(parse("http://facebook.com", useGet).host()).isEqualTo("facebook.com");
    assertThat(parse("http://facebooK.com", useGet).host()).isEqualTo("facebook.com");
    assertThat(parse("http://Facebook.com", useGet).host()).isEqualTo("facebook.com");
    assertThat(parse("http://FacebooK.com", useGet).host()).isEqualTo("facebook.com");
  }

  private void assertInvalid(String string, String exceptionMessage, boolean useGet) {
    if (useGet) {
      try {
        parse(string, useGet);
        fail("Expected get of \"" + string + "\" to throw with: " + exceptionMessage);
      } catch (IllegalArgumentException e) {
        assertThat(e.getMessage()).isEqualTo(exceptionMessage);
      }
    } else {
      assertThat(parse(string, useGet)).overridingErrorMessage(string).isNull();
    }
  }
}
