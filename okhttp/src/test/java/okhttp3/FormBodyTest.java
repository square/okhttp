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
package okhttp3;

import java.io.IOException;
import okio.Buffer;
import org.junit.Test;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;

public final class FormBodyTest {
  @Test public void urlEncoding() throws Exception {
    FormBody body = new FormBody.Builder()
        .add("a+=& b", "c+=& d")
        .add("space, the", "final frontier")
        .add("%25", "%25")
        .build();

    assertThat(body.size()).isEqualTo(3);

    assertThat(body.encodedName(0)).isEqualTo("a%2B%3D%26%20b");
    assertThat(body.encodedName(1)).isEqualTo("space%2C%20the");
    assertThat(body.encodedName(2)).isEqualTo("%2525");

    assertThat(body.name(0)).isEqualTo("a+=& b");
    assertThat(body.name(1)).isEqualTo("space, the");
    assertThat(body.name(2)).isEqualTo("%25");

    assertThat(body.encodedValue(0)).isEqualTo("c%2B%3D%26%20d");
    assertThat(body.encodedValue(1)).isEqualTo("final%20frontier");
    assertThat(body.encodedValue(2)).isEqualTo("%2525");

    assertThat(body.value(0)).isEqualTo("c+=& d");
    assertThat(body.value(1)).isEqualTo("final frontier");
    assertThat(body.value(2)).isEqualTo("%25");

    assertThat(body.contentType().toString()).isEqualTo(
        "application/x-www-form-urlencoded");

    String expected = "a%2B%3D%26%20b=c%2B%3D%26%20d&space%2C%20the=final%20frontier&%2525=%2525";
    assertThat(body.contentLength()).isEqualTo(expected.length());

    Buffer out = new Buffer();
    body.writeTo(out);
    assertThat(out.readUtf8()).isEqualTo(expected);
  }

  @Test public void addEncoded() throws Exception {
    FormBody body = new FormBody.Builder()
        .addEncoded("a+=& b", "c+=& d")
        .addEncoded("e+=& f", "g+=& h")
        .addEncoded("%25", "%25")
        .build();

    String expected = "a+%3D%26%20b=c+%3D%26%20d&e+%3D%26%20f=g+%3D%26%20h&%25=%25";
    Buffer out = new Buffer();
    body.writeTo(out);
    assertThat(out.readUtf8()).isEqualTo(expected);
  }

  @Test public void encodedPair() throws Exception {
    FormBody body = new FormBody.Builder()
        .add("sim", "ple")
        .build();

    String expected = "sim=ple";
    assertThat(body.contentLength()).isEqualTo(expected.length());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(buffer.readUtf8()).isEqualTo(expected);
  }

  @Test public void encodeMultiplePairs() throws Exception {
    FormBody body = new FormBody.Builder()
        .add("sim", "ple")
        .add("hey", "there")
        .add("help", "me")
        .build();

    String expected = "sim=ple&hey=there&help=me";
    assertThat(body.contentLength()).isEqualTo(expected.length());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(buffer.readUtf8()).isEqualTo(expected);
  }

  @Test public void buildEmptyForm() throws Exception {
    FormBody body = new FormBody.Builder().build();

    String expected = "";
    assertThat(body.contentLength()).isEqualTo(expected.length());

    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    assertThat(buffer.readUtf8()).isEqualTo(expected);
  }

  @Test public void characterEncoding() throws Exception {
    // Browsers convert '\u0000' to '%EF%BF%BD'.
    assertThat(formEncode(0)).isEqualTo("%00");
    assertThat(formEncode(1)).isEqualTo("%01");
    assertThat(formEncode(2)).isEqualTo("%02");
    assertThat(formEncode(3)).isEqualTo("%03");
    assertThat(formEncode(4)).isEqualTo("%04");
    assertThat(formEncode(5)).isEqualTo("%05");
    assertThat(formEncode(6)).isEqualTo("%06");
    assertThat(formEncode(7)).isEqualTo("%07");
    assertThat(formEncode(8)).isEqualTo("%08");
    assertThat(formEncode(9)).isEqualTo("%09");
    // Browsers convert '\n' to '\r\n'
    assertThat(formEncode(10)).isEqualTo("%0A");
    assertThat(formEncode(11)).isEqualTo("%0B");
    assertThat(formEncode(12)).isEqualTo("%0C");
    // Browsers convert '\r' to '\r\n'
    assertThat(formEncode(13)).isEqualTo("%0D");
    assertThat(formEncode(14)).isEqualTo("%0E");
    assertThat(formEncode(15)).isEqualTo("%0F");
    assertThat(formEncode(16)).isEqualTo("%10");
    assertThat(formEncode(17)).isEqualTo("%11");
    assertThat(formEncode(18)).isEqualTo("%12");
    assertThat(formEncode(19)).isEqualTo("%13");
    assertThat(formEncode(20)).isEqualTo("%14");
    assertThat(formEncode(21)).isEqualTo("%15");
    assertThat(formEncode(22)).isEqualTo("%16");
    assertThat(formEncode(23)).isEqualTo("%17");
    assertThat(formEncode(24)).isEqualTo("%18");
    assertThat(formEncode(25)).isEqualTo("%19");
    assertThat(formEncode(26)).isEqualTo("%1A");
    assertThat(formEncode(27)).isEqualTo("%1B");
    assertThat(formEncode(28)).isEqualTo("%1C");
    assertThat(formEncode(29)).isEqualTo("%1D");
    assertThat(formEncode(30)).isEqualTo("%1E");
    assertThat(formEncode(31)).isEqualTo("%1F");
    // Browsers use '+' for space.
    assertThat(formEncode(32)).isEqualTo("%20");
    assertThat(formEncode(33)).isEqualTo("%21");
    assertThat(formEncode(34)).isEqualTo("%22");
    assertThat(formEncode(35)).isEqualTo("%23");
    assertThat(formEncode(36)).isEqualTo("%24");
    assertThat(formEncode(37)).isEqualTo("%25");
    assertThat(formEncode(38)).isEqualTo("%26");
    assertThat(formEncode(39)).isEqualTo("%27");
    assertThat(formEncode(40)).isEqualTo("%28");
    assertThat(formEncode(41)).isEqualTo("%29");
    assertThat(formEncode(42)).isEqualTo("*");
    assertThat(formEncode(43)).isEqualTo("%2B");
    assertThat(formEncode(44)).isEqualTo("%2C");
    assertThat(formEncode(45)).isEqualTo("-");
    assertThat(formEncode(46)).isEqualTo(".");
    assertThat(formEncode(47)).isEqualTo("%2F");
    assertThat(formEncode(48)).isEqualTo("0");
    assertThat(formEncode(57)).isEqualTo("9");
    assertThat(formEncode(58)).isEqualTo("%3A");
    assertThat(formEncode(59)).isEqualTo("%3B");
    assertThat(formEncode(60)).isEqualTo("%3C");
    assertThat(formEncode(61)).isEqualTo("%3D");
    assertThat(formEncode(62)).isEqualTo("%3E");
    assertThat(formEncode(63)).isEqualTo("%3F");
    assertThat(formEncode(64)).isEqualTo("%40");
    assertThat(formEncode(65)).isEqualTo("A");
    assertThat(formEncode(90)).isEqualTo("Z");
    assertThat(formEncode(91)).isEqualTo("%5B");
    assertThat(formEncode(92)).isEqualTo("%5C");
    assertThat(formEncode(93)).isEqualTo("%5D");
    assertThat(formEncode(94)).isEqualTo("%5E");
    assertThat(formEncode(95)).isEqualTo("_");
    assertThat(formEncode(96)).isEqualTo("%60");
    assertThat(formEncode(97)).isEqualTo("a");
    assertThat(formEncode(122)).isEqualTo("z");
    assertThat(formEncode(123)).isEqualTo("%7B");
    assertThat(formEncode(124)).isEqualTo("%7C");
    assertThat(formEncode(125)).isEqualTo("%7D");
    assertThat(formEncode(126)).isEqualTo("%7E");
    assertThat(formEncode(127)).isEqualTo("%7F");
    assertThat(formEncode(128)).isEqualTo("%C2%80");
    assertThat(formEncode(255)).isEqualTo("%C3%BF");
  }

  private String formEncode(int codePoint) throws IOException {
    // Wrap the codepoint with regular printable characters to prevent trimming.
    FormBody body = new FormBody.Builder()
        .add("a", new String(new int[] {'b', codePoint, 'c'}, 0, 3))
        .build();
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    buffer.skip(3); // Skip "a=b" prefix.
    return buffer.readUtf8(buffer.size() - 1); // Skip the "c" suffix.
  }

  @Test public void manualCharset() throws Exception {
    FormBody body = new FormBody.Builder(Charset.forName("ISO-8859-1"))
        .add("name", "Nicolás")
        .build();

    String expected = "name=Nicol%E1s";
    assertThat(body.contentLength()).isEqualTo(expected.length());

    Buffer out = new Buffer();
    body.writeTo(out);
    assertThat(out.readUtf8()).isEqualTo(expected);
  }
}
