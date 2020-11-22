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
package okhttp3;

import java.nio.charset.StandardCharsets;
import okhttp3.internal.platform.Platform;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test MediaType API and parsing.
 *
 * <p>This test includes tests from <a href="https://code.google.com/p/guava-libraries/">Guava's</a>
 * MediaTypeTest.
 */
public class MediaTypeTest {
  protected MediaType parse(String string) {
    return MediaType.parse(string);
  }

  protected void assertInvalid(String string, String exceptionMessage) {
    assertThat(parse(string)).overridingErrorMessage(string).isNull();
  }

  @Test
  public void testParse() throws Exception {
    MediaType mediaType = parse("text/plain;boundary=foo;charset=utf-8");
    assertThat(mediaType.type()).isEqualTo("text");
    assertThat(mediaType.subtype()).isEqualTo("plain");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
    assertThat(mediaType.toString()).isEqualTo("text/plain;boundary=foo;charset=utf-8");
    assertThat(parse("text/plain;boundary=foo;charset=utf-8")).isEqualTo(mediaType);
    assertThat(parse("text/plain;boundary=foo;charset=utf-8").hashCode()).isEqualTo(
        (long) mediaType.hashCode());
  }

  @Test
  public void testValidParse() throws Exception {
    assertMediaType("text/plain");
    assertMediaType("application/atom+xml; charset=utf-8");
    assertMediaType("application/atom+xml; a=1; a=2; b=3");
    assertMediaType("image/gif; foo=bar");
    assertMediaType("text/plain; a=1");
    assertMediaType("text/plain; a=1; a=2; b=3");
    assertMediaType("text/plain; charset=utf-16");
    assertMediaType("text/plain; \t \n \r a=b");
    assertMediaType("text/plain;");
    assertMediaType("text/plain; ");
    assertMediaType("text/plain; a=1;");
    assertMediaType("text/plain; a=1; ");
    assertMediaType("text/plain; a=1;; b=2");
    assertMediaType("text/plain;;");
    assertMediaType("text/plain; ;");
  }

  @Test
  public void testInvalidParse() throws Exception {
    assertInvalid("", "No subtype found for: \"\"");
    assertInvalid("/", "No subtype found for: \"/\"");
    assertInvalid("text", "No subtype found for: \"text\"");
    assertInvalid("text/", "No subtype found for: \"text/\"");
    assertInvalid("te<t/plain", "No subtype found for: \"te<t/plain\"");
    assertInvalid(" text/plain", "No subtype found for: \" text/plain\"");
    assertInvalid("te xt/plain", "No subtype found for: \"te xt/plain\"");
    assertInvalid("text /plain", "No subtype found for: \"text /plain\"");
    assertInvalid("text/ plain", "No subtype found for: \"text/ plain\"");

    assertInvalid("text/pl@in",
        "Parameter is not formatted correctly: \"@in\" for: \"text/pl@in\"");
    assertInvalid("text/plain; a",
        "Parameter is not formatted correctly: \"a\" for: \"text/plain; a\"");
    assertInvalid("text/plain; a=",
        "Parameter is not formatted correctly: \"a=\" for: \"text/plain; a=\"");
    assertInvalid("text/plain; a=@",
        "Parameter is not formatted correctly: \"a=@\" for: \"text/plain; a=@\"");
    assertInvalid("text/plain; a=\"@",
        "Parameter is not formatted correctly: \"a=\"@\" for: \"text/plain; a=\"@\"");
    assertInvalid("text/plain; a=1; b",
        "Parameter is not formatted correctly: \"b\" for: \"text/plain; a=1; b\"");
    assertInvalid("text/plain; a=1; b=",
        "Parameter is not formatted correctly: \"b=\" for: \"text/plain; a=1; b=\"");
    assertInvalid("text/plain; a=\u2025",
        "Parameter is not formatted correctly: \"a=‥\" for: \"text/plain; a=‥\"");
    assertInvalid("text/pl ain",
        "Parameter is not formatted correctly: \" ain\" for: \"text/pl ain\"");
    assertInvalid("text/plain ",
        "Parameter is not formatted correctly: \" \" for: \"text/plain \"");
    assertInvalid("text/plain ; a=1",
        "Parameter is not formatted correctly: \" ; a=1\" for: \"text/plain ; a=1\"");
  }

  @Test
  public void testDoubleQuotesAreSpecial() throws Exception {
    MediaType mediaType = parse("text/plain;a=\";charset=utf-8;b=\"");
    assertThat(mediaType.charset()).isNull();
  }

  @Test
  public void testSingleQuotesAreNotSpecial() throws Exception {
    MediaType mediaType = parse("text/plain;a=';charset=utf-8;b='");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @Test
  public void testParseWithSpecialCharacters() throws Exception {
    MediaType mediaType =
        parse("!#$%&'*+-.{|}~/!#$%&'*+-.{|}~; !#$%&'*+-.{|}~=!#$%&'*+-.{|}~");
    assertThat(mediaType.type()).isEqualTo("!#$%&'*+-.{|}~");
    assertThat(mediaType.subtype()).isEqualTo("!#$%&'*+-.{|}~");
  }

  @Test
  public void testCharsetIsOneOfManyParameters() throws Exception {
    MediaType mediaType = parse("text/plain;a=1;b=2;charset=utf-8;c=3");
    assertThat(mediaType.type()).isEqualTo("text");
    assertThat(mediaType.subtype()).isEqualTo("plain");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
    assertThat(mediaType.parameter("charset")).isEqualTo("utf-8");
    assertThat(mediaType.parameter("a")).isEqualTo("1");
    assertThat(mediaType.parameter("b")).isEqualTo("2");
    assertThat(mediaType.parameter("c")).isEqualTo("3");
    assertThat(mediaType.parameter("CHARSET")).isEqualTo("utf-8");
    assertThat(mediaType.parameter("A")).isEqualTo("1");
    assertThat(mediaType.parameter("B")).isEqualTo("2");
    assertThat(mediaType.parameter("C")).isEqualTo("3");
  }

  @Test public void testCharsetAndQuoting(
  ) throws Exception {
    MediaType mediaType =
        parse("text/plain;a=\";charset=us-ascii\";charset=\"utf-8\";b=\"iso-8859-1\"");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @Test
  public void testDuplicatedCharsets() {
    MediaType mediaType = parse("text/plain; charset=utf-8; charset=UTF-8");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @Test
  public void testMultipleCharsetsReturnsFirstMatch() {
    MediaType mediaType = parse("text/plain; charset=utf-8; charset=utf-16");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @Test
  public void testIllegalCharsetName() {
    MediaType mediaType = parse("text/plain; charset=\"!@#$%^&*()\"");
    assertThat(mediaType.charset()).isNull();
  }

  @Test
  public void testUnsupportedCharset() {
    MediaType mediaType = parse("text/plain; charset=utf-wtf");
    assertThat(mediaType.charset()).isNull();
  }

  /**
   * This is invalid according to RFC 822. But it's what Chrome does and it avoids a potentially
   * unpleasant IllegalCharsetNameException.
   */
  @Test
  public void testCharsetNameIsSingleQuoted() throws Exception {
    MediaType mediaType = parse("text/plain;charset='utf-8'");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @Test
  public void testCharsetNameIsDoubleQuotedAndSingleQuoted() throws Exception {
    MediaType mediaType = parse("text/plain;charset=\"'utf-8'\"");
    if (Platform.Companion.isAndroid()) {
      // Charset.forName("'utf-8'") == UTF-8
      assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
    } else {
      assertThat(mediaType.charset()).isNull();
    }
  }

  @Test
  public void testCharsetNameIsDoubleQuotedSingleQuote() throws Exception {
    MediaType mediaType = parse("text/plain;charset=\"'\"");
    assertThat(mediaType.charset()).isNull();
  }

  @Test
  public void testDefaultCharset() throws Exception {
    MediaType noCharset = parse("text/plain");
    assertThat(noCharset.charset(UTF_8).name()).isEqualTo("UTF-8");
    assertThat(noCharset.charset(StandardCharsets.US_ASCII).name()).isEqualTo("US-ASCII");

    MediaType charset = parse("text/plain; charset=iso-8859-1");
    assertThat(charset.charset(UTF_8).name()).isEqualTo("ISO-8859-1");
    assertThat(charset.charset(StandardCharsets.US_ASCII).name()).isEqualTo("ISO-8859-1");
  }

  @Test
  public void testParseDanglingSemicolon() throws Exception {
    MediaType mediaType = parse("text/plain;");
    assertThat(mediaType.type()).isEqualTo("text");
    assertThat(mediaType.subtype()).isEqualTo("plain");
    assertThat(mediaType.charset()).isNull();
    assertThat(mediaType.toString()).isEqualTo("text/plain;");
  }

  @Test
  public void testParameter() throws Exception {
    MediaType mediaType = parse("multipart/mixed; boundary=\"abcd\"");
    assertThat(mediaType.parameter("boundary")).isEqualTo("abcd");
    assertThat(mediaType.parameter("BOUNDARY")).isEqualTo("abcd");
  }

  @Test
  public void testMultipleParameters() throws Exception {
    MediaType mediaType =
        parse("Message/Partial; number=2; total=3; id=\"oc=abc@example.com\"");
    assertThat(mediaType.parameter("number")).isEqualTo("2");
    assertThat(mediaType.parameter("total")).isEqualTo("3");
    assertThat(mediaType.parameter("id")).isEqualTo("oc=abc@example.com");
    assertThat(mediaType.parameter("foo")).isNull();
  }

  @Test
  public void testRepeatedParameter() throws Exception {
    MediaType mediaType = parse("multipart/mixed; number=2; number=3");
    assertThat(mediaType.parameter("number")).isEqualTo("2");
  }

  private void assertMediaType(String string) {
    assertThat(parse(string).toString()).isEqualTo(string);
  }
}
