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

import com.squareup.okhttp.internal.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.byteStringList;
import static org.junit.Assert.assertEquals;

public class HpackDraft05Test {

  private final MutableByteArrayInputStream bytesIn = new MutableByteArrayInputStream();
  private HpackDraft05.Reader hpackReader;

  @Before public void resetReader() {
    hpackReader = new HpackDraft05.Reader(new DataInputStream(bytesIn));
  }

  /**
   * HPACK has a max header table size, which can be smaller than the max header message.
   * Ensure the larger header content is not lost.
   */
  @Test public void tooLargeToHPackIsStillEmitted() throws IOException {
    char[] tooLarge = new char[4096];
    Arrays.fill(tooLarge, 'a');
    final List<ByteString> sentHeaders = byteStringList("foo", new String(tooLarge));

    ByteArrayOutputStream out = literalHeaders(sentHeaders);

    List<ByteString> nameValueBlock = readHeaderFrame(out);

    assertEquals(0, hpackReader.headerTable.size());

    assertEquals(sentHeaders, nameValueBlock);
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

    List<ByteString> nameValueBlock = readHeaderFrame(out);

    assertEquals(1, hpackReader.headerTable.size());
    assertEquals(55, hpackReader.headerTableSize);

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    checkEntry(entry, "custom-key", "custom-header", 55, true);

    assertEquals(byteStringList("custom-key", "custom-header"), nameValueBlock);
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

    List<ByteString> nameValueBlock = readHeaderFrame(out);

    assertEquals(0, hpackReader.headerTable.size());

    assertEquals(byteStringList(":path", "/sample/path"), nameValueBlock);
  }

  private List<ByteString> readHeaderFrame(ByteArrayOutputStream out) throws IOException {
    Http20Draft09.NameValueBlockCallback callback = new Http20Draft09.NameValueBlockCallback();
    readHeaderFrame(out.toByteArray(), callback);
    return callback.nameValueBlock;
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.3
   */
  @Test public void decodeIndexedHeaderField() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET

    List<ByteString> nameValueBlock = readHeaderFrame(out);

    assertEquals(1, hpackReader.headerTable.size());
    assertEquals(42, hpackReader.headerTableSize);

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    checkEntry(entry, ":method", "GET", 42, true);

    assertEquals(byteStringList(":method", "GET"), nameValueBlock);
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.4
   */
  @Test public void decodeIndexedHeaderFieldFromStaticTableWithoutBuffering() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET

    hpackReader.maxHeaderTableSize = 0; // SETTINGS_HEADER_TABLE_SIZE == 0
    List<ByteString> nameValueBlock = readHeaderFrame(out);

    // Not buffered in header table.
    assertEquals(0, hpackReader.headerTable.size());

    assertEquals(byteStringList(":method", "GET"), nameValueBlock);
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.2
   */
  @Test public void decodeRequestExamplesWithoutHuffman() throws IOException {
    ByteArrayOutputStream out = firstRequestWithoutHuffman();
    List<ByteString> nameValueBlock = readHeaderFrame(out);
    checkFirstRequestWithoutHuffman(nameValueBlock);

    out = secondRequestWithoutHuffman();
    nameValueBlock = readHeaderFrame(out);
    checkSecondRequestWithoutHuffman(nameValueBlock);

    out = thirdRequestWithoutHuffman();
    nameValueBlock = readHeaderFrame(out);
    checkThirdRequestWithoutHuffman(nameValueBlock);
  }

  private ByteArrayOutputStream firstRequestWithoutHuffman() {
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

    return out;
  }

  private void checkFirstRequestWithoutHuffman(List<ByteString> nameValueBlock) {
    assertEquals(4, hpackReader.headerTable.size());

    // [  1] (s =  57) :authority: www.example.com
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    checkEntry(entry, ":authority", "www.example.com", 57, true);

    // [  2] (s =  38) :path: /
    entry = hpackReader.headerTable.get(1);
    checkEntry(entry, ":path", "/", 38, true);

    // [  3] (s =  43) :scheme: http
    entry = hpackReader.headerTable.get(2);
    checkEntry(entry, ":scheme", "http", 43, true);

    // [  4] (s =  42) :method: GET
    entry = hpackReader.headerTable.get(3);
    checkEntry(entry, ":method", "GET", 42, true);

    // Table size: 180
    assertEquals(180, hpackReader.headerTableSize);

    // Decoded header set:
    assertEquals(byteStringList(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), nameValueBlock);
  }

  private ByteArrayOutputStream secondRequestWithoutHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x1b); // == Literal indexed ==
                     // Indexed name (idx = 27) -> cache-control
    out.write(0x08); // Literal value (len = 8)
    out.write("no-cache".getBytes(), 0, 8);

    return out;
  }

  private void checkSecondRequestWithoutHuffman(List<ByteString> nameValueBlock) {
    assertEquals(5, hpackReader.headerTable.size());

    // [  1] (s =  53) cache-control: no-cache
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    checkEntry(entry, "cache-control", "no-cache", 53, true);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable.get(1);
    checkEntry(entry, ":authority", "www.example.com", 57, true);

    // [  3] (s =  38) :path: /
    entry = hpackReader.headerTable.get(2);
    checkEntry(entry, ":path", "/", 38, true);

    // [  4] (s =  43) :scheme: http
    entry = hpackReader.headerTable.get(3);
    checkEntry(entry, ":scheme", "http", 43, true);

    // [  5] (s =  42) :method: GET
    entry = hpackReader.headerTable.get(4);
    checkEntry(entry, ":method", "GET", 42, true);

    // Table size: 233
    assertEquals(233, hpackReader.headerTableSize);

    // Decoded header set:
    assertEquals(byteStringList(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), nameValueBlock);
  }

  private ByteArrayOutputStream thirdRequestWithoutHuffman() {
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

    return out;
  }

  private void checkThirdRequestWithoutHuffman(List<ByteString> nameValueBlock) {
    assertEquals(8, hpackReader.headerTable.size());

    // [  1] (s =  54) custom-key: custom-value
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable.get(0);
    checkEntry(entry, "custom-key", "custom-value", 54, true);

    // [  2] (s =  48) :path: /index.html
    entry = hpackReader.headerTable.get(1);
    checkEntry(entry, ":path", "/index.html", 48, true);

    // [  3] (s =  44) :scheme: https
    entry = hpackReader.headerTable.get(2);
    checkEntry(entry, ":scheme", "https", 44, true);

    // [  4] (s =  53) cache-control: no-cache
    entry = hpackReader.headerTable.get(3);
    checkEntry(entry, "cache-control", "no-cache", 53, false);

    // [  5] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable.get(4);
    checkEntry(entry, ":authority", "www.example.com", 57, true);

    // [  6] (s =  38) :path: /
    entry = hpackReader.headerTable.get(5);
    checkEntry(entry, ":path", "/", 38, false);

    // [  7] (s =  43) :scheme: http
    entry = hpackReader.headerTable.get(6);
    checkEntry(entry, ":scheme", "http", 43, false);

    // [  8] (s =  42) :method: GET
    entry = hpackReader.headerTable.get(7);
    checkEntry(entry, ":method", "GET", 42, true);

    // Table size: 379
    assertEquals(379, hpackReader.headerTableSize);

    // Decoded header set:
    // TODO: order is not correct per docs, but then again, the spec doesn't require ordering.
    assertEquals(byteStringList(
        ":method", "GET",
        ":authority", "www.example.com",
        ":scheme", "https",
        ":path", "/index.html",
        "custom-key", "custom-value"), nameValueBlock);
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
    hpackWriter.writeByteString(ByteString.encodeUtf8("foo"));
    assertBytes(3, 'f', 'o', 'o');
    assertEquals("foo", new HpackDraft05.Reader(byteStream(3, 'f', 'o', 'o')).readString().utf8());
  }

  @Test public void emptyHeaderName() throws IOException {
    hpackWriter.writeByteString(ByteString.encodeUtf8(""));
    assertBytes(0);
    assertEquals("", new HpackDraft05.Reader(byteStream(0)).readString().utf8());
  }

  @Test public void headersRoundTrip() throws IOException {
    List<ByteString> sentHeaders = byteStringList("name", "value");
    hpackWriter.writeHeaders(sentHeaders);
    ByteArrayInputStream bytesIn = new ByteArrayInputStream(bytesOut.toByteArray());
    HpackDraft05.Reader reader = new HpackDraft05.Reader(new DataInputStream(bytesIn));
    Http20Draft09.NameValueBlockCallback callback = new Http20Draft09.NameValueBlockCallback();
    reader.readHeaders(bytesOut.size(), callback);
    reader.emitReferenceSet(callback);
    assertEquals(sentHeaders, callback.nameValueBlock);
  }

  private void readHeaderFrame(byte[] frame, HpackDraft05.Callback callback) throws IOException {
    bytesIn.set(frame);
    hpackReader.readHeaders(frame.length, callback);
    hpackReader.emitReferenceSet(callback);
  }

  private DataInputStream byteStream(int... bytes) {
    byte[] data = intArrayToByteArray(bytes);
    return new DataInputStream(new ByteArrayInputStream(data));
  }

  private ByteArrayOutputStream literalHeaders(List<ByteString> sentHeaders) throws IOException {
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
    new HpackDraft05.Writer(new DataOutputStream(headerBytes)).writeHeaders(sentHeaders);
    return headerBytes;
  }

  private void checkEntry(HpackDraft05.HeaderEntry entry, String name, String value, int size,
      boolean referenced) {
    assertEquals(name, entry.name.utf8());
    assertEquals(value, entry.value.utf8());
    assertEquals(size, entry.size);
    assertEquals(referenced, entry.referenced);
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
