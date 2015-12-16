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
package okhttp3.internal.framed;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import okio.Buffer;
import okio.ByteString;
import org.junit.Before;
import org.junit.Test;

import static okhttp3.TestUtil.headerEntries;
import static okio.ByteString.decodeHex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HpackTest {

  private final Buffer bytesIn = new Buffer();
  private Hpack.Reader hpackReader;
  private Buffer bytesOut = new Buffer();
  private Hpack.Writer hpackWriter;

  @Before public void reset() {
    hpackReader = newReader(bytesIn);
    hpackWriter = new Hpack.Writer(bytesOut);
  }

  /**
   * Variable-length quantity special cases strings which are longer than 127
   * bytes.  Values such as cookies can be 4KiB, and should be possible to send.
   *
   * <p> http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-5.2
   */
  @Test public void largeHeaderValue() throws IOException {
    char[] value = new char[4096];
    Arrays.fill(value, '!');
    List<Header> headerBlock = headerEntries("cookie", new String(value));

    hpackWriter.writeHeaders(headerBlock);
    bytesIn.writeAll(bytesOut);
    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  /**
   * HPACK has a max header table size, which can be smaller than the max header message.
   * Ensure the larger header content is not lost.
   */
  @Test public void tooLargeToHPackIsStillEmitted() throws IOException {
    bytesIn.writeByte(0x00); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.headerTableSizeSetting(1);
    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndResetHeaderList());
  }

  /** Oldest entries are evicted to support newer ones. */
  @Test public void testEviction() throws IOException {
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-foo");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-bar");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-baz");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    // Set to only support 110 bytes (enough for 2 headers).
    hpackReader.headerTableSizeSetting(110);
    hpackReader.readHeaders();

    assertEquals(2, hpackReader.headerCount);

    Header entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, "custom-bar", "custom-header", 55);

    entry = hpackReader.dynamicTable[headerTableLength() - 2];
    checkEntry(entry, "custom-baz", "custom-header", 55);

    // Once a header field is decoded and added to the reconstructed header
    // list, it cannot be removed from it. Hence, foo is here.
    assertEquals(
        headerEntries(
            "custom-foo", "custom-header",
            "custom-bar", "custom-header",
            "custom-baz", "custom-header"),
        hpackReader.getAndResetHeaderList());

    // Simulate receiving a small settings frame, that implies eviction.
    hpackReader.headerTableSizeSetting(55);
    assertEquals(1, hpackReader.headerCount);
  }

  /** Header table backing array is initially 8 long, let's ensure it grows. */
  @Test public void dynamicallyGrowsBeyond64Entries() throws IOException {
    for (int i = 0; i < 256; i++) {
      bytesIn.writeByte(0x40); // Literal indexed
      bytesIn.writeByte(0x0a); // Literal name (len = 10)
      bytesIn.writeUtf8("custom-foo");

      bytesIn.writeByte(0x0d); // Literal value (len = 13)
      bytesIn.writeUtf8("custom-header");
    }

    hpackReader.headerTableSizeSetting(16384); // Lots of headers need more room!
    hpackReader.readHeaders();

    assertEquals(256, hpackReader.headerCount);
  }

  @Test public void huffmanDecodingSupported() throws IOException {
    bytesIn.writeByte(0x44); // == Literal indexed ==
                             // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x8c); // Literal value Huffman encoded 12 bytes
                             // decodes to www.example.com which is length 15
    bytesIn.write(decodeHex("f1e3c2e5f23a6ba0ab90f4ff"));

    hpackReader.readHeaders();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(52, hpackReader.dynamicTableByteCount);

    Header entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":path", "www.example.com", 52);
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2.1
   */
  @Test public void readLiteralHeaderFieldWithIndexing() throws IOException {
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.readHeaders();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(55, hpackReader.dynamicTableByteCount);

    Header entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, "custom-key", "custom-header", 55);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndResetHeaderList());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2.2
   */
  @Test public void literalHeaderFieldWithoutIndexingIndexedName() throws IOException {
    List<Header> headerBlock = headerEntries(":path", "/sample/path");

    bytesIn.writeByte(0x04); // == Literal not indexed ==
                             // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c); // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path");

    hpackWriter.writeHeaders(headerBlock);
    assertEquals(bytesIn, bytesOut);

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  @Test public void literalHeaderFieldWithoutIndexingNewName() throws IOException {
    List<Header> headerBlock = headerEntries("custom-key", "custom-header");

    bytesIn.writeByte(0x00); // Not indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackWriter.writeHeaders(headerBlock);
    assertEquals(bytesIn, bytesOut);

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  @Test public void literalHeaderFieldNeverIndexedIndexedName() throws IOException {
    bytesIn.writeByte(0x14); // == Literal never indexed ==
                             // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c); // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path");

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries(":path", "/sample/path"), hpackReader.getAndResetHeaderList());
  }

  @Test public void literalHeaderFieldNeverIndexedNewName() throws IOException {
    bytesIn.writeByte(0x10); // Never indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndResetHeaderList());
  }

  @Test public void staticHeaderIsNotCopiedIntoTheIndexedTable() throws IOException {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);
    assertEquals(0, hpackReader.dynamicTableByteCount);

    assertEquals(null, hpackReader.dynamicTable[headerTableLength() - 1]);

    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndResetHeaderList());
  }

  // Example taken from twitter/hpack DecoderTest.testUnusedIndex
  @Test public void readIndexedHeaderFieldIndex0() throws IOException {
    bytesIn.writeByte(0x80); // == Indexed - Add idx = 0

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("index == 0", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testIllegalIndex
  @Test public void readIndexedHeaderFieldTooLargeIndex() throws IOException {
    bytesIn.writeShort(0xff00); // == Indexed - Add idx = 127

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Header index too large 127", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testInsidiousIndex
  @Test public void readIndexedHeaderFieldInsidiousIndex() throws IOException {
    bytesIn.writeByte(0xff); // == Indexed - Add ==
    bytesIn.write(decodeHex("8080808008")); // idx = -2147483521

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Header index too large -2147483521", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testHeaderTableSizeUpdate
  @Test public void minMaxHeaderTableSize() throws IOException {
    bytesIn.writeByte(0x20);
    hpackReader.readHeaders();

    assertEquals(0, hpackReader.maxDynamicTableByteCount());

    bytesIn.writeByte(0x3f); // encode size 4096
    bytesIn.writeByte(0xe1);
    bytesIn.writeByte(0x1f);
    hpackReader.readHeaders();

    assertEquals(4096, hpackReader.maxDynamicTableByteCount());
  }

  // Example taken from twitter/hpack DecoderTest.testIllegalHeaderTableSizeUpdate
  @Test public void cannotSetTableSizeLargerThanSettingsValue() throws IOException {
    bytesIn.writeByte(0x3f); // encode size 4097
    bytesIn.writeByte(0xe2);
    bytesIn.writeByte(0x1f);

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Invalid dynamic table size update 4097", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testInsidiousMaxHeaderSize
  @Test public void readHeaderTableStateChangeInsidiousMaxHeaderByteCount() throws IOException {
    bytesIn.writeByte(0x3f);
    bytesIn.write(decodeHex("e1ffffff07")); // count = -2147483648

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Invalid dynamic table size update -2147483648", e.getMessage());
    }
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2.4
   */
  @Test public void readIndexedHeaderFieldFromStaticTableWithoutBuffering() throws IOException {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET

    hpackReader.headerTableSizeSetting(0); // SETTINGS_HEADER_TABLE_SIZE == 0
    hpackReader.readHeaders();

    // Not buffered in header table.
    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndResetHeaderList());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2
   */
  @Test public void readRequestExamplesWithoutHuffman() throws IOException {
    firstRequestWithoutHuffman();
    hpackReader.readHeaders();
    checkReadFirstRequestWithoutHuffman();

    secondRequestWithoutHuffman();
    hpackReader.readHeaders();
    checkReadSecondRequestWithoutHuffman();

    thirdRequestWithoutHuffman();
    hpackReader.readHeaders();
    checkReadThirdRequestWithoutHuffman();
  }

  private void firstRequestWithoutHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86); // == Indexed - Add ==
                             // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x84); // == Indexed - Add ==
                             // idx = 6 -> :path: /
    bytesIn.writeByte(0x41); // == Literal indexed ==
                             // Indexed name (idx = 4) -> :authority
    bytesIn.writeByte(0x0f); // Literal value (len = 15)
    bytesIn.writeUtf8("www.example.com");
  }

  private void checkReadFirstRequestWithoutHuffman() {
    assertEquals(1, hpackReader.headerCount);

    // [  1] (s =  57) :authority: www.example.com
    Header entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":authority", "www.example.com", 57);

    // Table size: 57
    assertEquals(57, hpackReader.dynamicTableByteCount);

    // Decoded header list:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), hpackReader.getAndResetHeaderList());
  }

  private void secondRequestWithoutHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86); // == Indexed - Add ==
                             // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x84); // == Indexed - Add ==
                             // idx = 6 -> :path: /
    bytesIn.writeByte(0xbe); // == Indexed - Add ==
                             // Indexed name (idx = 62) -> :authority: www.example.com
    bytesIn.writeByte(0x58); // == Literal indexed ==
                             // Indexed name (idx = 24) -> cache-control
    bytesIn.writeByte(0x08); // Literal value (len = 8)
    bytesIn.writeUtf8("no-cache");
  }

  private void checkReadSecondRequestWithoutHuffman() {
    assertEquals(2, hpackReader.headerCount);

    // [  1] (s =  53) cache-control: no-cache
    Header entry = hpackReader.dynamicTable[headerTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":authority", "www.example.com", 57);

    // Table size: 110
    assertEquals(110, hpackReader.dynamicTableByteCount);

    // Decoded header list:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), hpackReader.getAndResetHeaderList());
  }

  private void thirdRequestWithoutHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x87); // == Indexed - Add ==
                             // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x85); // == Indexed - Add ==
                             // idx = 5 -> :path: /index.html
    bytesIn.writeByte(0xbf); // == Indexed - Add ==
                             // Indexed name (idx = 63) -> :authority: www.example.com
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");
    bytesIn.writeByte(0x0c); // Literal value (len = 12)
    bytesIn.writeUtf8("custom-value");
  }

  private void checkReadThirdRequestWithoutHuffman() {
    assertEquals(3, hpackReader.headerCount);

    // [  1] (s =  54) custom-key: custom-value
    Header entry = hpackReader.dynamicTable[headerTableLength() - 3];
    checkEntry(entry, "custom-key", "custom-value", 54);

    // [  2] (s =  53) cache-control: no-cache
    entry = hpackReader.dynamicTable[headerTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  3] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":authority", "www.example.com", 57);

    // Table size: 164
    assertEquals(164, hpackReader.dynamicTableByteCount);

    // Decoded header list:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "https",
        ":path", "/index.html",
        ":authority", "www.example.com",
        "custom-key", "custom-value"), hpackReader.getAndResetHeaderList());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.4
   */
  @Test public void readRequestExamplesWithHuffman() throws IOException {
    firstRequestWithHuffman();
    hpackReader.readHeaders();
    checkReadFirstRequestWithHuffman();

    secondRequestWithHuffman();
    hpackReader.readHeaders();
    checkReadSecondRequestWithHuffman();

    thirdRequestWithHuffman();
    hpackReader.readHeaders();
    checkReadThirdRequestWithHuffman();
  }

  private void firstRequestWithHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86); // == Indexed - Add ==
                             // idx = 6 -> :scheme: http
    bytesIn.writeByte(0x84); // == Indexed - Add ==
                             // idx = 4 -> :path: /
    bytesIn.writeByte(0x41); // == Literal indexed ==
                             // Indexed name (idx = 1) -> :authority
    bytesIn.writeByte(0x8c); // Literal value Huffman encoded 12 bytes
                             // decodes to www.example.com which is length 15
    bytesIn.write(decodeHex("f1e3c2e5f23a6ba0ab90f4ff"));
  }

  private void checkReadFirstRequestWithHuffman() {
    assertEquals(1, hpackReader.headerCount);

    // [  1] (s =  57) :authority: www.example.com
    Header entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":authority", "www.example.com", 57);

    // Table size: 57
    assertEquals(57, hpackReader.dynamicTableByteCount);

    // Decoded header list:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), hpackReader.getAndResetHeaderList());
  }

  private void secondRequestWithHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86); // == Indexed - Add ==
                             // idx = 6 -> :scheme: http
    bytesIn.writeByte(0x84); // == Indexed - Add ==
                             // idx = 4 -> :path: /
    bytesIn.writeByte(0xbe); // == Indexed - Add ==
                             // idx = 62 -> :authority: www.example.com
    bytesIn.writeByte(0x58); // == Literal indexed ==
                             // Indexed name (idx = 24) -> cache-control
    bytesIn.writeByte(0x86); // Literal value Huffman encoded 6 bytes
                             // decodes to no-cache which is length 8
    bytesIn.write(decodeHex("a8eb10649cbf"));
  }

  private void checkReadSecondRequestWithHuffman() {
    assertEquals(2, hpackReader.headerCount);

    // [  1] (s =  53) cache-control: no-cache
    Header entry = hpackReader.dynamicTable[headerTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":authority", "www.example.com", 57);

    // Table size: 110
    assertEquals(110, hpackReader.dynamicTableByteCount);

    // Decoded header list:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), hpackReader.getAndResetHeaderList());
  }

  private void thirdRequestWithHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x87); // == Indexed - Add ==
                             // idx = 7 -> :scheme: https
    bytesIn.writeByte(0x85); // == Indexed - Add ==
                             // idx = 5 -> :path: /index.html
    bytesIn.writeByte(0xbf); // == Indexed - Add ==
                             // idx = 63 -> :authority: www.example.com
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x88); // Literal name Huffman encoded 8 bytes
                             // decodes to custom-key which is length 10
    bytesIn.write(decodeHex("25a849e95ba97d7f"));
    bytesIn.writeByte(0x89); // Literal value Huffman encoded 9 bytes
                             // decodes to custom-value which is length 12
    bytesIn.write(decodeHex("25a849e95bb8e8b4bf"));
  }

  private void checkReadThirdRequestWithHuffman() {
    assertEquals(3, hpackReader.headerCount);

    // [  1] (s =  54) custom-key: custom-value
    Header entry = hpackReader.dynamicTable[headerTableLength() - 3];
    checkEntry(entry, "custom-key", "custom-value", 54);

    // [  2] (s =  53) cache-control: no-cache
    entry = hpackReader.dynamicTable[headerTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  3] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[headerTableLength() - 1];
    checkEntry(entry, ":authority", "www.example.com", 57);

    // Table size: 164
    assertEquals(164, hpackReader.dynamicTableByteCount);

    // Decoded header list:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "https",
        ":path", "/index.html",
        ":authority", "www.example.com",
        "custom-key", "custom-value"), hpackReader.getAndResetHeaderList());
  }

  @Test public void readSingleByteInt() throws IOException {
    assertEquals(10, newReader(byteStream()).readInt(10, 31));
    assertEquals(10, newReader(byteStream()).readInt(0xe0 | 10, 31));
  }

  @Test public void readMultibyteInt() throws IOException {
    assertEquals(1337, newReader(byteStream(154, 10)).readInt(31, 31));
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
        newReader(byteStream(224, 255, 255, 255, 7)).readInt(31, 31));
  }

  @Test public void prefixMask() throws IOException {
    hpackWriter.writeInt(31, 31, 0);
    assertBytes(31, 0);
    assertEquals(31, newReader(byteStream(0)).readInt(31, 31));
  }

  @Test public void prefixMaskMinusOne() throws IOException {
    hpackWriter.writeInt(30, 31, 0);
    assertBytes(30);
    assertEquals(31, newReader(byteStream(0)).readInt(31, 31));
  }

  @Test public void zero() throws IOException {
    hpackWriter.writeInt(0, 31, 0);
    assertBytes(0);
    assertEquals(0, newReader(byteStream()).readInt(0, 31));
  }

  @Test public void lowercaseHeaderNameBeforeEmit() throws IOException {
    hpackWriter.writeHeaders(Arrays.asList(new Header("FoO", "BaR")));
    assertBytes(0, 3, 'f', 'o', 'o', 3, 'B', 'a', 'R');
  }

  @Test public void mixedCaseHeaderNameIsMalformed() throws IOException {
    try {
      newReader(byteStream(0, 3, 'F', 'o', 'o', 3, 'B', 'a', 'R')).readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("PROTOCOL_ERROR response malformed: mixed case name: Foo", e.getMessage());
    }
  }

  @Test public void emptyHeaderName() throws IOException {
    hpackWriter.writeByteString(ByteString.encodeUtf8(""));
    assertBytes(0);
    assertEquals(ByteString.EMPTY, newReader(byteStream(0)).readByteString());
  }

  private Hpack.Reader newReader(Buffer source) {
    return new Hpack.Reader(4096, source);
  }

  private Buffer byteStream(int... bytes) {
    return new Buffer().write(intArrayToByteArray(bytes));
  }

  private void checkEntry(Header entry, String name, String value, int size) {
    assertEquals(name, entry.name.utf8());
    assertEquals(value, entry.value.utf8());
    assertEquals(size, entry.hpackSize);
  }

  private void assertBytes(int... bytes) throws IOException {
    ByteString expected = intArrayToByteArray(bytes);
    ByteString actual = bytesOut.readByteString();
    assertEquals(expected, actual);
  }

  private ByteString intArrayToByteArray(int[] bytes) {
    byte[] data = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      data[i] = (byte) bytes[i];
    }
    return ByteString.of(data);
  }

  private int headerTableLength() {
    return hpackReader.dynamicTable.length;
  }
}
