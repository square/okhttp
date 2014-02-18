/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal.bytes;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;

/**
 * A source that keeps a buffer internally so that callers can do small reads
 * without a performance penalty.
 */
public final class BufferedSource implements Source {
  public final OkBuffer buffer;
  public final Source source;
  private boolean closed;

  public BufferedSource(Source source, OkBuffer buffer) {
    this.buffer = buffer;
    this.source = source;
  }

  public BufferedSource(Source source) {
    this(source, new OkBuffer());
  }

  @Override public long read(OkBuffer sink, long byteCount, Deadline deadline)
      throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (closed) throw new IllegalStateException("closed");

    if (buffer.byteCount == 0) {
      long read = source.read(buffer, Segment.SIZE, deadline);
      if (read == -1) return -1;
    }

    long toRead = Math.min(byteCount, buffer.byteCount);
    return buffer.read(sink, toRead, deadline);
  }

  /**
   * Returns true if there are no more bytes in the buffer or the source. This
   * will block until there are bytes to read or the source is definitely
   * exhausted.
   */
  public boolean exhausted(Deadline deadline) throws IOException {
    return buffer.byteCount() == 0 && source.read(buffer, Segment.SIZE, deadline) == -1;
  }

  /**
   * Returns when the buffer contains at least {@code byteCount} bytes. Throws
   * an {@link EOFException} if the source is exhausted before the required
   * bytes can be read.
   */
  void require(long byteCount, Deadline deadline) throws IOException {
    while (buffer.byteCount < byteCount) {
      if (source.read(buffer, Segment.SIZE, deadline) == -1) throw new EOFException();
    }
  }

  public byte readByte() throws IOException {
    require(1, Deadline.NONE);
    return buffer.readByte();
  }

  public ByteString readByteString(int byteCount) throws IOException {
    require(byteCount, Deadline.NONE);
    return buffer.readByteString(byteCount);
  }

  public short readShort() throws IOException {
    require(2, Deadline.NONE);
    return buffer.readShort();
  }

  public int readShortLe() throws IOException {
    require(2, Deadline.NONE);
    return buffer.readShortLe();
  }

  public int readInt() throws IOException {
    require(4, Deadline.NONE);
    return buffer.readInt();
  }

  public int readIntLe() throws IOException {
    require(4, Deadline.NONE);
    return buffer.readIntLe();
  }

  /**
   * Reads and discards {@code byteCount} bytes from {@code source} using {@code
   * buffer} as a buffer. Throws an {@link EOFException} if the source is
   * exhausted before the requested bytes can be skipped.
   */
  public void skip(long byteCount, Deadline deadline) throws IOException {
    while (byteCount > 0) {
      if (buffer.byteCount == 0 && source.read(buffer, Segment.SIZE, deadline) == -1) {
        throw new EOFException();
      }
      long toSkip = Math.min(byteCount, buffer.byteCount());
      buffer.skip(toSkip);
      byteCount -= toSkip;
    }
  }

  /**
   * Returns the index of {@code b} in the buffer, refilling it if necessary
   * until it is found. This reads an unbounded number of bytes into the buffer.
   */
  public long seek(byte b, Deadline deadline) throws IOException {
    long start = 0;
    long index;
    while ((index = buffer.indexOf(b, start)) == -1) {
      start = buffer.byteCount;
      if (source.read(buffer, Segment.SIZE, deadline) == -1) throw new EOFException();
    }
    return index;
  }

  /** Returns an input stream that reads from this source. */
  public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() throws IOException {
        if (buffer.byteCount == 0) {
          long count = source.read(buffer, Segment.SIZE, Deadline.NONE);
          if (count == -1) return -1;
        }
        return buffer.readByte() & 0xff;
      }

      @Override public int read(byte[] data, int offset, int byteCount) throws IOException {
        checkOffsetAndCount(data.length, offset, byteCount);

        if (buffer.byteCount == 0) {
          long count = source.read(buffer, Segment.SIZE, Deadline.NONE);
          if (count == -1) return -1;
        }

        Segment head = buffer.head;
        int toCopy = Math.min(byteCount, head.limit - head.pos);
        System.arraycopy(head.data, head.pos, data, offset, toCopy);

        head.pos += toCopy;
        buffer.byteCount -= toCopy;

        if (head.pos == head.limit) {
          buffer.head = head.pop();
          SegmentPool.INSTANCE.recycle(head);
        }

        return toCopy;
      }

      @Override public int available() throws IOException {
        return (int) Math.min(buffer.byteCount, Integer.MAX_VALUE);
      }

      @Override public void close() throws IOException {
        BufferedSource.this.close(Deadline.NONE);
      }

      @Override public String toString() {
        return BufferedSource.this.toString() + ".inputStream()";
      }
    };
  }

  @Override public void close(Deadline deadline) throws IOException {
    if (closed) return;
    closed = true;
    source.close(deadline);
    buffer.clear();
  }

  @Override public String toString() {
    return "BufferedSource(" + source + ")";
  }
}
