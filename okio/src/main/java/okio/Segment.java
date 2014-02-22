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

/**
 * A segment of an OkBuffer.
 *
 * <p>Each segment in an OkBuffer is a circularly-linked list node referencing
 * the following and preceding segments in the buffer.
 *
 * <p>Each segment in the pool is a singly-linked list node referencing the rest
 * of segments in the pool.
 */
final class Segment {
  /** The size of all segments in bytes. */
  // TODO: Using fixed-size segments makes pooling easier. But it harms memory
  //       efficiency and encourages copying. Try variable sized segments?
  // TODO: Is 2 KiB a good default segment size?
  static final int SIZE = 2048;

  final byte[] data = new byte[SIZE];

  /** The next byte of application data byte to read in this segment. */
  int pos;

  /** The first byte of available data ready to be written to. */
  int limit;

  /** Next segment in a linked or circularly-linked list. */
  Segment next;

  /** Previous segment in a circularly-linked list. */
  Segment prev;

  /**
   * Removes this segment of a circularly-linked list and returns its successor.
   * Returns null if the list is now empty.
   */
  public Segment pop() {
    Segment result = next != this ? next : null;
    prev.next = next;
    next.prev = prev;
    next = null;
    prev = null;
    return result;
  }

  /**
   * Appends {@code segment} after this segment in the circularly-linked list.
   * Returns the pushed segment.
   */
  public Segment push(Segment segment) {
    segment.prev = this;
    segment.next = next;
    next.prev = segment;
    next = segment;
    return segment;
  }

  /**
   * Splits this head of a circularly-linked list into two segments. The first
   * segment contains the data in {@code [pos..pos+byteCount)}. The second
   * segment contains the data in {@code [pos+byteCount..limit)}. This can be
   * useful when moving partial segments from one OkBuffer to another.
   *
   * <p>Returns the new head of the circularly-linked list.
   */
  public Segment split(int byteCount) {
    int aSize = byteCount;
    int bSize = (limit - pos) - byteCount;
    if (aSize <= 0 || bSize <= 0) throw new IllegalArgumentException();

    // Which side of the split is larger? We want to copy as few bytes as possible.
    if (aSize < bSize) {
      // Create a segment of size 'aSize' before this segment.
      Segment before = SegmentPool.INSTANCE.take();
      System.arraycopy(data, pos, before.data, before.pos, aSize);
      pos += aSize;
      before.limit += aSize;
      prev.push(before);
      return before;
    } else {
      // Create a new segment of size 'bSize' after this segment.
      Segment after = SegmentPool.INSTANCE.take();
      System.arraycopy(data, pos + aSize, after.data, after.pos, bSize);
      limit -= bSize;
      after.limit += bSize;
      push(after);
      return this;
    }
  }

  /**
   * Call this when the tail and its predecessor may both be less than half
   * full. This will copy data so that segments can be recycled.
   */
  public void compact() {
    if (prev == this) throw new IllegalStateException();
    if ((prev.limit - prev.pos) + (limit - pos) > SIZE) return; // Cannot compact.
    writeTo(prev, limit - pos);
    pop();
    SegmentPool.INSTANCE.recycle(this);
  }

  /** Moves {@code byteCount} bytes from {@code sink} to this segment. */
  // TODO: if sink has fewer bytes than this, it may be cheaper to reverse the
  //       direction of the copy and swap the segments!
  public void writeTo(Segment sink, int byteCount) {
    if (byteCount + (sink.limit - sink.pos) > SIZE) throw new IllegalArgumentException();

    if (sink.limit + byteCount > SIZE) {
      // We can't fit byteCount bytes at the sink's current position. Compact sink first.
      System.arraycopy(sink.data, sink.pos, sink.data, 0, sink.limit - sink.pos);
      sink.limit -= sink.pos;
      sink.pos = 0;
    }

    System.arraycopy(data, pos, sink.data, sink.limit, byteCount);
    sink.limit += byteCount;
    pos += byteCount;
  }
}
