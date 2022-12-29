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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.fail
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okhttp3.HttpUrl.Companion.toHttpUrl

@Suppress("HttpUrlsUsage") // Don't warn if we should be using https://.
open class HttpUrlCommonTest {
  protected open fun parse(url: String): HttpUrl {
    return url.toHttpUrl()
  }

  protected open fun assertInvalid(string: String, exceptionMessage: String?) {
    try {
      val result = string.toHttpUrl()
      if (exceptionMessage != null) {
        fail("Expected failure with $exceptionMessage but got $result")
      } else {
        fail("Expected failure but got $result")
      }
    } catch(iae: IllegalArgumentException) {
      iae.printStackTrace()
      if (exceptionMessage != null) {
        assertThat(iae).hasMessage(exceptionMessage)
      }
    }
  }

  @Test
  fun parseTrimsAsciiWhitespace() {
    val expected = parse("http://host/")
    // Leading.
    assertThat(parse("http://host/\u000c\n\t \r")).isEqualTo(expected)
    // Trailing.
    assertThat(parse("\r\n\u000c \thttp://host/")).isEqualTo(expected)
    // Both.
    assertThat(parse(" http://host/ ")).isEqualTo(expected)
    // Both.
    assertThat(parse("    http://host/    ")).isEqualTo(expected)
    assertThat(parse("http://host/").resolve("   ")).isEqualTo(expected)
    assertThat(parse("http://host/").resolve("  .  ")).isEqualTo(expected)
  }

  @Test
  fun parseHostAsciiNonPrintable() {
    val host = "host\u0001"
    assertInvalid("http://$host/", "Invalid URL host: \"host\u0001\"")
    // TODO make exception message escape non-printable characters
  }

  @Test
  fun parseDoesNotTrimOtherWhitespaceCharacters() {
    // Whitespace characters list from Google's Guava team: http://goo.gl/IcR9RD
    // line tabulation
    assertThat(parse("http://h/\u000b").encodedPath).isEqualTo("/%0B")
    // information separator 4
    assertThat(parse("http://h/\u001c").encodedPath).isEqualTo("/%1C")
    // information separator 3
    assertThat(parse("http://h/\u001d").encodedPath).isEqualTo("/%1D")
    // information separator 2
    assertThat(parse("http://h/\u001e").encodedPath).isEqualTo("/%1E")
    // information separator 1
    assertThat(parse("http://h/\u001f").encodedPath).isEqualTo("/%1F")
    // next line
    assertThat(parse("http://h/\u0085").encodedPath).isEqualTo("/%C2%85")
    // non-breaking space
    assertThat(parse("http://h/\u00a0").encodedPath).isEqualTo("/%C2%A0")
    // ogham space mark
    assertThat(parse("http://h/\u1680").encodedPath).isEqualTo("/%E1%9A%80")
    // mongolian vowel separator
    assertThat(parse("http://h/\u180e").encodedPath).isEqualTo("/%E1%A0%8E")
    // en quad
    assertThat(parse("http://h/\u2000").encodedPath).isEqualTo("/%E2%80%80")
    // em quad
    assertThat(parse("http://h/\u2001").encodedPath).isEqualTo("/%E2%80%81")
    // en space
    assertThat(parse("http://h/\u2002").encodedPath).isEqualTo("/%E2%80%82")
    // em space
    assertThat(parse("http://h/\u2003").encodedPath).isEqualTo("/%E2%80%83")
    // three-per-em space
    assertThat(parse("http://h/\u2004").encodedPath).isEqualTo("/%E2%80%84")
    // four-per-em space
    assertThat(parse("http://h/\u2005").encodedPath).isEqualTo("/%E2%80%85")
    // six-per-em space
    assertThat(parse("http://h/\u2006").encodedPath).isEqualTo("/%E2%80%86")
    // figure space
    assertThat(parse("http://h/\u2007").encodedPath).isEqualTo("/%E2%80%87")
    // punctuation space
    assertThat(parse("http://h/\u2008").encodedPath).isEqualTo("/%E2%80%88")
    // thin space
    assertThat(parse("http://h/\u2009").encodedPath).isEqualTo("/%E2%80%89")
    // hair space
    assertThat(parse("http://h/\u200a").encodedPath).isEqualTo("/%E2%80%8A")
    // zero-width space
    assertThat(parse("http://h/\u200b").encodedPath).isEqualTo("/%E2%80%8B")
    // zero-width non-joiner
    assertThat(parse("http://h/\u200c").encodedPath).isEqualTo("/%E2%80%8C")
    // zero-width joiner
    assertThat(parse("http://h/\u200d").encodedPath).isEqualTo("/%E2%80%8D")
    // left-to-right mark
    assertThat(parse("http://h/\u200e").encodedPath).isEqualTo("/%E2%80%8E")
    // right-to-left mark
    assertThat(parse("http://h/\u200f").encodedPath).isEqualTo("/%E2%80%8F")
    // line separator
    assertThat(parse("http://h/\u2028").encodedPath).isEqualTo("/%E2%80%A8")
    // paragraph separator
    assertThat(parse("http://h/\u2029").encodedPath).isEqualTo("/%E2%80%A9")
    // narrow non-breaking space
    assertThat(parse("http://h/\u202f").encodedPath).isEqualTo("/%E2%80%AF")
    // medium mathematical space
    assertThat(parse("http://h/\u205f").encodedPath).isEqualTo("/%E2%81%9F")
    // ideographic space
    assertThat(parse("http://h/\u3000").encodedPath).isEqualTo("/%E3%80%80")
  }

  @Test
  fun newBuilderResolve() {
    // Non-exhaustive tests because implementation is the same as resolve.
    val base = parse("http://host/a/b")
    assertThat(base.newBuilder("https://host2")!!.build())
      .isEqualTo(parse("https://host2/"))
    assertThat(base.newBuilder("//host2")!!.build())
      .isEqualTo(parse("http://host2/"))
    assertThat(base.newBuilder("/path")!!.build())
      .isEqualTo(parse("http://host/path"))
    assertThat(base.newBuilder("path")!!.build())
      .isEqualTo(parse("http://host/a/path"))
    assertThat(base.newBuilder("?query")!!.build())
      .isEqualTo(parse("http://host/a/b?query"))
    assertThat(base.newBuilder("#fragment")!!.build())
      .isEqualTo(parse("http://host/a/b#fragment"))
    assertThat(base.newBuilder("")!!.build()).isEqualTo(parse("http://host/a/b"))
    assertThat(base.newBuilder("ftp://b")).isNull()
    assertThat(base.newBuilder("ht+tp://b")).isNull()
    assertThat(base.newBuilder("ht-tp://b")).isNull()
    assertThat(base.newBuilder("ht.tp://b")).isNull()
  }

  @Test
  fun redactedUrl() {
    val baseWithPasswordAndUsername = parse("http://username:password@host/a/b#fragment")
    val baseWithUsernameOnly = parse("http://username@host/a/b#fragment")
    val baseWithPasswordOnly = parse("http://password@host/a/b#fragment")
    assertThat(baseWithPasswordAndUsername.redact()).isEqualTo("http://host/...")
    assertThat(baseWithUsernameOnly.redact()).isEqualTo("http://host/...")
    assertThat(baseWithPasswordOnly.redact()).isEqualTo("http://host/...")
  }

  @Test
  fun resolveNoScheme() {
    val base = parse("http://host/a/b")
    assertThat(base.resolve("//host2")).isEqualTo(parse("http://host2/"))
    assertThat(base.resolve("/path")).isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("path")).isEqualTo(parse("http://host/a/path"))
    assertThat(base.resolve("?query")).isEqualTo(parse("http://host/a/b?query"))
    assertThat(base.resolve("#fragment"))
      .isEqualTo(parse("http://host/a/b#fragment"))
    assertThat(base.resolve("")).isEqualTo(parse("http://host/a/b"))
    assertThat(base.resolve("\\path")).isEqualTo(parse("http://host/path"))
  }

  @Test
  fun resolveUnsupportedScheme() {
    val base = parse("http://a/")
    assertThat(base.resolve("ftp://b")).isNull()
    assertThat(base.resolve("ht+tp://b")).isNull()
    assertThat(base.resolve("ht-tp://b")).isNull()
    assertThat(base.resolve("ht.tp://b")).isNull()
  }

  @Test
  fun resolveSchemeLikePath() {
    val base = parse("http://a/")
    assertThat(base.resolve("http//b/")).isEqualTo(parse("http://a/http//b/"))
    assertThat(base.resolve("ht+tp//b/")).isEqualTo(parse("http://a/ht+tp//b/"))
    assertThat(base.resolve("ht-tp//b/")).isEqualTo(parse("http://a/ht-tp//b/"))
    assertThat(base.resolve("ht.tp//b/")).isEqualTo(parse("http://a/ht.tp//b/"))
  }

  /**
   * https://tools.ietf.org/html/rfc3986#section-5.4.1
   */
  @Test
  fun rfc3886NormalExamples() {
    val url = parse("http://a/b/c/d;p?q")
    // No 'g:' scheme in HttpUrl.
    assertThat(url.resolve("g:h")).isNull()
    assertThat(url.resolve("g")).isEqualTo(parse("http://a/b/c/g"))
    assertThat(url.resolve("./g")).isEqualTo(parse("http://a/b/c/g"))
    assertThat(url.resolve("g/")).isEqualTo(parse("http://a/b/c/g/"))
    assertThat(url.resolve("/g")).isEqualTo(parse("http://a/g"))
    assertThat(url.resolve("//g")).isEqualTo(parse("http://g"))
    assertThat(url.resolve("?y")).isEqualTo(parse("http://a/b/c/d;p?y"))
    assertThat(url.resolve("g?y")).isEqualTo(parse("http://a/b/c/g?y"))
    assertThat(url.resolve("#s")).isEqualTo(parse("http://a/b/c/d;p?q#s"))
    assertThat(url.resolve("g#s")).isEqualTo(parse("http://a/b/c/g#s"))
    assertThat(url.resolve("g?y#s")).isEqualTo(parse("http://a/b/c/g?y#s"))
    assertThat(url.resolve(";x")).isEqualTo(parse("http://a/b/c/;x"))
    assertThat(url.resolve("g;x")).isEqualTo(parse("http://a/b/c/g;x"))
    assertThat(url.resolve("g;x?y#s")).isEqualTo(parse("http://a/b/c/g;x?y#s"))
    assertThat(url.resolve("")).isEqualTo(parse("http://a/b/c/d;p?q"))
    assertThat(url.resolve(".")).isEqualTo(parse("http://a/b/c/"))
    assertThat(url.resolve("./")).isEqualTo(parse("http://a/b/c/"))
    assertThat(url.resolve("..")).isEqualTo(parse("http://a/b/"))
    assertThat(url.resolve("../")).isEqualTo(parse("http://a/b/"))
    assertThat(url.resolve("../g")).isEqualTo(parse("http://a/b/g"))
    assertThat(url.resolve("../..")).isEqualTo(parse("http://a/"))
    assertThat(url.resolve("../../")).isEqualTo(parse("http://a/"))
    assertThat(url.resolve("../../g")).isEqualTo(parse("http://a/g"))
  }

  /**
   * https://tools.ietf.org/html/rfc3986#section-5.4.2
   */
  @Test
  fun rfc3886AbnormalExamples() {
    val url = parse("http://a/b/c/d;p?q")
    assertThat(url.resolve("../../../g")).isEqualTo(parse("http://a/g"))
    assertThat(url.resolve("../../../../g")).isEqualTo(parse("http://a/g"))
    assertThat(url.resolve("/./g")).isEqualTo(parse("http://a/g"))
    assertThat(url.resolve("/../g")).isEqualTo(parse("http://a/g"))
    assertThat(url.resolve("g.")).isEqualTo(parse("http://a/b/c/g."))
    assertThat(url.resolve(".g")).isEqualTo(parse("http://a/b/c/.g"))
    assertThat(url.resolve("g..")).isEqualTo(parse("http://a/b/c/g.."))
    assertThat(url.resolve("..g")).isEqualTo(parse("http://a/b/c/..g"))
    assertThat(url.resolve("./../g")).isEqualTo(parse("http://a/b/g"))
    assertThat(url.resolve("./g/.")).isEqualTo(parse("http://a/b/c/g/"))
    assertThat(url.resolve("g/./h")).isEqualTo(parse("http://a/b/c/g/h"))
    assertThat(url.resolve("g/../h")).isEqualTo(parse("http://a/b/c/h"))
    assertThat(url.resolve("g;x=1/./y")).isEqualTo(parse("http://a/b/c/g;x=1/y"))
    assertThat(url.resolve("g;x=1/../y")).isEqualTo(parse("http://a/b/c/y"))
    assertThat(url.resolve("g?y/./x")).isEqualTo(parse("http://a/b/c/g?y/./x"))
    assertThat(url.resolve("g?y/../x")).isEqualTo(parse("http://a/b/c/g?y/../x"))
    assertThat(url.resolve("g#s/./x")).isEqualTo(parse("http://a/b/c/g#s/./x"))
    assertThat(url.resolve("g#s/../x")).isEqualTo(parse("http://a/b/c/g#s/../x"))
    // "http:g" also okay.
    assertThat(url.resolve("http:g")).isEqualTo(parse("http://a/b/c/g"))
  }

  @Test
  fun parseAuthoritySlashCountDoesntMatter() {
    assertThat(parse("http:host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http://host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:/\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:///host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:\\//host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:/\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http://\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:\\\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:/\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:\\\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http:////host/path"))
      .isEqualTo(parse("http://host/path"))
  }

  @Test
  fun resolveAuthoritySlashCountDoesntMatterWithDifferentScheme() {
    val base = parse("https://a/b/c")
    assertThat(base.resolve("http:host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http://host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:///host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\//host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http://\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:////host/path"))
      .isEqualTo(parse("http://host/path"))
  }

  @Test
  fun resolveAuthoritySlashCountMattersWithSameScheme() {
    val base = parse("http://a/b/c")
    assertThat(base.resolve("http:host/path"))
      .isEqualTo(parse("http://a/b/host/path"))
    assertThat(base.resolve("http:/host/path"))
      .isEqualTo(parse("http://a/host/path"))
    assertThat(base.resolve("http:\\host/path"))
      .isEqualTo(parse("http://a/host/path"))
    assertThat(base.resolve("http://host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:///host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\//host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http://\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\\\/host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:/\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:\\\\\\host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(base.resolve("http:////host/path"))
      .isEqualTo(parse("http://host/path"))
  }

  @Test
  fun username() {
    assertThat(parse("http://@host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http://user@host/path"))
      .isEqualTo(parse("http://user@host/path"))
  }

  /**
   * Given multiple '@' characters, the last one is the delimiter.
   */
  @Test
  fun authorityWithMultipleAtSigns() {
    val httpUrl = parse("http://foo@bar@baz/path")
    assertThat(httpUrl.username).isEqualTo("foo@bar")
    assertThat(httpUrl.password).isEqualTo("")
    assertThat(httpUrl).isEqualTo(parse("http://foo%40bar@baz/path"))
  }

  /**
   * Given multiple ':' characters, the first one is the delimiter.
   */
  @Test
  fun authorityWithMultipleColons() {
    val httpUrl = parse("http://foo:pass1@bar:pass2@baz/path")
    assertThat(httpUrl.username).isEqualTo("foo")
    assertThat(httpUrl.password).isEqualTo("pass1@bar:pass2")
    assertThat(httpUrl).isEqualTo(parse("http://foo:pass1%40bar%3Apass2@baz/path"))
  }

  @Test
  fun usernameAndPassword() {
    assertThat(parse("http://username:password@host/path"))
      .isEqualTo(parse("http://username:password@host/path"))
    assertThat(parse("http://username:@host/path"))
      .isEqualTo(parse("http://username@host/path"))
  }

  @Test
  fun passwordWithEmptyUsername() {
    // Chrome doesn't mind, but Firefox rejects URLs with empty usernames and non-empty passwords.
    assertThat(parse("http://:@host/path"))
      .isEqualTo(parse("http://host/path"))
    assertThat(parse("http://:password@@host/path").encodedPassword)
      .isEqualTo("password%40")
  }

  @Test
  fun unprintableCharactersArePercentEncoded() {
    assertThat(parse("http://host/\u0000").encodedPath).isEqualTo("/%00")
    assertThat(parse("http://host/\u0008").encodedPath).isEqualTo("/%08")
    assertThat(parse("http://host/\ufffd").encodedPath).isEqualTo("/%EF%BF%BD")
  }

  @Test
  fun hostContainsIllegalCharacter() {
    assertInvalid("http://\n/", "Invalid URL host: \"\n\"")
    assertInvalid("http:// /", "Invalid URL host: \" \"")
    assertInvalid("http://%20/", "Invalid URL host: \"%20\"")
  }

  @Test
  fun hostIpv6() {
    // Square braces are absent from host()...
    assertThat(parse("http://[::1]/").host).isEqualTo("::1")

    // ... but they're included in toString().
    assertThat(parse("http://[::1]/").toString()).isEqualTo("http://[::1]/")

    // IPv6 colons don't interfere with port numbers or passwords.
    assertThat(parse("http://[::1]:8080/").port).isEqualTo(8080)
    assertThat(parse("http://user:password@[::1]/").password).isEqualTo("password")
    assertThat(parse("http://user:password@[::1]:8080/").host).isEqualTo("::1")

    // Permit the contents of IPv6 addresses to be percent-encoded...
    assertThat(parse("http://[%3A%3A%31]/").host).isEqualTo("::1")

    // Including the Square braces themselves! (This is what Chrome does.)
    assertThat(parse("http://%5B%3A%3A1%5D/").host).isEqualTo("::1")
  }

  @Test
  fun hostIpv6AddressDifferentFormats() {
    // Multiple representations of the same address; see http://tools.ietf.org/html/rfc5952.
    val a3 = "2001:db8::1:0:0:1"
    assertThat(parse("http://[2001:db8:0:0:1:0:0:1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:0db8:0:0:1:0:0:1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:db8::1:0:0:1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:db8::0:1:0:0:1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:0db8::1:0:0:1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:db8:0:0:1::1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:db8:0000:0:1::1]").host).isEqualTo(a3)
    assertThat(parse("http://[2001:DB8:0:0:1::1]").host).isEqualTo(a3)
  }

  @Test
  fun hostIpv6AddressLeadingCompression() {
    assertThat(parse("http://[::0001]").host).isEqualTo("::1")
    assertThat(parse("http://[0000::0001]").host).isEqualTo("::1")
    assertThat(parse("http://[0000:0000:0000:0000:0000:0000:0000:0001]").host)
      .isEqualTo("::1")
    assertThat(parse("http://[0000:0000:0000:0000:0000:0000::0001]").host)
      .isEqualTo("::1")
  }

  @Test
  fun hostIpv6AddressTrailingCompression() {
    assertThat(parse("http://[0001:0000::]").host).isEqualTo("1::")
    assertThat(parse("http://[0001::0000]").host).isEqualTo("1::")
    assertThat(parse("http://[0001::]").host).isEqualTo("1::")
    assertThat(parse("http://[1::]").host).isEqualTo("1::")
  }

  @Test
  fun hostIpv6AddressTooManyDigitsInGroup() {
    assertInvalid(
      "http://[00000:0000:0000:0000:0000:0000:0000:0001]",
      "Invalid URL host: \"[00000:0000:0000:0000:0000:0000:0000:0001]\""
    )
    assertInvalid("http://[::00001]", "Invalid URL host: \"[::00001]\"")
  }

  @Test
  fun hostIpv6AddressMisplacedColons() {
    assertInvalid(
      "http://[:0000:0000:0000:0000:0000:0000:0000:0001]",
      "Invalid URL host: \"[:0000:0000:0000:0000:0000:0000:0000:0001]\""
    )
    assertInvalid(
      "http://[:::0000:0000:0000:0000:0000:0000:0000:0001]",
      "Invalid URL host: \"[:::0000:0000:0000:0000:0000:0000:0000:0001]\""
    )
    assertInvalid("http://[:1]", "Invalid URL host: \"[:1]\"")
    assertInvalid("http://[:::1]", "Invalid URL host: \"[:::1]\"")
    assertInvalid(
      "http://[0000:0000:0000:0000:0000:0000:0001:]",
      "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0001:]\""
    )
    assertInvalid(
      "http://[0000:0000:0000:0000:0000:0000:0000:0001:]",
      "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001:]\""
    )
    assertInvalid(
      "http://[0000:0000:0000:0000:0000:0000:0000:0001::]",
      "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001::]\""
    )
    assertInvalid(
      "http://[0000:0000:0000:0000:0000:0000:0000:0001:::]",
      "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0001:::]\""
    )
    assertInvalid("http://[1:]", "Invalid URL host: \"[1:]\"")
    assertInvalid("http://[1:::]", "Invalid URL host: \"[1:::]\"")
    assertInvalid("http://[1:::1]", "Invalid URL host: \"[1:::1]\"")
    assertInvalid(
      "http://[0000:0000:0000:0000::0000:0000:0000:0001]",
      "Invalid URL host: \"[0000:0000:0000:0000::0000:0000:0000:0001]\""
    )
  }

  @Test
  fun hostIpv6AddressTooManyGroups() {
    assertInvalid(
      "http://[0000:0000:0000:0000:0000:0000:0000:0000:0001]",
      "Invalid URL host: \"[0000:0000:0000:0000:0000:0000:0000:0000:0001]\""
    )
  }

  @Test
  fun hostIpv6AddressTooMuchCompression() {
    assertInvalid(
      "http://[0000::0000:0000:0000:0000::0001]",
      "Invalid URL host: \"[0000::0000:0000:0000:0000::0001]\""
    )
    assertInvalid(
      "http://[::0000:0000:0000:0000::0001]",
      "Invalid URL host: \"[::0000:0000:0000:0000::0001]\""
    )
  }

  @Test
  fun hostIpv6ScopedAddress() {
    // java.net.InetAddress parses scoped addresses. These aren't valid in URLs.
    assertInvalid("http://[::1%2544]", "Invalid URL host: \"[::1%2544]\"")
  }

  @Test
  fun hostIpv6AddressTooManyLeadingZeros() {
    // Guava's been buggy on this case. https://github.com/google/guava/issues/3116
    assertInvalid(
      "http://[2001:db8:0:0:1:0:0:00001]",
      "Invalid URL host: \"[2001:db8:0:0:1:0:0:00001]\""
    )
  }

  @Test
  fun hostIpv6WithIpv4Suffix() {
    assertThat(parse("http://[::1:255.255.255.255]/").host)
      .isEqualTo("::1:ffff:ffff")
    assertThat(parse("http://[0:0:0:0:0:1:0.0.0.0]/").host).isEqualTo("::1:0:0")
  }

  @Test
  fun hostIpv6WithIpv4SuffixWithOctalPrefix() {
    // Chrome interprets a leading '0' as octal; Firefox rejects them. (We reject them.)
    assertInvalid(
      "http://[0:0:0:0:0:1:0.0.0.000000]/",
      "Invalid URL host: \"[0:0:0:0:0:1:0.0.0.000000]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:0.010.0.010]/",
      "Invalid URL host: \"[0:0:0:0:0:1:0.010.0.010]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:0.0.0.000001]/",
      "Invalid URL host: \"[0:0:0:0:0:1:0.0.0.000001]\""
    )
  }

  @Test
  fun hostIpv6WithIpv4SuffixWithHexadecimalPrefix() {
    // Chrome interprets a leading '0x' as hexadecimal; Firefox rejects them. (We reject them.)
    assertInvalid(
      "http://[0:0:0:0:0:1:0.0x10.0.0x10]/",
      "Invalid URL host: \"[0:0:0:0:0:1:0.0x10.0.0x10]\""
    )
  }

  @Test
  fun hostIpv6WithMalformedIpv4Suffix() {
    assertInvalid(
      "http://[0:0:0:0:0:1:0.0:0.0]/",
      "Invalid URL host: \"[0:0:0:0:0:1:0.0:0.0]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:0.0-0.0]/",
      "Invalid URL host: \"[0:0:0:0:0:1:0.0-0.0]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:.255.255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:1:.255.255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:255..255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:1:255..255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:255.255..255]/",
      "Invalid URL host: \"[0:0:0:0:0:1:255.255..255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:0:1:255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:0:1:255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:256.255.255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:1:256.255.255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:ff.255.255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:1:ff.255.255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:0:1:255.255.255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:0:1:255.255.255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:1:255.255.255.255]/",
      "Invalid URL host: \"[0:0:0:0:1:255.255.255.255]\""
    )
    assertInvalid(
      "http://[0:0:0:0:1:0.0.0.0:1]/",
      "Invalid URL host: \"[0:0:0:0:1:0.0.0.0:1]\""
    )
    assertInvalid(
      "http://[0:0.0.0.0:1:0:0:0:0:1]/",
      "Invalid URL host: \"[0:0.0.0.0:1:0:0:0:0:1]\""
    )
    assertInvalid(
      "http://[0.0.0.0:0:0:0:0:0:1]/",
      "Invalid URL host: \"[0.0.0.0:0:0:0:0:0:1]\""
    )
  }

  @Test
  fun hostIpv6WithIncompleteIpv4Suffix() {
    // To Chrome & Safari these are well-formed; Firefox disagrees. (We're consistent with Firefox).
    assertInvalid(
      "http://[0:0:0:0:0:1:255.255.255.]/",
      "Invalid URL host: \"[0:0:0:0:0:1:255.255.255.]\""
    )
    assertInvalid(
      "http://[0:0:0:0:0:1:255.255.255]/",
      "Invalid URL host: \"[0:0:0:0:0:1:255.255.255]\""
    )
  }

  @Test
  fun hostIpv6Malformed() {
    assertInvalid("http://[::g]/", "Invalid URL host: \"[::g]\"")
  }

  /**
   * The builder permits square braces but does not require them.
   */
  @Test
  fun hostIpv6Builder() {
    val base = parse("http://example.com/")
    assertThat(base.newBuilder().host("[::1]").build().toString())
      .isEqualTo("http://[::1]/")
    assertThat(base.newBuilder().host("[::0001]").build().toString())
      .isEqualTo("http://[::1]/")
    assertThat(base.newBuilder().host("::1").build().toString())
      .isEqualTo("http://[::1]/")
    assertThat(base.newBuilder().host("::0001").build().toString())
      .isEqualTo("http://[::1]/")
  }

  @Test
  fun relativePath() {
    val base = parse("http://host/a/b/c")
    assertThat(base.resolve("d/e/f")).isEqualTo(parse("http://host/a/b/d/e/f"))
    assertThat(base.resolve("../../d/e/f")).isEqualTo(parse("http://host/d/e/f"))
    assertThat(base.resolve("..")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("../..")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve("../../..")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve(".")).isEqualTo(parse("http://host/a/b/"))
    assertThat(base.resolve("././..")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("c/d/../e/../")).isEqualTo(parse("http://host/a/b/c/"))
    assertThat(base.resolve("..e/")).isEqualTo(parse("http://host/a/b/..e/"))
    assertThat(base.resolve("e/f../")).isEqualTo(parse("http://host/a/b/e/f../"))
    assertThat(base.resolve("%2E.")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve(".%2E")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("%2E%2E")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("%2e.")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve(".%2e")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("%2e%2e")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("%2E")).isEqualTo(parse("http://host/a/b/"))
    assertThat(base.resolve("%2e")).isEqualTo(parse("http://host/a/b/"))
  }

  @Test
  fun relativePathWithTrailingSlash() {
    val base = parse("http://host/a/b/c/")
    assertThat(base.resolve("..")).isEqualTo(parse("http://host/a/b/"))
    assertThat(base.resolve("../")).isEqualTo(parse("http://host/a/b/"))
    assertThat(base.resolve("../..")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("../../")).isEqualTo(parse("http://host/a/"))
    assertThat(base.resolve("../../..")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve("../../../")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve("../../../..")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve("../../../../")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve("../../../../a")).isEqualTo(parse("http://host/a"))
    assertThat(base.resolve("../../../../a/..")).isEqualTo(parse("http://host/"))
    assertThat(base.resolve("../../../../a/b/..")).isEqualTo(parse("http://host/a/"))
  }

  @Test
  fun pathWithBackslash() {
    val base = parse("http://host/a/b/c")
    assertThat(base.resolve("d\\e\\f")).isEqualTo(parse("http://host/a/b/d/e/f"))
    assertThat(base.resolve("../..\\d\\e\\f"))
      .isEqualTo(parse("http://host/d/e/f"))
    assertThat(base.resolve("..\\..")).isEqualTo(parse("http://host/"))
  }

  @Test
  fun relativePathWithSameScheme() {
    val base = parse("http://host/a/b/c")
    assertThat(base.resolve("http:d/e/f")).isEqualTo(parse("http://host/a/b/d/e/f"))
    assertThat(base.resolve("http:../../d/e/f"))
      .isEqualTo(parse("http://host/d/e/f"))
  }

  @Test
  fun decodeUsername() {
    assertThat(parse("http://user@host/").username).isEqualTo("user")
    assertThat(parse("http://%F0%9F%8D%A9@host/").username).isEqualTo("\uD83C\uDF69")
  }

  @Test
  fun decodePassword() {
    assertThat(parse("http://user:password@host/").password).isEqualTo("password")
    assertThat(parse("http://user:@host/").password).isEqualTo("")
    assertThat(parse("http://user:%F0%9F%8D%A9@host/").password)
      .isEqualTo("\uD83C\uDF69")
  }

  @Test
  fun decodeSlashCharacterInDecodedPathSegment() {
    assertThat(parse("http://host/a%2Fb%2Fc").pathSegments).containsExactly("a/b/c")
  }

  @Test
  fun decodeEmptyPathSegments() {
    assertThat(parse("http://host/").pathSegments).containsExactly("")
  }

  @Test
  fun percentDecode() {
    assertThat(parse("http://host/%00").pathSegments).containsExactly("\u0000")
    assertThat(parse("http://host/a/%E2%98%83/c").pathSegments)
      .containsExactly("a", "\u2603", "c")
    assertThat(parse("http://host/a/%F0%9F%8D%A9/c").pathSegments)
      .containsExactly("a", "\uD83C\uDF69", "c")
    assertThat(parse("http://host/a/%62/c").pathSegments)
      .containsExactly("a", "b", "c")
    assertThat(parse("http://host/a/%7A/c").pathSegments)
      .containsExactly("a", "z", "c")
    assertThat(parse("http://host/a/%7a/c").pathSegments)
      .containsExactly("a", "z", "c")
  }

  @Test
  fun malformedPercentEncoding() {
    assertThat(parse("http://host/a%f/b").pathSegments).containsExactly("a%f", "b")
    assertThat(parse("http://host/%/b").pathSegments).containsExactly("%", "b")
    assertThat(parse("http://host/%").pathSegments).containsExactly("%")
    assertThat(parse("http://github.com/%%30%30").pathSegments)
      .containsExactly("%00")
  }

  @Test
  fun malformedUtf8Encoding() {
    // Replace a partial UTF-8 sequence with the Unicode replacement character.
    assertThat(parse("http://host/a/%E2%98x/c").pathSegments)
      .containsExactly("a", "\ufffdx", "c")
  }

  @Test
  fun incompleteUrlComposition() {
    val noHost = assertFailsWith<IllegalStateException> {
      HttpUrl.Builder().scheme("http").build()
    }
    assertThat(noHost.message).isEqualTo("host == null")
    val noScheme = assertFailsWith<IllegalStateException> {
      HttpUrl.Builder().host("host").build()
    }
    assertThat(noScheme.message).isEqualTo("scheme == null")
  }

  @Test
  fun builderToString() {
    assertThat(parse("https://host.com/path").newBuilder().toString())
      .isEqualTo("https://host.com/path")
  }

  @Test
  fun incompleteBuilderToString() {
    assertThat(HttpUrl.Builder().scheme("https").encodedPath("/path").toString())
      .isEqualTo("https:///path")
    assertThat(HttpUrl.Builder().host("host.com").encodedPath("/path").toString())
      .isEqualTo("//host.com/path")
    assertThat(
      HttpUrl.Builder().host("host.com").encodedPath("/path").port(8080).toString()
    )
      .isEqualTo("//host.com:8080/path")
  }

  @Test
  fun changingSchemeChangesDefaultPort() {
    assertThat(
      parse("http://example.com")
        .newBuilder()
        .scheme("https")
        .build().port
    ).isEqualTo(443)
    assertThat(
      parse("https://example.com")
        .newBuilder()
        .scheme("http")
        .build().port
    ).isEqualTo(80)
    assertThat(
      parse("https://example.com:1234")
        .newBuilder()
        .scheme("http")
        .build().port
    ).isEqualTo(1234)
  }

  @Test
  fun composeWithEncodedPath() {
    val url = HttpUrl.Builder()
      .scheme("http")
      .host("host")
      .encodedPath("/a%2Fb/c")
      .build()
    assertThat(url.toString()).isEqualTo("http://host/a%2Fb/c")
    assertThat(url.encodedPath).isEqualTo("/a%2Fb/c")
    assertThat(url.pathSegments).containsExactly("a/b", "c")
  }

  @Test
  fun composeMixingPathSegments() {
    val url = HttpUrl.Builder()
      .scheme("http")
      .host("host")
      .encodedPath("/a%2fb/c")
      .addPathSegment("d%25e")
      .addEncodedPathSegment("f%25g")
      .build()
    assertThat(url.toString()).isEqualTo("http://host/a%2fb/c/d%2525e/f%25g")
    assertThat(url.encodedPath).isEqualTo("/a%2fb/c/d%2525e/f%25g")
    assertThat(url.encodedPathSegments)
      .containsExactly("a%2fb", "c", "d%2525e", "f%25g")
    assertThat(url.pathSegments).containsExactly("a/b", "c", "d%25e", "f%g")
  }

  @Test
  fun composeWithAddSegment() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder()
      .addPathSegment("")
      .build().encodedPath)
      .isEqualTo("/a/b/c/")
    assertThat(base.newBuilder()
      .addPathSegment("")
      .addPathSegment("d")
      .build().encodedPath)
      .isEqualTo("/a/b/c/d")
    assertThat(base.newBuilder()
      .addPathSegment("..")
      .build().encodedPath)
      .isEqualTo("/a/b/")
    assertThat(base.newBuilder()
      .addPathSegment("")
      .addPathSegment("..")
      .build().encodedPath)
      .isEqualTo("/a/b/")
    assertThat(base.newBuilder()
      .addPathSegment("")
      .addPathSegment("")
      .build().encodedPath)
      .isEqualTo("/a/b/c/")
  }

  @Test
  fun addPathSegments() {
    val base = parse("http://host/a/b/c")

    // Add a string with zero slashes: resulting URL gains one slash.
    assertThat(base.newBuilder().addPathSegments("").build().encodedPath)
      .isEqualTo("/a/b/c/")
    assertThat(base.newBuilder().addPathSegments("d").build().encodedPath)
      .isEqualTo("/a/b/c/d")

    // Add a string with one slash: resulting URL gains two slashes.
    assertThat(base.newBuilder().addPathSegments("/").build().encodedPath)
      .isEqualTo("/a/b/c//")
    assertThat(base.newBuilder().addPathSegments("d/").build().encodedPath)
      .isEqualTo("/a/b/c/d/")
    assertThat(base.newBuilder().addPathSegments("/d").build().encodedPath)
      .isEqualTo("/a/b/c//d")

    // Add a string with two slashes: resulting URL gains three slashes.
    assertThat(base.newBuilder().addPathSegments("//").build().encodedPath)
      .isEqualTo("/a/b/c///")
    assertThat(base.newBuilder().addPathSegments("/d/").build().encodedPath)
      .isEqualTo("/a/b/c//d/")
    assertThat(base.newBuilder().addPathSegments("d//").build().encodedPath)
      .isEqualTo("/a/b/c/d//")
    assertThat(base.newBuilder().addPathSegments("//d").build().encodedPath)
      .isEqualTo("/a/b/c///d")
    assertThat(base.newBuilder().addPathSegments("d/e/f").build().encodedPath)
      .isEqualTo("/a/b/c/d/e/f")
  }

  @Test
  fun addPathSegmentsOntoTrailingSlash() {
    val base = parse("http://host/a/b/c/")

    // Add a string with zero slashes: resulting URL gains zero slashes.
    assertThat(base.newBuilder().addPathSegments("").build().encodedPath)
      .isEqualTo("/a/b/c/")
    assertThat(base.newBuilder().addPathSegments("d").build().encodedPath)
      .isEqualTo("/a/b/c/d")

    // Add a string with one slash: resulting URL gains one slash.
    assertThat(base.newBuilder().addPathSegments("/").build().encodedPath)
      .isEqualTo("/a/b/c//")
    assertThat(base.newBuilder().addPathSegments("d/").build().encodedPath)
      .isEqualTo("/a/b/c/d/")
    assertThat(base.newBuilder().addPathSegments("/d").build().encodedPath)
      .isEqualTo("/a/b/c//d")

    // Add a string with two slashes: resulting URL gains two slashes.
    assertThat(base.newBuilder().addPathSegments("//").build().encodedPath)
      .isEqualTo("/a/b/c///")
    assertThat(base.newBuilder().addPathSegments("/d/").build().encodedPath)
      .isEqualTo("/a/b/c//d/")
    assertThat(base.newBuilder().addPathSegments("d//").build().encodedPath)
      .isEqualTo("/a/b/c/d//")
    assertThat(base.newBuilder().addPathSegments("//d").build().encodedPath)
      .isEqualTo("/a/b/c///d")
    assertThat(base.newBuilder().addPathSegments("d/e/f").build().encodedPath)
      .isEqualTo("/a/b/c/d/e/f")
  }

  @Test
  fun addPathSegmentsWithBackslash() {
    val base = parse("http://host/")
    assertThat(base.newBuilder().addPathSegments("d\\e").build().encodedPath)
      .isEqualTo("/d/e")
    assertThat(base.newBuilder().addEncodedPathSegments("d\\e").build().encodedPath)
      .isEqualTo("/d/e")
  }

  @Test
  fun addPathSegmentsWithEmptyPaths() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addPathSegments("/d/e///f").build().encodedPath)
      .isEqualTo("/a/b/c//d/e///f")
  }

  @Test
  fun addEncodedPathSegments() {
    val base = parse("http://host/a/b/c")
    assertThat(
      base.newBuilder().addEncodedPathSegments("d/e/%20/\n").build().encodedPath as Any
    ).isEqualTo("/a/b/c/d/e/%20/")
  }

  @Test
  fun addPathSegmentDotDoesNothing() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addPathSegment(".").build().encodedPath)
      .isEqualTo("/a/b/c")
  }

  @Test
  fun addPathSegmentEncodes() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addPathSegment("%2e").build().encodedPath)
      .isEqualTo("/a/b/c/%252e")
    assertThat(base.newBuilder().addPathSegment("%2e%2e").build().encodedPath)
      .isEqualTo("/a/b/c/%252e%252e")
  }

  @Test
  fun addPathSegmentDotDotPopsDirectory() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addPathSegment("..").build().encodedPath)
      .isEqualTo("/a/b/")
  }

  @Test
  fun addPathSegmentDotAndIgnoredCharacter() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addPathSegment(".\n").build().encodedPath)
      .isEqualTo("/a/b/c/.%0A")
  }

  @Test
  fun addEncodedPathSegmentDotAndIgnoredCharacter() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addEncodedPathSegment(".\n").build().encodedPath)
      .isEqualTo("/a/b/c")
  }

  @Test
  fun addEncodedPathSegmentDotDotAndIgnoredCharacter() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().addEncodedPathSegment("..\n").build().encodedPath)
      .isEqualTo("/a/b/")
  }

  @Test
  fun setPathSegment() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().setPathSegment(0, "d").build().encodedPath)
      .isEqualTo("/d/b/c")
    assertThat(base.newBuilder().setPathSegment(1, "d").build().encodedPath)
      .isEqualTo("/a/d/c")
    assertThat(base.newBuilder().setPathSegment(2, "d").build().encodedPath)
      .isEqualTo("/a/b/d")
  }

  @Test
  fun setPathSegmentAcceptsEmpty() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().setPathSegment(0, "").build().encodedPath)
      .isEqualTo("//b/c")
    assertThat(base.newBuilder().setPathSegment(2, "").build().encodedPath)
      .isEqualTo("/a/b/")
  }

  @Test
  fun setPathSegmentRejectsDot() {
    val base = parse("http://host/a/b/c")
    assertFailsWith<IllegalArgumentException> {
      base.newBuilder().setPathSegment(0, ".")
    }
  }

  @Test
  fun setPathSegmentRejectsDotDot() {
    val base = parse("http://host/a/b/c")
    assertFailsWith<IllegalArgumentException> {
      base.newBuilder().setPathSegment(0, "..")
    }
  }

  @Test
  fun setPathSegmentOutOfBounds() {
    assertFailsWith<IndexOutOfBoundsException> {
      HttpUrl.Builder().setPathSegment(1, "a")
    }
  }

  @Test
  fun setEncodedPathSegmentEncodes() {
    val base = parse("http://host/a/b/c")
    assertThat(base.newBuilder().setEncodedPathSegment(0, "%25").build().encodedPath)
      .isEqualTo("/%25/b/c")
  }

  @Test
  fun setEncodedPathSegmentRejectsDot() {
    val base = parse("http://host/a/b/c")
    assertFailsWith<IllegalArgumentException> {
      base.newBuilder().setEncodedPathSegment(0, ".")
    }
  }

  @Test
  fun setEncodedPathSegmentRejectsDotDot() {
    val base = parse("http://host/a/b/c")
    assertFailsWith<IllegalArgumentException> {
      base.newBuilder().setEncodedPathSegment(0, "..")
    }
  }

  @Test
  fun setEncodedPathSegmentOutOfBounds() {
    assertFailsWith<IndexOutOfBoundsException> {
      HttpUrl.Builder().setEncodedPathSegment(1, "a")
    }
  }

  @Test
  fun removePathSegment() {
    val base = parse("http://host/a/b/c")
    val url = base.newBuilder()
      .removePathSegment(0)
      .build()
    assertThat(url.encodedPath).isEqualTo("/b/c")
  }

  @Test
  fun removePathSegmentDoesntRemovePath() {
    val base = parse("http://host/a/b/c")
    val url = base.newBuilder()
      .removePathSegment(0)
      .removePathSegment(0)
      .removePathSegment(0)
      .build()
    assertThat(url.pathSegments).containsExactly("")
    assertThat(url.encodedPath).isEqualTo("/")
  }

  @Test
  fun removePathSegmentOutOfBounds() {
    assertFailsWith<IndexOutOfBoundsException> {
      HttpUrl.Builder().removePathSegment(1)
    }
  }

  /**
   * When callers use `addEncodedQueryParameter()` we only encode what's strictly required. We
   * retain the encoded (or non-encoded) state of the input.
   */
  @Test
  fun queryCharactersNotReencodedWhenComposedWithAddEncoded() {
    val url = HttpUrl.Builder()
      .scheme("http")
      .host("host")
      .addEncodedQueryParameter("a", "!$(),/:;?@[]\\^`{|}~")
      .build()
    assertThat(url.toString()).isEqualTo("http://host/?a=!$(),/:;?@[]\\^`{|}~")
    assertThat(url.queryParameter("a")).isEqualTo("!$(),/:;?@[]\\^`{|}~")
  }

  /**
   * When callers parse a URL with query components that aren't encoded, we shouldn't convert them
   * into a canonical form because doing so could be semantically different.
   */
  @Test
  fun queryCharactersNotReencodedWhenParsed() {
    val url = parse("http://host/?a=!$(),/:;?@[]\\^`{|}~")
    assertThat(url.toString()).isEqualTo("http://host/?a=!$(),/:;?@[]\\^`{|}~")
    assertThat(url.queryParameter("a")).isEqualTo("!$(),/:;?@[]\\^`{|}~")
  }

  @Test
  fun composeQueryRemoveQueryParameter() {
    val url = parse("http://host/").newBuilder()
      .addQueryParameter("a+=& b", "c+=& d")
      .removeAllQueryParameters("a+=& b")
      .build()
    assertThat(url.toString()).isEqualTo("http://host/")
    assertThat(url.queryParameter("a+=& b")).isNull()
  }

  @Test
  fun composeQueryRemoveEncodedQueryParameter() {
    val url = parse("http://host/").newBuilder()
      .addEncodedQueryParameter("a+=& b", "c+=& d")
      .removeAllEncodedQueryParameters("a+=& b")
      .build()
    assertThat(url.toString()).isEqualTo("http://host/")
    assertThat(url.queryParameter("a =& b")).isNull()
  }

  @Test
  fun absentQueryIsZeroNameValuePairs() {
    val url = parse("http://host/").newBuilder()
      .query(null)
      .build()
    assertThat(url.querySize).isEqualTo(0)
  }

  @Test
  fun emptyQueryIsSingleNameValuePairWithEmptyKey() {
    val url = parse("http://host/").newBuilder()
      .query("")
      .build()
    assertThat(url.querySize).isEqualTo(1)
    assertThat(url.queryParameterName(0)).isEqualTo("")
    assertThat(url.queryParameterValue(0)).isNull()
  }

  @Test
  fun ampersandQueryIsTwoNameValuePairsWithEmptyKeys() {
    val url = parse("http://host/").newBuilder()
      .query("&")
      .build()
    assertThat(url.querySize).isEqualTo(2)
    assertThat(url.queryParameterName(0)).isEqualTo("")
    assertThat(url.queryParameterValue(0)).isNull()
    assertThat(url.queryParameterName(1)).isEqualTo("")
    assertThat(url.queryParameterValue(1)).isNull()
  }

  @Test
  fun removeAllDoesNotRemoveQueryIfNoParametersWereRemoved() {
    val url = parse("http://host/").newBuilder()
      .query("")
      .removeAllQueryParameters("a")
      .build()
    assertThat(url.toString()).isEqualTo("http://host/?")
  }

  @Test
  fun queryParametersWithRepeatedName() {
    val url = parse("http://host/?foo[]=1&foo[]=2&foo[]=3")
    assertThat(url.querySize).isEqualTo(3)
    assertThat(url.queryParameterNames).isEqualTo(setOf("foo[]"))
    assertThat(url.queryParameterValue(0)).isEqualTo("1")
    assertThat(url.queryParameterValue(1)).isEqualTo("2")
    assertThat(url.queryParameterValue(2)).isEqualTo("3")
    assertThat(url.queryParameterValues("foo[]")).containsExactly("1", "2", "3")
  }

  @Test
  fun queryParameterLookupWithNonCanonicalEncoding() {
    val url = parse("http://host/?%6d=m&+=%20")
    assertThat(url.queryParameterName(0)).isEqualTo("m")
    assertThat(url.queryParameterName(1)).isEqualTo(" ")
    assertThat(url.queryParameter("m")).isEqualTo("m")
    assertThat(url.queryParameter(" ")).isEqualTo(" ")
  }

  @Test
  fun parsedQueryDoesntIncludeFragment() {
    val url = parse("http://host/?#fragment")
    assertThat(url.fragment).isEqualTo("fragment")
    assertThat(url.query).isEqualTo("")
    assertThat(url.encodedQuery).isEqualTo("")
  }

  /**
   * Although HttpUrl prefers percent-encodings in uppercase, it should preserve the exact structure
   * of the original encoding.
   */
  @Test
  fun rawEncodingRetained() {
    val urlString = "http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D#%6d%6D"
    val url = parse(urlString)
    assertThat(url.encodedUsername).isEqualTo("%6d%6D")
    assertThat(url.encodedPassword).isEqualTo("%6d%6D")
    assertThat(url.encodedPath).isEqualTo("/%6d%6D")
    assertThat(url.encodedPathSegments).containsExactly("%6d%6D")
    assertThat(url.encodedQuery).isEqualTo("%6d%6D")
    assertThat(url.encodedFragment).isEqualTo("%6d%6D")
    assertThat(url.toString()).isEqualTo(urlString)
    assertThat(url.newBuilder().build().toString()).isEqualTo(urlString)
    assertThat(url.resolve("").toString())
      .isEqualTo("http://%6d%6D:%6d%6D@host/%6d%6D?%6d%6D")
  }

  @Test
  fun clearFragment() {
    val url = parse("http://host/#fragment")
      .newBuilder()
      .fragment(null)
      .build()
    assertThat(url.toString()).isEqualTo("http://host/")
    assertThat(url.fragment).isNull()
    assertThat(url.encodedFragment).isNull()
  }

  @Test
  fun clearEncodedFragment() {
    val url = parse("http://host/#fragment")
      .newBuilder()
      .encodedFragment(null)
      .build()
    assertThat(url.toString()).isEqualTo("http://host/")
    assertThat(url.fragment).isNull()
    assertThat(url.encodedFragment).isNull()
  }

  @Test
  fun unparseableTopPrivateDomain() {
    assertInvalid("http://a../", "Invalid URL host: \"a..\"")
    assertInvalid("http://..a/", "Invalid URL host: \"..a\"")
    assertInvalid("http://a..b/", "Invalid URL host: \"a..b\"")
    assertInvalid("http://.a/", "Invalid URL host: \".a\"")
    assertInvalid("http://../", "Invalid URL host: \"..\"")
  }

  @Test
  fun trailingDotIsOkay() {
    val name251 = "a.".repeat(125) + "a"
    assertThat(parse("http://a./").toString()).isEqualTo("http://a./")
    assertThat(parse("http://${name251}a./").toString()).isEqualTo("http://${name251}a./")
    assertThat(parse("http://${name251}aa/").toString()).isEqualTo("http://${name251}aa/")
    assertInvalid("http://${name251}aa./", "Invalid URL host: \"${name251}aa.\"")
  }

  @Test
  fun labelIsEmpty() {
    assertInvalid("http:///", "Invalid URL host: \"\"")
    assertInvalid("http://a..b/", "Invalid URL host: \"a..b\"")
    assertInvalid("http://.a/", "Invalid URL host: \".a\"")
    assertInvalid("http://./", "Invalid URL host: \".\"")
    assertInvalid("http://../", "Invalid URL host: \"..\"")
    assertInvalid("http://.../", "Invalid URL host: \"...\"")
    assertInvalid("http://…/", "Invalid URL host: \"…\"")
  }

  @Test
  fun labelTooLong() {
    val a63 = "a".repeat(63)
    assertThat(parse("http://$a63/").toString()).isEqualTo("http://$a63/")
    assertThat(parse("http://a.$a63/").toString()).isEqualTo("http://a.$a63/")
    assertThat(parse("http://$a63.a/").toString()).isEqualTo("http://$a63.a/")
    assertInvalid("http://a$a63/", "Invalid URL host: \"a$a63\"")
    assertInvalid("http://a.a$a63/", "Invalid URL host: \"a.a$a63\"")
    assertInvalid("http://a$a63.a/", "Invalid URL host: \"a$a63.a\"")
  }

  @Test
  fun hostnameTooLong() {
    val dotA126 = "a.".repeat(126)
    assertThat(parse("http://a$dotA126/").toString())
      .isEqualTo("http://a$dotA126/")
    assertInvalid("http://aa$dotA126/", "Invalid URL host: \"aa$dotA126\"")
  }
}
