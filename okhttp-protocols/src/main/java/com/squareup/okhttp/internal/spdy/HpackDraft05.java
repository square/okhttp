package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.BitArray;
import com.squareup.okhttp.internal.ByteString;
import com.squareup.okhttp.internal.Util;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read and write HPACK v05.
 *
 * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05
 *
 * This implementation uses an array for the header table with a bitset for
 * references.  Dynamic entries are added to the array, starting in the last
 * position moving forward.  When the array fills, it is doubled.
 */
final class HpackDraft05 {

  // Visible for testing.
  static class HeaderEntry {
    final ByteString name;
    final ByteString value;
    final int size;

    HeaderEntry(String name, String value) {
      this(ByteString.encodeUtf8(name), ByteString.encodeUtf8(value));
    }

    HeaderEntry(ByteString name, ByteString value) {
      this(name, value, 32 + name.size() + value.size());
    }

    private HeaderEntry(ByteString name, ByteString value, int size) {
      this.name = name;
      this.value = value;
      this.size = size;
    }
  }

  private static final int PREFIX_6_BITS = 0x3f;
  private static final int PREFIX_7_BITS = 0x7f;
  private static final int PREFIX_8_BITS = 0xff;

  private static final HeaderEntry[] STATIC_HEADER_TABLE = new HeaderEntry[] {
      new HeaderEntry(":authority", ""),
      new HeaderEntry(":method", "GET"),
      new HeaderEntry(":method", "POST"),
      new HeaderEntry(":path", "/"),
      new HeaderEntry(":path", "/index.html"),
      new HeaderEntry(":scheme", "http"),
      new HeaderEntry(":scheme", "https"),
      new HeaderEntry(":status", "200"),
      new HeaderEntry(":status", "500"),
      new HeaderEntry(":status", "404"),
      new HeaderEntry(":status", "403"),
      new HeaderEntry(":status", "400"),
      new HeaderEntry(":status", "401"),
      new HeaderEntry("accept-charset", ""),
      new HeaderEntry("accept-encoding", ""),
      new HeaderEntry("accept-language", ""),
      new HeaderEntry("accept-ranges", ""),
      new HeaderEntry("accept", ""),
      new HeaderEntry("access-control-allow-origin", ""),
      new HeaderEntry("age", ""),
      new HeaderEntry("allow", ""),
      new HeaderEntry("authorization", ""),
      new HeaderEntry("cache-control", ""),
      new HeaderEntry("content-disposition", ""),
      new HeaderEntry("content-encoding", ""),
      new HeaderEntry("content-language", ""),
      new HeaderEntry("content-length", ""),
      new HeaderEntry("content-location", ""),
      new HeaderEntry("content-range", ""),
      new HeaderEntry("content-type", ""),
      new HeaderEntry("cookie", ""),
      new HeaderEntry("date", ""),
      new HeaderEntry("etag", ""),
      new HeaderEntry("expect", ""),
      new HeaderEntry("expires", ""),
      new HeaderEntry("from", ""),
      new HeaderEntry("host", ""),
      new HeaderEntry("if-match", ""),
      new HeaderEntry("if-modified-since", ""),
      new HeaderEntry("if-none-match", ""),
      new HeaderEntry("if-range", ""),
      new HeaderEntry("if-unmodified-since", ""),
      new HeaderEntry("last-modified", ""),
      new HeaderEntry("link", ""),
      new HeaderEntry("location", ""),
      new HeaderEntry("max-forwards", ""),
      new HeaderEntry("proxy-authenticate", ""),
      new HeaderEntry("proxy-authorization", ""),
      new HeaderEntry("range", ""),
      new HeaderEntry("referer", ""),
      new HeaderEntry("refresh", ""),
      new HeaderEntry("retry-after", ""),
      new HeaderEntry("server", ""),
      new HeaderEntry("set-cookie", ""),
      new HeaderEntry("strict-transport-security", ""),
      new HeaderEntry("transfer-encoding", ""),
      new HeaderEntry("user-agent", ""),
      new HeaderEntry("vary", ""),
      new HeaderEntry("via", ""),
      new HeaderEntry("www-authenticate", "")
  };

  private HpackDraft05() {
  }

  // TODO: huffman encoding!
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#section-4.1.2
  static class Reader {
    private final Huffman.Codec huffmanCodec;

    private final DataInputStream in;
    private final List<ByteString> emittedHeaders = new ArrayList<ByteString>();
    private int maxHeaderTableByteCount;
    private long bytesLeft = 0;

    // Visible for testing.
    HeaderEntry[] headerTable = new HeaderEntry[8];
    // Array is populated back to front, so new entries always have lowest index.
    int nextHeaderIndex = headerTable.length - 1;
    int headerCount = 0;

    /**
     * Set bit positions indicate {@code headerTable[pos]} should be emitted.
     */
    // Using a BitArray as it has left-shift operator.
    BitArray referencedHeaders = new BitArray();

    /**
     * Set bit positions indicate {@code STATIC_HEADER_TABLE[pos]} should be
     * emitted.
     */
    BitArray referencedStaticHeaders = new BitArray();
    int headerTableByteCount = 0;

    Reader(boolean client, int maxHeaderTableByteCount, DataInputStream in) {
      this.huffmanCodec = client ? Huffman.Codec.RESPONSE : Huffman.Codec.REQUEST;
      this.maxHeaderTableByteCount = maxHeaderTableByteCount;
      this.in = in;
    }

    int maxHeaderTableByteCount() {
      return maxHeaderTableByteCount;
    }

    /** Evicts entries as needed. */
    void maxHeaderTableByteCount(int newMaxHeaderTableByteCount) {
      if (newMaxHeaderTableByteCount < headerTableByteCount) {
        evictToRecoverBytes(headerTableByteCount - newMaxHeaderTableByteCount);
      }
      this.maxHeaderTableByteCount = newMaxHeaderTableByteCount;
    }

    /** Returns the count of entries evicted. */
    private int evictToRecoverBytes(int bytesToRecover) {
      int entriesToEvict = 0;
      if (bytesToRecover > 0) {
        // determine how many headers need to be evicted.
        for (int j = headerTable.length - 1; j >= nextHeaderIndex && bytesToRecover > 0; j--) {
          bytesToRecover -= headerTable[j].size;
          headerTableByteCount -= headerTable[j].size;
          headerCount--;
          entriesToEvict++;
        }
        referencedHeaders.shiftLeft(entriesToEvict);
        System.arraycopy(headerTable, nextHeaderIndex + 1, headerTable,
            nextHeaderIndex + 1 + entriesToEvict, headerCount);
        nextHeaderIndex += entriesToEvict;
      }
      return entriesToEvict;
    }

    /**
     * Read {@code byteCount} bytes of headers from the source stream into the
     * set of emitted headers.
     */
    public void readHeaders(int byteCount) throws IOException {
      bytesLeft += byteCount;
      // TODO: limit to 'byteCount' bytes?

      while (bytesLeft > 0) {
        int b = readByte();

        if (b == 0x80) { // 10000000
          clearReferenceSet();
        } else if ((b & 0x80) == 0x80) { // 1NNNNNNN
          int index = readInt(b, PREFIX_7_BITS);
          readIndexedHeader(index - 1);
        } else { // 0NNNNNNN
          if (b == 0x40) { // 01000000
            readLiteralHeaderWithoutIndexingNewName();
          } else if ((b & 0xe0) == 0x40) {  // 01NNNNNN
            int index = readInt(b, PREFIX_6_BITS);
            readLiteralHeaderWithoutIndexingIndexedName(index - 1);
          } else if (b == 0) { // 00000000
            readLiteralHeaderWithIncrementalIndexingNewName();
          } else if ((b & 0xc0) == 0) { // 00NNNNNN
            int index = readInt(b, PREFIX_6_BITS);
            readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1);
          } else {
            // TODO: we should throw something that we can coerce to a PROTOCOL_ERROR
            throw new AssertionError("unhandled byte: " + Integer.toBinaryString(b));
          }
        }
      }
    }

    private void clearReferenceSet() {
      referencedStaticHeaders.clear();
      referencedHeaders.clear();
    }

    public void emitReferenceSet() {
      for (int i = 0; i < STATIC_HEADER_TABLE.length; ++i) {
        if (referencedStaticHeaders.get(i)) {
          emittedHeaders.add(STATIC_HEADER_TABLE[i].name);
          emittedHeaders.add(STATIC_HEADER_TABLE[i].value);
        }
      }
      for (int i = headerTable.length - 1; i != nextHeaderIndex; --i) {
        if (referencedHeaders.get(i)) {
          emittedHeaders.add(headerTable[i].name);
          emittedHeaders.add(headerTable[i].value);
        }
      }
    }

    /**
     * Returns all headers emitted since they were last cleared, then clears the
     * emitted headers.
     */
    public List<ByteString> getAndReset() {
      List<ByteString> result = new ArrayList<ByteString>(emittedHeaders);
      emittedHeaders.clear();
      return result;
    }

    private void readIndexedHeader(int index) {
      if (isStaticHeader(index)) {
        if (maxHeaderTableByteCount == 0) {
          referencedStaticHeaders.set(index - headerCount);
        } else {
          HeaderEntry staticEntry = STATIC_HEADER_TABLE[index - headerCount];
          insertIntoHeaderTable(-1, staticEntry);
        }
      } else if (!referencedHeaders.get(headerTableIndex(index))) {
        referencedHeaders.set(headerTableIndex(index));
      } else {
        // TODO: we should throw something that we can coerce to a PROTOCOL_ERROR
        throw new AssertionError("invalid index " + index);
      }
    }

    // referencedHeaders is relative to nextHeaderIndex + 1.
    private int headerTableIndex(int index) {
      return nextHeaderIndex + 1 + index;
    }

    private void readLiteralHeaderWithoutIndexingIndexedName(int index) throws IOException {
      ByteString name = getName(index);
      ByteString value = readString();
      emittedHeaders.add(name);
      emittedHeaders.add(value);
    }

    private void readLiteralHeaderWithoutIndexingNewName() throws IOException {
      ByteString name = readString();
      ByteString value = readString();
      emittedHeaders.add(name);
      emittedHeaders.add(value);
    }

    private void readLiteralHeaderWithIncrementalIndexingIndexedName(int nameIndex)
        throws IOException {
      ByteString name = getName(nameIndex);
      ByteString value = readString();
      insertIntoHeaderTable(-1, new HeaderEntry(name, value));
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName() throws IOException {
      ByteString name = readString();
      ByteString value = readString();
      insertIntoHeaderTable(-1, new HeaderEntry(name, value));
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
    private void insertIntoHeaderTable(int index, HeaderEntry entry) {
      int delta = entry.size;
      if (index != -1) { // Index -1 == new header.
        delta -= headerTable[headerTableIndex(index)].size;
      }

      // if the new or replacement header is too big, drop all entries.
      if (delta > maxHeaderTableByteCount) {
        clearReferenceSet();
        Arrays.fill(headerTable, null);
        nextHeaderIndex = headerTable.length - 1;
        headerCount = 0;
        headerTableByteCount = 0;
        // emit the large header to the callback.
        emittedHeaders.add(entry.name);
        emittedHeaders.add(entry.value);
        return;
      }

      // Evict headers to the required length.
      int bytesToRecover = (headerTableByteCount + delta) - maxHeaderTableByteCount;
      int entriesEvicted = evictToRecoverBytes(bytesToRecover);

      if (index == -1) {
        if (headerCount + 1 > headerTable.length) {
          HeaderEntry[] doubled = new HeaderEntry[headerTable.length * 2];
          System.arraycopy(headerTable, 0, doubled, headerTable.length, headerTable.length);
          referencedHeaders.shiftLeft(headerTable.length);
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
      bytesLeft--;
      return in.readByte() & 0xff;
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

    /**
     * Reads a UTF-8 encoded string. Since ASCII is a subset of UTF-8, this method
     * may be used to read strings that are known to be ASCII-only.
     */
    public ByteString readString() throws IOException {
      int firstByte = readByte();
      int length = readInt(firstByte, PREFIX_8_BITS);
      if ((length & 0x80) == 0x80) { // 1NNNNNNN
        length &= ~0x80;
        byte[] buff = new byte[length];
        Util.readFully(in, buff);
        bytesLeft -= length;
        return ByteString.of(huffmanCodec.decode(buff));
      }
      bytesLeft -= length;
      return ByteString.read(in, length);
    }
  }

  static class Writer {
    private final OutputStream out;

    Writer(OutputStream out) {
      this.out = out;
    }

    public void writeHeaders(List<ByteString> nameValueBlock) throws IOException {
      // TODO: implement a compression strategy.
      for (int i = 0, size = nameValueBlock.size(); i < size; i += 2) {
        out.write(0x40); // Literal Header without Indexing - New Name.
        writeByteString(nameValueBlock.get(i));
        writeByteString(nameValueBlock.get(i + 1));
      }
    }

    // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#section-4.1.1
    public void writeInt(int value, int prefixMask, int bits) throws IOException {
      // Write the raw value for a single byte value.
      if (value < prefixMask) {
        out.write(bits | value);
        return;
      }

      // Write the mask to start a multibyte value.
      out.write(bits | prefixMask);
      value -= prefixMask;

      // Write 7 bits at a time 'til we're done.
      while (value >= 0x80) {
        int b = value & 0x7f;
        out.write(b | 0x80);
        value >>>= 7;
      }
      out.write(value);
    }

    public void writeByteString(ByteString data) throws IOException {
      writeInt(data.size(), PREFIX_8_BITS, 0);
      data.write(out);
    }
  }
}
