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
package okhttp3.internal.http2;

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

public final class HpackTest {
  private final Buffer bytesIn = new Buffer();
  private Hpack.Reader hpackReader;
  private Buffer bytesOut = new Buffer();
  private Hpack.Writer hpackWriter;

  @Before public void reset() {
    hpackReader = newReader(bytesIn);
    hpackWriter = new Hpack.Writer(4096, false, bytesOut);
  }

  /**
   * Variable-length quantity special cases strings which are longer than 127 bytes.  Values such as
   * cookies can be 4KiB, and should be possible to send.
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
   * HPACK has a max header table size, which can be smaller than the max header message. Ensure the
   * larger header content is not lost.
   */
  @Test public void tooLargeToHPackIsStillEmitted() throws IOException {
    bytesIn.writeByte(0x21); // Dynamic table size update (size = 1).
    bytesIn.writeByte(0x00); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndResetHeaderList());
  }

  /** Oldest entries are evicted to support newer ones. */
  @Test public void writerEviction() throws IOException {
    List<Header> headerBlock =
        headerEntries(
            "custom-foo", "custom-header",
            "custom-bar", "custom-header",
            "custom-baz", "custom-header");

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
    // Use a new Writer because we don't support change the dynamic table
    // size after Writer constructed.
    Hpack.Writer writer = new Hpack.Writer(110, false, bytesOut);
    writer.writeHeaders(headerBlock);

    assertEquals(bytesIn, bytesOut);
    assertEquals(2, writer.headerCount);

    int tableLength = writer.dynamicTable.length;
    Header entry = writer.dynamicTable[tableLength - 1];
    checkEntry(entry, "custom-bar", "custom-header", 55);

    entry = writer.dynamicTable[tableLength - 2];
    checkEntry(entry, "custom-baz", "custom-header", 55);
  }

  @Test public void readerEviction() throws IOException {
    List<Header> headerBlock =
        headerEntries(
            "custom-foo", "custom-header",
            "custom-bar", "custom-header",
            "custom-baz", "custom-header");

    // Set to only support 110 bytes (enough for 2 headers).
    bytesIn.writeByte(0x3F); // Dynamic table size update (size = 110).
    bytesIn.writeByte(0x4F);

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

    hpackReader.readHeaders();

    assertEquals(2, hpackReader.headerCount);

    Header entry1 = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
    checkEntry(entry1, "custom-bar", "custom-header", 55);

    Header entry2 = hpackReader.dynamicTable[readerHeaderTableLength() - 2];
    checkEntry(entry2, "custom-baz", "custom-header", 55);

    // Once a header field is decoded and added to the reconstructed header
    // list, it cannot be removed from it. Hence, foo is here.
    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());

    // Simulate receiving a small dynamic table size update, that implies eviction.
    bytesIn.writeByte(0x3F); // Dynamic table size update (size = 55).
    bytesIn.writeByte(0x18);
    hpackReader.readHeaders();
    assertEquals(1, hpackReader.headerCount);
  }

  /** Header table backing array is initially 8 long, let's ensure it grows. */
  @Test public void dynamicallyGrowsBeyond64Entries() throws IOException {
    // Lots of headers need more room!
    hpackReader = new Hpack.Reader(16384, 4096, bytesIn);
    bytesIn.writeByte(0x3F); // Dynamic table size update (size = 16384).
    bytesIn.writeByte(0xE1);
    bytesIn.writeByte(0x7F);

    for (int i = 0; i < 256; i++) {
      bytesIn.writeByte(0x40); // Literal indexed
      bytesIn.writeByte(0x0a); // Literal name (len = 10)
      bytesIn.writeUtf8("custom-foo");

      bytesIn.writeByte(0x0d); // Literal value (len = 13)
      bytesIn.writeUtf8("custom-header");
    }

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

    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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

    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    List<Header> headerBlock = headerEntries("custom-key", "custom-header");

    bytesIn.writeByte(0x10); // Never indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  @Test public void literalHeaderFieldWithIncrementalIndexingIndexedName() throws IOException {
    List<Header> headerBlock = headerEntries(":path", "/sample/path");

    bytesIn.writeByte(0x44); // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c); // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path");

    hpackReader.readHeaders();

    assertEquals(1, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  @Test public void literalHeaderFieldWithIncrementalIndexingNewName() throws IOException {
    List<Header> headerBlock = headerEntries("custom-key", "custom-header");

    bytesIn.writeByte(0x40); // Never indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackWriter.writeHeaders(headerBlock);
    assertEquals(bytesIn, bytesOut);

    assertEquals(1, hpackWriter.headerCount);

    Header entry = hpackWriter.dynamicTable[hpackWriter.dynamicTable.length - 1];
    checkEntry(entry, "custom-key", "custom-header", 55);

    hpackReader.readHeaders();

    assertEquals(1, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  @Test public void theSameHeaderAfterOneIncrementalIndexed() throws IOException {
    List<Header> headerBlock =
        headerEntries(
            "custom-key", "custom-header",
            "custom-key", "custom-header");

    bytesIn.writeByte(0x40); // Never indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    bytesIn.writeByte(0xbe); // Indexed name and value (idx = 63)

    hpackWriter.writeHeaders(headerBlock);
    assertEquals(bytesIn, bytesOut);

    assertEquals(1, hpackWriter.headerCount);

    Header entry = hpackWriter.dynamicTable[hpackWriter.dynamicTable.length - 1];
    checkEntry(entry, "custom-key", "custom-header", 55);

    hpackReader.readHeaders();

    assertEquals(1, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndResetHeaderList());
  }

  @Test public void staticHeaderIsNotCopiedIntoTheIndexedTable() throws IOException {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET

    hpackReader.readHeaders();

    assertEquals(0, hpackReader.headerCount);
    assertEquals(0, hpackReader.dynamicTableByteCount);

    assertEquals(null, hpackReader.dynamicTable[readerHeaderTableLength() - 1]);

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
    bytesIn.writeByte(0x20); // Dynamic table size update (size = 0).
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET

    hpackReader.readHeaders();

    // Not buffered in header table.
    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndResetHeaderList());
  }

  @Test public void readLiteralHeaderWithIncrementalIndexingStaticName() throws IOException {
    bytesIn.writeByte(0x7d); // == Literal indexed ==
    // Indexed name (idx = 60) -> "www-authenticate"
    bytesIn.writeByte(0x05); // Literal value (len = 5)
    bytesIn.writeUtf8("Basic");

    hpackReader.readHeaders();

    assertEquals(Arrays.asList(new Header("www-authenticate", "Basic")), hpackReader.getAndResetHeaderList());
  }

  @Test public void readLiteralHeaderWithIncrementalIndexingDynamicName() throws IOException {
    bytesIn.writeByte(0x40);
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-foo");
    bytesIn.writeByte(0x05); // Literal value (len = 5)
    bytesIn.writeUtf8("Basic");

    bytesIn.writeByte(0x7e);
    bytesIn.writeByte(0x06); // Literal value (len = 6)
    bytesIn.writeUtf8("Basic2");

    hpackReader.readHeaders();

    assertEquals(
        Arrays.asList(new Header("custom-foo", "Basic"), new Header("custom-foo", "Basic2")),
        hpackReader.getAndResetHeaderList());
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

  @Test public void readFailingRequestExample() throws IOException {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86); // == Indexed - Add ==
    // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x84); // == Indexed - Add ==

    bytesIn.writeByte(0x7f); // == Bad index! ==

    // Indexed name (idx = 4) -> :authority
    bytesIn.writeByte(0x0f); // Literal value (len = 15)
    bytesIn.writeUtf8("www.example.com");

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Header index too large 78", e.getMessage());
    }
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
    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 3];
    checkEntry(entry, "custom-key", "custom-value", 54);

    // [  2] (s =  53) cache-control: no-cache
    entry = hpackReader.dynamicTable[readerHeaderTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  3] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    Header entry = hpackReader.dynamicTable[readerHeaderTableLength() - 3];
    checkEntry(entry, "custom-key", "custom-value", 54);

    // [  2] (s =  53) cache-control: no-cache
    entry = hpackReader.dynamicTable[readerHeaderTableLength() - 2];
    checkEntry(entry, "cache-control", "no-cache", 53);

    // [  3] (s =  57) :authority: www.example.com
    entry = hpackReader.dynamicTable[readerHeaderTableLength() - 1];
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
    assertBytes(0x40, 3, 'f', 'o', 'o', 3, 'B', 'a', 'R');
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

  @Test public void emitsDynamicTableSizeUpdate() throws IOException {
    hpackWriter.setHeaderTableSizeSetting(2048);
    hpackWriter.writeHeaders(Arrays.asList(new Header("foo", "bar")));
    assertBytes(
        0x3F, 0xE1, 0xF, // Dynamic table size update (size = 2048).
        0x40, 3, 'f', 'o', 'o', 3, 'b', 'a', 'r');

    hpackWriter.setHeaderTableSizeSetting(8192);
    hpackWriter.writeHeaders(Arrays.asList(new Header("bar", "foo")));
    assertBytes(
        0x3F, 0xE1, 0x3F, // Dynamic table size update (size = 8192).
        0x40, 3, 'b', 'a', 'r', 3, 'f', 'o', 'o');

    // No more dynamic table updates should be emitted.
    hpackWriter.writeHeaders(Arrays.asList(new Header("far", "boo")));
    assertBytes(0x40, 3, 'f', 'a', 'r', 3, 'b', 'o', 'o');
  }

  @Test public void noDynamicTableSizeUpdateWhenSizeIsEqual() throws IOException {
    int currentSize = hpackWriter.headerTableSizeSetting;
    hpackWriter.setHeaderTableSizeSetting(currentSize);
    hpackWriter.writeHeaders(Arrays.asList(new Header("foo", "bar")));

    assertBytes(0x40, 3, 'f', 'o', 'o', 3, 'b', 'a', 'r');
  }

  @Test public void growDynamicTableSize() throws IOException {
    hpackWriter.setHeaderTableSizeSetting(8192);
    hpackWriter.setHeaderTableSizeSetting(16384);
    hpackWriter.writeHeaders(Arrays.asList(new Header("foo", "bar")));

    assertBytes(
        0x3F, 0xE1, 0x7F, // Dynamic table size update (size = 16384).
        0x40, 3, 'f', 'o', 'o', 3, 'b', 'a', 'r');
  }

  @Test public void shrinkDynamicTableSize() throws IOException {
    hpackWriter.setHeaderTableSizeSetting(2048);
    hpackWriter.setHeaderTableSizeSetting(0);
    hpackWriter.writeHeaders(Arrays.asList(new Header("foo", "bar")));

    assertBytes(
        0x20, // Dynamic size update (size = 0).
        0x40, 3, 'f', 'o', 'o', 3, 'b', 'a', 'r');
  }

  @Test public void manyDynamicTableSizeChanges() throws IOException {
    hpackWriter.setHeaderTableSizeSetting(16384);
    hpackWriter.setHeaderTableSizeSetting(8096);
    hpackWriter.setHeaderTableSizeSetting(0);
    hpackWriter.setHeaderTableSizeSetting(4096);
    hpackWriter.setHeaderTableSizeSetting(2048);
    hpackWriter.writeHeaders(Arrays.asList(new Header("foo", "bar")));

    assertBytes(
        0x20, // Dynamic size update (size = 0).
        0x3F, 0xE1, 0xF, // Dynamic size update (size = 2048).
        0x40, 3, 'f', 'o', 'o', 3, 'b', 'a', 'r');
  }

  @Test public void dynamicTableEvictionWhenSizeLowered() throws IOException {
    List<Header> headerBlock =
        headerEntries(
            "custom-key1", "custom-header",
            "custom-key2", "custom-header");
    hpackWriter.writeHeaders(headerBlock);
    assertEquals(2, hpackWriter.headerCount);

    hpackWriter.setHeaderTableSizeSetting(56);
    assertEquals(1, hpackWriter.headerCount);

    hpackWriter.setHeaderTableSizeSetting(0);
    assertEquals(0, hpackWriter.headerCount);
  }

  @Test public void noEvictionOnDynamicTableSizeIncrease() throws IOException {
    List<Header> headerBlock =
        headerEntries(
            "custom-key1", "custom-header",
            "custom-key2", "custom-header");
    hpackWriter.writeHeaders(headerBlock);
    assertEquals(2, hpackWriter.headerCount);

    hpackWriter.setHeaderTableSizeSetting(8192);
    assertEquals(2, hpackWriter.headerCount);
  }

  @Test public void dynamicTableSizeHasAnUpperBound() {
    hpackWriter.setHeaderTableSizeSetting(1048576);
    assertEquals(16384, hpackWriter.maxDynamicTableByteCount);
  }

  @Test public void huffmanEncode() throws IOException {
    hpackWriter = new Hpack.Writer(4096, true, bytesOut);
    hpackWriter.writeHeaders(headerEntries("foo", "bar"));

    ByteString expected = new Buffer()
        .writeByte(0x40) // Literal header, new name.
        .writeByte(0x82) // String literal is Huffman encoded (len = 2).
        .writeByte(0x94) // 'foo' Huffman encoded.
        .writeByte(0xE7)
        .writeByte(3) // String literal not Huffman encoded (len = 3).
        .writeByte('b')
        .writeByte('a')
        .writeByte('r')
        .readByteString();

    ByteString actual = bytesOut.readByteString();
    assertEquals(expected, actual);
  }

  @Test public void staticTableIndexedHeaders() throws IOException {
    hpackWriter.writeHeaders(headerEntries(":method", "GET"));
    assertBytes(0x82);
    assertEquals(0, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":method", "POST"));
    assertBytes(0x83);
    assertEquals(0, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":path", "/"));
    assertBytes(0x84);
    assertEquals(0, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":path", "/index.html"));
    assertBytes(0x85);
    assertEquals(0, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":scheme", "http"));
    assertBytes(0x86);
    assertEquals(0, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":scheme", "https"));
    assertBytes(0x87);
    assertEquals(0, hpackWriter.headerCount);
  }

  @Test public void dynamicTableIndexedHeader() throws IOException {
    hpackWriter.writeHeaders(headerEntries("custom-key", "custom-header"));
    assertBytes(0x40,
        10, 'c', 'u', 's', 't', 'o', 'm', '-', 'k', 'e', 'y',
        13, 'c', 'u', 's', 't', 'o', 'm', '-', 'h', 'e', 'a', 'd', 'e', 'r');
    assertEquals(1, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries("custom-key", "custom-header"));
    assertBytes(0xbe);
    assertEquals(1, hpackWriter.headerCount);
  }

  @Test public void doNotIndexPseudoHeaders() throws IOException {
    hpackWriter.writeHeaders(headerEntries(":method", "PUT"));
    assertBytes(0x02, 3, 'P', 'U', 'T');
    assertEquals(0, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":path", "/okhttp"));
    assertBytes(0x04, 7, '/', 'o', 'k', 'h', 't', 't', 'p');
    assertEquals(0, hpackWriter.headerCount);
  }

  @Test public void incrementalIndexingWithAuthorityPseudoHeader() throws IOException {
    hpackWriter.writeHeaders(headerEntries(":authority", "foo.com"));
    assertBytes(0x41, 7, 'f', 'o', 'o', '.', 'c', 'o', 'm');
    assertEquals(1, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":authority", "foo.com"));
    assertBytes(0xbe);
    assertEquals(1, hpackWriter.headerCount);

    // If the :authority header somehow changes, it should be re-added to the dynamic table.
    hpackWriter.writeHeaders(headerEntries(":authority", "bar.com"));
    assertBytes(0x41, 7, 'b', 'a', 'r', '.', 'c', 'o', 'm');
    assertEquals(2, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries(":authority", "bar.com"));
    assertBytes(0xbe);
    assertEquals(2, hpackWriter.headerCount);
  }

  @Test public void incrementalIndexingWithStaticTableIndexedName() throws IOException {
    hpackWriter.writeHeaders(headerEntries("accept-encoding", "gzip"));
    assertBytes(0x50, 4, 'g', 'z', 'i', 'p');
    assertEquals(1, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries("accept-encoding", "gzip"));
    assertBytes(0xbe);
    assertEquals(1, hpackWriter.headerCount);
  }

  @Test public void incrementalIndexingWithDynamcTableIndexedName() throws IOException {
    hpackWriter.writeHeaders(headerEntries("foo", "bar"));
    assertBytes(0x40, 3, 'f', 'o', 'o', 3, 'b', 'a', 'r');
    assertEquals(1, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries("foo", "bar1"));
    assertBytes(0x7e, 4, 'b', 'a', 'r', '1');
    assertEquals(2, hpackWriter.headerCount);

    hpackWriter.writeHeaders(headerEntries("foo", "bar1"));
    assertBytes(0xbe);
    assertEquals(2, hpackWriter.headerCount);
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

  private int readerHeaderTableLength() {
    return hpackReader.dynamicTable.length;
  }
}
