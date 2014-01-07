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

  interface Callback {
    void onHeader(ByteString name, ByteString value);
  }

  // Visible for testing.
  static class HeaderEntry {
    final ByteString name;
    final ByteString value;
    final int size;
    // read when in headerTable
    boolean referenced = true;

    HeaderEntry(ByteString name, ByteString value) {
      this(name, value, 32 + name.size() + value.size());
    }

    HeaderEntry(String name, String value) {
      this(ByteString.encodeUtf8(name), ByteString.encodeUtf8(value));
    }

    private HeaderEntry(ByteString name, ByteString value, int size) {
      this.name = name;
      this.value = value;
      this.size = size;
    }

    @Override public HeaderEntry clone() {
      return new HeaderEntry(name, value, size);
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
    private final DataInputStream in;
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
     * set of emitted header {@code callback}.
     */
    public void readHeaders(int byteCount, Callback callback) throws IOException {
      bytesLeft += byteCount;
      // TODO: limit to 'byteCount' bytes?

      while (bytesLeft > 0) {
        int b = readByte();

        if ((b & 0x80) != 0) {
          int index = readInt(b, PREFIX_7_BITS);
          if (index == 0) {
            clearReferenceSet();
          } else {
            readIndexedHeader(index - 1, callback);
          }
        } else if (b == 0x40) {
          readLiteralHeaderWithoutIndexingNewName(callback);
        } else if ((b & 0xe0) == 0x40) {
          int index = readInt(b, PREFIX_6_BITS);
          readLiteralHeaderWithoutIndexingIndexedName(index - 1, callback);
        } else if (b == 0) {
          readLiteralHeaderWithIncrementalIndexingNewName(callback);
        } else if ((b & 0xc0) == 0) {
          int index = readInt(b, PREFIX_6_BITS);
          readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1, callback);
        } else {
          // TODO: we should throw something that we can coerce to a PROTOCOL_ERROR
          throw new AssertionError("unhandled byte: " + Integer.toBinaryString(b));
        }
      }
    }

    private void clearReferenceSet() {
      staticReferenceSet.clear();
      for (int i = 0, size = headerTable.size(); i < size; i++) {
        headerTable.get(i).referenced = false;
      }
    }

    public void emitReferenceSet(Callback callback) {
      for (int i = staticReferenceSet.nextSetBit(0); i != -1;
          i = staticReferenceSet.nextSetBit(i + 1)) {
        HeaderEntry header = STATIC_HEADER_TABLE[i];
        callback.onHeader(header.name, header.value);
      }
      for (int i = headerTable.size() - 1; i != -1; i--) {
        HeaderEntry header = headerTable.get(i);
        if (header.referenced) callback.onHeader(header.name, header.value);
      }
    }

    private void readIndexedHeader(int index, Callback callback) {
      if (isStaticHeader(index)) {
        if (maxHeaderTableSize == 0) {
          staticReferenceSet.set(index - headerTable.size());
        } else {
          HeaderEntry staticEntry = STATIC_HEADER_TABLE[index - headerTable.size()];
          insertIntoHeaderTable(-1, staticEntry.clone(), callback);
        }
      } else if (!headerTable.get(index).referenced) {
        HeaderEntry existing = headerTable.get(index);
        existing.referenced = true;
        insertIntoHeaderTable(index, existing, callback);
      } else {
        // TODO: we should throw something that we can coerce to a PROTOCOL_ERROR
        throw new AssertionError("invalid index " + index);
      }
    }

    private void readLiteralHeaderWithoutIndexingIndexedName(int index, Callback callback)
        throws IOException {
      ByteString name = getName(index);
      ByteString value = readString();
      callback.onHeader(name, value);
    }

    private void readLiteralHeaderWithoutIndexingNewName(Callback callback)
        throws IOException {
      ByteString name = readString();
      ByteString value = readString();
      callback.onHeader(name, value);
    }

    private void readLiteralHeaderWithIncrementalIndexingIndexedName(int nameIndex,
        Callback callback) throws IOException {
      ByteString name = getName(nameIndex);
      ByteString value = readString();
      insertIntoHeaderTable(-1, new HeaderEntry(name, value), callback);
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName(Callback callback)
        throws IOException {
      ByteString name = readString();
      ByteString value = readString();
      insertIntoHeaderTable(-1, new HeaderEntry(name, value), callback);
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
    private void insertIntoHeaderTable(int index, HeaderEntry entry, Callback callback) {
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
        callback.onHeader(entry.name, entry.value);
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
