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
import static com.squareup.okhttp.internal.spdy.HpackDraft05.bitPositionSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HpackDraft05Test {

  private final MutableByteArrayInputStream bytesIn = new MutableByteArrayInputStream();
  private HpackDraft05.Reader hpackReader;

  @Before public void resetReader() {
    hpackReader = new HpackDraft05.Reader(false, new DataInputStream(bytesIn));
  }

  /**
   * HPACK has a max header table size, which can be smaller than the max header message.
   * Ensure the larger header content is not lost.
   */
  @Test public void tooLargeToHPackIsStillEmitted() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x00); // Literal indexed
    out.write(0x0a); // Literal name (len = 10)
    out.write("custom-key".getBytes(), 0, 10);

    out.write(0x0d); // Literal value (len = 13)
    out.write("custom-header".getBytes(), 0, 13);

    bytesIn.set(out.toByteArray());
    hpackReader.maxHeaderTableByteCount = 1;
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(byteStringList("custom-key", "custom-header"), hpackReader.getAndReset());
  }

  /** Oldest entries are evicted to support newer ones. */
  @Test public void testEviction() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x00); // Literal indexed
    out.write(0x0a); // Literal name (len = 10)
    out.write("custom-foo".getBytes(), 0, 10);

    out.write(0x0d); // Literal value (len = 13)
    out.write("custom-header".getBytes(), 0, 13);

    out.write(0x00); // Literal indexed
    out.write(0x0a); // Literal name (len = 10)
    out.write("custom-bar".getBytes(), 0, 10);

    out.write(0x0d); // Literal value (len = 13)
    out.write("custom-header".getBytes(), 0, 13);

    out.write(0x00); // Literal indexed
    out.write(0x0a); // Literal name (len = 10)
    out.write("custom-baz".getBytes(), 0, 10);

    out.write(0x0d); // Literal value (len = 13)
    out.write("custom-header".getBytes(), 0, 13);

    bytesIn.set(out.toByteArray());
    hpackReader.maxHeaderTableByteCount = 110;
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(2, hpackReader.headerCount);

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, "custom-bar", "custom-header", 55);
    assertHeaderReferenced(headerTableLength() - 1);

    entry = hpackReader.headerTable[headerTableLength() - 2];
    checkEntry(entry, "custom-baz", "custom-header", 55);
    assertHeaderReferenced(headerTableLength() - 2);

    // foo isn't here as it is no longer in the table.
    // TODO: emit before eviction?
    assertEquals(byteStringList("custom-bar", "custom-header", "custom-baz", "custom-header"),
        hpackReader.getAndReset());
  }

  /** Header table backing array is initially 8 long, let's ensure it grows. */
  @Test public void dynamicallyGrowsUpTo64Entries() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    for (int i = 0; i < 64; i++) {
      out.write(0x00); // Literal indexed
      out.write(0x0a); // Literal name (len = 10)
      out.write("custom-foo".getBytes(), 0, 10);

      out.write(0x0d); // Literal value (len = 13)
      out.write("custom-header".getBytes(), 0, 13);
    }

    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(64, hpackReader.headerCount);
  }

  @Test public void greaterThan64HeadersNotYetSupported() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    for (int i = 0; i < 65; i++) {
      out.write(0x00); // Literal indexed
      out.write(0x0a); // Literal name (len = 10)
      out.write("custom-foo".getBytes(), 0, 10);

      out.write(0x0d); // Literal value (len = 13)
      out.write("custom-header".getBytes(), 0, 13);
    }

    bytesIn.set(out.toByteArray());
    try {
      hpackReader.readHeaders(out.size());
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  /** Huffman headers are accepted, but come out as garbage for now. */
  @Test public void huffmanDecodingNotYetSupported() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x04); // == Literal indexed ==
                     // Indexed name (idx = 4) -> :path
    out.write(0x8b); // Literal value Huffman encoded 11 bytes
                     // decodes to www.example.com which is length 15
    byte[] huffmanBytes = new byte[] {
        (byte) 0xdb, (byte) 0x6d, (byte) 0x88, (byte) 0x3e,
        (byte) 0x68, (byte) 0xd1, (byte) 0xcb, (byte) 0x12,
        (byte) 0x25, (byte) 0xba, (byte) 0x7f};
    out.write(huffmanBytes, 0, huffmanBytes.length);

    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(52, hpackReader.headerTableByteCount);

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":path", "www.example.com", 52);
    assertHeaderReferenced(headerTableLength() - 1);
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
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(55, hpackReader.headerTableByteCount);

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, "custom-key", "custom-header", 55);
    assertHeaderReferenced(headerTableLength() - 1);

    assertEquals(byteStringList("custom-key", "custom-header"), hpackReader.getAndReset());
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
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(0, hpackReader.headerCount);

    assertEquals(byteStringList(":path", "/sample/path"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.3
   */
  @Test public void decodeIndexedHeaderField() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET

    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    assertEquals(1, hpackReader.headerCount);
    assertEquals(42, hpackReader.headerTableByteCount);

    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 1];
    checkEntry(entry, ":method", "GET", 42);
    assertHeaderReferenced(headerTableLength() - 1);

    assertEquals(byteStringList(":method", "GET"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.1.4
   */
  @Test public void decodeIndexedHeaderFieldFromStaticTableWithoutBuffering() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET

    bytesIn.set(out.toByteArray());
    hpackReader.maxHeaderTableByteCount = 0; // SETTINGS_HEADER_TABLE_SIZE == 0
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();

    // Not buffered in header table.
    assertEquals(0, hpackReader.headerCount);

    assertEquals(byteStringList(":method", "GET"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.2
   */
  @Test public void decodeRequestExamplesWithoutHuffman() throws IOException {
    ByteArrayOutputStream out = firstRequestWithoutHuffman();
    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();
    checkFirstRequestWithoutHuffman();

    out = secondRequestWithoutHuffman();
    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();
    checkSecondRequestWithoutHuffman();

    out = thirdRequestWithoutHuffman();
    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();
    checkThirdRequestWithoutHuffman();
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

  private void checkFirstRequestWithoutHuffman() {
    assertEquals(4, hpackReader.headerCount);

    // [  1] (s =  57) :authority: www.example.com
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 4];
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
    assertEquals(byteStringList(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), hpackReader.getAndReset());
  }

  private ByteArrayOutputStream secondRequestWithoutHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x1b); // == Literal indexed ==
                     // Indexed name (idx = 27) -> cache-control
    out.write(0x08); // Literal value (len = 8)
    out.write("no-cache".getBytes(), 0, 8);

    return out;
  }

  private void checkSecondRequestWithoutHuffman() {
    assertEquals(5, hpackReader.headerCount);

    // [  1] (s =  53) cache-control: no-cache
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 5];
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
    assertEquals(byteStringList(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), hpackReader.getAndReset());
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

  private void checkThirdRequestWithoutHuffman() {
    assertEquals(8, hpackReader.headerCount);

    // [  1] (s =  54) custom-key: custom-value
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 8];
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
    assertEquals(byteStringList(
        ":method", "GET",
        ":authority", "www.example.com",
        ":scheme", "https",
        ":path", "/index.html",
        "custom-key", "custom-value"), hpackReader.getAndReset());
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-E.3
   */
  @Test public void decodeRequestExamplesWithHuffman() throws IOException {
    ByteArrayOutputStream out = firstRequestWithHuffman();
    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();
    checkFirstRequestWithHuffman();

    out = secondRequestWithHuffman();
    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();
    checkSecondRequestWithHuffman();

    out = thirdRequestWithHuffman();
    bytesIn.set(out.toByteArray());
    hpackReader.readHeaders(out.size());
    hpackReader.emitReferenceSet();
    checkThirdRequestWithHuffman();
  }

  private ByteArrayOutputStream firstRequestWithHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x82); // == Indexed - Add ==
                     // idx = 2 -> :method: GET
    out.write(0x87); // == Indexed - Add ==
                     // idx = 7 -> :scheme: http
    out.write(0x86); // == Indexed - Add ==
                     // idx = 6 -> :path: /
    out.write(0x04); // == Literal indexed ==
                     // Indexed name (idx = 4) -> :authority
    out.write(0x8b); // Literal value Huffman encoded 11 bytes
                     // decodes to www.example.com which is length 15
    byte[] huffmanBytes = new byte[] {
        (byte) 0xdb, (byte) 0x6d, (byte) 0x88, (byte) 0x3e,
        (byte) 0x68, (byte) 0xd1, (byte) 0xcb, (byte) 0x12,
        (byte) 0x25, (byte) 0xba, (byte) 0x7f};
    out.write(huffmanBytes, 0, huffmanBytes.length);

    return out;
  }

  private void checkFirstRequestWithHuffman() {
    assertEquals(4, hpackReader.headerCount);

    // [  1] (s =  57) :authority: www.example.com
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 4];
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
    assertEquals(byteStringList(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com"), hpackReader.getAndReset());
  }

  private ByteArrayOutputStream secondRequestWithHuffman() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    out.write(0x1b); // == Literal indexed ==
                     // Indexed name (idx = 27) -> cache-control
    out.write(0x86); // Literal value Huffman encoded 6 bytes
                     // decodes to no-cache which is length 8
    byte[] huffmanBytes = new byte[] {
        (byte) 0x63, (byte) 0x65, (byte) 0x4a, (byte) 0x13,
        (byte) 0x98, (byte) 0xff};
    out.write(huffmanBytes, 0, huffmanBytes.length);

    return out;
  }

  private void checkSecondRequestWithHuffman() {
    assertEquals(5, hpackReader.headerCount);

    // [  1] (s =  53) cache-control: no-cache
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 5];
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
    assertEquals(byteStringList(
        ":method", "GET",
        ":scheme", "http",
        ":path", "/",
        ":authority", "www.example.com",
        "cache-control", "no-cache"), hpackReader.getAndReset());
  }

  private ByteArrayOutputStream thirdRequestWithHuffman() {
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
    out.write(0x88); // Literal name Huffman encoded 8 bytes
                     // decodes to custom-key which is length 10
    byte[] huffmanBytes = new byte[] {
        (byte) 0x4e, (byte) 0xb0, (byte) 0x8b, (byte) 0x74,
        (byte) 0x97, (byte) 0x90, (byte) 0xfa, (byte) 0x7f};
    out.write(huffmanBytes, 0, huffmanBytes.length);
    out.write(0x89); // Literal value Huffman encoded 6 bytes
                     // decodes to custom-value which is length 12
    huffmanBytes = new byte[] {
        (byte) 0x4e, (byte) 0xb0, (byte) 0x8b, (byte) 0x74,
        (byte) 0x97, (byte) 0x9a, (byte) 0x17, (byte) 0xa8,
        (byte) 0xff};
    out.write(huffmanBytes, 0, huffmanBytes.length);

    return out;
  }

  private void checkThirdRequestWithHuffman() {
    assertEquals(8, hpackReader.headerCount);

    // [  1] (s =  54) custom-key: custom-value
    HpackDraft05.HeaderEntry entry = hpackReader.headerTable[headerTableLength() - 8];
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
    assertEquals(byteStringList(
        ":method", "GET",
        ":authority", "www.example.com",
        ":scheme", "https",
        ":path", "/index.html",
        "custom-key", "custom-value"), hpackReader.getAndReset());
  }

  private ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
  private final HpackDraft05.Writer hpackWriter =
      new HpackDraft05.Writer(new DataOutputStream(bytesOut));

  @Test public void readSingleByteInt() throws IOException {
    assertEquals(10, new HpackDraft05.Reader(false, byteStream()).readInt(10, 31));
    assertEquals(10, new HpackDraft05.Reader(false, byteStream()).readInt(0xe0 | 10, 31));
  }

  @Test public void readMultibyteInt() throws IOException {
    assertEquals(1337, new HpackDraft05.Reader(false, byteStream(154, 10)).readInt(31, 31));
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
        new HpackDraft05.Reader(false, byteStream(224, 255, 255, 255, 7)).readInt(31, 31));
  }

  @Test public void prefixMask() throws IOException {
    hpackWriter.writeInt(31, 31, 0);
    assertBytes(31, 0);
    assertEquals(31, new HpackDraft05.Reader(false, byteStream(0)).readInt(31, 31));
  }

  @Test public void prefixMaskMinusOne() throws IOException {
    hpackWriter.writeInt(30, 31, 0);
    assertBytes(30);
    assertEquals(31, new HpackDraft05.Reader(false, byteStream(0)).readInt(31, 31));
  }

  @Test public void zero() throws IOException {
    hpackWriter.writeInt(0, 31, 0);
    assertBytes(0);
    assertEquals(0, new HpackDraft05.Reader(false, byteStream()).readInt(0, 31));
  }

  @Test public void headerName() throws IOException {
    hpackWriter.writeByteString(ByteString.encodeUtf8("foo"));
    assertBytes(3, 'f', 'o', 'o');
    assertEquals("foo", new HpackDraft05.Reader(false, byteStream(3, 'f', 'o', 'o')).readString().utf8());
  }

  @Test public void emptyHeaderName() throws IOException {
    hpackWriter.writeByteString(ByteString.encodeUtf8(""));
    assertBytes(0);
    assertEquals("", new HpackDraft05.Reader(false, byteStream(0)).readString().utf8());
  }

  @Test public void headersRoundTrip() throws IOException {
    List<ByteString> sentHeaders = byteStringList("name", "value");
    hpackWriter.writeHeaders(sentHeaders);
    ByteArrayInputStream bytesIn = new ByteArrayInputStream(bytesOut.toByteArray());
    HpackDraft05.Reader reader = new HpackDraft05.Reader(false, new DataInputStream(bytesIn));
    reader.readHeaders(bytesOut.size());
    reader.emitReferenceSet();
    List<ByteString> receivedHeaders = reader.getAndReset();
    assertEquals(sentHeaders, receivedHeaders);
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

  private void checkEntry(HpackDraft05.HeaderEntry entry, String name, String value, int size) {
    assertEquals(name, entry.name.utf8());
    assertEquals(value, entry.value.utf8());
    assertEquals(size, entry.size);
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

  private void assertHeaderReferenced(int index) {
    assertTrue(bitPositionSet(hpackReader.referencedHeaders, index));
  }

  private void assertHeaderNotReferenced(int index) {
    assertFalse(bitPositionSet(hpackReader.referencedHeaders, index));
  }

  private int headerTableLength() {
    return hpackReader.headerTable.length;
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
