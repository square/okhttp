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

  @Override public long read(OkBuffer sink, long byteCount)
      throws IOException {
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
    return buffer.exhausted() && source.read(buffer, Segment.SIZE) == -1;
  }

  @Override public void require(long byteCount) throws IOException {
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

  @Override public String readUtf8Line(boolean throwOnEof) throws IOException {
    long start = 0;
    long newline;
    while ((newline = buffer.indexOf((byte) '\n', start)) == -1) {
      start = buffer.size;
      if (source.read(buffer, Segment.SIZE) == -1) {
        if (throwOnEof) throw new EOFException();
        return buffer.size != 0 ? readUtf8(buffer.size) : null;
      }
    }

    if (newline > 0 && buffer.getByte(newline - 1) == '\r') {
      // Read everything until '\r\n', then skip the '\r\n'.
      String result = readUtf8((newline - 1));
      skip(2);
      return result;

    } else {
      // Read everything until '\n', then skip the '\n'.
      String result = readUtf8((newline));
      skip(1);
      return result;
    }
  }

  @Override public short readShort() throws IOException {
    require(2);
    return buffer.readShort();
  }

  @Override public int readShortLe() throws IOException {
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

  @Override public void skip(long byteCount) throws IOException {
    while (byteCount > 0) {
      if (buffer.size == 0 && source.read(buffer, Segment.SIZE) == -1) {
        throw new EOFException();
      }
      long toSkip = Math.min(byteCount, buffer.size());
      buffer.skip(toSkip);
      byteCount -= toSkip;
    }
  }

  @Override public long seek(byte b) throws IOException {
    long start = 0;
    long index;
    while ((index = buffer.indexOf(b, start)) == -1) {
      start = buffer.size;
      if (source.read(buffer, Segment.SIZE) == -1) throw new EOFException();
    }
    return index;
  }

  @Override public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() throws IOException {
        if (buffer.size == 0) {
          long count = source.read(buffer, Segment.SIZE);
          if (count == -1) return -1;
        }
        return buffer.readByte() & 0xff;
      }

      @Override public int read(byte[] data, int offset, int byteCount) throws IOException {
        checkOffsetAndCount(data.length, offset, byteCount);

        if (buffer.size == 0) {
          long count = source.read(buffer, Segment.SIZE);
          if (count == -1) return -1;
        }

        return buffer.read(data, offset, byteCount);
      }

      @Override public int available() throws IOException {
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
