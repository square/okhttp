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
package com.squareup.okhttp.internal.ws;

import com.squareup.okhttp.WebSocketListener;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static com.squareup.okhttp.WebSocket.PayloadType;
import static com.squareup.okhttp.internal.Util.readFully;
import static com.squareup.okhttp.internal.ws.Protocol.B0_FLAG_FIN;
import static com.squareup.okhttp.internal.ws.Protocol.B0_FLAG_RSV1;
import static com.squareup.okhttp.internal.ws.Protocol.B0_FLAG_RSV2;
import static com.squareup.okhttp.internal.ws.Protocol.B0_FLAG_RSV3;
import static com.squareup.okhttp.internal.ws.Protocol.B0_MASK_OPCODE;
import static com.squareup.okhttp.internal.ws.Protocol.B1_FLAG_MASK;
import static com.squareup.okhttp.internal.ws.Protocol.B1_MASK_LENGTH;
import static com.squareup.okhttp.internal.ws.Protocol.PAYLOAD_MAX;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_BINARY;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTINUATION;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTROL_CLOSE;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTROL_PING;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTROL_PONG;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_FLAG_CONTROL;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_TEXT;
import static com.squareup.okhttp.internal.ws.Protocol.PAYLOAD_LONG;
import static com.squareup.okhttp.internal.ws.Protocol.PAYLOAD_SHORT;
import static com.squareup.okhttp.internal.ws.Protocol.toggleMask;
import static java.lang.Integer.toHexString;

/**
 * An <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a>-compatible WebSocket frame reader.
 */
public final class WebSocketReader {
  private final boolean isClient;
  private final BufferedSource source;
  private final WebSocketListener listener;

  private final Source framedMessageSource = new FramedMessageSource();

  private boolean closed;
  private boolean messageClosed;

  // Stateful data about the current frame.
  private int opcode;
  private long frameLength;
  private long frameBytesRead;
  private boolean isFinalFrame;
  private boolean isControlFrame;
  private boolean isMasked;

  private final byte[] maskKey = new byte[4];
  private final byte[] maskBuffer = new byte[2048];

  public WebSocketReader(boolean isClient, BufferedSource source, WebSocketListener listener) {
    this.isClient = isClient;
    this.source = source;
    this.listener = listener;
  }

  /**
   * Reads one message from source consuming any control frames that precede or are interleaved
   * between frame fragments. This will result in one call to {@link WebSocketListener#onMessage}.
   */
  public void readMessage() throws IOException {
    readUntilNonControlFrame();

    PayloadType type;
    switch (opcode) {
      case OPCODE_TEXT:
        type = PayloadType.TEXT;
        break;
      case OPCODE_BINARY:
        type = PayloadType.BINARY;
        break;
      default:
        throw new IllegalStateException("Unknown opcode: " + toHexString(opcode));
    }

    messageClosed = false;
    listener.onMessage(Okio.buffer(framedMessageSource), type);
    if (!messageClosed) {
      throw new IllegalStateException("Listener failed to call close on message payload.");
    }
  }

  /** Read headers and process any control frames until we reach a non-control frame. */
  private void readUntilNonControlFrame() throws IOException {
    while (true) {
      readHeader();
      if (!isControlFrame) {
        break;
      }
      readControlFrame();
    }
  }

  private void readHeader() throws IOException {
    if (closed) throw new IllegalStateException("Closed");

    int b0 = source.readByte() & 0xff;

    opcode = b0 & B0_MASK_OPCODE;
    isFinalFrame = (b0 & B0_FLAG_FIN) != 0;
    isControlFrame = (b0 & OPCODE_FLAG_CONTROL) != 0;

    // Control frames must be final frames (cannot contain continuations).
    if (isControlFrame && !isFinalFrame) {
      throw new ProtocolException("Control frames must be final.");
    }

    boolean reservedFlag1 = (b0 & B0_FLAG_RSV1) != 0;
    boolean reservedFlag2 = (b0 & B0_FLAG_RSV2) != 0;
    boolean reservedFlag3 = (b0 & B0_FLAG_RSV3) != 0;
    if (reservedFlag1 || reservedFlag2 || reservedFlag3) {
      // Reserved flags are for extensions which we currently do not support.
      throw new ProtocolException("Reserved flags are unsupported.");
    }

    int b1 = source.readByte() & 0xff;

    isMasked = (b1 & B1_FLAG_MASK) != 0;
    if (isMasked == isClient) {
      // Masked payloads must be read on the server. Unmasked payloads must be read on the client.
      throw new ProtocolException("Client-sent frames must be masked. Server sent must not.");
    }

    // Get frame length, optionally reading from follow-up bytes if indicated by special values.
    frameLength = b1 & B1_MASK_LENGTH;
    if (frameLength == PAYLOAD_SHORT) {
      frameLength = source.readShort();
    } else if (frameLength == PAYLOAD_LONG) {
      frameLength = source.readLong();
    }
    frameBytesRead = 0;

    if (isControlFrame && frameLength > PAYLOAD_MAX) {
      throw new ProtocolException("Control frame must be less than " + PAYLOAD_MAX + "B.");
    }

    if (isMasked) {
      // Read the masking key as bytes so that they can be used directly for unmasking.
      readFully(source, maskKey);
    }
  }

  private void readControlFrame() throws IOException {
    Buffer buffer = null;
    if (frameBytesRead < frameLength) {
      buffer = new Buffer();

      if (isClient) {
        while (frameBytesRead < frameLength) {
          int toRead = (int) Math.min(frameLength - frameBytesRead, maskBuffer.length);
          int read = source.read(maskBuffer, 0, toRead);
          if (read == -1) throw new EOFException();
          toggleMask(maskBuffer, read, maskKey, frameBytesRead);
          buffer.write(maskBuffer, 0, read);
          frameBytesRead += read;
        }
      } else {
        source.readFully(buffer, frameLength);
      }
    }

    switch (opcode) {
      case OPCODE_CONTROL_PING:
        break; // TODO enqueue a pong with the read buffer.
      case OPCODE_CONTROL_PONG:
        // Thanks for the pong! Nothing to do here.
        break;
      case OPCODE_CONTROL_CLOSE:
        // TODO if we did not initiate the close, enqueue an ack close on the writer.
        closed = true;
        break;
      default:
        throw new IllegalStateException("Unknown control opcode: " + toHexString(opcode));
    }
  }

  /**
   * A special source which knows how to read a message body across one or more frames. Control
   * frames that occur between fragments will be processed. If the message payload is masked this
   * will unmask as it's being processed.
   */
  private final class FramedMessageSource implements Source {
    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (closed) throw new IOException("Closed");

      if (frameBytesRead == frameLength) {
        if (isFinalFrame) return -1; // We are exhausted and have no continuations.

        readUntilNonControlFrame();
        if (opcode != OPCODE_CONTINUATION) {
          throw new ProtocolException("Expected continuation opcode. Got: " + toHexString(opcode));
        }
      }

      long toRead = Math.min(byteCount, frameLength - frameBytesRead);

      long read;
      if (isMasked) {
        toRead = Math.min(toRead, maskBuffer.length);
        read = source.read(maskBuffer, 0, (int) toRead);
        if (read == -1) throw new EOFException();
        toggleMask(maskBuffer, read, maskKey, frameBytesRead);
        sink.write(maskBuffer, 0, (int) read);
      } else {
        read = source.read(sink, toRead);
        if (read == -1) throw new EOFException();
      }

      frameBytesRead += read;
      return read;
    }

    @Override public Timeout timeout() {
      return source.timeout();
    }

    @Override public void close() throws IOException {
      if (messageClosed) return;
      messageClosed = true;
      if (closed) return;

      // Exhaust the remainder of the message, if any.
      source.skip(frameLength - frameBytesRead);
      while (!isFinalFrame) {
        readUntilNonControlFrame();
        source.skip(frameLength);
      }
    }
  }
}
