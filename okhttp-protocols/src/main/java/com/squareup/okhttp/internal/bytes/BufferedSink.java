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

  @Override public void write(OkBuffer source, long byteCount)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, byteCount);
    emitCompleteSegments();
  }

  public void write(ByteString byteString) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(byteString);
    emitCompleteSegments();
  }

  public void writeUtf8(String string) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string);
    emitCompleteSegments();
  }

  public void write(byte[] data, int offset, int byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(data, offset, byteCount);
    emitCompleteSegments();
  }

  public void writeByte(int b) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeByte(b);
    emitCompleteSegments();
  }

  public void writeShort(int s) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeShort(s);
    emitCompleteSegments();
  }

  public void writeInt(int i) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeInt(i);
    emitCompleteSegments();
  }

  void emitCompleteSegments() throws IOException {
    long byteCount = buffer.completeSegmentByteCount();
    if (byteCount == 0) return;
    sink.write(buffer, byteCount);
  }

  /** Returns an output stream that writes to this sink. */
  public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        buffer.writeByte((byte) b);
        emitCompleteSegments();
      }

      @Override public void write(byte[] data, int offset, int byteCount) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        buffer.write(data, offset, byteCount);
        emitCompleteSegments();
      }

      @Override public void flush() throws IOException {
        BufferedSink.this.flush();
      }

      @Override public void close() throws IOException {
        BufferedSink.this.close();
      }

      @Override public String toString() {
        return "outputStream(" + sink + ")";
      }
    };
  }

  @Override public void flush() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    if (buffer.byteCount > 0) {
      sink.write(buffer, buffer.byteCount);
    }
    sink.flush();
  }

  @Override public void close() throws IOException {
    flush();
    sink.close();
    closed = true;
  }

  @Override public Sink deadline(Deadline deadline) {
    sink.deadline(deadline);
    return this;
  }

  @Override public String toString() {
    return "BufferedSink(" + sink + ")";
  }
}
