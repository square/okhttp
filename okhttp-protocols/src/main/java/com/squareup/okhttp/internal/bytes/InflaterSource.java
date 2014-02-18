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
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** A source that inflates another source. */
public final class InflaterSource implements Source {
  private final BufferedSource source;
  private final Inflater inflater;

  /**
   * When we call Inflater.setInput(), the inflater keeps our byte array until
   * it needs input again. This tracks how many bytes the inflater is currently
   * holding on to.
   */
  private int bufferBytesHeldByInflater;
  private boolean closed;

  public InflaterSource(Source source, Inflater inflater) {
    this(new BufferedSource(source), inflater);
  }

  /**
   * This package-private constructor shares a buffer with its trusted caller.
   * In general we can't share a BufferedSource because the inflater holds input
   * bytes until they are inflated.
   */
  InflaterSource(BufferedSource source, Inflater inflater) {
    if (source == null) throw new IllegalArgumentException("source == null");
    if (inflater == null) throw new IllegalArgumentException("inflater == null");
    this.source = source;
    this.inflater = inflater;
  }

  @Override public long read(
      OkBuffer sink, long byteCount, Deadline deadline) throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (closed) throw new IllegalStateException("closed");
    if (byteCount == 0) return 0;

    while (true) {
      boolean sourceExhausted = refill(deadline);

      // Decompress the inflater's compressed data into the sink.
      try {
        Segment tail = sink.writableSegment(1);
        int bytesInflated = inflater.inflate(tail.data, tail.limit, Segment.SIZE - tail.limit);
        if (bytesInflated > 0) {
          tail.limit += bytesInflated;
          sink.byteCount += bytesInflated;
          return bytesInflated;
        }
        if (inflater.finished() || inflater.needsDictionary()) {
          releaseInflatedBytes();
          return -1;
        }
        if (sourceExhausted) throw new EOFException("source exhausted prematurely");
      } catch (DataFormatException e) {
        throw new IOException(e);
      }
    }
  }

  /**
   * Refills the inflater with compressed data if it needs input. (And only if
   * it needs input). Returns true if the inflater required input but the source
   * was exhausted.
   */
  public boolean refill(Deadline deadline) throws IOException {
    if (!inflater.needsInput()) return false;

    releaseInflatedBytes();
    if (inflater.getRemaining() != 0) throw new IllegalStateException("?"); // TODO: possible?

    // If there are compressed bytes in the source, assign them to the inflater.
    if (source.exhausted(deadline)) return true;

    // Assign buffer bytes to the inflater.
    Segment head = source.buffer.head;
    bufferBytesHeldByInflater = head.limit - head.pos;
    inflater.setInput(head.data, head.pos, bufferBytesHeldByInflater);
    return false;
  }

  /** When the inflater has processed compressed data, remove it from the buffer. */
  private void releaseInflatedBytes() {
    if (bufferBytesHeldByInflater == 0) return;
    int toRelease = bufferBytesHeldByInflater - inflater.getRemaining();
    bufferBytesHeldByInflater -= toRelease;
    source.buffer.skip(toRelease);
  }

  @Override public void close(Deadline deadline) throws IOException {
    if (closed) return;
    inflater.end();
    closed = true;
    source.close(deadline);
  }
}
