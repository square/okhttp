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

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import okhttp3.internal.Util;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test MediaType API and parsing.
 *
 * <p>This test includes tests from <a href="https://code.google.com/p/guava-libraries/">Guava's</a>
 * MediaTypeTest.
 */
@RunWith(Parameterized.class)
public class MediaTypeTest {
  @Parameterized.Parameters(name = "Use get = {0}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[] { true },
        new Object[] { false }
    );
  }

  @Parameterized.Parameter
  public boolean useGet;

  private MediaType parse(String string) {
    return useGet
        ? MediaType.get(string)
        : MediaType.parse(string);
  }

  @Test public void testParse() throws Exception {
    MediaType mediaType = parse("text/plain;boundary=foo;charset=utf-8");
    assertEquals("text", mediaType.type());
    assertEquals("plain", mediaType.subtype());
    assertEquals("UTF-8", mediaType.charset().name());
    assertEquals("text/plain;boundary=foo;charset=utf-8", mediaType.toString());
    assertTrue(mediaType.equals(parse("text/plain;boundary=foo;charset=utf-8")));
    assertEquals(mediaType.hashCode(),
        parse("text/plain;boundary=foo;charset=utf-8").hashCode());
  }

  @Test public void testValidParse() throws Exception {
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

  @Test public void testInvalidParse() throws Exception {
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

  @Test public void testDoubleQuotesAreSpecial() throws Exception {
    MediaType mediaType = parse("text/plain;a=\";charset=utf-8;b=\"");
    assertNull(mediaType.charset());
  }

  @Test public void testSingleQuotesAreNotSpecial() throws Exception {
    MediaType mediaType = parse("text/plain;a=';charset=utf-8;b='");
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testParseWithSpecialCharacters() throws Exception {
    MediaType mediaType = parse("!#$%&'*+-.{|}~/!#$%&'*+-.{|}~; !#$%&'*+-.{|}~=!#$%&'*+-.{|}~");
    assertEquals("!#$%&'*+-.{|}~", mediaType.type());
    assertEquals("!#$%&'*+-.{|}~", mediaType.subtype());
  }

  @Test public void testCharsetIsOneOfManyParameters() throws Exception {
    MediaType mediaType = parse("text/plain;a=1;b=2;charset=utf-8;c=3");
    assertEquals("text", mediaType.type());
    assertEquals("plain", mediaType.subtype());
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testCharsetAndQuoting() throws Exception {
    MediaType mediaType = parse(
        "text/plain;a=\";charset=us-ascii\";charset=\"utf-8\";b=\"iso-8859-1\"");
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testDuplicatedCharsets() {
    MediaType mediaType = parse("text/plain; charset=utf-8; charset=UTF-8");
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testMultipleCharsets() {
    assertInvalid("text/plain; charset=utf-8; charset=utf-16",
        "Multiple charsets defined: \"utf-8\" and: \"utf-16\" for: \"text/plain; charset=utf-8; charset=utf-16\"");
  }

  @Test public void testIllegalCharsetName() {
    MediaType mediaType = parse("text/plain; charset=\"!@#$%^&*()\"");
    assertNull(mediaType.charset());
  }

  @Test public void testUnsupportedCharset() {
    MediaType mediaType = parse("text/plain; charset=utf-wtf");
    assertNull(mediaType.charset());
  }

  /**
   * This is invalid according to RFC 822. But it's what Chrome does and it avoids a potentially
   * unpleasant IllegalCharsetNameException.
   */
  @Test public void testCharsetNameIsSingleQuoted() throws Exception {
    MediaType mediaType = parse("text/plain;charset='utf-8'");
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testCharsetNameIsDoubleQuotedAndSingleQuoted() throws Exception {
    MediaType mediaType = parse("text/plain;charset=\"'utf-8'\"");
    assertNull(mediaType.charset());
  }

  @Test public void testCharsetNameIsDoubleQuotedSingleQuote() throws Exception {
    MediaType mediaType = parse("text/plain;charset=\"'\"");
    assertNull(mediaType.charset());
  }

  @Test public void testDefaultCharset() throws Exception {
    MediaType noCharset = parse("text/plain");
    assertEquals("UTF-8", noCharset.charset(Util.UTF_8).name());
    assertEquals("US-ASCII", noCharset.charset(Charset.forName("US-ASCII")).name());

    MediaType charset = parse("text/plain; charset=iso-8859-1");
    assertEquals("ISO-8859-1", charset.charset(Util.UTF_8).name());
    assertEquals("ISO-8859-1", charset.charset(Charset.forName("US-ASCII")).name());
  }

  @Test public void testParseDanglingSemicolon() throws Exception {
    MediaType mediaType = parse("text/plain;");
    assertEquals("text", mediaType.type());
    assertEquals("plain", mediaType.subtype());
    assertNull(mediaType.charset());
    assertEquals("text/plain;", mediaType.toString());
  }

  private void assertMediaType(String string) {
    assertEquals(string, parse(string).toString());
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
