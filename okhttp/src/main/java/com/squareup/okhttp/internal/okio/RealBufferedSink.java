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
import java.io.OutputStream;

final class RealBufferedSink implements BufferedSink {
  public final OkBuffer buffer;
  public final Sink sink;
  private boolean closed;

  public RealBufferedSink(Sink sink, OkBuffer buffer) {
    if (sink == null) throw new IllegalArgumentException("sink == null");
    this.buffer = buffer;
    this.sink = sink;
  }

  public RealBufferedSink(Sink sink) {
    this(sink, new OkBuffer());
  }

  @Override public OkBuffer buffer() {
    return buffer;
  }

  @Override public void write(OkBuffer source, long byteCount)
      throws IOException {
    checkNotClosed();
    buffer.write(source, byteCount);
    emitCompleteSegments();
  }

  @Override public BufferedSink write(ByteString byteString) throws IOException {
    checkNotClosed();
    buffer.write(byteString);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeUtf8(String string) throws IOException {
    checkNotClosed();
    buffer.writeUtf8(string);
    return emitCompleteSegments();
  }

  @Override public BufferedSink write(byte[] source) throws IOException {
    checkNotClosed();
    buffer.write(source);
    return emitCompleteSegments();
  }

  @Override public BufferedSink write(byte[] source, int offset, int byteCount) throws IOException {
    checkNotClosed();
    buffer.write(source, offset, byteCount);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeByte(int b) throws IOException {
    checkNotClosed();
    buffer.writeByte(b);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeShort(int s) throws IOException {
    checkNotClosed();
    buffer.writeShort(s);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeInt(int i) throws IOException {
    checkNotClosed();
    buffer.writeInt(i);
    return emitCompleteSegments();
  }

  @Override public BufferedSink emitCompleteSegments() throws IOException {
    checkNotClosed();
    long byteCount = buffer.completeSegmentByteCount();
    if (byteCount > 0) sink.write(buffer, byteCount);
    return this;
  }

  @Override public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) throws IOException {
        checkNotClosed();
        buffer.writeByte((byte) b);
        emitCompleteSegments();
      }

      @Override public void write(byte[] data, int offset, int byteCount) throws IOException {
        checkNotClosed();
        buffer.write(data, offset, byteCount);
        emitCompleteSegments();
      }

      @Override public void flush() throws IOException {
        // For backwards compatibility, a flush() on a closed stream is a no-op.
        if (!RealBufferedSink.this.closed) {
          RealBufferedSink.this.flush();
        }
      }

      @Override public void close() throws IOException {
        RealBufferedSink.this.close();
      }

      @Override public String toString() {
        return RealBufferedSink.this + ".outputStream()";
      }

      private void checkNotClosed() throws IOException {
        // By convention in java.io, IOException and not IllegalStateException is used.
        if (RealBufferedSink.this.closed) {
          throw new IOException("closed");
        }
      }
    };
  }

  @Override public void flush() throws IOException {
    checkNotClosed();
    if (buffer.size > 0) {
      sink.write(buffer, buffer.size);
    }
    sink.flush();
  }

  @Override public void close() throws IOException {
    if (closed) return;

    // Emit buffered data to the underlying sink. If this fails, we still need
    // to close the sink; otherwise we risk leaking resources.
    Throwable thrown = null;
    try {
      if (buffer.size > 0) {
        sink.write(buffer, buffer.size);
      }
    } catch (Throwable e) {
      thrown = e;
    }

    try {
      sink.close();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    closed = true;

    if (thrown != null) Util.sneakyRethrow(thrown);
  }

  @Override public Sink deadline(Deadline deadline) {
    sink.deadline(deadline);
    return this;
  }

  @Override public String toString() {
    return "buffer(" + sink + ")";
  }

  private void checkNotClosed() {
    if (closed) {
      throw new IllegalStateException("closed");
    }
  }
}
