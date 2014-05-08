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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import okio.Buffer;
import okio.ByteString;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.headerEntries;
import static okio.ByteString.decodeHex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HpackDraft07Test {

  private final Buffer bytesIn = new Buffer();
  private HpackDraft07.Reader hpackReader;
  private Buffer bytesOut = new Buffer();
  private HpackDraft07.Writer hpackWriter;

  @Before public void reset() {
    hpackReader = newReader(bytesIn);
    hpackWriter = new HpackDraft07.Writer(bytesOut);
  }

  /**
   * Variable-length quantity special cases strings which are longer than 127
   * bytes.  Values such as cookies can be 4KiB, and should be possible to send.
   *
   * <p> http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#section-4.1.1
   */
  @Test public void largeHeaderValue() throws IOException {
    char[] value = new char[4096];
    Arrays.fill(value, '!');
    List<Header> headerBlock = headerEntries("cookie", new String(value));

    hpackWriter.writeHeaders(headerBlock);
    bytesIn.writeAll(bytesOut);
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndReset());
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

    hpackReader.maxHeaderTableByteCountSetting(1);
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndReset());
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
    hpackReader.maxHeaderTableByteCountSetting(110);
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(2, hpackReader.headerCount);

    Header entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, "custom-bar", "custom-header", 55);
    assertHeaderReferenced(headerTableLength() - 1);

    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, "custom-baz", "custom-header", 55);
    assertHeaderReferenced(headerTableLength() - 2);

    // foo isn't here as it is no longer in the table.
    // TODO: emit before eviction?
    assertEquals(headerEntries("custom-bar", "custom-header", "custom-baz", "custom-header"),
        hpackReader.getAndReset());

    // Simulate receiving a small settings frame, that implies eviction.
    hpackReader.maxHeaderTableByteCountSetting(55);
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

    hpackReader.maxHeaderTableByteCountSetting(16384); // Lots of headers need more room!
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(256, hpackReader.headerCount);
    assertHeaderReferenced(headerTableLength() - 1);
    assertHeaderReferenced(headerTableLength() - hpackReader.headerCount);
  }

  @Test public void huffmanDecodingSupported() throws IOException {
    bytesIn.writeByte(0x44); // == Literal indexed ==
                             // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x8c); // Literal value Huffman encoded 12 bytes
                             // decodes to www.example.com which is length 15
    bytesIn.write(decodeHex("e7cf9bebe89b6fb16fa9b6ff"));

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(52, hpackReader.headerTableByteCount);

    Header entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":path", "www.example.com", 52);
    assertHeaderReferenced(headerTableLength() - 1);
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-D.1.1
   */
  @Test public void readLiteralHeaderFieldWithIndexing() throws IOException {
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(55, hpackReader.headerTableByteCount);

    Header entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, "custom-key", "custom-header", 55);
    assertHeaderReferenced(headerTableLength() - 1);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndReset());
  }

  /**
   * https://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-D.2.2
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
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndReset());
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
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerBlock, hpackReader.getAndReset());
  }

  @Test public void literalHeaderFieldNeverIndexedIndexedName() throws IOException {
    bytesIn.writeByte(0x14); // == Literal never indexed ==
                             // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c); // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path");

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries(":path", "/sample/path"), hpackReader.getAndReset());
  }

  @Test public void literalHeaderFieldNeverIndexedNewName() throws IOException {
    bytesIn.writeByte(0x10); // Never indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");

    bytesIn.writeByte(0x0d); // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header");

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries("custom-key", "custom-header"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-D.1.3
   */
  @Test public void readIndexedHeaderField() throws IOException {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(42, hpackReader.headerTableByteCount);

    Header entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndReset());
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
    } catch (IllegalArgumentException e) {
      assertEquals("input must be between 0 and 63: -2147483514", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testIllegalEncodeContextUpdate
  @Test public void readHeaderTableStateChangeInvalid() throws IOException {
    bytesIn.writeByte(0x31); // header table state should be 0x30 for empty!

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Invalid header table state change 49", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testMaxHeaderTableSize
  @Test public void minMaxHeaderTableSize() throws IOException {
    bytesIn.writeByte(0x20);
    hpackReader.readHeaders();

    assertEquals(0, hpackReader.maxHeaderTableByteCount());

    bytesIn.writeByte(0x2f); // encode size 4096
    bytesIn.writeByte(0xf1);
    bytesIn.writeByte(0x1f);
    hpackReader.readHeaders();

    assertEquals(4096, hpackReader.maxHeaderTableByteCount());
  }

  // Example taken from twitter/hpack DecoderTest.testIllegalMaxHeaderTableSize
  @Test public void cannotSetTableSizeLargerThanSettingsValue() throws IOException {
    bytesIn.writeByte(0x2f); // encode size 4097
    bytesIn.writeByte(0xf2);
    bytesIn.writeByte(0x1f);

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Invalid header table byte count 4097", e.getMessage());
    }
  }

  // Example taken from twitter/hpack DecoderTest.testInsidiousMaxHeaderSize
  @Test public void readHeaderTableStateChangeInsidiousMaxHeaderByteCount() throws IOException {
    bytesIn.writeByte(0x2f); // TODO: header table state change
    bytesIn.write(decodeHex("f1ffffff07")); // count = -2147483648

    try {
      hpackReader.readHeaders();
      fail();
    } catch (IOException e) {
      assertEquals("Invalid header table byte count -2147483648", e.getMessage());
    }
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#section-3.2.1
   */
  @Test public void toggleIndex() throws IOException {
    // Static table entries are copied to the top of the reference set.
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    // Specifying an index to an entry in the reference set removes it.
    bytesIn.writeByte(0x81); // == Indexed - Remove ==
                             // idx = 1 -> :method: GET

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(42, hpackReader.headerTableByteCount);

    Header entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderNotReferenced(headerTableLength() - 1);

    assertTrue(hpackReader.getAndReset().isEmpty());
  }

  /** Ensure a later toggle of the same index emits! */
  @Test public void toggleIndexOffOn() throws IOException {
    bytesIn.writeByte(0x82); // Copy static header 1 to the header table as index 1.
    bytesIn.writeByte(0x81); // Remove index 1 from the reference set.

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    assertEquals(1, hpackReader.headerCount);
    assertTrue(hpackReader.getAndReset().isEmpty());

    bytesIn.writeByte(0x81); // Add index 1 back to the reference set.

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    assertEquals(1, hpackReader.headerCount);
    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndReset());
  }

  /** Check later toggle of the same index for large header sets. */
  @Test public void toggleIndexOffBeyond64Entries() throws IOException {
    int expectedHeaderCount = 65;

    for (int i = 0; i < expectedHeaderCount; i++) {
      bytesIn.writeByte(0x82 + i); // Copy static header 1 to the header table as index 1.
      bytesIn.writeByte(0x81); // Remove index 1 from the reference set.
    }

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    assertEquals(expectedHeaderCount, hpackReader.headerCount);
    assertTrue(hpackReader.getAndReset().isEmpty());

    bytesIn.writeByte(0x81); // Add index 1 back to the reference set.

    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    assertEquals(expectedHeaderCount, hpackReader.headerCount);
    assertHeaderReferenced(headerTableLength() - expectedHeaderCount);
    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndReset());
  }

  /**
   * https://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-D.2.4
   */
  @Test public void readIndexedHeaderFieldFromStaticTableWithoutBuffering() throws IOException {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET

    hpackReader.maxHeaderTableByteCountSetting(0); // SETTINGS_HEADER_TABLE_SIZE == 0
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();

    // Not buffered in header table.
    assertEquals(0, hpackReader.headerCount);

    assertEquals(headerEntries(":method", "GET"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-D.2
   */
  @Test public void readRequestExamplesWithoutHuffman() throws IOException {
    firstRequestWithoutHuffman();
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    checkReadFirstRequestWithoutHuffman();

    secondRequestWithoutHuffman();
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    checkReadSecondRequestWithoutHuffman();

    thirdRequestWithoutHuffman();
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    checkReadThirdRequestWithoutHuffman();
  }

  private void firstRequestWithoutHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x87); // == Indexed - Add ==
                             // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x86); // == Indexed - Add ==
                             // idx = 6 -> :path: /
    bytesIn.writeByte(0x44); // == Literal indexed ==
                             // Indexed name (idx = 4) -> :authority
    bytesIn.writeByte(0x0f); // Literal value (len = 15)
    bytesIn.writeUtf8("www.example.com");
  }

  private void checkReadFirstRequestWithoutHuffman() {
    assertEquals(4, hpackReader.headerCount);

    // [  1] (s =  57) :authority: www.example.com
    Header entry = hpackReader.headerTable[headerTableLength() - 4];
    checkEntry(entry, ":authority", "www.example.com", 57);
    assertHeaderReferenced(headerTableLength() - 4);

    // [  2] (s =  38) :path: /
    entry = hpackReader.headerTable[headerTableLength() - 3];
    checkEntry(entry, ":path", "/", 38);
    assertHeaderReferenced(headerTableLength() - 3);

    // [  3] (s =  43) :scheme: http
    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, ":scheme", "http", 43);
    assertHeaderReferenced(headerTableLength() - 2);

    // [  4] (s =  42) :method: GET
    entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    // Table size: 180
    assertEquals(180, hpackReader.headerTableByteCount);

    // Decoded header set:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), hpackReader.getAndReset());
  }

  private void secondRequestWithoutHuffman() {
    bytesIn.writeByte(0x5c); // == Literal indexed ==
                             // Indexed name (idx = 28) -> cache-control
    bytesIn.writeByte(0x08); // Literal value (len = 8)
    bytesIn.writeUtf8("no-cache");
  }

  private void checkReadSecondRequestWithoutHuffman() {
    assertEquals(5, hpackReader.headerCount);

    // [  1] (s =  53) cache-control: no-cache
    Header entry = hpackReader.headerTable[headerTableLength() - 5];
    checkEntry(entry, "cache-control", "no-cache", 53);
    assertHeaderReferenced(headerTableLength() - 5);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable[headerTableLength() - 4];
    checkEntry(entry, ":authority", "www.example.com", 57);
    assertHeaderReferenced(headerTableLength() - 4);

    // [  3] (s =  38) :path: /
    entry = hpackReader.headerTable[headerTableLength() - 3];
    checkEntry(entry, ":path", "/", 38);
    assertHeaderReferenced(headerTableLength() - 3);

    // [  4] (s =  43) :scheme: http
    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, ":scheme", "http", 43);
    assertHeaderReferenced(headerTableLength() - 2);

    // [  5] (s =  42) :method: GET
    entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    // Table size: 233
    assertEquals(233, hpackReader.headerTableByteCount);

    // Decoded header set:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), hpackReader.getAndReset());
  }

  private void thirdRequestWithoutHuffman() {
    bytesIn.writeByte(0x30); // == Empty reference set ==
    bytesIn.writeByte(0x85); // == Indexed - Add ==
                             // idx = 5 -> :method: GET
    bytesIn.writeByte(0x8c); // == Indexed - Add ==
                             // idx = 12 -> :scheme: https
    bytesIn.writeByte(0x8b); // == Indexed - Add ==
                             // idx = 11 -> :path: /index.html
    bytesIn.writeByte(0x84); // == Indexed - Add ==
                             // idx = 4 -> :authority: www.example.com
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x0a); // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key");
    bytesIn.writeByte(0x0c); // Literal value (len = 12)
    bytesIn.writeUtf8("custom-value");
  }

  private void checkReadThirdRequestWithoutHuffman() {
    assertEquals(8, hpackReader.headerCount);

    // [  1] (s =  54) custom-key: custom-value
    Header entry = hpackReader.headerTable[headerTableLength() - 8];
    checkEntry(entry, "custom-key", "custom-value", 54);
    assertHeaderReferenced(headerTableLength() - 8);

    // [  2] (s =  48) :path: /index.html
    entry = hpackReader.headerTable[headerTableLength() - 7];
    checkEntry(entry, ":path", "/index.html", 48);
    assertHeaderReferenced(headerTableLength() - 7);

    // [  3] (s =  44) :scheme: https
    entry = hpackReader.headerTable[headerTableLength() - 6];
    checkEntry(entry, ":scheme", "https", 44);
    assertHeaderReferenced(headerTableLength() - 6);

    // [  4] (s =  53) cache-control: no-cache
    entry = hpackReader.headerTable[headerTableLength() - 5];
    checkEntry(entry, "cache-control", "no-cache", 53);
    assertHeaderNotReferenced(headerTableLength() - 5);

    // [  5] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable[headerTableLength() - 4];
    checkEntry(entry, ":authority", "www.example.com", 57);
    assertHeaderReferenced(headerTableLength() - 4);

    // [  6] (s =  38) :path: /
    entry = hpackReader.headerTable[headerTableLength() - 3];
    checkEntry(entry, ":path", "/", 38);
    assertHeaderNotReferenced(headerTableLength() - 3);

    // [  7] (s =  43) :scheme: http
    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, ":scheme", "http", 43);
    assertHeaderNotReferenced(headerTableLength() - 2);

    // [  8] (s =  42) :method: GET
    entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    // Table size: 379
    assertEquals(379, hpackReader.headerTableByteCount);

    // Decoded header set:
    // TODO: order is not correct per docs, but then again, the spec doesn't require ordering.
    assertEquals(headerEntries(
        ":method", "GET",
        ":authority", "www.example.com",
        ":scheme", "https",
        ":path", "/index.html",
        "custom-key", "custom-value"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-D.3
   */
  @Test public void readRequestExamplesWithHuffman() throws IOException {
    firstRequestWithHuffman();
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    checkReadFirstRequestWithHuffman();

    secondRequestWithHuffman();
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    checkReadSecondRequestWithHuffman();

    thirdRequestWithHuffman();
    hpackReader.readHeaders();
    hpackReader.emitReferenceSet();
    checkReadThirdRequestWithHuffman();
  }

  private void firstRequestWithHuffman() {
    bytesIn.writeByte(0x82); // == Indexed - Add ==
                             // idx = 2 -> :method: GET
    bytesIn.writeByte(0x87); // == Indexed - Add ==
                             // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x86); // == Indexed - Add ==
                             // idx = 6 -> :path: /
    bytesIn.writeByte(0x44); // == Literal indexed ==
                             // Indexed name (idx = 4) -> :authority
    bytesIn.writeByte(0x8c); // Literal value Huffman encoded 12 bytes
                             // decodes to www.example.com which is length 15
    bytesIn.write(decodeHex("e7cf9bebe89b6fb16fa9b6ff"));
  }

  private void checkReadFirstRequestWithHuffman() {
    assertEquals(4, hpackReader.headerCount);

    // [  1] (s =  57) :authority: www.example.com
    Header entry = hpackReader.headerTable[headerTableLength() - 4];
    checkEntry(entry, ":authority", "www.example.com", 57);
    assertHeaderReferenced(headerTableLength() - 4);

    // [  2] (s =  38) :path: /
    entry = hpackReader.headerTable[headerTableLength() - 3];
    checkEntry(entry, ":path", "/", 38);
    assertHeaderReferenced(headerTableLength() - 3);

    // [  3] (s =  43) :scheme: http
    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, ":scheme", "http", 43);
    assertHeaderReferenced(headerTableLength() - 2);

    // [  4] (s =  42) :method: GET
    entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    // Table size: 180
    assertEquals(180, hpackReader.headerTableByteCount);

    // Decoded header set:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), hpackReader.getAndReset());
  }

  private void secondRequestWithHuffman() {
    bytesIn.writeByte(0x5c); // == Literal indexed ==
                             // Indexed name (idx = 28) -> cache-control
    bytesIn.writeByte(0x86); // Literal value Huffman encoded 6 bytes
                             // decodes to no-cache which is length 8
    bytesIn.write(decodeHex("b9b9949556bf"));
  }

  private void checkReadSecondRequestWithHuffman() {
    assertEquals(5, hpackReader.headerCount);

    // [  1] (s =  53) cache-control: no-cache
    Header entry = hpackReader.headerTable[headerTableLength() - 5];
    checkEntry(entry, "cache-control", "no-cache", 53);
    assertHeaderReferenced(headerTableLength() - 5);

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable[headerTableLength() - 4];
    checkEntry(entry, ":authority", "www.example.com", 57);
    assertHeaderReferenced(headerTableLength() - 4);

    // [  3] (s =  38) :path: /
    entry = hpackReader.headerTable[headerTableLength() - 3];
    checkEntry(entry, ":path", "/", 38);
    assertHeaderReferenced(headerTableLength() - 3);

    // [  4] (s =  43) :scheme: http
    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, ":scheme", "http", 43);
    assertHeaderReferenced(headerTableLength() - 2);

    // [  5] (s =  42) :method: GET
    entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    // Table size: 233
    assertEquals(233, hpackReader.headerTableByteCount);

    // Decoded header set:
    assertEquals(headerEntries(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), hpackReader.getAndReset());
  }

  private void thirdRequestWithHuffman() {
    bytesIn.writeByte(0x30); // == Empty reference set ==
    bytesIn.writeByte(0x85); // == Indexed - Add ==
                             // idx = 5 -> :method: GET
    bytesIn.writeByte(0x8c); // == Indexed - Add ==
                             // idx = 12 -> :scheme: https
    bytesIn.writeByte(0x8b); // == Indexed - Add ==
                             // idx = 11 -> :path: /index.html
    bytesIn.writeByte(0x84); // == Indexed - Add ==
                             // idx = 4 -> :authority: www.example.com
    bytesIn.writeByte(0x40); // Literal indexed
    bytesIn.writeByte(0x88); // Literal name Huffman encoded 8 bytes
                             // decodes to custom-key which is length 10
    bytesIn.write(decodeHex("571c5cdb737b2faf"));
    bytesIn.writeByte(0x89); // Literal value Huffman encoded 6 bytes
                             // decodes to custom-value which is length 12
    bytesIn.write(decodeHex("571c5cdb73724d9c57"));
  }

  private void checkReadThirdRequestWithHuffman() {
    assertEquals(8, hpackReader.headerCount);

    // [  1] (s =  54) custom-key: custom-value
    Header entry = hpackReader.headerTable[headerTableLength() - 8];
    checkEntry(entry, "custom-key", "custom-value", 54);
    assertHeaderReferenced(headerTableLength() - 8);

    // [  2] (s =  48) :path: /index.html
    entry = hpackReader.headerTable[headerTableLength() - 7];
    checkEntry(entry, ":path", "/index.html", 48);
    assertHeaderReferenced(headerTableLength() - 7);

    // [  3] (s =  44) :scheme: https
    entry = hpackReader.headerTable[headerTableLength() - 6];
    checkEntry(entry, ":scheme", "https", 44);
    assertHeaderReferenced(headerTableLength() - 6);

    // [  4] (s =  53) cache-control: no-cache
    entry = hpackReader.headerTable[headerTableLength() - 5];
    checkEntry(entry, "cache-control", "no-cache", 53);
    assertHeaderNotReferenced(headerTableLength() - 5);

    // [  5] (s =  57) :authority: www.example.com
    entry = hpackReader.headerTable[headerTableLength() - 4];
    checkEntry(entry, ":authority", "www.example.com", 57);
    assertHeaderReferenced(headerTableLength() - 4);

    // [  6] (s =  38) :path: /
    entry = hpackReader.headerTable[headerTableLength() - 3];
    checkEntry(entry, ":path", "/", 38);
    assertHeaderNotReferenced(headerTableLength() - 3);

    // [  7] (s =  43) :scheme: http
    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, ":scheme", "http", 43);
    assertHeaderNotReferenced(headerTableLength() - 2);

    // [  8] (s =  42) :method: GET
    entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    // Table size: 379
    assertEquals(379, hpackReader.headerTableByteCount);

    // Decoded header set:
    // TODO: order is not correct per docs, but then again, the spec doesn't require ordering.
    assertEquals(headerEntries(
        ":method", "GET",
        ":authority", "www.example.com",
        ":scheme", "https",
        ":path", "/index.html",
        "custom-key", "custom-value"), hpackReader.getAndReset());
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

  private HpackDraft07.Reader newReader(Buffer source) {
    return new HpackDraft07.Reader(4096, source);
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

  private void assertHeaderReferenced(int index) {
    assertTrue(hpackReader.referencedHeaders.get(index));
  }

  private void assertHeaderNotReferenced(int index) {
    assertFalse(hpackReader.referencedHeaders.get(index));
  }

  private int headerTableLength() {
    return hpackReader.headerTable.length;
  }
}
