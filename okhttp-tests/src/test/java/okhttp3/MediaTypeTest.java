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
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import okhttp3.internal.Util;
import org.junit.Test;

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
public class MediaTypeTest {
  @Test public void testParse() throws Exception {
    MediaType mediaType = MediaType.parse("text/plain;boundary=foo;charset=utf-8");
    assertEquals("text", mediaType.type());
    assertEquals("plain", mediaType.subtype());
    assertEquals("UTF-8", mediaType.charset().name());
    assertEquals("text/plain;boundary=foo;charset=utf-8", mediaType.toString());
    assertTrue(mediaType.equals(MediaType.parse("text/plain;boundary=foo;charset=utf-8")));
    assertEquals(mediaType.hashCode(),
        MediaType.parse("text/plain;boundary=foo;charset=utf-8").hashCode());
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
    assertInvalid("");
    assertInvalid("/");
    assertInvalid("/");
    assertInvalid("text");
    assertInvalid("text/");
    assertInvalid("te<t/plain");
    assertInvalid("text/pl@in");
    assertInvalid("text/plain; a");
    assertInvalid("text/plain; a=");
    assertInvalid("text/plain; a=@");
    assertInvalid("text/plain; a=\"@");
    assertInvalid("text/plain; a=1; b");
    assertInvalid("text/plain; a=1; b=");
    assertInvalid("text/plain; a=\u2025");
    assertInvalid(" text/plain");
    assertInvalid("te xt/plain");
    assertInvalid("text /plain");
    assertInvalid("text/ plain");
    assertInvalid("text/pl ain");
    assertInvalid("text/plain ");
    assertInvalid("text/plain ; a=1");
  }

  @Test public void testParseWithSpecialCharacters() throws Exception {
    MediaType mediaType = MediaType.parse(
        "!#$%&'*+-.{|}~/!#$%&'*+-.{|}~; !#$%&'*+-.{|}~=!#$%&'*+-.{|}~");
    assertEquals("!#$%&'*+-.{|}~", mediaType.type());
    assertEquals("!#$%&'*+-.{|}~", mediaType.subtype());
  }

  @Test public void testCharsetIsOneOfManyParameters() throws Exception {
    MediaType mediaType = MediaType.parse("text/plain;a=1;b=2;charset=utf-8;c=3");
    assertEquals("text", mediaType.type());
    assertEquals("plain", mediaType.subtype());
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testCharsetAndQuoting() throws Exception {
    MediaType mediaType = MediaType.parse(
        "text/plain;a=\";charset=us-ascii\";charset=\"utf-8\";b=\"iso-8859-1\"");
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testDuplicatedCharsets() {
    MediaType mediaType = MediaType.parse("text/plain; charset=utf-8; charset=UTF-8");
    assertEquals("UTF-8", mediaType.charset().name());
  }

  @Test public void testMultipleCharsets() {
    try {
      MediaType.parse("text/plain; charset=utf-8; charset=utf-16");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void testIllegalCharsetName() {
    MediaType mediaType = MediaType.parse("text/plain; charset=\"!@#$%^&*()\"");
    try {
      mediaType.charset();
      fail();
    } catch (IllegalCharsetNameException expected) {
    }
  }

  @Test public void testUnsupportedCharset() {
    MediaType mediaType = MediaType.parse("text/plain; charset=utf-wtf");
    try {
      mediaType.charset();
      fail();
    } catch (UnsupportedCharsetException expected) {
    }
  }

  @Test public void testDefaultCharset() throws Exception {
    MediaType noCharset = MediaType.parse("text/plain");
    assertEquals("UTF-8", noCharset.charset(Util.UTF_8).name());
    assertEquals("US-ASCII", noCharset.charset(Charset.forName("US-ASCII")).name());

    MediaType charset = MediaType.parse("text/plain; charset=iso-8859-1");
    assertEquals("ISO-8859-1", charset.charset(Util.UTF_8).name());
    assertEquals("ISO-8859-1", charset.charset(Charset.forName("US-ASCII")).name());
  }

  @Test public void testParseDanglingSemicolon() throws Exception {
    MediaType mediaType = MediaType.parse("text/plain;");
    assertEquals("text", mediaType.type());
    assertEquals("plain", mediaType.subtype());
    assertEquals(null, mediaType.charset());
    assertEquals("text/plain;", mediaType.toString());
  }

  private void assertMediaType(String string) {
    MediaType mediaType = MediaType.parse(string);
    assertEquals(string, mediaType.toString());
  }

  private void assertInvalid(String string) {
    assertNull(string, MediaType.parse(string));
  }
}
