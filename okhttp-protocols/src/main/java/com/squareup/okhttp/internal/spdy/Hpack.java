package com.squareup.okhttp.internal.spdy;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Read and write HPACK v01.
 * http://http2.github.io/compression-spec/compression-spec.html#rfc.status
 */
final class Hpack {
  static final int PREFIX_5_BITS = 0x1f;
  static final int PREFIX_6_BITS = 0x3f;
  static final int PREFIX_7_BITS = 0x7f;
  static final int PREFIX_8_BITS = 0xff;

  static final List<String> INITIAL_CLIENT_TO_SERVER_HEADER_TABLE = Arrays.asList(
      ":scheme", "http",
      ":scheme", "https",
      ":host", "",
      ":path", "/",
      ":method", "GET",
      "accept", "",
      "accept-charset", "",
      "accept-encoding", "",
      "accept-language", "",
      "cookie", "",
      "if-modified-since", "",
      "user-agent", "",
      "referer", "",
      "authorization", "",
      "allow", "",
      "cache-control", "",
      "connection", "",
      "content-length", "",
      "content-type", "",
      "date", "",
      "expect", "",
      "from", "",
      "if-match", "",
      "if-none-match", "",
      "if-range", "",
      "if-unmodified-since", "",
      "max-forwards", "",
      "proxy-authorization", "",
      "range", "",
      "via", ""
  );

  static final List<String> INITIAL_SERVER_TO_CLIENT_HEADER_TABLE = Arrays.asList(
      ":status", "200",
      "age", "",
      "cache-control", "",
      "content-length", "",
      "content-type", "",
      "date", "",
      "etag", "",
      "expires", "",
      "last-modified", "",
      "server", "",
      "set-cookie", "",
      "vary", "",
      "via", "",
      "access-control-allow-origin", "",
      "accept-ranges", "",
      "allow", "",
      "connection", "",
      "content-disposition", "",
      "content-encoding", "",
      "content-language", "",
      "content-location", "",
      "content-range", "",
      "link", "",
      "location", "",
      "proxy-authenticate", "",
      "refresh", "",
      "retry-after", "",
      "strict-transport-security", "",
      "transfer-encoding", "",
      "www-authenticate", ""
  );

  private Hpack() {
  }

  static class Reader {
    private final long maxBufferSize = 4096; // TODO: needs to come from settings.
    private final DataInputStream in;

    private final BitSet referenceSet = new BitSet();
    private final List<String> headerTable;
    private final List<String> emittedHeaders = new ArrayList<String>();
    private long bufferSize = 4096;
    private long bytesLeft = 0;

    Reader(DataInputStream in, boolean client) {
      this.in = in;
      this.headerTable = new ArrayList<String>(client
          ? INITIAL_SERVER_TO_CLIENT_HEADER_TABLE
          : INITIAL_CLIENT_TO_SERVER_HEADER_TABLE);
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

      if (bytesLeft < 0) throw new ProtocolException();
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
        emittedHeaders.add(getName(index));
        emittedHeaders.add(getValue(index));
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
      int index = headerTable.size() / 2;
      String name = getName(nameIndex); // Firefox uses nameIndex - 1?
      String value = readString();
      appendToHeaderTable(name, value);
      emittedHeaders.add(name);
      emittedHeaders.add(value);
      referenceSet.set(index);
    }

    private void readLiteralHeaderWithIncrementalIndexingNewName() throws IOException {
      int index = headerTable.size() / 2;
      String name = readString();
      String value = readString();
      appendToHeaderTable(name, value);
      emittedHeaders.add(name);
      emittedHeaders.add(value);
      referenceSet.set(index);
    }

    private void readLiteralHeaderWithSubstitutionIndexingIndexedName(int nameIndex)
        throws IOException {
      int index = readInt(readByte(), PREFIX_8_BITS);
      String name = getName(nameIndex);
      String value = readString();
      replaceInHeaderTable(index, name, value);
      emittedHeaders.add(name);
      emittedHeaders.add(value);
      referenceSet.set(index);
    }

    private void readLiteralHeaderWithSubstitutionIndexingNewName() throws IOException {
      String name = readString();
      int index = readInt(readByte(), PREFIX_8_BITS);
      String value = readString();
      replaceInHeaderTable(index, name, value);
      emittedHeaders.add(name);
      emittedHeaders.add(value);
      referenceSet.set(index);
    }

    private String getName(int index) {
      return headerTable.get(index * 2);
    }

    private String getValue(int index) {
      return headerTable.get(index * 2 + 1);
    }

    private void appendToHeaderTable(String name, String value) {
      insertIntoHeaderTable(headerTable.size() / 2, name, value);
    }

    private void replaceInHeaderTable(int index, String name, String value) {
      remove(index);
      insertIntoHeaderTable(index, name, value);
    }

    private void insertIntoHeaderTable(int index, String name, String value) {
      // TODO: This needs to be the length in UTF-8 bytes, not the length in chars.

      int delta = 32 + name.length() + value.length();

      // Prune headers to the required length.
      while (bufferSize + delta > maxBufferSize) {
        remove(0);
        index--;
      }

      if (delta > maxBufferSize) {
        return; // New values won't fit in the buffer; skip 'em.
      }

      if (index < 0) index = 0;

      headerTable.add(index * 2, name);
      headerTable.add(index * 2 + 1, value);
      bufferSize += delta;
    }

    private void remove(int index) {
      String name = headerTable.remove(index * 2);
      String value = headerTable.remove(index * 2); // No +1 because it's shifted by remove() above.
      // TODO: This needs to be the length in UTF-8 bytes, not the length in chars.
      bufferSize -= (32 + name.length() + value.length());
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
