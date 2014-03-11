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
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static okio.Util.UTF_8;
import static okio.Util.checkOffsetAndCount;

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
public final class OkBuffer implements BufferedSource, BufferedSink, Cloneable {
  Segment head;
  long size;

  public OkBuffer() {
  }

  /** Returns the number of bytes currently in this buffer. */
  public long size() {
    return size;
  }

  @Override public OkBuffer buffer() {
    return this;
  }

  @Override public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) {
        writeByte((byte) b);
      }

      @Override public void write(byte[] data, int offset, int byteCount) {
        OkBuffer.this.write(data, offset, byteCount);
      }

      @Override public void flush() {
      }

      @Override public void close() {
      }

      @Override public String toString() {
        return this + ".outputStream()";
      }
    };
  }

  @Override public OkBuffer emitCompleteSegments() {
    return this; // Nowhere to emit to!
  }

  @Override public boolean exhausted() {
    return size == 0;
  }

  @Override public void require(long byteCount) throws EOFException {
    if (this.size < byteCount) throw new EOFException();
  }

  @Override public long seek(byte b) throws EOFException {
    long index = indexOf(b);
    if (index == -1) throw new EOFException();
    return index;
  }

  @Override public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() {
        return readByte() & 0xff;
      }

      @Override public int read(byte[] sink, int offset, int byteCount) {
        return OkBuffer.this.read(sink, offset, byteCount);
      }

      @Override public int available() {
        return (int) Math.min(size, Integer.MAX_VALUE);
      }

      @Override public void close() {
      }

      @Override public String toString() {
        return OkBuffer.this + ".inputStream()";
      }
    };
  }

  /**
   * Returns the number of bytes in segments that are not writable. This is the
   * number of bytes that can be flushed immediately to an underlying sink
   * without harming throughput.
   */
  public long completeSegmentByteCount() {
    long result = size;
    if (result == 0) return 0;

    // Omit the tail if it's still writable.
    Segment tail = head.prev;
    if (tail.limit < Segment.SIZE) {
      result -= tail.limit - tail.pos;
    }

    return result;
  }

  @Override public byte readByte() {
    if (size == 0) throw new IllegalStateException("size == 0");

    Segment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    byte[] data = segment.data;
    byte b = data[pos++];
    size -= 1;

    if (pos == limit) {
      head = segment.pop();
      SegmentPool.INSTANCE.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return b;
  }

  /** Returns the byte at {@code i}. */
  public byte getByte(long i) {
    checkOffsetAndCount(size, i, 1);
    for (Segment s = head; true; s = s.next) {
      int segmentByteCount = s.limit - s.pos;
      if (i < segmentByteCount) return s.data[s.pos + (int) i];
      i -= segmentByteCount;
    }
  }

  @Override public short readShort() {
    if (size < 2) throw new IllegalArgumentException("size < 2: " + size);

    Segment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the short is split across multiple segments, delegate to readByte().
    if (limit - pos < 2) {
      int s = (readByte() & 0xff) << 8
          |   (readByte() & 0xff);
      return (short) s;
    }

    byte[] data = segment.data;
    int s = (data[pos++] & 0xff) << 8
        |   (data[pos++] & 0xff);
    size -= 2;

    if (pos == limit) {
      head = segment.pop();
      SegmentPool.INSTANCE.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return (short) s;
  }

  @Override public int readInt() {
    if (size < 4) throw new IllegalArgumentException("size < 4: " + size);

    Segment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the int is split across multiple segments, delegate to readByte().
    if (limit - pos < 4) {
      return (readByte() & 0xff) << 24
          |  (readByte() & 0xff) << 16
          |  (readByte() & 0xff) << 8
          |  (readByte() & 0xff);
    }

    byte[] data = segment.data;
    int i = (data[pos++] & 0xff) << 24
        |   (data[pos++] & 0xff) << 16
        |   (data[pos++] & 0xff) << 8
        |   (data[pos++] & 0xff);
    size -= 4;

    if (pos == limit) {
      head = segment.pop();
      SegmentPool.INSTANCE.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return i;
  }

  public int readShortLe() {
    return Util.reverseBytesShort(readShort());
  }

  public int readIntLe() {
    return Util.reverseBytesInt(readInt());
  }

  public ByteString readByteString(long byteCount) {
    return new ByteString(readBytes(byteCount));
  }

  public String readUtf8(long byteCount) {
    checkOffsetAndCount(this.size, 0, byteCount);
    if (byteCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("byteCount > Integer.MAX_VALUE: " + byteCount);
    }
    if (byteCount == 0) return "";

    Segment head = this.head;
    if (head.pos + byteCount > head.limit) {
      // If the string spans multiple segments, delegate to readBytes().
      return new String(readBytes(byteCount), Util.UTF_8);
    }

    String result = new String(head.data, head.pos, (int) byteCount, UTF_8);
    head.pos += byteCount;
    this.size -= byteCount;

    if (head.pos == head.limit) {
      this.head = head.pop();
      SegmentPool.INSTANCE.recycle(head);
    }

    return result;
  }

  @Override public String readUtf8Line(boolean throwOnEof) throws EOFException {
    long newline = indexOf((byte) '\n');

    if (newline == -1) {
      if (throwOnEof) throw new EOFException();
      return size != 0 ? readUtf8(size) : null;
    }

    if (newline > 0 && getByte(newline - 1) == '\r') {
      // Read everything until '\r\n', then skip the '\r\n'.
      String result = readUtf8((newline - 1));
      skip(2);
      return result;

    } else {
      // Read everything until '\n', then skip the '\n'.
      String result = readUtf8(newline);
      skip(1);
      return result;
    }
  }

  private byte[] readBytes(long byteCount) {
    checkOffsetAndCount(this.size, 0, byteCount);
    if (byteCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("byteCount > Integer.MAX_VALUE: " + byteCount);
    }

    int offset = 0;
    byte[] result = new byte[(int) byteCount];

    while (offset < byteCount) {
      int toCopy = (int) Math.min(byteCount - offset, head.limit - head.pos);
      System.arraycopy(head.data, head.pos, result, offset, toCopy);

      offset += toCopy;
      head.pos += toCopy;

      if (head.pos == head.limit) {
        Segment toRecycle = head;
        head = toRecycle.pop();
        SegmentPool.INSTANCE.recycle(toRecycle);
      }
    }

    this.size -= byteCount;
    return result;
  }

  /** Like {@link InputStream#read}. */
  int read(byte[] sink, int offset, int byteCount) {
    Segment s = this.head;
    if (s == null) return -1;
    int toCopy = Math.min(byteCount, s.limit - s.pos);
    System.arraycopy(s.data, s.pos, sink, offset, toCopy);

    s.pos += toCopy;
    this.size -= toCopy;

    if (s.pos == s.limit) {
      this.head = s.pop();
      SegmentPool.INSTANCE.recycle(s);
    }

    return toCopy;
  }

  /**
   * Discards all bytes in this buffer. Calling this method when you're done
   * with a buffer will return its segments to the pool.
   */
  public void clear() {
    skip(size);
  }

  /** Discards {@code byteCount} bytes from the head of this buffer. */
  public void skip(long byteCount) {
    checkOffsetAndCount(this.size, 0, byteCount);

    this.size -= byteCount;
    while (byteCount > 0) {
      int toSkip = (int) Math.min(byteCount, head.limit - head.pos);
      byteCount -= toSkip;
      head.pos += toSkip;

      if (head.pos == head.limit) {
        Segment toRecycle = head;
        head = toRecycle.pop();
        SegmentPool.INSTANCE.recycle(toRecycle);
      }
    }
  }

  /** Appends {@code byteString} to this. */
  @Override public OkBuffer write(ByteString byteString) {
    return write(byteString.data, 0, byteString.data.length);
  }

  /** Encodes {@code string} as UTF-8 and appends the bytes to this. */
  @Override public OkBuffer writeUtf8(String string) {
    byte[] data = string.getBytes(Util.UTF_8);
    return write(data, 0, data.length);
  }

  @Override public OkBuffer write(byte[] source) {
    return write(source, 0, source.length);
  }

  @Override public OkBuffer write(byte[] source, int offset, int byteCount) {
    int limit = offset + byteCount;
    while (offset < limit) {
      Segment tail = writableSegment(1);

      int toCopy = Math.min(limit - offset, Segment.SIZE - tail.limit);
      System.arraycopy(source, offset, tail.data, tail.limit, toCopy);

      offset += toCopy;
      tail.limit += toCopy;
    }

    this.size += byteCount;
    return this;
  }

  /** Appends a Big-Endian byte to the end of this buffer. */
  @Override public OkBuffer writeByte(int b) {
    Segment tail = writableSegment(1);
    tail.data[tail.limit++] = (byte) b;
    size += 1;
    return this;
  }

  /** Appends a Big-Endian short to the end of this buffer. */
  @Override public OkBuffer writeShort(int s) {
    Segment tail = writableSegment(2);
    byte[] data = tail.data;
    int limit = tail.limit;
    data[limit++] = (byte) ((s >> 8) & 0xff);
    data[limit++] = (byte)  (s       & 0xff);
    tail.limit = limit;
    size += 2;
    return this;
  }

  /** Appends a Big-Endian int to the end of this buffer. */
  @Override public OkBuffer writeInt(int i) {
    Segment tail = writableSegment(4);
    byte[] data = tail.data;
    int limit = tail.limit;
    data[limit++] = (byte) ((i >> 24) & 0xff);
    data[limit++] = (byte) ((i >> 16) & 0xff);
    data[limit++] = (byte) ((i >>  8) & 0xff);
    data[limit++] = (byte)  (i        & 0xff);
    tail.limit = limit;
    size += 4;
    return this;
  }

  /**
   * Returns a tail segment that we can write at least {@code minimumCapacity}
   * bytes to, creating it if necessary.
   */
  Segment writableSegment(int minimumCapacity) {
    if (minimumCapacity < 1 || minimumCapacity > Segment.SIZE) throw new IllegalArgumentException();

    if (head == null) {
      head = SegmentPool.INSTANCE.take(); // Acquire a first segment.
      return head.next = head.prev = head;
    }

    Segment tail = head.prev;
    if (tail.limit + minimumCapacity > Segment.SIZE) {
      tail = tail.push(SegmentPool.INSTANCE.take()); // Append a new empty segment to fill up.
    }
    return tail;
  }

  @Override public void write(OkBuffer source, long byteCount) {
    // Move bytes from the head of the source buffer to the tail of this buffer
    // while balancing two conflicting goals: don't waste CPU and don't waste
    // memory.
    //
    //
    // Don't waste CPU (ie. don't copy data around).
    //
    // Copying large amounts of data is expensive. Instead, we prefer to
    // reassign entire segments from one OkBuffer to the other.
    //
    //
    // Don't waste memory.
    //
    // As an invariant, adjacent pairs of segments in an OkBuffer should be at
    // least 50% full, except for the head segment and the tail segment.
    //
    // The head segment cannot maintain the invariant because the application is
    // consuming bytes from this segment, decreasing its level.
    //
    // The tail segment cannot maintain the invariant because the application is
    // producing bytes, which may require new nearly-empty tail segments to be
    // appended.
    //
    //
    // Moving segments between buffers
    //
    // When writing one buffer to another, we prefer to reassign entire segments
    // over copying bytes into their most compact form. Suppose we have a buffer
    // with these segment levels [91%, 61%]. If we append a buffer with a
    // single [72%] segment, that yields [91%, 61%, 72%]. No bytes are copied.
    //
    // Or suppose we have a buffer with these segment levels: [100%, 2%], and we
    // want to append it to a buffer with these segment levels [99%, 3%]. This
    // operation will yield the following segments: [100%, 2%, 99%, 3%]. That
    // is, we do not spend time copying bytes around to achieve more efficient
    // memory use like [100%, 100%, 4%].
    //
    // When combining buffers, we will compact adjacent buffers when their
    // combined level doesn't exceed 100%. For example, when we start with
    // [100%, 40%] and append [30%, 80%], the result is [100%, 70%, 80%].
    //
    //
    // Splitting segments
    //
    // Occasionally we write only part of a source buffer to a sink buffer. For
    // example, given a sink [51%, 91%], we may want to write the first 30% of
    // a source [92%, 82%] to it. To simplify, we first transform the source to
    // an equivalent buffer [30%, 62%, 82%] and then move the head segment,
    // yielding sink [51%, 91%, 30%] and source [62%, 82%].

    if (source == this) {
      throw new IllegalArgumentException("source == this");
    }
    checkOffsetAndCount(source.size, 0, byteCount);

    while (byteCount > 0) {
      // Is a prefix of the source's head segment all that we need to move?
      if (byteCount < (source.head.limit - source.head.pos)) {
        Segment tail = head != null ? head.prev : null;
        if (tail == null || byteCount + (tail.limit - tail.pos) > Segment.SIZE) {
          // We're going to need another segment. Split the source's head
          // segment in two, then move the first of those two to this buffer.
          source.head = source.head.split((int) byteCount);
        } else {
          // Our existing segments are sufficient. Move bytes from source's head to our tail.
          source.head.writeTo(tail, (int) byteCount);
          source.size -= byteCount;
          this.size += byteCount;
          return;
        }
      }

      // Remove the source's head segment and append it to our tail.
      Segment segmentToMove = source.head;
      long movedByteCount = segmentToMove.limit - segmentToMove.pos;
      source.head = segmentToMove.pop();
      if (head == null) {
        head = segmentToMove;
        head.next = head.prev = head;
      } else {
        Segment tail = head.prev;
        tail = tail.push(segmentToMove);
        tail.compact();
      }
      source.size -= movedByteCount;
      this.size += movedByteCount;
      byteCount -= movedByteCount;
    }
  }

  @Override public long read(OkBuffer sink, long byteCount) {
    if (this.size == 0) return -1L;
    if (byteCount > this.size) byteCount = this.size;
    sink.write(this, byteCount);
    return byteCount;
  }

  @Override public OkBuffer deadline(Deadline deadline) {
    // All operations are in memory so this class doesn't need to honor deadlines.
    return this;
  }

  /**
   * Returns the index of {@code b} in this, or -1 if this buffer does not
   * contain {@code b}.
   */
  public long indexOf(byte b) {
    return indexOf(b, 0);
  }

  /**
   * Returns the index of {@code b} in this at or beyond {@code fromIndex}, or
   * -1 if this buffer does not contain {@code b} in that range.
   */
  public long indexOf(byte b, long fromIndex) {
    Segment s = head;
    if (s == null) return -1L;
    long offset = 0L;
    do {
      int segmentByteCount = s.limit - s.pos;
      if (fromIndex > segmentByteCount) {
        fromIndex -= segmentByteCount;
      } else {
        byte[] data = s.data;
        for (long pos = s.pos + fromIndex, limit = s.limit; pos < limit; pos++) {
          if (data[(int) pos] == b) return offset + pos - s.pos;
        }
        fromIndex = 0;
      }
      offset += segmentByteCount;
      s = s.next;
    } while (s != head);
    return -1L;
  }

  @Override public void flush() {
  }

  @Override public void close() {
  }

  /** For testing. This returns the sizes of the segments in this buffer. */
  List<Integer> segmentSizes() {
    if (head == null) return Collections.emptyList();
    List<Integer> result = new ArrayList<Integer>();
    result.add(head.limit - head.pos);
    for (Segment s = head.next; s != head; s = s.next) {
      result.add(s.limit - s.pos);
    }
    return result;
  }

  @Override public boolean equals(Object o) {
    if (!(o instanceof OkBuffer)) return false;
    OkBuffer that = (OkBuffer) o;
    if (size != that.size) return false;
    if (size == 0) return true; // Both buffers are empty.

    Segment sa = this.head;
    Segment sb = that.head;
    int posA = sa.pos;
    int posB = sb.pos;

    for (long pos = 0, count; pos < size; pos += count) {
      count = Math.min(sa.limit - posA, sb.limit - posB);

      for (int i = 0; i < count; i++) {
        if (sa.data[posA++] != sb.data[posB++]) return false;
      }

      if (posA == sa.limit) {
        sa = sa.next;
        posA = sa.pos;
      }

      if (posB == sb.limit) {
        sb = sb.next;
        posB = sb.pos;
      }
    }

    return true;
  }

  @Override public int hashCode() {
    Segment s = head;
    if (s == null) return 0;
    int result = 1;
    do {
      for (int pos = s.pos, limit = s.limit; pos < limit; pos++) {
        result = 31 * result + s.data[pos];
      }
      s = s.next;
    } while (s != head);
    return result;
  }

  @Override public String toString() {
    if (size == 0) {
      return "OkBuffer[size=0]";
    }

    if (size <= 16) {
      ByteString data = clone().readByteString(size);
      return String.format("OkBuffer[size=%s data=%s]", size, data.hex());
    }

    try {
      MessageDigest md5 = MessageDigest.getInstance("MD5");
      md5.update(head.data, head.pos, head.limit - head.pos);
      for (Segment s = head.next; s != head; s = s.next) {
        md5.update(s.data, s.pos, s.limit - s.pos);
      }
      return String.format("OkBuffer[size=%s md5=%s]",
          size, ByteString.of(md5.digest()).hex());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError();
    }
  }

  /** Returns a deep copy of this buffer. */
  @Override public OkBuffer clone() {
    OkBuffer result = new OkBuffer();
    if (size() == 0) return result;

    result.write(head.data, head.pos, head.limit - head.pos);
    for (Segment s = head.next; s != head; s = s.next) {
      result.write(s.data, s.pos, s.limit - s.pos);
    }

    return result;
  }
}
