package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.BitArray;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;

/**
 * Read and write HPACK v07.
 *
 * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07
 *
 * This implementation uses an array for the header table with a bitset for
 * references.  Dynamic entries are added to the array, starting in the last
 * position moving forward.  When the array fills, it is doubled.
 */
final class HpackDraft07 {
  private static final int PREFIX_4_BITS = 0x0f;
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
      new Header("accept-encoding", ""),
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

  private HpackDraft07() {
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#section-3.2
  static final class Reader {

    private final List<Header> emittedHeaders = new ArrayList<Header>();
    private final BufferedSource source;

    private int maxHeaderTableByteCountSetting;
    private int maxHeaderTableByteCount;
    // Visible for testing.
    Header[] headerTable = new Header[8];
    // Array is populated back to front, so new entries always have lowest index.
    int nextHeaderIndex = headerTable.length - 1;
    int headerCount = 0;

    /**
     * Set bit positions indicate {@code headerTable[pos]} should be emitted.
     */
    // Using a BitArray as it has left-shift operator.
    BitArray referencedHeaders = new BitArray.FixedCapacity();

    /**
     * Set bit positions indicate {@code headerTable[pos]} was already emitted.
     */
    BitArray emittedReferencedHeaders = new BitArray.FixedCapacity();
    int headerTableByteCount = 0;

    Reader(int maxHeaderTableByteCountSetting, Source source) {
      this.maxHeaderTableByteCountSetting = maxHeaderTableByteCountSetting;
      this.maxHeaderTableByteCount = maxHeaderTableByteCountSetting;
      this.source = Okio.buffer(source);
    }

    int maxHeaderTableByteCount() {
      return maxHeaderTableByteCount;
    }

    /**
     * Called by the reader when the peer sent a new header table size setting.
     * While this establishes the maximum header table size, the
     * {@link #maxHeaderTableByteCount} set during processing may limit the
     * table size to a smaller amount.
     * <p> Evicts entries or clears the table as needed.
     */
    void maxHeaderTableByteCountSetting(int newMaxHeaderTableByteCountSetting) {
      this.maxHeaderTableByteCountSetting = newMaxHeaderTableByteCountSetting;
      this.maxHeaderTableByteCount = maxHeaderTableByteCountSetting;
      adjustHeaderTableByteCount();
    }

    private void adjustHeaderTableByteCount() {
      if (maxHeaderTableByteCount < headerTableByteCount) {
        if (maxHeaderTableByteCount == 0) {
          clearHeaderTable();
        } else {
          evictToRecoverBytes(headerTableByteCount - maxHeaderTableByteCount);
        }
      }
    }

    private void clearHeaderTable() {
      clearReferenceSet();
      Arrays.fill(headerTable, null);
      nextHeaderIndex = headerTable.length - 1;
      headerCount = 0;
      headerTableByteCount = 0;
    }

    /** Returns the count of entries evicted. */
    private int evictToRecoverBytes(int bytesToRecover) {
      int entriesToEvict = 0;
      if (bytesToRecover > 0) {
        // determine how many headers need to be evicted.
        for (int j = headerTable.length - 1; j >= nextHeaderIndex && bytesToRecover > 0; j--) {
          bytesToRecover -= headerTable[j].hpackSize;
          headerTableByteCount -= headerTable[j].hpackSize;
          headerCount--;
          entriesToEvict++;
        }
        referencedHeaders.shiftLeft(entriesToEvict);
        emittedReferencedHeaders.shiftLeft(entriesToEvict);
        System.arraycopy(headerTable, nextHeaderIndex + 1, headerTable,
            nextHeaderIndex + 1 + entriesToEvict, headerCount);
        nextHeaderIndex += entriesToEvict;
      }
      return entriesToEvict;
    }

    /**
     * Read {@code byteCount} bytes of headers from the source stream into the
     * set of emitted headers. This implementation does not propagate the never
     * indexed flag of a header.
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
          if ((b & 0x10) == 0x10) { // 0011NNNN
            if ((b & 0x0f) != 0) throw new IOException("Invalid header table state change " + b);
            clearReferenceSet(); // 00110000
          } else { // 0010NNNN
            maxHeaderTableByteCount = readInt(b, PREFIX_4_BITS);
            if (maxHeaderTableByteCount < 0
                || maxHeaderTableByteCount > maxHeaderTableByteCountSetting) {
              throw new IOException("Invalid header table byte count " + maxHeaderTableByteCount);
            }
            adjustHeaderTableByteCount();
          }
        } else if (b == 0x10 || b == 0) { // 000?0000 - Ignore never indexed bit.
          readLiteralHeaderWithoutIndexingNewName();
        } else { // 000?NNNN - Ignore never indexed bit.
          int index = readInt(b, PREFIX_4_BITS);
          readLiteralHeaderWithoutIndexingIndexedName(index - 1);
        }
      }
    }

    private void clearReferenceSet() {
      referencedHeaders.clear();
      emittedReferencedHeaders.clear();
    }

    void emitReferenceSet() {
      for (int i = headerTable.length - 1; i != nextHeaderIndex; --i) {
        if (referencedHeaders.get(i) && !emittedReferencedHeaders.get(i)) {
          emittedHeaders.add(headerTable[i]);
        }
      }
    }

    /**
     * Returns all headers emitted since they were last cleared, then clears the
     * emitted headers.
     */
    List<Header> getAndReset() {
      List<Header> result = new ArrayList<Header>(emittedHeaders);
      emittedHeaders.clear();
      emittedReferencedHeaders.clear();
      return result;
    }

    private void readIndexedHeader(int index) throws IOException {
      if (isStaticHeader(index)) {
        index -= headerCount;
        if (index > STATIC_HEADER_TABLE.length - 1) {
          throw new IOException("Header index too large " + (index + 1));
        }
        Header staticEntry = STATIC_HEADER_TABLE[index];
        if (maxHeaderTableByteCount == 0) {
          emittedHeaders.add(staticEntry);
        } else {
          insertIntoHeaderTable(-1, staticEntry);
        }
      } else {
        int headerTableIndex = headerTableIndex(index);
        if (!referencedHeaders.get(headerTableIndex)) { // When re-referencing, emit immediately.
          emittedHeaders.add(headerTable[headerTableIndex]);
          emittedReferencedHeaders.set(headerTableIndex);
        }
        referencedHeaders.toggle(headerTableIndex);
      }
    }

    // referencedHeaders is relative to nextHeaderIndex + 1.
    private int headerTableIndex(int index) {
      return nextHeaderIndex + 1 + index;
    }

    private void readLiteralHeaderWithoutIndexingIndexedName(int index) throws IOException {
      ByteString name = getName(index);
      ByteString value = readByteString();
      emittedHeaders.add(new Header(name, value));
    }

    private void readLiteralHeaderWithoutIndexingNewName() throws IOException {
      ByteString name = checkLowercase(readByteString());
      ByteString value = readByteString();
      emittedHeaders.add(new Header(name, value));
    }

    private void readLiteralHeaderWithIncrementalIndexingIndexedName(int nameIndex)
        throws IOException {
      ByteString name = getName(nameIndex);
      ByteString value = readByteString();
      insertIntoHeaderTable(-1, new Header(name, value));
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName() throws IOException {
      ByteString name = checkLowercase(readByteString());
      ByteString value = readByteString();
      insertIntoHeaderTable(-1, new Header(name, value));
    }

    private ByteString getName(int index) {
      if (isStaticHeader(index)) {
        return STATIC_HEADER_TABLE[index - headerCount].name;
      } else {
        return headerTable[headerTableIndex(index)].name;
      }
    }

    private boolean isStaticHeader(int index) {
      return index >= headerCount;
    }

    /** index == -1 when new. */
    private void insertIntoHeaderTable(int index, Header entry) {
      int delta = entry.hpackSize;
      if (index != -1) { // Index -1 == new header.
        delta -= headerTable[headerTableIndex(index)].hpackSize;
      }

      // if the new or replacement header is too big, drop all entries.
      if (delta > maxHeaderTableByteCount) {
        clearHeaderTable();
        // emit the large header to the callback.
        emittedHeaders.add(entry);
        return;
      }

      // Evict headers to the required length.
      int bytesToRecover = (headerTableByteCount + delta) - maxHeaderTableByteCount;
      int entriesEvicted = evictToRecoverBytes(bytesToRecover);

      if (index == -1) { // Adding a value to the header table.
        if (headerCount + 1 > headerTable.length) { // Need to grow the header table.
          Header[] doubled = new Header[headerTable.length * 2];
          System.arraycopy(headerTable, 0, doubled, headerTable.length, headerTable.length);
          if (doubled.length == 64) {
            referencedHeaders = ((BitArray.FixedCapacity) referencedHeaders).toVariableCapacity();
            emittedReferencedHeaders =
                ((BitArray.FixedCapacity) emittedReferencedHeaders).toVariableCapacity();
          }
          referencedHeaders.shiftLeft(headerTable.length);
          emittedReferencedHeaders.shiftLeft(headerTable.length);
          nextHeaderIndex = headerTable.length - 1;
          headerTable = doubled;
        }
        index = nextHeaderIndex--;
        referencedHeaders.set(index);
        headerTable[index] = entry;
        headerCount++;
      } else { // Replace value at same position.
        index += headerTableIndex(index) + entriesEvicted;
        referencedHeaders.set(index);
        headerTable[index] = entry;
      }
      headerTableByteCount += delta;
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
        return Huffman.get().decode(source.readByteArray(length));
      } else {
        return source.readByteString(length);
      }
    }
  }

  private static final Map<ByteString, Integer> NAME_TO_FIRST_INDEX = nameToFirstIndex();

  private static Map<ByteString, Integer> nameToFirstIndex() {
    Map<ByteString, Integer> result =
        new LinkedHashMap<ByteString, Integer>(STATIC_HEADER_TABLE.length);
    for (int i = 0; i < STATIC_HEADER_TABLE.length; i++) {
      if (!result.containsKey(STATIC_HEADER_TABLE[i].name)) {
        result.put(STATIC_HEADER_TABLE[i].name, i);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  static final class Writer {
    private final Buffer out;

    Writer(Buffer out) {
      this.out = out;
    }

    /** This does not use "never indexed" semantics for sensitive headers. */
    // https://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#section-4.3.3
    void writeHeaders(List<Header> headerBlock) throws IOException {
      // TODO: implement index tracking
      for (int i = 0, size = headerBlock.size(); i < size; i++) {
        ByteString name = headerBlock.get(i).name.toAsciiLowercase();
        Integer staticIndex = NAME_TO_FIRST_INDEX.get(name);
        if (staticIndex != null) {
          // Literal Header Field without Indexing - Indexed Name.
          writeInt(staticIndex + 1, PREFIX_4_BITS, 0);
          writeByteString(headerBlock.get(i).value);
        } else {
          out.writeByte(0x00); // Literal Header without Indexing - New Name.
          writeByteString(name);
          writeByteString(headerBlock.get(i).value);
        }
      }
    }

    // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#section-4.1.1
    void writeInt(int value, int prefixMask, int bits) throws IOException {
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
      writeInt(data.size(), PREFIX_7_BITS, 0);
      out.write(data);
    }
  }

  /**
   * An HTTP/2 response cannot contain uppercase header characters and must
   * be treated as malformed.
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
