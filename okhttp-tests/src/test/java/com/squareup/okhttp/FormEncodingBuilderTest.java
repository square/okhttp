/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp;

import java.io.IOException;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class FormEncodingBuilderTest {
  @Test public void urlEncoding() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .add("a+=& b", "c+=& d")
        .add("space, the", "final frontier")
        .add("%25", "%25")
        .build();

    assertEquals("application/x-www-form-urlencoded", formEncoding.contentType().toString());

    String expected = "a%2B%3D%26%20b=c%2B%3D%26%20d&space%2C%20the=final%20frontier&%2525=%2525";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer out = new Buffer();
    formEncoding.writeTo(out);
    assertEquals(expected, out.readUtf8());
  }

  @Test public void addEncoded() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .addEncoded("a+=& b", "c+=& d")
        .addEncoded("e+=& f", "g+=& h")
        .addEncoded("%25", "%25")
        .build();

    String expected = "a+%3D%26%20b=c+%3D%26%20d&e+%3D%26%20f=g+%3D%26%20h&%25=%25";
    Buffer out = new Buffer();
    formEncoding.writeTo(out);
    assertEquals(expected, out.readUtf8());
  }

  @Test public void encodedPair() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .add("sim", "ple")
        .build();

    String expected = "sim=ple";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer buffer = new Buffer();
    formEncoding.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void encodeMultiplePairs() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder()
        .add("sim", "ple")
        .add("hey", "there")
        .add("help", "me")
        .build();

    String expected = "sim=ple&hey=there&help=me";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer buffer = new Buffer();
    formEncoding.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void buildEmptyForm() throws Exception {
    RequestBody formEncoding = new FormEncodingBuilder().build();

    String expected = "";
    assertEquals(expected.length(), formEncoding.contentLength());

    Buffer buffer = new Buffer();
    formEncoding.writeTo(buffer);
    assertEquals(expected, buffer.readUtf8());
  }

  @Test public void characterEncoding() throws Exception {
    assertEquals("%00", formEncode(0)); // Browsers convert '\u0000' to '%EF%BF%BD'.
    assertEquals("%01", formEncode(1));
    assertEquals("%02", formEncode(2));
    assertEquals("%03", formEncode(3));
    assertEquals("%04", formEncode(4));
    assertEquals("%05", formEncode(5));
    assertEquals("%06", formEncode(6));
    assertEquals("%07", formEncode(7));
    assertEquals("%08", formEncode(8));
    assertEquals("%09", formEncode(9));
    assertEquals("%0A", formEncode(10)); // Browsers convert '\n' to '\r\n'
    assertEquals("%0B", formEncode(11));
    assertEquals("%0C", formEncode(12));
    assertEquals("%0D", formEncode(13)); // Browsers convert '\r' to '\r\n'
    assertEquals("%0E", formEncode(14));
    assertEquals("%0F", formEncode(15));
    assertEquals("%10", formEncode(16));
    assertEquals("%11", formEncode(17));
    assertEquals("%12", formEncode(18));
    assertEquals("%13", formEncode(19));
    assertEquals("%14", formEncode(20));
    assertEquals("%15", formEncode(21));
    assertEquals("%16", formEncode(22));
    assertEquals("%17", formEncode(23));
    assertEquals("%18", formEncode(24));
    assertEquals("%19", formEncode(25));
    assertEquals("%1A", formEncode(26));
    assertEquals("%1B", formEncode(27));
    assertEquals("%1C", formEncode(28));
    assertEquals("%1D", formEncode(29));
    assertEquals("%1E", formEncode(30));
    assertEquals("%1F", formEncode(31));
    assertEquals("%20", formEncode(32)); // Browsers use '+' for space.
    assertEquals("%21", formEncode(33));
    assertEquals("%22", formEncode(34));
    assertEquals("%23", formEncode(35));
    assertEquals("%24", formEncode(36));
    assertEquals("%25", formEncode(37));
    assertEquals("%26", formEncode(38));
    assertEquals("%27", formEncode(39));
    assertEquals("%28", formEncode(40));
    assertEquals("%29", formEncode(41));
    assertEquals("*", formEncode(42));
    assertEquals("%2B", formEncode(43));
    assertEquals("%2C", formEncode(44));
    assertEquals("-", formEncode(45));
    assertEquals(".", formEncode(46));
    assertEquals("%2F", formEncode(47));
    assertEquals("0", formEncode(48));
    assertEquals("9", formEncode(57));
    assertEquals("%3A", formEncode(58));
    assertEquals("%3B", formEncode(59));
    assertEquals("%3C", formEncode(60));
    assertEquals("%3D", formEncode(61));
    assertEquals("%3E", formEncode(62));
    assertEquals("%3F", formEncode(63));
    assertEquals("%40", formEncode(64));
    assertEquals("A", formEncode(65));
    assertEquals("Z", formEncode(90));
    assertEquals("%5B", formEncode(91));
    assertEquals("%5C", formEncode(92));
    assertEquals("%5D", formEncode(93));
    assertEquals("%5E", formEncode(94));
    assertEquals("_", formEncode(95));
    assertEquals("%60", formEncode(96));
    assertEquals("a", formEncode(97));
    assertEquals("z", formEncode(122));
    assertEquals("%7B", formEncode(123));
    assertEquals("%7C", formEncode(124));
    assertEquals("%7D", formEncode(125));
    assertEquals("%7E", formEncode(126));
    assertEquals("%7F", formEncode(127));
    assertEquals("%C2%80", formEncode(128));
    assertEquals("%C3%BF", formEncode(255));
  }

  private String formEncode(int codePoint) throws IOException {
    // Wrap the codepoint with regular printable characters to prevent trimming.
    RequestBody body = new FormEncodingBuilder()
        .add("a", new String(new int[] { 'b', codePoint, 'c' }, 0, 3))
        .build();
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    buffer.skip(3); // Skip "a=b" prefix.
    return buffer.readUtf8(buffer.size() - 1); // Skip the "c" suffix.
  }
}
