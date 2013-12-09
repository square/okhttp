package com.squareup.okhttp.internal.spdy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Read and write HPACK v03.
 * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-03
 */
final class Hpack {

  static class HeaderEntry {
    private final String name;
    private final String value;

    HeaderEntry(String name, String value) {
      this.name = name;
      this.value = value;
    }

    // TODO: This needs to be the length in UTF-8 bytes, not the length in chars.
    int length() {
      return 32 + name.length() + value.length();
    }
  }

  static final int PREFIX_5_BITS = 0x1f;
  static final int PREFIX_6_BITS = 0x3f;
  static final int PREFIX_7_BITS = 0x7f;
  static final int PREFIX_8_BITS = 0xff;

  static final List<HeaderEntry> INITIAL_CLIENT_TO_SERVER_HEADER_TABLE = Arrays.asList(
      new HeaderEntry(":scheme", "http"),
      new HeaderEntry(":scheme", "https"),
      new HeaderEntry(":host", ""),
      new HeaderEntry(":path", "/"),
      new HeaderEntry(":method", "GET"),
      new HeaderEntry("accept", ""),
      new HeaderEntry("accept-charset", ""),
      new HeaderEntry("accept-encoding", ""),
      new HeaderEntry("accept-language", ""),
      new HeaderEntry("cookie", ""),
      new HeaderEntry("if-modified-since", ""),
      new HeaderEntry("user-agent", ""),
      new HeaderEntry("referer", ""),
      new HeaderEntry("authorization", ""),
      new HeaderEntry("allow", ""),
      new HeaderEntry("cache-control", ""),
      new HeaderEntry("connection", ""),
      new HeaderEntry("content-length", ""),
      new HeaderEntry("content-type", ""),
      new HeaderEntry("date", ""),
      new HeaderEntry("expect", ""),
      new HeaderEntry("from", ""),
      new HeaderEntry("if-match", ""),
      new HeaderEntry("if-none-match", ""),
      new HeaderEntry("if-range", ""),
      new HeaderEntry("if-unmodified-since", ""),
      new HeaderEntry("max-forwards", ""),
      new HeaderEntry("proxy-authorization", ""),
      new HeaderEntry("range", ""),
      new HeaderEntry("via", "")
  );

  static final List<HeaderEntry> INITIAL_SERVER_TO_CLIENT_HEADER_TABLE = Arrays.asList(
      new HeaderEntry(":status", "200"),
      new HeaderEntry("age", ""),
      new HeaderEntry("cache-control", ""),
      new HeaderEntry("content-length", ""),
      new HeaderEntry("content-type", ""),
      new HeaderEntry("date", ""),
      new HeaderEntry("etag", ""),
      new HeaderEntry("expires", ""),
      new HeaderEntry("last-modified", ""),
      new HeaderEntry("server", ""),
      new HeaderEntry("set-cookie", ""),
      new HeaderEntry("vary", ""),
      new HeaderEntry("via", ""),
      new HeaderEntry("access-control-allow-origin", ""),
      new HeaderEntry("accept-ranges", ""),
      new HeaderEntry("allow", ""),
      new HeaderEntry("connection", ""),
      new HeaderEntry("content-disposition", ""),
      new HeaderEntry("content-encoding", ""),
      new HeaderEntry("content-language", ""),
      new HeaderEntry("content-location", ""),
      new HeaderEntry("content-range", ""),
      new HeaderEntry("link", ""),
      new HeaderEntry("location", ""),
      new HeaderEntry("proxy-authenticate", ""),
      new HeaderEntry("refresh", ""),
      new HeaderEntry("retry-after", ""),
      new HeaderEntry("strict-transport-security", ""),
      new HeaderEntry("transfer-encoding", ""),
      new HeaderEntry("www-authenticate", "")
  );

  // Update these when initial tables change to sum of each entry length.
  static final int INITIAL_CLIENT_TO_SERVER_HEADER_TABLE_LENGTH = 1262;
  static final int INITIAL_SERVER_TO_CLIENT_HEADER_TABLE_LENGTH = 1304;

  private Hpack() {
  }

  static class Reader {
    private final long maxBufferSize = 4096; // TODO: needs to come from settings.
    private final DataInputStream in;

    private final BitSet referenceSet = new BitSet();
    private final List<HeaderEntry> headerTable;
    private final List<String> emittedHeaders = new ArrayList<String>();
    private long bufferSize = 0;
    private long bytesLeft = 0;

    Reader(DataInputStream in, boolean client) {
      this.in = in;
      if (client) {  // we are reading from the server
        this.headerTable = new ArrayList<HeaderEntry>(INITIAL_SERVER_TO_CLIENT_HEADER_TABLE);
        this.bufferSize = INITIAL_SERVER_TO_CLIENT_HEADER_TABLE_LENGTH;
      } else {
        this.headerTable = new ArrayList<HeaderEntry>(INITIAL_CLIENT_TO_SERVER_HEADER_TABLE);
        this.bufferSize = INITIAL_CLIENT_TO_SERVER_HEADER_TABLE_LENGTH;
      }
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
          readIndexedHeader(index);
        } else if (b == 0x60) {
          readLiteralHeaderWithoutIndexingNewName();
        } else if ((b & 0xe0) == 0x60) {
          int index = readInt(b, PREFIX_5_BITS);
          readLiteralHeaderWithoutIndexingIndexedName(index - 1);
        } else if (b == 0x40) {
          readLiteralHeaderWithIncrementalIndexingNewName();
        } else if ((b & 0xe0) == 0x40) {
          int index = readInt(b, PREFIX_5_BITS);
          readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1);
        } else if (b == 0) {
          readLiteralHeaderWithSubstitutionIndexingNewName();
        } else if ((b & 0xc0) == 0) {
          int index = readInt(b, PREFIX_6_BITS);
          readLiteralHeaderWithSubstitutionIndexingIndexedName(index - 1);
        } else {
          throw new AssertionError();
        }
      }
    }

    public void emitReferenceSet() {
      for (int i = referenceSet.nextSetBit(0); i != -1; i = referenceSet.nextSetBit(i + 1)) {
        emittedHeaders.add(getName(i));
        emittedHeaders.add(getValue(i));
      }
    }

    /**
     * Returns all headers emitted since they were last cleared, then clears the
     * emitted headers.
     */
    public List<String> getAndReset() {
      List<String> result = new ArrayList<String>(emittedHeaders);
      emittedHeaders.clear();
      return result;
    }

    private void readIndexedHeader(int index) {
      if (referenceSet.get(index)) {
        referenceSet.clear(index);
      } else {
        referenceSet.set(index);
      }
    }

    private void readLiteralHeaderWithoutIndexingIndexedName(int index)
        throws IOException {
      String name = getName(index);
      String value = readString();
      emittedHeaders.add(name);
      emittedHeaders.add(value);
    }

    private void readLiteralHeaderWithoutIndexingNewName()
        throws IOException {
      String name = readString();
      String value = readString();
      emittedHeaders.add(name);
      emittedHeaders.add(value);
    }

    private void readLiteralHeaderWithIncrementalIndexingIndexedName(int nameIndex)
        throws IOException {
      String name = getName(nameIndex);
      String value = readString();
      int index = headerTable.size(); // append to tail
      insertIntoHeaderTable(index, new HeaderEntry(name, value));
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName() throws IOException {
      String name = readString();
      String value = readString();
      int index = headerTable.size(); // append to tail
      insertIntoHeaderTable(index, new HeaderEntry(name, value));
    }

    private void readLiteralHeaderWithSubstitutionIndexingIndexedName(int nameIndex)
        throws IOException {
      int index = readInt(readByte(), PREFIX_8_BITS);
      String name = getName(nameIndex);
      String value = readString();
      insertIntoHeaderTable(index, new HeaderEntry(name, value));
    }

    private void readLiteralHeaderWithSubstitutionIndexingNewName() throws IOException {
      String name = readString();
      int index = readInt(readByte(), PREFIX_8_BITS);
      String value = readString();
      insertIntoHeaderTable(index, new HeaderEntry(name, value));
    }

    private String getName(int index) {
      return headerTable.get(index).name;
    }

    private String getValue(int index) {
      return headerTable.get(index).value;
    }

    private void insertIntoHeaderTable(int index, HeaderEntry entry) {
      int delta = entry.length();
      if (index != headerTable.size()) {
        delta -= headerTable.get(index).length();
      }

      // if the new or replacement header is too big, drop all entries.
      if (delta > maxBufferSize) {
        headerTable.clear();
        bufferSize = 0;
        // emit the large header to the callback.
        emittedHeaders.add(entry.name);
        emittedHeaders.add(entry.value);
        return;
      }

      // Prune headers to the required length.
      while (bufferSize + delta > maxBufferSize) {
        remove(0);
        index--;
      }

      if (index < 0) { // we pruned it, so insert at beginning
        index = 0;
        headerTable.add(index, entry);
      } else if (index == headerTable.size()) { // append to the end
        headerTable.add(index, entry);
      } else { // replace value at same position
        headerTable.set(index, entry);
      }

      bufferSize += delta;
      referenceSet.set(index);
    }

    private void remove(int index) {
      bufferSize -= headerTable.remove(index).length();
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
    public String readString() throws IOException {
      int firstByte = readByte();
      int length = readInt(firstByte, PREFIX_8_BITS);
      byte[] encoded = new byte[length];
      bytesLeft -= length;
      in.readFully(encoded);
      return new String(encoded, "UTF-8");
    }
  }

  static class Writer {
    private final OutputStream out;

    Writer(OutputStream out) {
      this.out = out;
    }

    public void writeHeaders(List<String> nameValueBlock) throws IOException {
      // TODO: implement a compression strategy.
      for (int i = 0, size = nameValueBlock.size(); i < size; i += 2) {
        out.write(0x60); // Literal Header without Indexing - New Name.
        writeString(nameValueBlock.get(i));
        writeString(nameValueBlock.get(i + 1));
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

    /**
     * Writes a UTF-8 encoded string. Since ASCII is a subset of UTF-8, this
     * method can be used to write strings that are known to be ASCII-only.
     */
    public void writeString(String headerName) throws IOException {
      byte[] bytes = headerName.getBytes("UTF-8");
      writeInt(bytes.length, PREFIX_8_BITS, 0);
      out.write(bytes);
    }
  }
}
