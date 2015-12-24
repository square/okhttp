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
package okhttp3.internal.ws;

import java.io.IOException;
import java.util.Random;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Sink;
import okio.Timeout;

import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_FIN;
import static okhttp3.internal.ws.WebSocketProtocol.B1_FLAG_MASK;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTINUATION;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_CLOSE;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PING;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PONG;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_LONG;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.toggleMask;
import static okhttp3.internal.ws.WebSocketProtocol.validateCloseCode;

/**
 * An <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a>-compatible WebSocket frame writer.
 *
 * <p>This class is partially thread safe. Only a single "main" thread should be sending messages
 * via calls to {@link #newMessageSink}, {@link #writePing}, or {@link #writeClose}. Other threads
 * may call {@link #writePing}, {@link #writePong}, or {@link #writeClose} which will interleave on
 * the wire with frames from the "main" sending thread.
 */
public final class WebSocketWriter {
  private final boolean isClient;
  private final Random random;

  /** Writes must be guarded by synchronizing on 'this'. */
  private final BufferedSink sink;
  /** Access must be guarded by synchronizing on 'this'. */
  private boolean writerClosed;

  private final Buffer buffer = new Buffer();
  private final FrameSink frameSink = new FrameSink();

  private boolean activeWriter;

  private final byte[] maskKey;
  private final byte[] maskBuffer;

  public WebSocketWriter(boolean isClient, BufferedSink sink, Random random) {
    if (sink == null) throw new NullPointerException("sink == null");
    if (random == null) throw new NullPointerException("random == null");
    this.isClient = isClient;
    this.sink = sink;
    this.random = random;

    // Masks are only a concern for client writers.
    maskKey = isClient ? new byte[4] : null;
    maskBuffer = isClient ? new byte[2048] : null;
  }

  /** Send a ping with the supplied {@code payload}. Payload may be {@code null} */
  public void writePing(Buffer payload) throws IOException {
    synchronized (this) {
      writeControlFrameSynchronized(OPCODE_CONTROL_PING, payload);
    }
  }

  /** Send a pong with the supplied {@code payload}. Payload may be {@code null} */
  public void writePong(Buffer payload) throws IOException {
    synchronized (this) {
      writeControlFrameSynchronized(OPCODE_CONTROL_PONG, payload);
    }
  }

  /**
   * Send a close frame with optional code and reason.
   *
   * @param code Status code as defined by <a
   * href="http://tools.ietf.org/html/rfc6455#section-7.4">Section 7.4 of RFC 6455</a> or {@code 0}.
   * @param reason Reason for shutting down or {@code null}.
   */
  public void writeClose(int code, String reason) throws IOException {
    Buffer payload = null;
    if (code != 0 || reason != null) {
      if (code != 0) {
        validateCloseCode(code, true);
      }
      payload = new Buffer();
      payload.writeShort(code);
      if (reason != null) {
        payload.writeUtf8(reason);
      }
    }

    synchronized (this) {
      writeControlFrameSynchronized(OPCODE_CONTROL_CLOSE, payload);
      writerClosed = true;
    }
  }

  private void writeControlFrameSynchronized(int opcode, Buffer payload) throws IOException {
    assert Thread.holdsLock(this);

    if (writerClosed) throw new IOException("closed");

    int length = 0;
    if (payload != null) {
      length = (int) payload.size();
      if (length > PAYLOAD_BYTE_MAX) {
        throw new IllegalArgumentException(
            "Payload size must be less than or equal to " + PAYLOAD_BYTE_MAX);
      }
    }

    int b0 = B0_FLAG_FIN | opcode;
    sink.writeByte(b0);

    int b1 = length;
    if (isClient) {
      b1 |= B1_FLAG_MASK;
      sink.writeByte(b1);

      random.nextBytes(maskKey);
      sink.write(maskKey);

      if (payload != null) {
        writeMaskedSynchronized(payload, length);
      }
    } else {
      sink.writeByte(b1);

      if (payload != null) {
        sink.writeAll(payload);
      }
    }

    sink.emit();
  }

  /**
   * Stream a message payload as a series of frames. This allows control frames to be interleaved
   * between parts of the message.
   */
  public Sink newMessageSink(int formatOpcode) {
    if (activeWriter) {
      throw new IllegalStateException("Another message writer is active. Did you call close()?");
    }
    activeWriter = true;

    // Reset FrameSink state for a new writer.
    frameSink.formatOpcode = formatOpcode;
    frameSink.isFirstFrame = true;
    frameSink.closed = false;

    return frameSink;
  }

  private void writeMessageFrameSynchronized(int formatOpcode, long byteCount, boolean isFirstFrame,
      boolean isFinal) throws IOException {
    assert Thread.holdsLock(this);

    if (writerClosed) throw new IOException("closed");

    int b0 = isFirstFrame ? formatOpcode : OPCODE_CONTINUATION;
    if (isFinal) {
      b0 |= B0_FLAG_FIN;
    }
    sink.writeByte(b0);

    int b1 = 0;
    if (isClient) {
      b1 |= B1_FLAG_MASK;
      random.nextBytes(maskKey);
    }
    if (byteCount <= PAYLOAD_BYTE_MAX) {
      b1 |= (int) byteCount;
      sink.writeByte(b1);
    } else if (byteCount <= PAYLOAD_SHORT_MAX) {
      b1 |= PAYLOAD_SHORT;
      sink.writeByte(b1);
      sink.writeShort((int) byteCount);
    } else {
      b1 |= PAYLOAD_LONG;
      sink.writeByte(b1);
      sink.writeLong(byteCount);
    }

    if (isClient) {
      sink.write(maskKey);
      writeMaskedSynchronized(buffer, byteCount);
    } else {
      sink.write(buffer, byteCount);
    }

    sink.emit();
  }

  private void writeMaskedSynchronized(BufferedSource source, long byteCount) throws IOException {
    assert Thread.holdsLock(this);

    long written = 0;
    while (written < byteCount) {
      int toRead = (int) Math.min(byteCount, maskBuffer.length);
      int read = source.read(maskBuffer, 0, toRead);
      if (read == -1) throw new AssertionError();
      toggleMask(maskBuffer, read, maskKey, written);
      sink.write(maskBuffer, 0, read);
      written += read;
    }
  }

  private final class FrameSink implements Sink {
    private int formatOpcode;
    private boolean isFirstFrame;
    private boolean closed;

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IOException("closed");

      buffer.write(source, byteCount);

      long emitCount = buffer.completeSegmentByteCount();
      if (emitCount > 0) {
        synchronized (WebSocketWriter.this) {
          writeMessageFrameSynchronized(formatOpcode, emitCount, isFirstFrame, false /* final */);
        }
        isFirstFrame = false;
      }
    }

    @Override public void flush() throws IOException {
      if (closed) throw new IOException("closed");

      synchronized (WebSocketWriter.this) {
        writeMessageFrameSynchronized(formatOpcode, buffer.size(), isFirstFrame, false /* final */);
      }
      isFirstFrame = false;
    }

    @Override public Timeout timeout() {
      return sink.timeout();
    }

    @SuppressWarnings("PointlessBitwiseExpression")
    @Override public void close() throws IOException {
      if (closed) throw new IOException("closed");

      synchronized (WebSocketWriter.this) {
        writeMessageFrameSynchronized(formatOpcode, buffer.size(), isFirstFrame, true /* final */);
      }
      closed = true;
      activeWriter = false;
    }
  }
}
