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

import com.squareup.okhttp.internal.Util;
import java.io.IOException;

/**
 * A collection of bytes in memory.
 *
 * <p><strong>Moving data from one OkBuffer to another is fast.</strong> Instead
 * of copying bytes from one place in memory to another, this class just changes
 * ownership of the underlying bytes.
 *
 * <p><strong>This buffer grows with your data.</strong> Just like ArrayList,
 * each OkBuffer starts small. It consumes only the memory it needs to.
 *
 * <p><strong>This buffer pools its byte arrays.</strong> When you allocate a
 * byte array in Java, the runtime must zero-fill the requested array before
 * returning it to you. Even if you're going to write over that space anyway.
 * This class avoids zero-fill and GC churn by pooling byte arrays.
 */
public final class OkBuffer implements Source, Sink {
  private static final char[] HEX_DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

  private Segment segment;
  private long byteCount;

  public OkBuffer() {
  }

  /** Returns the number of bytes currently in this buffer. */
  public long byteCount() {
    return byteCount;
  }

  /** Removes {@code byteCount} bytes from this and returns them as a byte string. */
  public ByteString readByteString(int byteCount) {
    return new ByteString(readBytes(byteCount));
  }

  /** Removes {@code byteCount} bytes from this, decodes them as UTF-8 and returns the string. */
  public String readUtf8(int byteCount) {
    return new String(readBytes(byteCount), Util.UTF_8);
  }

  private byte[] readBytes(int byteCount) {
    if (byteCount > this.byteCount) {
      throw new IllegalArgumentException(
          String.format("requested %s > available %s", byteCount, this.byteCount));
    }

    int offset = 0;
    byte[] result = new byte[byteCount];

    while (offset < byteCount) {
      int toCopy = Math.min(byteCount - offset, segment.limit - segment.pos);
      System.arraycopy(segment.data, segment.pos, result, offset, toCopy);

      offset += toCopy;
      segment.pos += toCopy;

      if (segment.pos == segment.limit) {
        segment = segment.pop(); // Recycle this empty segment.
      }
    }

    this.byteCount -= byteCount;
    return result;
  }

  /** Appends {@code byteString} to this. */
  public void write(ByteString byteString) {
    write(byteString.data);
  }

  /** Encodes {@code string} as UTF-8 and appends the bytes to this. */
  public void writeUtf8(String string) {
    write(string.getBytes(Util.UTF_8));
  }

  private void write(byte[] data) {
    int offset = 0;
    while (offset < data.length) {
      if (segment == null) {
        segment = SegmentPool.INSTANCE.take(); // Acquire a first segment.
        segment.next = segment.prev = segment;
      }

      Segment tail = segment.prev;
      if (tail.limit == Segment.SIZE) {
        tail = tail.push(); // Acquire a new empty segment.
      }

      int toCopy = Math.min(data.length - offset, Segment.SIZE - tail.limit);
      System.arraycopy(data, offset, tail.data, tail.limit, toCopy);

      offset += toCopy;
      tail.limit += toCopy;
    }

    this.byteCount += data.length;
  }

  @Override public void write(OkBuffer source, long byteCount, Timeout timeout) {
    throw new UnsupportedOperationException();
  }

  @Override public long read(OkBuffer sink, long byteCount, Timeout timeout) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public long indexOf(byte b, Timeout timeout) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flush(Timeout timeout) {
    throw new UnsupportedOperationException();
  }

  @Override public void close(Timeout timeout) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the contents of this buffer in hex. For buffers larger than 1 MiB
   * this method is undefined.
   */
  @Override public String toString() {
    if (byteCount > 0x100000) return super.toString();
    char[] result = new char[(int) (byteCount * 2)];
    int offset = 0;
    for (Segment s = segment; offset < byteCount; s = s.next) {
      for (int i = s.pos; i < s.limit; i++) {
        result[offset++] = HEX_DIGITS[(s.data[i] >> 4) & 0xf];
        result[offset++] = HEX_DIGITS[s.data[i] & 0xf];
      }
      offset += s.limit - s.pos;
    }
    return new String(result);
  }
}
