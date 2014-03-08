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
package okio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static okio.Util.checkOffsetAndCount;

final class RealBufferedSource implements BufferedSource {
  public final OkBuffer buffer;
  public final Source source;
  private boolean closed;

  public RealBufferedSource(Source source, OkBuffer buffer) {
    if (source == null) throw new IllegalArgumentException("source == null");
    this.buffer = buffer;
    this.source = source;
  }

  public RealBufferedSource(Source source) {
    this(source, new OkBuffer());
  }

  @Override public OkBuffer buffer() {
    return buffer;
  }

  @Override public long read(OkBuffer sink, long byteCount) throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (closed) throw new IllegalStateException("closed");

    if (buffer.size == 0) {
      long read = source.read(buffer, Segment.SIZE);
      if (read == -1) return -1;
    }

    long toRead = Math.min(byteCount, buffer.size);
    return buffer.read(sink, toRead);
  }

  @Override public boolean exhausted() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    return buffer.exhausted() && source.read(buffer, Segment.SIZE) == -1;
  }

  @Override public void require(long byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    while (buffer.size < byteCount) {
      if (source.read(buffer, Segment.SIZE) == -1) throw new EOFException();
    }
  }

  @Override public byte readByte() throws IOException {
    require(1);
    return buffer.readByte();
  }

  @Override public ByteString readByteString(long byteCount) throws IOException {
    require(byteCount);
    return buffer.readByteString(byteCount);
  }

  @Override public String readUtf8(long byteCount) throws IOException {
    require(byteCount);
    return buffer.readUtf8(byteCount);
  }

  @Override public String readUtf8Line() throws IOException {
    long newline = indexOf((byte) '\n');

    if (newline == -1) {
      return buffer.size != 0 ? readUtf8(buffer.size) : null;
    }

    return buffer.readUtf8Line(newline);
  }

  @Override public String readUtf8LineStrict() throws IOException {
    long newline = indexOf((byte) '\n');
    if (newline == -1L) throw new EOFException();
    return buffer.readUtf8Line(newline);
  }

  @Override public short readShort() throws IOException {
    require(2);
    return buffer.readShort();
  }

  @Override public short readShortLe() throws IOException {
    require(2);
    return buffer.readShortLe();
  }

  @Override public int readInt() throws IOException {
    require(4);
    return buffer.readInt();
  }

  @Override public int readIntLe() throws IOException {
    require(4);
    return buffer.readIntLe();
  }

  @Override public long readLong() throws IOException {
    require(8);
    return buffer.readLong();
  }

  @Override public long readLongLe() throws IOException {
    require(8);
    return buffer.readLongLe();
  }

  @Override public void skip(long byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    while (byteCount > 0) {
      if (buffer.size == 0 && source.read(buffer, Segment.SIZE) == -1) {
        throw new EOFException();
      }
      long toSkip = Math.min(byteCount, buffer.size());
      buffer.skip(toSkip);
      byteCount -= toSkip;
    }
  }

  @Override public long indexOf(byte b) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long start = 0;
    long index;
    while ((index = buffer.indexOf(b, start)) == -1) {
      start = buffer.size;
      if (source.read(buffer, Segment.SIZE) == -1) return -1L;
    }
    return index;
  }

  @Override public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() throws IOException {
        if (closed) throw new IOException("closed");
        if (buffer.size == 0) {
          long count = source.read(buffer, Segment.SIZE);
          if (count == -1) return -1;
        }
        return buffer.readByte() & 0xff;
      }

      @Override public int read(byte[] data, int offset, int byteCount) throws IOException {
        if (closed) throw new IOException("closed");
        checkOffsetAndCount(data.length, offset, byteCount);

        if (buffer.size == 0) {
          long count = source.read(buffer, Segment.SIZE);
          if (count == -1) return -1;
        }

        return buffer.read(data, offset, byteCount);
      }

      @Override public int available() throws IOException {
        if (closed) throw new IOException("closed");
        return (int) Math.min(buffer.size, Integer.MAX_VALUE);
      }

      @Override public void close() throws IOException {
        RealBufferedSource.this.close();
      }

      @Override public String toString() {
        return RealBufferedSource.this + ".inputStream()";
      }
    };
  }

  @Override public Source deadline(Deadline deadline) {
    source.deadline(deadline);
    return this;
  }

  @Override public void close() throws IOException {
    if (closed) return;
    closed = true;
    source.close();
    buffer.clear();
  }

  @Override public String toString() {
    return "buffer(" + source + ")";
  }
}
