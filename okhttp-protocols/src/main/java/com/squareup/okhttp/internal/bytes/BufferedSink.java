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

import java.io.IOException;
import java.io.OutputStream;

/**
 * A sink that keeps a buffer internally so that callers can do small writes
 * without a performance penalty.
 */
public final class BufferedSink implements Sink {
  public final OkBuffer buffer;
  public final Sink sink;
  private boolean closed;

  public BufferedSink(Sink sink, OkBuffer buffer) {
    this.buffer = buffer;
    this.sink = sink;
  }

  public BufferedSink(Sink sink) {
    this(sink, new OkBuffer());
  }

  @Override public void write(OkBuffer source, long byteCount, Deadline deadline)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, byteCount, deadline);
    emitCompleteSegments(deadline);
  }

  public void write(ByteString byteString, Deadline deadline) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(byteString);
    emitCompleteSegments(deadline);
  }

  public void writeUtf8(String string, Deadline deadline) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string);
    emitCompleteSegments(deadline);
  }

  public void writeByte(int b, Deadline deadline) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeByte(b);
    emitCompleteSegments(deadline);
  }

  public void writeShort(int s, Deadline deadline) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeShort(s);
    emitCompleteSegments(deadline);
  }

  public void writeInt(int i, Deadline deadline) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeInt(i);
    emitCompleteSegments(deadline);
  }

  void emitCompleteSegments(Deadline deadline) throws IOException {
    long byteCount = buffer.byteCount;
    if (byteCount == 0) return;

    // Omit the tail if it's still writable.
    Segment tail = buffer.head.prev;
    if (tail.limit < Segment.SIZE) {
      byteCount -= tail.limit - tail.pos;
      if (byteCount == 0) return;
    }

    sink.write(buffer, byteCount, deadline);
  }

  /** Returns an output stream that writes to this sink. */
  public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        buffer.writeByte((byte) b);
        emitCompleteSegments(Deadline.NONE);
      }

      @Override public void write(byte[] data, int offset, int byteCount) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        buffer.write(data, offset, byteCount);
        emitCompleteSegments(Deadline.NONE);
      }

      @Override public void flush() throws IOException {
        BufferedSink.this.flush(Deadline.NONE);
      }

      @Override public void close() throws IOException {
        BufferedSink.this.close(Deadline.NONE);
      }

      @Override public String toString() {
        return "outputStream(" + sink + ")";
      }
    };
  }

  @Override public void flush(Deadline deadline) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    if (buffer.byteCount > 0) {
      sink.write(buffer, buffer.byteCount, deadline);
    }
  }

  @Override public void close(Deadline deadline) throws IOException {
    flush(deadline);
    sink.close(deadline);
    closed = true;
  }

  @Override public String toString() {
    return "BufferedSink(" + sink + ")";
  }
}
