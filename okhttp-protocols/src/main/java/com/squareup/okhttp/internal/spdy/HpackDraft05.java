package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.ByteString;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Read and write HPACK v05.
 * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05
 */
final class HpackDraft05 {

  // Visible for testing.
  static class HeaderEntry {
    final ByteString name;
    final ByteString value;
    final int size;
    // Static entries can be shared safely, as long as {@code referenced} is not mutated.
    final boolean isStatic;
    // Only read when in headerTable.
    // Mutable to avoid needing another BitSet for referenced header indexes.  Using a BitSet for
    // reference entries sounds good, except that entries are added at index zero.  This implies
    // shifting the BitSet, which would be expensive to implement.
    boolean referenced = true;

    HeaderEntry(ByteString name, ByteString value, boolean isStatic) {
      this(name, value, 32 + name.size() + value.size(), isStatic);
    }

    private HeaderEntry(ByteString name, ByteString value, int size, boolean isStatic) {
      this.name = name;
      this.value = value;
      this.size = size;
      this.isStatic = isStatic;
    }

    /** Adds name and value, if this entry is referenced. */
    void addTo(List<ByteString> out) {
      if (!referenced) return;
      out.add(name);
      out.add(value);
    }

    /** Copies this header entry and designates it as not a static entry. */
    @Override public HeaderEntry clone() {
      return new HeaderEntry(name, value, size, false);
    }
  }

  private static final int PREFIX_6_BITS = 0x3f;
  private static final int PREFIX_7_BITS = 0x7f;
  private static final int PREFIX_8_BITS = 0xff;

  private static final HeaderEntry[] STATIC_HEADER_TABLE = new HeaderEntry[] {
      staticEntry(":authority", ""),
      staticEntry(":method", "GET"),
      staticEntry(":method", "POST"),
      staticEntry(":path", "/"),
      staticEntry(":path", "/index.html"),
      staticEntry(":scheme", "http"),
      staticEntry(":scheme", "https"),
      staticEntry(":status", "200"),
      staticEntry(":status", "500"),
      staticEntry(":status", "404"),
      staticEntry(":status", "403"),
      staticEntry(":status", "400"),
      staticEntry(":status", "401"),
      staticEntry("accept-charset", ""),
      staticEntry("accept-encoding", ""),
      staticEntry("accept-language", ""),
      staticEntry("accept-ranges", ""),
      staticEntry("accept", ""),
      staticEntry("access-control-allow-origin", ""),
      staticEntry("age", ""),
      staticEntry("allow", ""),
      staticEntry("authorization", ""),
      staticEntry("cache-control", ""),
      staticEntry("content-disposition", ""),
      staticEntry("content-encoding", ""),
      staticEntry("content-language", ""),
      staticEntry("content-length", ""),
      staticEntry("content-location", ""),
      staticEntry("content-range", ""),
      staticEntry("content-type", ""),
      staticEntry("cookie", ""),
      staticEntry("date", ""),
      staticEntry("etag", ""),
      staticEntry("expect", ""),
      staticEntry("expires", ""),
      staticEntry("from", ""),
      staticEntry("host", ""),
      staticEntry("if-match", ""),
      staticEntry("if-modified-since", ""),
      staticEntry("if-none-match", ""),
      staticEntry("if-range", ""),
      staticEntry("if-unmodified-since", ""),
      staticEntry("last-modified", ""),
      staticEntry("link", ""),
      staticEntry("location", ""),
      staticEntry("max-forwards", ""),
      staticEntry("proxy-authenticate", ""),
      staticEntry("proxy-authorization", ""),
      staticEntry("range", ""),
      staticEntry("referer", ""),
      staticEntry("refresh", ""),
      staticEntry("retry-after", ""),
      staticEntry("server", ""),
      staticEntry("set-cookie", ""),
      staticEntry("strict-transport-security", ""),
      staticEntry("transfer-encoding", ""),
      staticEntry("user-agent", ""),
      staticEntry("vary", ""),
      staticEntry("via", ""),
      staticEntry("www-authenticate", "")
  };

  private HpackDraft05() {
  }

  // TODO: huffman encoding!
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#section-4.1.2
  static class Reader {
    private final DataInputStream in;
    private final List<ByteString> emittedHeaders = new ArrayList<ByteString>();
    private long bytesLeft = 0;

    // Visible for testing.
    final List<HeaderEntry> headerTable = new ArrayList<HeaderEntry>(5); // average of 5 headers
    final BitSet staticReferenceSet = new BitSet();
    long headerTableSize = 0;
    long maxHeaderTableSize = 4096; // TODO: needs to come from SETTINGS_HEADER_TABLE_SIZE.

    Reader(DataInputStream in) {
      this.in = in;
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

        if ((b & 0x80) != 0) {
          int index = readInt(b, PREFIX_7_BITS);
          if (index == 0) {
            clearReferenceSet();
          } else {
            readIndexedHeader(index - 1);
          }
        } else if (b == 0x40) {
          readLiteralHeaderWithoutIndexingNewName();
        } else if ((b & 0xe0) == 0x40) {
          int index = readInt(b, PREFIX_6_BITS);
          readLiteralHeaderWithoutIndexingIndexedName(index - 1);
        } else if (b == 0) {
          readLiteralHeaderWithIncrementalIndexingNewName();
        } else if ((b & 0xc0) == 0) {
          int index = readInt(b, PREFIX_6_BITS);
          readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1);
        } else {
          // TODO: we should throw something that we can coerce to a PROTOCOL_ERROR
          throw new AssertionError("unhandled byte: " + Integer.toBinaryString(b));
        }
      }
    }

    private void clearReferenceSet() {
      staticReferenceSet.clear();
      for (int i = 0, size = headerTable.size(); i < size; i++) {
        HeaderEntry entry = headerTable.get(i);
        if (entry.isStatic) { // lazy clone static entries on mutation.
          entry = entry.clone();
          entry.referenced = false;
          headerTable.set(i, entry);
        } else {
          entry.referenced = false;
        }
      }
    }

    public void emitReferenceSet() {
      for (int i = staticReferenceSet.nextSetBit(0); i != -1;
          i = staticReferenceSet.nextSetBit(i + 1)) {
        STATIC_HEADER_TABLE[i].addTo(emittedHeaders);
      }
      for (int i = headerTable.size() - 1; i != -1; i--) {
        headerTable.get(i).addTo(emittedHeaders);
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
        if (maxHeaderTableSize == 0) {
          staticReferenceSet.set(index - headerTable.size());
        } else {
          HeaderEntry staticEntry = STATIC_HEADER_TABLE[index - headerTable.size()];
          insertIntoHeaderTable(-1, staticEntry);
       }
      } else if (!headerTable.get(index).referenced) {
        HeaderEntry existing = headerTable.get(index);
        existing.referenced = true;
        insertIntoHeaderTable(index, existing);
      } else {
        // TODO: we should throw something that we can coerce to a PROTOCOL_ERROR
        throw new AssertionError("invalid index " + index);
      }
    }

    private void readLiteralHeaderWithoutIndexingIndexedName(int index)
        throws IOException {
      ByteString name = getName(index);
      ByteString value = readString();
      emittedHeaders.add(name);
      emittedHeaders.add(value);
    }

    private void readLiteralHeaderWithoutIndexingNewName()
        throws IOException {
      ByteString name = readString();
      ByteString value = readString();
      emittedHeaders.add(name);
      emittedHeaders.add(value);
    }

    private void readLiteralHeaderWithIncrementalIndexingIndexedName(int nameIndex)
        throws IOException {
      ByteString name = getName(nameIndex);
      ByteString value = readString();
      insertIntoHeaderTable(-1, new HeaderEntry(name, value, false));
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName() throws IOException {
      ByteString name = readString();
      ByteString value = readString();
      insertIntoHeaderTable(-1, new HeaderEntry(name, value, false));
    }

    private ByteString getName(int index) {
      if (isStaticHeader(index)) {
        return STATIC_HEADER_TABLE[index - headerTable.size()].name;
      } else {
        return headerTable.get(index).name;
      }
    }

    private boolean isStaticHeader(int index) {
      return index >= headerTable.size();
    }

    /** index == -1 when new. */
    private void insertIntoHeaderTable(int index, HeaderEntry entry) {
      int delta = entry.size;
      if (index != -1) { // Index -1 == new header.
        delta -= headerTable.get(index).size;
      }

      // if the new or replacement header is too big, drop all entries.
      if (delta > maxHeaderTableSize) {
        staticReferenceSet.clear();
        headerTable.clear();
        headerTableSize = 0;
        // emit the large header to the callback.
        entry.addTo(emittedHeaders);
        return;
      }

      // Evict headers to the required length.
      while (headerTableSize + delta > maxHeaderTableSize) {
        remove(headerTable.size() - 1);
      }

      if (index == -1) {
        headerTable.add(0, entry);
      } else { // Replace value at same position.
        headerTable.set(index, entry);
      }

      headerTableSize += delta;
    }

    private void remove(int index) {
      headerTableSize -= headerTable.remove(index).size;
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
      byte[] encoded = new byte[length];
      bytesLeft -= length;
      in.readFully(encoded);
      return ByteString.of(encoded);
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

  private static HeaderEntry staticEntry(String name, String value) {
    return new HeaderEntry(ByteString.encodeUtf8(name), ByteString.encodeUtf8(value), true);
  }
}
