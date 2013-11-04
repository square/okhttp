package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.Util;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Reads a SPDY/3 Name/Value header block. This class is made complicated by the
 * requirement that we're strict with which bytes we put in the compressed bytes
 * buffer. We need to put all compressed bytes into that buffer -- but no other
 * bytes.
 */
class NameValueBlockReader implements Closeable {
  private final DataInputStream nameValueBlockIn;
  private final FillableInflaterInputStream fillableInflaterInputStream;
  private int compressedLimit;

  NameValueBlockReader(final InputStream in) {
    // Limit the inflater input stream to only those bytes in the Name/Value block. We cut the
    // inflater off at its source because we can't predict the ratio of compressed bytes to
    // uncompressed bytes.
    InputStream throttleStream = new InputStream() {
      @Override public int read() throws IOException {
        return Util.readSingleByte(this);
      }

      @Override public int read(byte[] buffer, int offset, int byteCount) throws IOException {
        byteCount = Math.min(byteCount, compressedLimit);
        int consumed = in.read(buffer, offset, byteCount);
        compressedLimit -= consumed;
        return consumed;
      }

      @Override public void close() throws IOException {
        in.close();
      }
    };

    // Subclass inflater to install a dictionary when it's needed.
    Inflater inflater = new Inflater() {
      @Override public int inflate(byte[] buffer, int offset, int count)
          throws DataFormatException {
        int result = super.inflate(buffer, offset, count);
        if (result == 0 && needsDictionary()) {
          setDictionary(Spdy3.DICTIONARY);
          result = super.inflate(buffer, offset, count);
        }
        return result;
      }
    };

    fillableInflaterInputStream = new FillableInflaterInputStream(throttleStream, inflater);
    nameValueBlockIn = new DataInputStream(fillableInflaterInputStream);
  }

  /** Extend the inflater stream so we can eagerly fill the compressed bytes buffer if necessary. */
  static class FillableInflaterInputStream extends InflaterInputStream {
    public FillableInflaterInputStream(InputStream in, Inflater inf) {
      super(in, inf);
    }

    @Override public void fill() throws IOException {
      super.fill(); // This method is protected in the superclass.
    }
  }

  public List<String> readNameValueBlock(int length) throws IOException {
    this.compressedLimit += length;
    try {
      int numberOfPairs = nameValueBlockIn.readInt();
      if (numberOfPairs < 0) {
        throw new IOException("numberOfPairs < 0: " + numberOfPairs);
      }
      if (numberOfPairs > 1024) {
        throw new IOException("numberOfPairs > 1024: " + numberOfPairs);
      }
      List<String> entries = new ArrayList<String>(numberOfPairs * 2);
      for (int i = 0; i < numberOfPairs; i++) {
        String name = readString();
        String values = readString();
        if (name.length() == 0) throw new IOException("name.length == 0");
        entries.add(name);
        entries.add(values);
      }

      doneReading();

      return entries;
    } catch (DataFormatException e) {
      throw new IOException(e.getMessage());
    }
  }

  private void doneReading() throws IOException {
    if (compressedLimit == 0) return;

    // Read any outstanding unread bytes. One side-effect of deflate compression is that sometimes
    // there are bytes remaining in the stream after we've consumed all of the content.
    fillableInflaterInputStream.fill();

    if (compressedLimit != 0) {
      throw new IOException("compressedLimit > 0: " + compressedLimit);
    }
  }

  private String readString() throws DataFormatException, IOException {
    int length = nameValueBlockIn.readInt();
    byte[] bytes = new byte[length];
    Util.readFully(nameValueBlockIn, bytes);
    return new String(bytes, 0, length, "UTF-8");
  }

  @Override public void close() throws IOException {
    nameValueBlockIn.close();
  }
}
