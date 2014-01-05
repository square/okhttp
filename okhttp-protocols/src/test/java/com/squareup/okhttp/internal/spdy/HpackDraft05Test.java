/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp.internal.spdy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HpackDraft05Test {

  private final MutableByteArrayInputStream bytesIn = new MutableByteArrayInputStream();
  private final HpackDraft05.Reader hpackReader = new HpackDraft05.Reader(new DataInputStream(bytesIn));

  @After public void resetReader(){
    hpackReader.reset();
  }

  /**
   * HPACK has a max header table size, which can be smaller than the max header message.
   * Ensure the larger header content is not lost.
   */
  @Test public void tooLargeToHPackIsStillEmitted() throws IOException {
    char[] tooLarge = new char[4096];
    Arrays.fill(tooLarge, 'a');
    final List<String> sentHeaders = Arrays.asList("foo", new String(tooLarge));

    bytesIn.set(literalHeaders(sentHeaders));
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerTable.size());

    assertEquals(sentHeaders, hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.1
   */
  @Test public void decodeLiteralHeaderFieldWithIndexing() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x00); // Literal indexed
    out.write(0x0a); // Literal name (len = 10)
    out.write("custom-key".getBytes(), 0, 10);

    out.write(0x0d); // Literal value (len = 13)
    out.write("custom-header".getBytes(), 0, 13);

    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerTable.size());

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    assertEquals("custom-key", entry.name);
    assertEquals("custom-header", entry.value);
    assertEquals(55, entry.size);
    assertEquals(entry.size, hpackReader.headerTableSize);

    assertEquals(Arrays.asList("custom-key", "custom-header"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.2
   */
  @Test public void decodeLiteralHeaderFieldWithoutIndexingIndexedName() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x44); // == Literal not indexed ==
                     // Indexed name (idx = 4) -> :path
    out.write(0x0c); // Literal value (len = 12)
    out.write("/sample/path".getBytes(), 0, 12);

    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerTable.size());

    assertEquals(Arrays.asList(":path", "/sample/path"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.3
   */
  @Test public void decodeIndexedHeaderField() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET

    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerTable.size());

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    assertEquals(":method", entry.name);
    assertEquals("GET", entry.value);
    assertEquals(42, entry.size);
    assertEquals(entry.size, hpackReader.headerTableSize);

    assertEquals(Arrays.asList(":method", "GET"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.4
   */
  @Test public void decodeIndexedHeaderFieldFromStaticTableWithoutBuffering() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET

    bytesIn.set(out.toByteArray());
    hpackReader.maxHeaderTableSize = 0; // SETTINGS_HEADER_TABLE_SIZE == 0
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();

    // Not buffered in header table.
    assertEquals(0, hpackReader.headerTable.size());

    assertEquals(Arrays.asList(":method", "GET"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.2
   */
  @Test public void decodeRequestExamplesWithoutHuffman() throws IOException {
    bytesIn.set(firstRequestWithoutHuffman());
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();
    checkFirstRequestWithoutHuffman();

    bytesIn.set(secondRequestWithoutHuffman());
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();
    checkSecondRequestWithoutHuffman();

    bytesIn.set(thirdRequestWithoutHuffman());
    hpackReader.readHeaders(bytesIn.available());
    hpackReader.emitReferenceSet();
    checkThirdRequestWithoutHuffman();
  }

  private byte[] firstRequestWithoutHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET
    out.write(0x87); // == Indexed - Add ==
                     // idx = 7 -> :scheme: http
    out.write(0x86); // == Indexed - Add ==
                     // idx = 6 -> :path: /
    out.write(0x04); // == Literal indexed ==
                     // Indexed name (idx = 4) -> :authority
    out.write(0x0f); // Literal value (len = 15)
    out.write("www.example.com".getBytes(), 0, 15);

    return out.toByteArray();
  }

  private void checkFirstRequestWithoutHuffman() {
    assertEquals(4, hpackReader.headerTable.size());

    // [  1] (s =  57) :authority: www.example.com
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    assertEquals(":authority", entry.name);
    assertEquals("www.example.com", entry.value);
    assertEquals(57, entry.size);
    assertTrue(entry.referenced);

    // [  2] (s =  38) :path: /
    entry = hpackReader.headerTable.get(1);
    assertEquals(":path", entry.name);
    assertEquals("/", entry.value);
    assertEquals(38, entry.size);
    assertTrue(entry.referenced);

    // [  3] (s =  43) :scheme: http
    entry = hpackReader.headerTable.get(2);
    assertEquals(":scheme", entry.name);
    assertEquals("http", entry.value);
    assertEquals(43, entry.size);
    assertTrue(entry.referenced);

    // [  4] (s =  42) :method: GET
    entry = hpackReader.headerTable.get(3);
    assertEquals(":method", entry.name);
    assertEquals("GET", entry.value);
    assertEquals(42, entry.size);
    assertTrue(entry.referenced);

    // Table size: 180
    assertEquals(180, hpackReader.headerTableSize);

    // Decoded header set:
    assertEquals(Arrays.asList( //
        ":method", "GET", //
        ":scheme", "http", //
        ":path", "/", //
        ":authority", "www.example.com"), hpackReader.getAndReset());
  }

  private byte[] secondRequestWithoutHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x1b); // == Literal indexed ==
                     // Indexed name (idx = 27) -> cache-control
    out.write(0x08); // Literal value (len = 8)
    out.write("no-cache".getBytes(), 0, 8);

    return out.toByteArray();
  }

  private void checkSecondRequestWithoutHuffman() {
    assertEquals(5, hpackReader.headerTable.size());

    // [  1] (s =  53) cache-control: no-cache
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    assertEquals("cache-control", entry.name);
    assertEquals("no-cache", entry.value);
    assertEquals(53, entry.size);
    assertTrue(entry.referenced);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable.get(1);
    assertEquals(":authority", entry.name);
    assertEquals("www.example.com", entry.value);
    assertEquals(57, entry.size);
    assertTrue(entry.referenced);

    // [  3] (s =  38) :path: /
    entry = hpackReader.headerTable.get(2);
    assertEquals(":path", entry.name);
    assertEquals("/", entry.value);
    assertEquals(38, entry.size);
    assertTrue(entry.referenced);

    // [  4] (s =  43) :scheme: http
    entry = hpackReader.headerTable.get(3);
    assertEquals(":scheme", entry.name);
    assertEquals("http", entry.value);
    assertEquals(43, entry.size);
    assertTrue(entry.referenced);

    // [  5] (s =  42) :method: GET
    entry = hpackReader.headerTable.get(4);
    assertEquals(":method", entry.name);
    assertEquals("GET", entry.value);
    assertEquals(42, entry.size);
    assertTrue(entry.referenced);

    // Table size: 233
    assertEquals(233, hpackReader.headerTableSize);

    // Decoded header set:
    assertEquals(Arrays.asList( //
        ":method", "GET", //
        ":scheme", "http", //
        ":path", "/", //
        ":authority", "www.example.com", //
        "cache-control", "no-cache"), hpackReader.getAndReset());
  }

  private byte[] thirdRequestWithoutHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x80); // == Empty reference set ==
    out.write(0x85); // == Indexed - Add ==
                     // idx = 5 -> :method: GET
    out.write(0x8c); // == Indexed - Add ==
                     // idx = 12 -> :scheme: https
    out.write(0x8b); // == Indexed - Add ==
                     // idx = 11 -> :path: /index.html
    out.write(0x84); // == Indexed - Add ==
                     // idx = 4 -> :authority: www.example.com
    out.write(0x00); // Literal indexed
    out.write(0x0a); // Literal name (len = 10)
    out.write("custom-key".getBytes(), 0, 10);
    out.write(0x0c); // Literal value (len = 12)
    out.write("custom-value".getBytes(), 0, 12);

    return out.toByteArray();
  }

  private void checkThirdRequestWithoutHuffman() {
    assertEquals(8, hpackReader.headerTable.size());

    // [  1] (s =  54) custom-key: custom-value
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    assertEquals("custom-key", entry.name);
    assertEquals("custom-value", entry.value);
    assertEquals(54, entry.size);
    assertTrue(entry.referenced);

    // [  2] (s =  48) :path: /index.html
    entry = hpackReader.headerTable.get(1);
    assertEquals(":path", entry.name);
    assertEquals("/index.html", entry.value);
    assertEquals(48, entry.size);
    assertTrue(entry.referenced);

    // [  3] (s =  44) :scheme: https
    entry = hpackReader.headerTable.get(2);
    assertEquals(":scheme", entry.name);
    assertEquals("https", entry.value);
    assertEquals(44, entry.size);
    assertTrue(entry.referenced);

    // [  4] (s =  53) cache-control: no-cache
    entry = hpackReader.headerTable.get(3);
    assertEquals("cache-control", entry.name);
    assertEquals("no-cache", entry.value);
    assertEquals(53, entry.size);
    assertFalse(entry.referenced);

    // [  5] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable.get(4);
    assertEquals(":authority", entry.name);
    assertEquals("www.example.com", entry.value);
    assertEquals(57, entry.size);
    assertTrue(entry.referenced);

    // [  6] (s =  38) :path: /
    entry = hpackReader.headerTable.get(5);
    assertEquals(":path", entry.name);
    assertEquals("/", entry.value);
    assertEquals(38, entry.size);
    assertFalse(entry.referenced);

    // [  7] (s =  43) :scheme: http
    entry = hpackReader.headerTable.get(6);
    assertEquals(":scheme", entry.name);
    assertEquals("http", entry.value);
    assertEquals(43, entry.size);
    assertFalse(entry.referenced);

    // [  8] (s =  42) :method: GET
    entry = hpackReader.headerTable.get(7);
    assertEquals(":method", entry.name);
    assertEquals("GET", entry.value);
    assertEquals(42, entry.size);
    assertTrue(entry.referenced);

    // Table size: 379
    assertEquals(379, hpackReader.headerTableSize);

    // Decoded header set:
    // TODO: order is not correct per docs, but then again, the spec doesn't require ordering.
    assertEquals(Arrays.asList( //
        ":method", "GET", //
        ":authority", "www.example.com", //
        ":scheme", "https", //
        ":path", "/index.html", //
        "custom-key", "custom-value"), hpackReader.getAndReset());
  }

  private ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
  private final HpackDraft05.Writer hpackWriter = new HpackDraft05.Writer(new DataOutputStream(bytesOut));

  @Test public void readSingleByteInt() throws IOException {
    assertEquals(10, new HpackDraft05.Reader(byteStream()).readInt(10, 31));
    assertEquals(10, new HpackDraft05.Reader(byteStream()).readInt(0xe0 | 10, 31));
  }

  @Test public void readMultibyteInt() throws IOException {
    assertEquals(1337, new HpackDraft05.Reader(byteStream(154, 10)).readInt(31, 31));
  }

  @Test public void writeSingleByteInt() throws IOException {
    hpackWriter.writeInt(10, 31, 0);
    assertBytes(10);
    hpackWriter.writeInt(10, 31, 0xe0);
    assertBytes(0xe0 | 10);
  }

  @Test public void writeMultibyteInt() throws IOException {
    hpackWriter.writeInt(1337, 31, 0);
    assertBytes(31, 154, 10);
    hpackWriter.writeInt(1337, 31, 0xe0);
    assertBytes(0xe0 | 31, 154, 10);
  }

  @Test public void max31BitValue() throws IOException {
    hpackWriter.writeInt(0x7fffffff, 31, 0);
    assertBytes(31, 224, 255, 255, 255, 7);
    assertEquals(0x7fffffff,
        new HpackDraft05.Reader(byteStream(224, 255, 255, 255, 7)).readInt(31, 31));
  }

  @Test public void prefixMask() throws IOException {
    hpackWriter.writeInt(31, 31, 0);
    assertBytes(31, 0);
    assertEquals(31, new HpackDraft05.Reader(byteStream(0)).readInt(31, 31));
  }

  @Test public void prefixMaskMinusOne() throws IOException {
    hpackWriter.writeInt(30, 31, 0);
    assertBytes(30);
    assertEquals(31, new HpackDraft05.Reader(byteStream(0)).readInt(31, 31));
  }

  @Test public void zero() throws IOException {
    hpackWriter.writeInt(0, 31, 0);
    assertBytes(0);
    assertEquals(0, new HpackDraft05.Reader(byteStream()).readInt(0, 31));
  }

  @Test public void headerName() throws IOException {
    hpackWriter.writeString("foo");
    assertBytes(3, 'f', 'o', 'o');
    assertEquals("foo", new HpackDraft05.Reader(byteStream(3, 'f', 'o', 'o')).readString());
  }

  @Test public void emptyHeaderName() throws IOException {
    hpackWriter.writeString("");
    assertBytes(0);
    assertEquals("", new HpackDraft05.Reader(byteStream(0)).readString());
  }

  @Test public void headersRoundTrip() throws IOException {
    List<String> sentHeaders = Arrays.asList("name", "value");
    hpackWriter.writeHeaders(sentHeaders);
    ByteArrayInputStream bytesIn = new ByteArrayInputStream(bytesOut.toByteArray());
    HpackDraft05.Reader reader = new HpackDraft05.Reader(new DataInputStream(bytesIn));
    reader.readHeaders(bytesOut.size());
    reader.emitReferenceSet();
    List<String> receivedHeaders = reader.getAndReset();
    assertEquals(sentHeaders, receivedHeaders);
  }

  private DataInputStream byteStream(int... bytes) {
    byte[] data = intArrayToByteArray(bytes);
    return new DataInputStream(new ByteArrayInputStream(data));
  }

  private byte[] literalHeaders(List<String> sentHeaders) throws IOException {
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
    new HpackDraft05.Writer(new DataOutputStream(headerBytes)).writeHeaders(sentHeaders);
    return headerBytes.toByteArray();
  }

  private void assertBytes(int... bytes) {
    byte[] expected = intArrayToByteArray(bytes);
    byte[] actual = bytesOut.toByteArray();
    assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    bytesOut.reset(); // So the next test starts with a clean slate.
  }

  private byte[] intArrayToByteArray(int[] bytes) {
    byte[] data = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      data[i] = (byte) bytes[i];
    }
    return data;
  }

  private static class MutableByteArrayInputStream extends ByteArrayInputStream {

    private MutableByteArrayInputStream() {
      super(new byte[] { });
    }

    private void set(byte[] replacement) {
      this.buf = replacement;
      this.pos = 0;
      this.count = replacement.length;
    }
  }
}
