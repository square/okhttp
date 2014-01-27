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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

  private Segment head;
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
      int toCopy = Math.min(byteCount - offset, head.limit - head.pos);
      System.arraycopy(head.data, head.pos, result, offset, toCopy);

      offset += toCopy;
      head.pos += toCopy;

      if (head.pos == head.limit) {
        Segment toRecycle = head;
        head = toRecycle.pop();
        SegmentPool.INSTANCE.recycle(toRecycle);
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
      if (head == null) {
        head = SegmentPool.INSTANCE.take(); // Acquire a first segment.
        head.next = head.prev = head;
      }

      Segment tail = head.prev;
      if (tail.limit == Segment.SIZE) {
        tail = tail.push(SegmentPool.INSTANCE.take()); // Append a new empty segment to fill up.
      }

      int toCopy = Math.min(data.length - offset, Segment.SIZE - tail.limit);
      System.arraycopy(data, offset, tail.data, tail.limit, toCopy);

      offset += toCopy;
      tail.limit += toCopy;
    }

    this.byteCount += data.length;
  }

  @Override public void write(OkBuffer source, long byteCount, Timeout timeout) {
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
    // combined level is less than 100%. For example, when we start with [100%,
    // 40%] and append [30%, 80%], the result is [100%, 70%, 80%].
    //
    //
    // Splitting segments
    //
    // Occasionally we write only part of a source buffer to a sink buffer. For
    // example, given a sink [51%, 91%], we may want to write the first 30% of
    // a source [92%, 82%] to it. To simplify, we first transform the source to
    // an equivalent buffer [30%, 62%, 82%] and then move the head segment,
    // yielding sink [51%, 91%, 30%] and source [62%, 82%].

    if (source == this) throw new IllegalArgumentException("source == this");
    if (byteCount > source.byteCount) {
      throw new IllegalArgumentException(
          String.format("requested %s > available %s", byteCount, this.byteCount));
    }

    while (byteCount > 0) {
      // Is a prefix of the source's head segment all that we need to move?
      if (byteCount < (source.head.limit - source.head.pos)) {
        Segment tail = head.prev;
        if (head == null || byteCount + (tail.limit - tail.pos) > Segment.SIZE) {
          // We're going to need another segment. Split the source's head
          // segment in two, then move the first of those two to this buffer.
          source.head = source.head.split((int) byteCount);
        } else {
          // Our existing segments are sufficient. Move bytes from source's head to our tail.
          source.head.writeTo(tail, (int) byteCount);
          source.byteCount -= byteCount;
          this.byteCount += byteCount;
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
      source.byteCount -= movedByteCount;
      this.byteCount += movedByteCount;
      byteCount -= movedByteCount;
    }
  }

  @Override public long read(OkBuffer sink, long byteCount, Timeout timeout) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public long indexOf(byte b, Timeout timeout) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flush(Timeout timeout) {
    throw new UnsupportedOperationException("Cannot flush() an OkBuffer");
  }

  @Override public void close(Timeout timeout) {
    throw new UnsupportedOperationException("Cannot close() an OkBuffer");
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

  /**
   * Returns the contents of this buffer in hex. For buffers larger than 1 MiB
   * this method is undefined.
   */
  @Override public String toString() {
    if (byteCount > 0x100000) return super.toString();
    char[] result = new char[(int) (byteCount * 2)];
    int offset = 0;
    for (Segment s = head; offset < byteCount; s = s.next) {
      for (int i = s.pos; i < s.limit; i++) {
        result[offset++] = HEX_DIGITS[(s.data[i] >> 4) & 0xf];
        result[offset++] = HEX_DIGITS[s.data[i] & 0xf];
      }
      offset += s.limit - s.pos;
    }
    return new String(result);
  }
}
