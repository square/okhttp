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
import okio.ByteString;
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
 * <p>This class is not thread safe.
 */
final class WebSocketWriter {
  final boolean isClient;
  final Random random;

  final BufferedSink sink;
  /** The {@link Buffer} of {@link #sink}. Write to this and then flush/emit {@link #sink}. */
  final Buffer sinkBuffer;
  boolean writerClosed;

  final Buffer buffer = new Buffer();
  final FrameSink frameSink = new FrameSink();

  boolean activeWriter;

  private final byte[] maskKey;
  private final Buffer.UnsafeCursor maskCursor;

  WebSocketWriter(boolean isClient, BufferedSink sink, Random random) {
    if (sink == null) throw new NullPointerException("sink == null");
    if (random == null) throw new NullPointerException("random == null");
    this.isClient = isClient;
    this.sink = sink;
    this.sinkBuffer = sink.buffer();
    this.random = random;

    // Masks are only a concern for client writers.
    maskKey = isClient ? new byte[4] : null;
    maskCursor = isClient ? new Buffer.UnsafeCursor() : null;
  }

  /** Send a ping with the supplied {@code payload}. */
  void writePing(ByteString payload) throws IOException {
    writeControlFrame(OPCODE_CONTROL_PING, payload);
  }

  /** Send a pong with the supplied {@code payload}. */
  void writePong(ByteString payload) throws IOException {
    writeControlFrame(OPCODE_CONTROL_PONG, payload);
  }

  /**
   * Send a close frame with optional code and reason.
   *
   * @param code Status code as defined by <a
   * href="http://tools.ietf.org/html/rfc6455#section-7.4">Section 7.4 of RFC 6455</a> or {@code 0}.
   * @param reason Reason for shutting down or {@code null}.
   */
  void writeClose(int code, ByteString reason) throws IOException {
    ByteString payload = ByteString.EMPTY;
    if (code != 0 || reason != null) {
      if (code != 0) {
        validateCloseCode(code);
      }
      Buffer buffer = new Buffer();
      buffer.writeShort(code);
      if (reason != null) {
        buffer.write(reason);
      }
      payload = buffer.readByteString();
    }

    try {
      writeControlFrame(OPCODE_CONTROL_CLOSE, payload);
    } finally {
      writerClosed = true;
    }
  }

  private void writeControlFrame(int opcode, ByteString payload) throws IOException {
    if (writerClosed) throw new IOException("closed");

    int length = payload.size();
    if (length > PAYLOAD_BYTE_MAX) {
      throw new IllegalArgumentException(
          "Payload size must be less than or equal to " + PAYLOAD_BYTE_MAX);
    }

    int b0 = B0_FLAG_FIN | opcode;
    sinkBuffer.writeByte(b0);

    int b1 = length;
    if (isClient) {
      b1 |= B1_FLAG_MASK;
      sinkBuffer.writeByte(b1);

      random.nextBytes(maskKey);
      sinkBuffer.write(maskKey);

      if (length > 0) {
        long payloadStart = sinkBuffer.size();
        sinkBuffer.write(payload);

        sinkBuffer.readAndWriteUnsafe(maskCursor);
        maskCursor.seek(payloadStart);
        toggleMask(maskCursor, maskKey);
        maskCursor.close();
      }
    } else {
      sinkBuffer.writeByte(b1);
      sinkBuffer.write(payload);
    }

    sink.flush();
  }

  /**
   * Stream a message payload as a series of frames. This allows control frames to be interleaved
   * between parts of the message.
   */
  Sink newMessageSink(int formatOpcode, long contentLength) {
    if (activeWriter) {
      throw new IllegalStateException("Another message writer is active. Did you call close()?");
    }
    activeWriter = true;

    // Reset FrameSink state for a new writer.
    frameSink.formatOpcode = formatOpcode;
    frameSink.contentLength = contentLength;
    frameSink.isFirstFrame = true;
    frameSink.closed = false;

    return frameSink;
  }

  void writeMessageFrame(int formatOpcode, long byteCount, boolean isFirstFrame,
      boolean isFinal) throws IOException {
    if (writerClosed) throw new IOException("closed");

    int b0 = isFirstFrame ? formatOpcode : OPCODE_CONTINUATION;
    if (isFinal) {
      b0 |= B0_FLAG_FIN;
    }
    sinkBuffer.writeByte(b0);

    int b1 = 0;
    if (isClient) {
      b1 |= B1_FLAG_MASK;
    }
    if (byteCount <= PAYLOAD_BYTE_MAX) {
      b1 |= (int) byteCount;
      sinkBuffer.writeByte(b1);
    } else if (byteCount <= PAYLOAD_SHORT_MAX) {
      b1 |= PAYLOAD_SHORT;
      sinkBuffer.writeByte(b1);
      sinkBuffer.writeShort((int) byteCount);
    } else {
      b1 |= PAYLOAD_LONG;
      sinkBuffer.writeByte(b1);
      sinkBuffer.writeLong(byteCount);
    }

    if (isClient) {
      random.nextBytes(maskKey);
      sinkBuffer.write(maskKey);

      if (byteCount > 0) {
        long bufferStart = sinkBuffer.size();
        sinkBuffer.write(buffer, byteCount);

        sinkBuffer.readAndWriteUnsafe(maskCursor);
        maskCursor.seek(bufferStart);
        toggleMask(maskCursor, maskKey);
        maskCursor.close();
      }
    } else {
      sinkBuffer.write(buffer, byteCount);
    }

    sink.emit();
  }

  final class FrameSink implements Sink {
    int formatOpcode;
    long contentLength;
    boolean isFirstFrame;
    boolean closed;

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IOException("closed");

      buffer.write(source, byteCount);

      // Determine if this is a buffered write which we can defer until close() flushes.
      boolean deferWrite = isFirstFrame
          && contentLength != -1
          && buffer.size() > contentLength - 8192 /* segment size */;

      long emitCount = buffer.completeSegmentByteCount();
      if (emitCount > 0 && !deferWrite) {
        writeMessageFrame(formatOpcode, emitCount, isFirstFrame, false /* final */);
        isFirstFrame = false;
      }
    }

    @Override public void flush() throws IOException {
      if (closed) throw new IOException("closed");

      writeMessageFrame(formatOpcode, buffer.size(), isFirstFrame, false /* final */);
      isFirstFrame = false;
    }

    @Override public Timeout timeout() {
      return sink.timeout();
    }

    @Override public void close() throws IOException {
      if (closed) throw new IOException("closed");

      writeMessageFrame(formatOpcode, buffer.size(), isFirstFrame, true /* final */);
      closed = true;
      activeWriter = false;
    }
  }
}
