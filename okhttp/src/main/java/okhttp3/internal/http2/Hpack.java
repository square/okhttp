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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;

/**
 * Read and write HPACK v10.
 *
 * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12
 *
 * This implementation uses an array for the dynamic table and a list for indexed entries.  Dynamic
 * entries are added to the array, starting in the last position moving forward.  When the array
 * fills, it is doubled.
 */
final class Hpack {
  private static final int PREFIX_4_BITS = 0x0f;
  private static final int PREFIX_5_BITS = 0x1f;
  private static final int PREFIX_6_BITS = 0x3f;
  private static final int PREFIX_7_BITS = 0x7f;

  private static final Header[] STATIC_HEADER_TABLE = new Header[] {
      new Header(Header.TARGET_AUTHORITY, ""),
      new Header(Header.TARGET_METHOD, "GET"),
      new Header(Header.TARGET_METHOD, "POST"),
      new Header(Header.TARGET_PATH, "/"),
      new Header(Header.TARGET_PATH, "/index.html"),
      new Header(Header.TARGET_SCHEME, "http"),
      new Header(Header.TARGET_SCHEME, "https"),
      new Header(Header.RESPONSE_STATUS, "200"),
      new Header(Header.RESPONSE_STATUS, "204"),
      new Header(Header.RESPONSE_STATUS, "206"),
      new Header(Header.RESPONSE_STATUS, "304"),
      new Header(Header.RESPONSE_STATUS, "400"),
      new Header(Header.RESPONSE_STATUS, "404"),
      new Header(Header.RESPONSE_STATUS, "500"),
      new Header("accept-charset", ""),
      new Header("accept-encoding", "gzip, deflate"),
      new Header("accept-language", ""),
      new Header("accept-ranges", ""),
      new Header("accept", ""),
      new Header("access-control-allow-origin", ""),
      new Header("age", ""),
      new Header("allow", ""),
      new Header("authorization", ""),
      new Header("cache-control", ""),
      new Header("content-disposition", ""),
      new Header("content-encoding", ""),
      new Header("content-language", ""),
      new Header("content-length", ""),
      new Header("content-location", ""),
      new Header("content-range", ""),
      new Header("content-type", ""),
      new Header("cookie", ""),
      new Header("date", ""),
      new Header("etag", ""),
      new Header("expect", ""),
      new Header("expires", ""),
      new Header("from", ""),
      new Header("host", ""),
      new Header("if-match", ""),
      new Header("if-modified-since", ""),
      new Header("if-none-match", ""),
      new Header("if-range", ""),
      new Header("if-unmodified-since", ""),
      new Header("last-modified", ""),
      new Header("link", ""),
      new Header("location", ""),
      new Header("max-forwards", ""),
      new Header("proxy-authenticate", ""),
      new Header("proxy-authorization", ""),
      new Header("range", ""),
      new Header("referer", ""),
      new Header("refresh", ""),
      new Header("retry-after", ""),
      new Header("server", ""),
      new Header("set-cookie", ""),
      new Header("strict-transport-security", ""),
      new Header("transfer-encoding", ""),
      new Header("user-agent", ""),
      new Header("vary", ""),
      new Header("via", ""),
      new Header("www-authenticate", "")
  };

  private Hpack() {
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-3.1
  static final class Reader {

    private final List<Header> headerList = new ArrayList<>();
    private final BufferedSource source;

    private final int headerTableSizeSetting;
    private int maxDynamicTableByteCount;

    // Visible for testing.
    Header[] dynamicTable = new Header[8];
    // Array is populated back to front, so new entries always have lowest index.
    int nextHeaderIndex = dynamicTable.length - 1;
    int headerCount = 0;
    int dynamicTableByteCount = 0;

    Reader(int headerTableSizeSetting, Source source) {
      this(headerTableSizeSetting, headerTableSizeSetting, source);
    }

    Reader(int headerTableSizeSetting, int maxDynamicTableByteCount, Source source) {
      this.headerTableSizeSetting = headerTableSizeSetting;
      this.maxDynamicTableByteCount = maxDynamicTableByteCount;
      this.source = Okio.buffer(source);
    }

    int maxDynamicTableByteCount() {
      return maxDynamicTableByteCount;
    }

    private void adjustDynamicTableByteCount() {
      if (maxDynamicTableByteCount < dynamicTableByteCount) {
        if (maxDynamicTableByteCount == 0) {
          clearDynamicTable();
        } else {
          evictToRecoverBytes(dynamicTableByteCount - maxDynamicTableByteCount);
        }
      }
    }

    private void clearDynamicTable() {
      Arrays.fill(dynamicTable, null);
      nextHeaderIndex = dynamicTable.length - 1;
      headerCount = 0;
      dynamicTableByteCount = 0;
    }

    /** Returns the count of entries evicted. */
    private int evictToRecoverBytes(int bytesToRecover) {
      int entriesToEvict = 0;
      if (bytesToRecover > 0) {
        // determine how many headers need to be evicted.
        for (int j = dynamicTable.length - 1; j >= nextHeaderIndex && bytesToRecover > 0; j--) {
          bytesToRecover -= dynamicTable[j].hpackSize;
          dynamicTableByteCount -= dynamicTable[j].hpackSize;
          headerCount--;
          entriesToEvict++;
        }
        System.arraycopy(dynamicTable, nextHeaderIndex + 1, dynamicTable,
            nextHeaderIndex + 1 + entriesToEvict, headerCount);
        nextHeaderIndex += entriesToEvict;
      }
      return entriesToEvict;
    }

    /**
     * Read {@code byteCount} bytes of headers from the source stream. This implementation does not
     * propagate the never indexed flag of a header.
     */
    void readHeaders() throws IOException {
      while (!source.exhausted()) {
        int b = source.readByte() & 0xff;
        if (b == 0x80) { // 10000000
          throw new IOException("index == 0");
        } else if ((b & 0x80) == 0x80) { // 1NNNNNNN
          int index = readInt(b, PREFIX_7_BITS);
          readIndexedHeader(index - 1);
        } else if (b == 0x40) { // 01000000
          readLiteralHeaderWithIncrementalIndexingNewName();
        } else if ((b & 0x40) == 0x40) {  // 01NNNNNN
          int index = readInt(b, PREFIX_6_BITS);
          readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1);
        } else if ((b & 0x20) == 0x20) {  // 001NNNNN
          maxDynamicTableByteCount = readInt(b, PREFIX_5_BITS);
          if (maxDynamicTableByteCount < 0
              || maxDynamicTableByteCount > headerTableSizeSetting) {
            throw new IOException("Invalid dynamic table size update " + maxDynamicTableByteCount);
          }
          adjustDynamicTableByteCount();
        } else if (b == 0x10 || b == 0) { // 000?0000 - Ignore never indexed bit.
          readLiteralHeaderWithoutIndexingNewName();
        } else { // 000?NNNN - Ignore never indexed bit.
          int index = readInt(b, PREFIX_4_BITS);
          readLiteralHeaderWithoutIndexingIndexedName(index - 1);
        }
      }
    }

    public List<Header> getAndResetHeaderList() {
      List<Header> result = new ArrayList<>(headerList);
      headerList.clear();
      return result;
    }

    private void readIndexedHeader(int index) throws IOException {
      if (isStaticHeader(index)) {
        Header staticEntry = STATIC_HEADER_TABLE[index];
        headerList.add(staticEntry);
      } else {
        int dynamicTableIndex = dynamicTableIndex(index - STATIC_HEADER_TABLE.length);
        if (dynamicTableIndex < 0 || dynamicTableIndex > dynamicTable.length - 1) {
          throw new IOException("Header index too large " + (index + 1));
        }
        headerList.add(dynamicTable[dynamicTableIndex]);
      }
    }

    // referencedHeaders is relative to nextHeaderIndex + 1.
    private int dynamicTableIndex(int index) {
      return nextHeaderIndex + 1 + index;
    }

    private void readLiteralHeaderWithoutIndexingIndexedName(int index) throws IOException {
      ByteString name = getName(index);
      ByteString value = readByteString();
      headerList.add(new Header(name, value));
    }

    private void readLiteralHeaderWithoutIndexingNewName() throws IOException {
      ByteString name = checkLowercase(readByteString());
      ByteString value = readByteString();
      headerList.add(new Header(name, value));
    }

    private void readLiteralHeaderWithIncrementalIndexingIndexedName(int nameIndex)
        throws IOException {
      ByteString name = getName(nameIndex);
      ByteString value = readByteString();
      insertIntoDynamicTable(-1, new Header(name, value));
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName() throws IOException {
      ByteString name = checkLowercase(readByteString());
      ByteString value = readByteString();
      insertIntoDynamicTable(-1, new Header(name, value));
    }

    private ByteString getName(int index) {
      if (isStaticHeader(index)) {
        return STATIC_HEADER_TABLE[index].name;
      } else {
        return dynamicTable[dynamicTableIndex(index - STATIC_HEADER_TABLE.length)].name;
      }
    }

    private boolean isStaticHeader(int index) {
      return index >= 0 && index <= STATIC_HEADER_TABLE.length - 1;
    }

    /** index == -1 when new. */
    private void insertIntoDynamicTable(int index, Header entry) {
      headerList.add(entry);

      int delta = entry.hpackSize;
      if (index != -1) { // Index -1 == new header.
        delta -= dynamicTable[dynamicTableIndex(index)].hpackSize;
      }

      // if the new or replacement header is too big, drop all entries.
      if (delta > maxDynamicTableByteCount) {
        clearDynamicTable();
        return;
      }

      // Evict headers to the required length.
      int bytesToRecover = (dynamicTableByteCount + delta) - maxDynamicTableByteCount;
      int entriesEvicted = evictToRecoverBytes(bytesToRecover);

      if (index == -1) { // Adding a value to the dynamic table.
        if (headerCount + 1 > dynamicTable.length) { // Need to grow the dynamic table.
          Header[] doubled = new Header[dynamicTable.length * 2];
          System.arraycopy(dynamicTable, 0, doubled, dynamicTable.length, dynamicTable.length);
          nextHeaderIndex = dynamicTable.length - 1;
          dynamicTable = doubled;
        }
        index = nextHeaderIndex--;
        dynamicTable[index] = entry;
        headerCount++;
      } else { // Replace value at same position.
        index += dynamicTableIndex(index) + entriesEvicted;
        dynamicTable[index] = entry;
      }
      dynamicTableByteCount += delta;
    }

    private int readByte() throws IOException {
      return source.readByte() & 0xff;
    }

    int readInt(int firstByte, int prefixMask) throws IOException {
      int prefix = firstByte & prefixMask;
      if (prefix < prefixMask) {
        return prefix; // This was a single byte value.
      }

      // This is a multibyte value. Read 7 bits at a time.
      int result = prefixMask;
      int shift = 0;
      while (true) {
        int b = readByte();
        if ((b & 0x80) != 0) { // Equivalent to (b >= 128) since b is in [0..255].
          result += (b & 0x7f) << shift;
          shift += 7;
        } else {
          result += b << shift; // Last byte.
          break;
        }
      }
      return result;
    }

    /** Reads a potentially Huffman encoded byte string. */
    ByteString readByteString() throws IOException {
      int firstByte = readByte();
      boolean huffmanDecode = (firstByte & 0x80) == 0x80; // 1NNNNNNN
      int length = readInt(firstByte, PREFIX_7_BITS);

      if (huffmanDecode) {
        return ByteString.of(Huffman.get().decode(source.readByteArray(length)));
      } else {
        return source.readByteString(length);
      }
    }
  }

  private static final Map<ByteString, Integer> NAME_TO_FIRST_INDEX = nameToFirstIndex();

  private static Map<ByteString, Integer> nameToFirstIndex() {
    Map<ByteString, Integer> result = new LinkedHashMap<>(STATIC_HEADER_TABLE.length);
    for (int i = 0; i < STATIC_HEADER_TABLE.length; i++) {
      if (!result.containsKey(STATIC_HEADER_TABLE[i].name)) {
        result.put(STATIC_HEADER_TABLE[i].name, i);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  static final class Writer {
    private static final int SETTINGS_HEADER_TABLE_SIZE = 4096;

    /**
     * The decoder has ultimate control of the maximum size of the dynamic table but we can choose
     * to use less. We'll put a cap at 16K. This is arbitrary but should be enough for most
     * purposes.
     */
    private static final int SETTINGS_HEADER_TABLE_SIZE_LIMIT = 16384;

    private final Buffer out;
    private final boolean useCompression;

    /**
     * In the scenario where the dynamic table size changes multiple times between transmission of
     * header blocks, we need to keep track of the smallest value in that interval.
     */
    private int smallestHeaderTableSizeSetting = Integer.MAX_VALUE;
    private boolean emitDynamicTableSizeUpdate;

    int headerTableSizeSetting;
    int maxDynamicTableByteCount;

    // Visible for testing.
    Header[] dynamicTable = new Header[8];
    // Array is populated back to front, so new entries always have lowest index.
    int nextHeaderIndex = dynamicTable.length - 1;
    int headerCount = 0;
    int dynamicTableByteCount = 0;

    Writer(Buffer out) {
      this(SETTINGS_HEADER_TABLE_SIZE, true, out);
    }

    Writer(int headerTableSizeSetting, boolean useCompression, Buffer out) {
      this.headerTableSizeSetting = headerTableSizeSetting;
      this.maxDynamicTableByteCount = headerTableSizeSetting;
      this.useCompression = useCompression;
      this.out = out;
    }

    private void clearDynamicTable() {
      Arrays.fill(dynamicTable, null);
      nextHeaderIndex = dynamicTable.length - 1;
      headerCount = 0;
      dynamicTableByteCount = 0;
    }

    /** Returns the count of entries evicted. */
    private int evictToRecoverBytes(int bytesToRecover) {
      int entriesToEvict = 0;
      if (bytesToRecover > 0) {
        // determine how many headers need to be evicted.
        for (int j = dynamicTable.length - 1; j >= nextHeaderIndex && bytesToRecover > 0; j--) {
          bytesToRecover -= dynamicTable[j].hpackSize;
          dynamicTableByteCount -= dynamicTable[j].hpackSize;
          headerCount--;
          entriesToEvict++;
        }
        System.arraycopy(dynamicTable, nextHeaderIndex + 1, dynamicTable,
            nextHeaderIndex + 1 + entriesToEvict, headerCount);
        Arrays.fill(dynamicTable, nextHeaderIndex + 1, nextHeaderIndex + 1 + entriesToEvict, null);
        nextHeaderIndex += entriesToEvict;
      }
      return entriesToEvict;
    }

    private void insertIntoDynamicTable(Header entry) {
      int delta = entry.hpackSize;

      // if the new or replacement header is too big, drop all entries.
      if (delta > maxDynamicTableByteCount) {
        clearDynamicTable();
        return;
      }

      // Evict headers to the required length.
      int bytesToRecover = (dynamicTableByteCount + delta) - maxDynamicTableByteCount;
      evictToRecoverBytes(bytesToRecover);

      if (headerCount + 1 > dynamicTable.length) { // Need to grow the dynamic table.
        Header[] doubled = new Header[dynamicTable.length * 2];
        System.arraycopy(dynamicTable, 0, doubled, dynamicTable.length, dynamicTable.length);
        nextHeaderIndex = dynamicTable.length - 1;
        dynamicTable = doubled;
      }
      int index = nextHeaderIndex--;
      dynamicTable[index] = entry;
      headerCount++;
      dynamicTableByteCount += delta;
    }

    /** This does not use "never indexed" semantics for sensitive headers. */
    // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-6.2.3
    void writeHeaders(List<Header> headerBlock) throws IOException {
      if (emitDynamicTableSizeUpdate) {
        if (smallestHeaderTableSizeSetting < maxDynamicTableByteCount) {
          // Multiple dynamic table size updates!
          writeInt(smallestHeaderTableSizeSetting, PREFIX_5_BITS, 0x20);
        }
        emitDynamicTableSizeUpdate = false;
        smallestHeaderTableSizeSetting = Integer.MAX_VALUE;
        writeInt(maxDynamicTableByteCount, PREFIX_5_BITS, 0x20);
      }

      for (int i = 0, size = headerBlock.size(); i < size; i++) {
        Header header = headerBlock.get(i);
        ByteString name = header.name.toAsciiLowercase();
        ByteString value = header.value;
        int headerIndex = -1;
        int headerNameIndex = -1;

        Integer staticIndex = NAME_TO_FIRST_INDEX.get(name);
        if (staticIndex != null) {
          headerNameIndex = staticIndex + 1;
          if (headerNameIndex > 1 && headerNameIndex < 8) {
            // Only search a subset of the static header table. Most entries have an empty value, so
            // it's unnecessary to waste cycles looking at them. This check is built on the
            // observation that the header entries we care about are in adjacent pairs, and we
            // always know the first index of the pair.
            if (Util.equal(STATIC_HEADER_TABLE[headerNameIndex - 1].value, value)) {
              headerIndex = headerNameIndex;
            } else if (Util.equal(STATIC_HEADER_TABLE[headerNameIndex].value, value)) {
              headerIndex = headerNameIndex + 1;
            }
          }
        }

        if (headerIndex == -1) {
          for (int j = nextHeaderIndex + 1, length = dynamicTable.length; j < length; j++) {
            if (Util.equal(dynamicTable[j].name, name)) {
              if (Util.equal(dynamicTable[j].value, value)) {
                headerIndex = j - nextHeaderIndex + STATIC_HEADER_TABLE.length;
                break;
              } else if (headerNameIndex == -1) {
                headerNameIndex = j - nextHeaderIndex + STATIC_HEADER_TABLE.length;
              }
            }
          }
        }

        if (headerIndex != -1) {
          // Indexed Header Field.
          writeInt(headerIndex, PREFIX_7_BITS, 0x80);
        } else if (headerNameIndex == -1) {
          // Literal Header Field with Incremental Indexing - New Name.
          out.writeByte(0x40);
          writeByteString(name);
          writeByteString(value);
          insertIntoDynamicTable(header);
        } else if (name.startsWith(Header.PSEUDO_PREFIX) && !Header.TARGET_AUTHORITY.equals(name)) {
          // Follow Chromes lead - only include the :authority pseudo header, but exclude all other
          // pseudo headers. Literal Header Field without Indexing - Indexed Name.
          writeInt(headerNameIndex, PREFIX_4_BITS, 0);
          writeByteString(value);
        } else {
          // Literal Header Field with Incremental Indexing - Indexed Name.
          writeInt(headerNameIndex, PREFIX_6_BITS, 0x40);
          writeByteString(value);
          insertIntoDynamicTable(header);
        }
      }
    }

    // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-4.1.1
    void writeInt(int value, int prefixMask, int bits) {
      // Write the raw value for a single byte value.
      if (value < prefixMask) {
        out.writeByte(bits | value);
        return;
      }

      // Write the mask to start a multibyte value.
      out.writeByte(bits | prefixMask);
      value -= prefixMask;

      // Write 7 bits at a time 'til we're done.
      while (value >= 0x80) {
        int b = value & 0x7f;
        out.writeByte(b | 0x80);
        value >>>= 7;
      }
      out.writeByte(value);
    }

    void writeByteString(ByteString data) throws IOException {
      if (useCompression && Huffman.get().encodedLength(data) < data.size()) {
        Buffer huffmanBuffer = new Buffer();
        Huffman.get().encode(data, huffmanBuffer);
        ByteString huffmanBytes = huffmanBuffer.readByteString();
        writeInt(huffmanBytes.size(), PREFIX_7_BITS, 0x80);
        out.write(huffmanBytes);
      } else {
        writeInt(data.size(), PREFIX_7_BITS, 0);
        out.write(data);
      }
    }

    void setHeaderTableSizeSetting(int headerTableSizeSetting) {
      this.headerTableSizeSetting = headerTableSizeSetting;
      int effectiveHeaderTableSize = Math.min(headerTableSizeSetting,
          SETTINGS_HEADER_TABLE_SIZE_LIMIT);

      if (maxDynamicTableByteCount == effectiveHeaderTableSize) return; // No change.

      if (effectiveHeaderTableSize < maxDynamicTableByteCount) {
        smallestHeaderTableSizeSetting = Math.min(smallestHeaderTableSizeSetting,
            effectiveHeaderTableSize);
      }
      emitDynamicTableSizeUpdate = true;
      maxDynamicTableByteCount = effectiveHeaderTableSize;
      adjustDynamicTableByteCount();
    }

    private void adjustDynamicTableByteCount() {
      if (maxDynamicTableByteCount < dynamicTableByteCount) {
        if (maxDynamicTableByteCount == 0) {
          clearDynamicTable();
        } else {
          evictToRecoverBytes(dynamicTableByteCount - maxDynamicTableByteCount);
        }
      }
    }
  }

  /**
   * An HTTP/2 response cannot contain uppercase header characters and must be treated as
   * malformed.
   */
  private static ByteString checkLowercase(ByteString name) throws IOException {
    for (int i = 0, length = name.size(); i < length; i++) {
      byte c = name.getByte(i);
      if (c >= 'A' && c <= 'Z') {
        throw new IOException("PROTOCOL_ERROR response malformed: mixed case name: " + name.utf8());
      }
    }
    return name;
  }
}
