/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ByteStringTest {

  @Test public void getByte() throws Exception {
    ByteString byteString = ByteString.decodeHex("ab12");
    assertEquals(-85, byteString.getByte(0));
    assertEquals(18, byteString.getByte(1));
  }

  @Test public void getByteOutOfBounds() throws Exception {
    ByteString byteString = ByteString.decodeHex("ab12");
    try {
      byteString.getByte(2);
      fail();
    } catch (IndexOutOfBoundsException expected) {
    }
  }

  @Test public void equals() throws Exception {
    ByteString byteString = ByteString.decodeHex("000102");
    assertTrue(byteString.equals(byteString));
    assertTrue(byteString.equals(ByteString.decodeHex("000102")));
    assertTrue(ByteString.of().equals(ByteString.EMPTY));
    assertTrue(ByteString.EMPTY.equals(ByteString.of()));
    assertFalse(byteString.equals(new Object()));
    assertFalse(byteString.equals(ByteString.decodeHex("000201")));
  }

  private final String bronzeHorseman = "На берегу пустынных волн";

  @Test public void utf8() throws Exception {
    ByteString byteString = ByteString.encodeUtf8(bronzeHorseman);
    assertByteArraysEquals(byteString.toByteArray(), bronzeHorseman.getBytes(Util.UTF_8));
    assertTrue(byteString.equals(ByteString.of(bronzeHorseman.getBytes(Util.UTF_8))));
    assertEquals(byteString.utf8(), bronzeHorseman);
  }

  @Test public void testHashCode() throws Exception {
    ByteString byteString = ByteString.decodeHex("0102");
    assertEquals(byteString.hashCode(), byteString.hashCode());
    assertEquals(byteString.hashCode(), ByteString.decodeHex("0102").hashCode());
  }

  @Test public void read() throws Exception {
    InputStream in = new ByteArrayInputStream("abc".getBytes(Util.UTF_8));
    assertEquals(ByteString.decodeHex("6162"), ByteString.read(in, 2));
    assertEquals(ByteString.decodeHex("63"), ByteString.read(in, 1));
    assertEquals(ByteString.of(), ByteString.read(in, 0));
  }

  @Test public void readLowerCase() throws Exception {
    InputStream in = new ByteArrayInputStream("ABC".getBytes(Util.UTF_8));
    assertEquals(ByteString.encodeUtf8("ab"), ByteString.read(in, 2).toAsciiLowercase());
    assertEquals(ByteString.encodeUtf8("c"), ByteString.read(in, 1).toAsciiLowercase());
    assertEquals(ByteString.EMPTY, ByteString.read(in, 0).toAsciiLowercase());
  }

  @Test public void toAsciiLowerCaseNoUppercase() throws Exception {
    ByteString s = ByteString.encodeUtf8("a1_+");
    assertSame(s, s.toAsciiLowercase());
  }

  @Test public void toAsciiAllUppercase() throws Exception {
    assertEquals(ByteString.encodeUtf8("ab"), ByteString.encodeUtf8("AB").toAsciiLowercase());
  }

  @Test public void toAsciiStartsLowercaseEndsUppercase() throws Exception {
    assertEquals(ByteString.encodeUtf8("abcd"), ByteString.encodeUtf8("abCD").toAsciiLowercase());
  }

  @Test public void write() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteString.decodeHex("616263").write(out);
    assertByteArraysEquals(new byte[] { 0x61, 0x62, 0x63 }, out.toByteArray());
  }

  @Test public void encodeBase64() {
    assertEquals("", ByteString.encodeUtf8("").base64());
    assertEquals("AA==", ByteString.encodeUtf8("\u0000").base64());
    assertEquals("AAA=", ByteString.encodeUtf8("\u0000\u0000").base64());
    assertEquals("AAAA", ByteString.encodeUtf8("\u0000\u0000\u0000").base64());
    assertEquals("V2UncmUgZ29ubmEgbWFrZSBhIGZvcnR1bmUgd2l0aCB0aGlzIHBsYWNlLg==",
        ByteString.encodeUtf8("We're gonna make a fortune with this place.").base64());
  }

  @Test public void ignoreUnnecessaryPadding() {
    assertEquals("", ByteString.decodeBase64("====").utf8());
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64("AAAA====").utf8());
  }

  @Test public void decodeBase64() {
    assertEquals("", ByteString.decodeBase64("").utf8());
    assertEquals(null, ByteString.decodeBase64("/===")); // Can't do anything with 6 bits!
    assertEquals(ByteString.decodeHex("ff"), ByteString.decodeBase64("//=="));
    assertEquals(ByteString.decodeHex("ffff"), ByteString.decodeBase64("///="));
    assertEquals(ByteString.decodeHex("ffffff"), ByteString.decodeBase64("////"));
    assertEquals(ByteString.decodeHex("ffffffffffff"), ByteString.decodeBase64("////////"));
    assertEquals("What's to be scared about? It's just a little hiccup in the power...",
        ByteString.decodeBase64("V2hhdCdzIHRvIGJlIHNjYXJlZCBhYm91dD8gSXQncyBqdXN0IGEgbGl0dGxlIGhpY2"
            + "N1cCBpbiB0aGUgcG93ZXIuLi4=").utf8());
  }

  @Test public void decodeBase64WithWhitespace() {
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64(" AA AA ").utf8());
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64(" AA A\r\nA ").utf8());
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64("AA AA").utf8());
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64(" AA AA ").utf8());
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64(" AA A\r\nA ").utf8());
    assertEquals("\u0000\u0000\u0000", ByteString.decodeBase64("A    AAA").utf8());
    assertEquals("", ByteString.decodeBase64("    ").utf8());
  }

  @Test public void encodeHex() throws Exception {
    assertEquals("000102", ByteString.of((byte) 0x0, (byte) 0x1, (byte) 0x2).hex());
  }

  @Test public void decodeHex() throws Exception {
    assertEquals(ByteString.of((byte) 0x0, (byte) 0x1, (byte) 0x2), ByteString.decodeHex("000102"));
  }

  @Test public void decodeHexOddNumberOfChars() throws Exception {
    try {
      ByteString.decodeHex("aaa");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void decodeHexInvalidChar() throws Exception {
    try {
      ByteString.decodeHex("a\u0000");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void toStringOnEmptyByteString() {
    assertEquals("ByteString[size=0]", ByteString.of().toString());
  }

  @Test public void toStringOnSmallByteStringIncludesContents() {
    assertEquals("ByteString[size=16 data=a1b2c3d4e5f61a2b3c4d5e6f10203040]",
        ByteString.decodeHex("a1b2c3d4e5f61a2b3c4d5e6f10203040").toString());
  }

  @Test public void toStringOnLargeByteStringIncludesMd5() {
    assertEquals("ByteString[size=17 md5=2c9728a2138b2f25e9f89f99bdccf8db]",
        ByteString.encodeUtf8("12345678901234567").toString());
  }

  private static void assertByteArraysEquals(byte[] a, byte[] b) {
    assertEquals(Arrays.toString(a), Arrays.toString(b));
  }
}
