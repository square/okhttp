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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test MediaType API and parsing.
 *
 * <p>This test includes tests from <a href="https://code.google.com/p/guava-libraries/">Guava's</a>
 * MediaTypeTest.
 */
public class MediaTypeTest {
  private MediaType parse(String string, boolean useGet) {
    return useGet
        ? MediaType.get(string)
        : MediaType.parse(string);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testParse(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;boundary=foo;charset=utf-8", useGet);
    assertThat(mediaType.type()).isEqualTo("text");
    assertThat(mediaType.subtype()).isEqualTo("plain");
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
    assertThat(mediaType.toString()).isEqualTo("text/plain;boundary=foo;charset=utf-8");
    assertThat(parse("text/plain;boundary=foo;charset=utf-8", useGet)).isEqualTo(mediaType);
    assertThat(parse("text/plain;boundary=foo;charset=utf-8", useGet).hashCode()).isEqualTo(
        (long) mediaType.hashCode());
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testValidParse(boolean useGet) throws Exception {
    assertMediaType("text/plain", useGet);
    assertMediaType("application/atom+xml; charset=utf-8", useGet);
    assertMediaType("application/atom+xml; a=1; a=2; b=3", useGet);
    assertMediaType("image/gif; foo=bar", useGet);
    assertMediaType("text/plain; a=1", useGet);
    assertMediaType("text/plain; a=1; a=2; b=3", useGet);
    assertMediaType("text/plain; charset=utf-16", useGet);
    assertMediaType("text/plain; \t \n \r a=b", useGet);
    assertMediaType("text/plain;", useGet);
    assertMediaType("text/plain; ", useGet);
    assertMediaType("text/plain; a=1;", useGet);
    assertMediaType("text/plain; a=1; ", useGet);
    assertMediaType("text/plain; a=1;; b=2", useGet);
    assertMediaType("text/plain;;", useGet);
    assertMediaType("text/plain; ;", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testInvalidParse(boolean useGet) throws Exception {
    assertInvalid("", "No subtype found for: \"\"", useGet);
    assertInvalid("/", "No subtype found for: \"/\"", useGet);
    assertInvalid("text", "No subtype found for: \"text\"", useGet);
    assertInvalid("text/", "No subtype found for: \"text/\"", useGet);
    assertInvalid("te<t/plain", "No subtype found for: \"te<t/plain\"", useGet);
    assertInvalid(" text/plain", "No subtype found for: \" text/plain\"", useGet);
    assertInvalid("te xt/plain", "No subtype found for: \"te xt/plain\"", useGet);
    assertInvalid("text /plain", "No subtype found for: \"text /plain\"", useGet);
    assertInvalid("text/ plain", "No subtype found for: \"text/ plain\"", useGet);

    assertInvalid("text/pl@in",
        "Parameter is not formatted correctly: \"@in\" for: \"text/pl@in\"", useGet);
    assertInvalid("text/plain; a",
        "Parameter is not formatted correctly: \"a\" for: \"text/plain; a\"", useGet);
    assertInvalid("text/plain; a=",
        "Parameter is not formatted correctly: \"a=\" for: \"text/plain; a=\"", useGet);
    assertInvalid("text/plain; a=@",
        "Parameter is not formatted correctly: \"a=@\" for: \"text/plain; a=@\"", useGet);
    assertInvalid("text/plain; a=\"@",
        "Parameter is not formatted correctly: \"a=\"@\" for: \"text/plain; a=\"@\"", useGet);
    assertInvalid("text/plain; a=1; b",
        "Parameter is not formatted correctly: \"b\" for: \"text/plain; a=1; b\"", useGet);
    assertInvalid("text/plain; a=1; b=",
        "Parameter is not formatted correctly: \"b=\" for: \"text/plain; a=1; b=\"", useGet);
    assertInvalid("text/plain; a=\u2025",
        "Parameter is not formatted correctly: \"a=‥\" for: \"text/plain; a=‥\"", useGet);
    assertInvalid("text/pl ain",
        "Parameter is not formatted correctly: \" ain\" for: \"text/pl ain\"", useGet);
    assertInvalid("text/plain ",
        "Parameter is not formatted correctly: \" \" for: \"text/plain \"", useGet);
    assertInvalid("text/plain ; a=1",
        "Parameter is not formatted correctly: \" ; a=1\" for: \"text/plain ; a=1\"", useGet);
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testDoubleQuotesAreSpecial(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;a=\";charset=utf-8;b=\"", useGet);
    assertThat(mediaType.charset()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testSingleQuotesAreNotSpecial(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;a=';charset=utf-8;b='", useGet);
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testParseWithSpecialCharacters(boolean useGet) throws Exception {
    MediaType mediaType =
        parse("!#$%&'*+-.{|}~/!#$%&'*+-.{|}~; !#$%&'*+-.{|}~=!#$%&'*+-.{|}~", useGet);
    assertThat(mediaType.type()).isEqualTo("!#$%&'*+-.{|}~");
    assertThat(mediaType.subtype()).isEqualTo("!#$%&'*+-.{|}~");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testCharsetIsOneOfManyParameters(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;a=1;b=2;charset=utf-8;c=3", useGet);
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

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class) public void testCharsetAndQuoting(
      boolean useGet) throws Exception {
    MediaType mediaType =
        parse("text/plain;a=\";charset=us-ascii\";charset=\"utf-8\";b=\"iso-8859-1\"", useGet);
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testDuplicatedCharsets(boolean useGet) {
    MediaType mediaType = parse("text/plain; charset=utf-8; charset=UTF-8", useGet);
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testMultipleCharsetsReturnsFirstMatch(boolean useGet) {
    MediaType mediaType = parse("text/plain; charset=utf-8; charset=utf-16", useGet);
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testIllegalCharsetName(boolean useGet) {
    MediaType mediaType = parse("text/plain; charset=\"!@#$%^&*()\"", useGet);
    assertThat(mediaType.charset()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testUnsupportedCharset(boolean useGet) {
    MediaType mediaType = parse("text/plain; charset=utf-wtf", useGet);
    assertThat(mediaType.charset()).isNull();
  }

  /**
   * This is invalid according to RFC 822. But it's what Chrome does and it avoids a potentially
   * unpleasant IllegalCharsetNameException.
   */
  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testCharsetNameIsSingleQuoted(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;charset='utf-8'", useGet);
    assertThat(mediaType.charset().name()).isEqualTo("UTF-8");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testCharsetNameIsDoubleQuotedAndSingleQuoted(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;charset=\"'utf-8'\"", useGet);
    assertThat(mediaType.charset()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testCharsetNameIsDoubleQuotedSingleQuote(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;charset=\"'\"", useGet);
    assertThat(mediaType.charset()).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testDefaultCharset(boolean useGet) throws Exception {
    MediaType noCharset = parse("text/plain", useGet);
    assertThat(noCharset.charset(UTF_8).name()).isEqualTo("UTF-8");
    assertThat(noCharset.charset(StandardCharsets.US_ASCII).name()).isEqualTo("US-ASCII");

    MediaType charset = parse("text/plain; charset=iso-8859-1", useGet);
    assertThat(charset.charset(UTF_8).name()).isEqualTo("ISO-8859-1");
    assertThat(charset.charset(StandardCharsets.US_ASCII).name()).isEqualTo("ISO-8859-1");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testParseDanglingSemicolon(boolean useGet) throws Exception {
    MediaType mediaType = parse("text/plain;", useGet);
    assertThat(mediaType.type()).isEqualTo("text");
    assertThat(mediaType.subtype()).isEqualTo("plain");
    assertThat(mediaType.charset()).isNull();
    assertThat(mediaType.toString()).isEqualTo("text/plain;");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testParameter(boolean useGet) throws Exception {
    MediaType mediaType = parse("multipart/mixed; boundary=\"abcd\"", useGet);
    assertThat(mediaType.parameter("boundary")).isEqualTo("abcd");
    assertThat(mediaType.parameter("BOUNDARY")).isEqualTo("abcd");
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testMultipleParameters(boolean useGet) throws Exception {
    MediaType mediaType =
        parse("Message/Partial; number=2; total=3; id=\"oc=abc@example.com\"", useGet);
    assertThat(mediaType.parameter("number")).isEqualTo("2");
    assertThat(mediaType.parameter("total")).isEqualTo("3");
    assertThat(mediaType.parameter("id")).isEqualTo("oc=abc@example.com");
    assertThat(mediaType.parameter("foo")).isNull();
  }

  @ParameterizedTest @ArgumentsSource(BooleanParamProvider.class)
  public void testRepeatedParameter(boolean useGet) throws Exception {
    MediaType mediaType = parse("multipart/mixed; number=2; number=3", useGet);
    assertThat(mediaType.parameter("number")).isEqualTo("2");
  }

  private void assertMediaType(String string, boolean useGet) {
    assertThat(parse(string, useGet).toString()).isEqualTo(string);
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
