/*
 * Copyright (C) 2013 Square, Inc.
 * Copyright (C) 2011 The Guava Authors
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

import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.platform.Platform.Companion.isAndroid

/**
 * Test MediaType API and parsing.
 *
 * This test includes tests from [Guava's](https://code.google.com/p/guava-libraries/)
 * MediaTypeTest.
 */
open class MediaTypeTest {
  protected open fun parse(string: String): MediaType = string.toMediaTypeOrNull()!!

  protected open fun assertInvalid(string: String, exceptionMessage: String?) {
    assertNull(string.toMediaTypeOrNull(), exceptionMessage)
  }

  @Test fun testParse() {
    val mediaType = parse("text/plain;boundary=foo;charset=utf-8")
    assertEquals("text", mediaType.type)
    assertEquals("plain", mediaType.subtype)
    assertEquals("UTF-8", mediaType.charset()!!.name())
    assertEquals("text/plain;boundary=foo;charset=utf-8", mediaType.toString())
    assertEquals(mediaType, parse("text/plain;boundary=foo;charset=utf-8"))
    assertEquals(
      mediaType.hashCode(),
      parse("text/plain;boundary=foo;charset=utf-8").hashCode()
    )
  }

  @Test fun testValidParse() {
    assertMediaType("text/plain")
    assertMediaType("application/atom+xml; charset=utf-8")
    assertMediaType("application/atom+xml; a=1; a=2; b=3")
    assertMediaType("image/gif; foo=bar")
    assertMediaType("text/plain; a=1")
    assertMediaType("text/plain; a=1; a=2; b=3")
    assertMediaType("text/plain; charset=utf-16")
    assertMediaType("text/plain; \t \n \r a=b")
    assertMediaType("text/plain;")
    assertMediaType("text/plain; ")
    assertMediaType("text/plain; a=1;")
    assertMediaType("text/plain; a=1; ")
    assertMediaType("text/plain; a=1;; b=2")
    assertMediaType("text/plain;;")
    assertMediaType("text/plain; ;")
  }

  @Test fun testInvalidParse() {
    assertInvalid("", "No subtype found for: \"\"")
    assertInvalid("/", "No subtype found for: \"/\"")
    assertInvalid("text", "No subtype found for: \"text\"")
    assertInvalid("text/", "No subtype found for: \"text/\"")
    assertInvalid("te<t/plain", "No subtype found for: \"te<t/plain\"")
    assertInvalid(" text/plain", "No subtype found for: \" text/plain\"")
    assertInvalid("te xt/plain", "No subtype found for: \"te xt/plain\"")
    assertInvalid("text /plain", "No subtype found for: \"text /plain\"")
    assertInvalid("text/ plain", "No subtype found for: \"text/ plain\"")
    assertInvalid(
      "text/pl@in",
      "Parameter is not formatted correctly: \"@in\" for: \"text/pl@in\""
    )
    assertInvalid(
      "text/plain; a",
      "Parameter is not formatted correctly: \"a\" for: \"text/plain; a\""
    )
    assertInvalid(
      "text/plain; a=",
      "Parameter is not formatted correctly: \"a=\" for: \"text/plain; a=\""
    )
    assertInvalid(
      "text/plain; a=@",
      "Parameter is not formatted correctly: \"a=@\" for: \"text/plain; a=@\""
    )
    assertInvalid(
      "text/plain; a=\"@",
      "Parameter is not formatted correctly: \"a=\"@\" for: \"text/plain; a=\"@\""
    )
    assertInvalid(
      "text/plain; a=1; b",
      "Parameter is not formatted correctly: \"b\" for: \"text/plain; a=1; b\""
    )
    assertInvalid(
      "text/plain; a=1; b=",
      "Parameter is not formatted correctly: \"b=\" for: \"text/plain; a=1; b=\""
    )
    assertInvalid(
      "text/plain; a=\u2025",
      "Parameter is not formatted correctly: \"a=‥\" for: \"text/plain; a=‥\""
    )
    assertInvalid(
      "text/pl ain",
      "Parameter is not formatted correctly: \" ain\" for: \"text/pl ain\""
    )
    assertInvalid(
      "text/plain ",
      "Parameter is not formatted correctly: \" \" for: \"text/plain \""
    )
    assertInvalid(
      "text/plain ; a=1",
      "Parameter is not formatted correctly: \" ; a=1\" for: \"text/plain ; a=1\""
    )
  }

  @Test fun testDoubleQuotesAreSpecial() {
    val mediaType = parse("text/plain;a=\";charset=utf-8;b=\"")
    assertNull(mediaType.charset())
  }

  @Test fun testSingleQuotesAreNotSpecial() {
    val mediaType = parse("text/plain;a=';charset=utf-8;b='")
    assertEquals("UTF-8", mediaType.charset()!!.name())
  }

  @Test fun testParseWithSpecialCharacters() {
    val mediaType = parse("!#$%&'*+-.{|}~/!#$%&'*+-.{|}~; !#$%&'*+-.{|}~=!#$%&'*+-.{|}~")
    assertEquals("!#$%&'*+-.{|}~", mediaType.type)
    assertEquals("!#$%&'*+-.{|}~", mediaType.subtype)
  }

  @Test fun testCharsetIsOneOfManyParameters() {
    val mediaType = parse("text/plain;a=1;b=2;charset=utf-8;c=3")
    assertEquals("text", mediaType.type)
    assertEquals("plain", mediaType.subtype)
    assertEquals("UTF-8", mediaType.charset()!!.name())
    assertEquals("utf-8", mediaType.parameter("charset"))
    assertEquals("1", mediaType.parameter("a"))
    assertEquals("2", mediaType.parameter("b"))
    assertEquals("3", mediaType.parameter("c"))
    assertEquals("utf-8", mediaType.parameter("CHARSET"))
    assertEquals("1", mediaType.parameter("A"))
    assertEquals("2", mediaType.parameter("B"))
    assertEquals("3", mediaType.parameter("C"))
  }

  @Test fun testCharsetAndQuoting() {
    val mediaType = parse("text/plain;a=\";charset=us-ascii\";charset=\"utf-8\";b=\"iso-8859-1\"")
    assertEquals("UTF-8", mediaType.charset()!!.name())
  }

  @Test fun testDuplicatedCharsets() {
    val mediaType = parse("text/plain; charset=utf-8; charset=UTF-8")
    assertEquals("UTF-8", mediaType.charset()!!.name())
  }

  @Test fun testMultipleCharsetsReturnsFirstMatch() {
    val mediaType = parse("text/plain; charset=utf-8; charset=utf-16")
    assertEquals("UTF-8", mediaType.charset()!!.name())
  }

  @Test fun testIllegalCharsetName() {
    val mediaType = parse("text/plain; charset=\"!@#$%^&*()\"")
    assertNull(mediaType.charset())
  }

  @Test fun testUnsupportedCharset() {
    val mediaType = parse("text/plain; charset=utf-wtf")
    assertNull(mediaType.charset())
  }

  /**
   * This is invalid according to RFC 822. But it's what Chrome does and it avoids a potentially
   * unpleasant IllegalCharsetNameException.
   */
  @Test fun testCharsetNameIsSingleQuoted() {
    val mediaType = parse("text/plain;charset='utf-8'")
    assertEquals("UTF-8", mediaType.charset()!!.name())
  }

  @Test fun testCharsetNameIsDoubleQuotedAndSingleQuoted() {
    val mediaType = parse("text/plain;charset=\"'utf-8'\"")
    if (isAndroid) {
      // Charset.forName("'utf-8'") == UTF-8
      assertEquals("UTF-8", mediaType.charset()!!.name())
    } else {
      assertNull(mediaType.charset())
    }
  }

  @Test fun testCharsetNameIsDoubleQuotedSingleQuote() {
    val mediaType = parse("text/plain;charset=\"'\"")
    assertNull(mediaType.charset())
  }

  @Test fun testDefaultCharset() {
    val noCharset = parse("text/plain")
    assertEquals(
      "UTF-8", noCharset.charset(StandardCharsets.UTF_8)!!.name()
    )
    assertEquals(
      "US-ASCII", noCharset.charset(StandardCharsets.US_ASCII)!!.name()
    )
    val charset = parse("text/plain; charset=iso-8859-1")
    assertEquals(
      "ISO-8859-1", charset.charset(StandardCharsets.UTF_8)!!.name()
    )
    assertEquals(
      "ISO-8859-1", charset.charset(StandardCharsets.US_ASCII)!!.name()
    )
  }

  @Test fun testParseDanglingSemicolon() {
    val mediaType = parse("text/plain;")
    assertEquals("text", mediaType.type)
    assertEquals("plain", mediaType.subtype)
    assertNull(mediaType.charset())
    assertEquals("text/plain;", mediaType.toString())
  }

  @Test fun testParameter() {
    val mediaType = parse("multipart/mixed; boundary=\"abcd\"")
    assertEquals("abcd", mediaType.parameter("boundary"))
    assertEquals("abcd", mediaType.parameter("BOUNDARY"))
  }

  @Test fun testMultipleParameters() {
    val mediaType = parse("Message/Partial; number=2; total=3; id=\"oc=abc@example.com\"")
    assertEquals("2", mediaType.parameter("number"))
    assertEquals("3", mediaType.parameter("total"))
    assertEquals("oc=abc@example.com", mediaType.parameter("id"))
    assertNull(mediaType.parameter("foo"))
  }

  @Test fun testRepeatedParameter() {
    val mediaType = parse("multipart/mixed; number=2; number=3")
    assertEquals("2", mediaType.parameter("number"))
  }

  @Test fun testTurkishDotlessIWithEnUs() {
    withLocale(Locale("en", "US")) {
      val mediaType = parse("IMAGE/JPEG")
      assertEquals("image", mediaType.type)
      assertEquals("jpeg", mediaType.subtype)
    }
  }

  @Test fun testTurkishDotlessIWithTrTr() {
    withLocale(Locale("tr", "TR")) {
      val mediaType = parse("IMAGE/JPEG")
      assertEquals("image", mediaType.type)
      assertEquals("jpeg", mediaType.subtype)
    }
  }

  private fun <T> withLocale(locale: Locale, block: () -> T): T {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(locale)
      return block()
    } finally {
      Locale.setDefault(previous)
    }
  }
  private fun assertMediaType(string: String) {
    assertEquals(string, parse(string).toString())
  }
}
