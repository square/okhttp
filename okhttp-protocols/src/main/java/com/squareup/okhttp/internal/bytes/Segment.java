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

  final byte[] data;
  int pos;
  int limit;

  /** Next segment in a linked list. */
  Segment next;

  /** Previous segment in a linked list. */
  Segment prev;

  Segment() {
    data = new byte[SIZE];
  }

  /**
   * Removes this head of a circularly-linked list, recycles it, and returns the
   * new head of the list. Returns null if the list is now empty.
   */
  public Segment pop() {
    Segment result = next != this ? next : null;
    prev.next = next;
    next.prev = prev;
    next = null;
    prev = null;
    SegmentPool.INSTANCE.recycle(this);
    return result;
  }

  /**
   * Acquires a segment and appends it to this tail of a circularly-linked list.
   * Returns the new tail segment.
   */
  public Segment push() {
    Segment result = SegmentPool.INSTANCE.take();
    result.prev = this;
    result.next = next;
    next.prev = result;
    next = result;
    return result;
  }
}
