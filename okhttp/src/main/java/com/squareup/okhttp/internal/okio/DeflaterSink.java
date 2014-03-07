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
package com.squareup.okhttp.internal.okio;

import java.io.IOException;
import java.util.zip.Deflater;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

import static com.squareup.okhttp.internal.okio.Util.checkOffsetAndCount;

/**
 * A sink that uses <a href="http://tools.ietf.org/html/rfc1951">DEFLATE</a> to
 * compress data written to another source.
 *
 * <h3>Sync flush</h3>
 * Aggressive flushing of this stream may result in reduced compression. Each
 * call to {@link #flush} immediately compresses all currently-buffered data;
 * this early compression may be less effective than compression performed
 * without flushing.
 *
 * <p>This is equivalent to using {@link Deflater} with the sync flush option.
 * This class does not offer any partial flush mechanism. For best performance,
 * only call {@link #flush} when application behavior requires it.
 */
public final class DeflaterSink implements Sink {
  private final BufferedSink sink;
  private final Deflater deflater;

  public DeflaterSink(Sink sink, Deflater deflater) {
    this.sink = Okio.buffer(sink);
    this.deflater = deflater;
  }

  @Override public void write(OkBuffer source, long byteCount)
      throws IOException {
    checkOffsetAndCount(source.size, 0, byteCount);
    while (byteCount > 0) {
      // Share bytes from the head segment of 'source' with the deflater.
      Segment head = source.head;
      int toDeflate = (int) Math.min(byteCount, head.limit - head.pos);
      deflater.setInput(head.data, head.pos, toDeflate);

      // Deflate those bytes into sink.
      deflate(false);

      // Mark those bytes as read.
      source.size -= toDeflate;
      head.pos += toDeflate;
      if (head.pos == head.limit) {
        source.head = head.pop();
        SegmentPool.INSTANCE.recycle(head);
      }

      byteCount -= toDeflate;
    }
  }

  @IgnoreJRERequirement
  private void deflate(boolean syncFlush) throws IOException {
    OkBuffer buffer = sink.buffer();
    while (true) {
      Segment s = buffer.writableSegment(1);

      // The 4-parameter overload of deflate() doesn't exist in the RI until
      // Java 1.7, and is public (although with @hide) on Android since 2.3.
      // The @hide tag means that this code won't compile against the Android
      // 2.3 SDK, but it will run fine there.
      int deflated = syncFlush
          ? deflater.deflate(s.data, s.limit, Segment.SIZE - s.limit, Deflater.SYNC_FLUSH)
          : deflater.deflate(s.data, s.limit, Segment.SIZE - s.limit);

      if (deflated == 0) return;
      s.limit += deflated;
      buffer.size += deflated;
      sink.emitCompleteSegments();
    }
  }

  @Override public void flush() throws IOException {
    deflate(true);
    sink.flush();
  }

  @Override public void close() throws IOException {
    deflater.finish();
    deflate(false);
    sink.close();
  }

  @Override public Sink deadline(Deadline deadline) {
    sink.deadline(deadline);
    return this;
  }

  @Override public String toString() {
    return "DeflaterSink(" + sink + ")";
  }
}
