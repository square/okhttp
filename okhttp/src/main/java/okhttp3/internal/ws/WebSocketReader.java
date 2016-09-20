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

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static java.lang.Integer.toHexString;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_FIN;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV1;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV2;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV3;
import static okhttp3.internal.ws.WebSocketProtocol.B0_MASK_OPCODE;
import static okhttp3.internal.ws.WebSocketProtocol.B1_FLAG_MASK;
import static okhttp3.internal.ws.WebSocketProtocol.B1_MASK_LENGTH;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_NO_STATUS_CODE;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTINUATION;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_CLOSE;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PING;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PONG;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_FLAG_CONTROL;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_LONG;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT;
import static okhttp3.internal.ws.WebSocketProtocol.toggleMask;
import static okhttp3.internal.ws.WebSocketProtocol.validateCloseCode;

/**
 * An <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a>-compatible WebSocket frame reader.
 * <p>
 * This class is not thread safe.
 */
final class WebSocketReader {
  public interface FrameCallback {
    void onReadMessage(ResponseBody body) throws IOException;
    void onReadPing(ByteString buffer);
    void onReadPong(ByteString buffer);
    void onReadClose(int code, String reason);
  }

  final boolean isClient;
  final BufferedSource source;
  final FrameCallback frameCallback;

  final Source framedMessageSource = new FramedMessageSource();

  boolean closed;
  boolean messageClosed;

  // Stateful data about the current frame.
  int opcode;
  long frameLength;
  long frameBytesRead;
  boolean isFinalFrame;
  boolean isControlFrame;
  boolean isMasked;

  final byte[] maskKey = new byte[4];
  final byte[] maskBuffer = new byte[8192];

  WebSocketReader(boolean isClient, BufferedSource source, FrameCallback frameCallback) {
    if (source == null) throw new NullPointerException("source == null");
    if (frameCallback == null) throw new NullPointerException("frameCallback == null");
    this.isClient = isClient;
    this.source = source;
    this.frameCallback = frameCallback;
  }

  /**
   * Process the next protocol frame.
   *
   * <ul>
   *     <li>If it is a control frame this will result in a single call to {@link FrameCallback}.
   *     <li>If it is a message frame this will result in a single call to {@link
   *         FrameCallback#onReadMessage}. If the message spans multiple frames, each interleaved
   *         control frame will result in a corresponding call to {@link FrameCallback}.
   * </ul>
   */
  void processNextFrame() throws IOException {
    readHeader();
    if (isControlFrame) {
      readControlFrame();
    } else {
      readMessageFrame();
    }
  }

  private void readHeader() throws IOException {
    if (closed) throw new IOException("closed");

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
      throw new ProtocolException(isClient
          ? "Server-sent frames must not be masked."
          : "Client-sent frames must be masked.");
    }

    // Get frame length, optionally reading from follow-up bytes if indicated by special values.
    frameLength = b1 & B1_MASK_LENGTH;
    if (frameLength == PAYLOAD_SHORT) {
      frameLength = source.readShort() & 0xffffL; // Value is unsigned.
    } else if (frameLength == PAYLOAD_LONG) {
      frameLength = source.readLong();
      if (frameLength < 0) {
        throw new ProtocolException(
            "Frame length 0x" + Long.toHexString(frameLength) + " > 0x7FFFFFFFFFFFFFFF");
      }
    }
    frameBytesRead = 0;

    if (isControlFrame && frameLength > PAYLOAD_BYTE_MAX) {
      throw new ProtocolException("Control frame must be less than " + PAYLOAD_BYTE_MAX + "B.");
    }

    if (isMasked) {
      // Read the masking key as bytes so that they can be used directly for unmasking.
      source.readFully(maskKey);
    }
  }

  private void readControlFrame() throws IOException {
    Buffer buffer = new Buffer();
    if (frameBytesRead < frameLength) {
      if (isClient) {
        source.readFully(buffer, frameLength);
      } else {
        while (frameBytesRead < frameLength) {
          int toRead = (int) Math.min(frameLength - frameBytesRead, maskBuffer.length);
          int read = source.read(maskBuffer, 0, toRead);
          if (read == -1) throw new EOFException();
          toggleMask(maskBuffer, read, maskKey, frameBytesRead);
          buffer.write(maskBuffer, 0, read);
          frameBytesRead += read;
        }
      }
    }

    switch (opcode) {
      case OPCODE_CONTROL_PING:
        frameCallback.onReadPing(buffer.readByteString());
        break;
      case OPCODE_CONTROL_PONG:
        frameCallback.onReadPong(buffer.readByteString());
        break;
      case OPCODE_CONTROL_CLOSE:
        int code = CLOSE_NO_STATUS_CODE;
        String reason = "";
        long bufferSize = buffer.size();
        if (bufferSize == 1) {
          throw new ProtocolException("Malformed close payload length of 1.");
        } else if (bufferSize != 0) {
          code = buffer.readShort();
          reason = buffer.readUtf8();
          validateCloseCode(code, false);
        }
        frameCallback.onReadClose(code, reason);
        closed = true;
        break;
      default:
        throw new ProtocolException("Unknown control opcode: " + toHexString(opcode));
    }
  }

  private void readMessageFrame() throws IOException {
    final MediaType type;
    switch (opcode) {
      case OPCODE_TEXT:
        type = WebSocket.TEXT;
        break;
      case OPCODE_BINARY:
        type = WebSocket.BINARY;
        break;
      default:
        throw new ProtocolException("Unknown opcode: " + toHexString(opcode));
    }

    final BufferedSource source = Okio.buffer(framedMessageSource);
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return type;
      }

      @Override public long contentLength() {
        return -1;
      }

      @Override public BufferedSource source() {
        return source;
      }
    };

    messageClosed = false;
    frameCallback.onReadMessage(body);
    if (!messageClosed) {
      throw new IllegalStateException("Listener failed to call close on message payload.");
    }
  }

  /** Read headers and process any control frames until we reach a non-control frame. */
  void readUntilNonControlFrame() throws IOException {
    while (!closed) {
      readHeader();
      if (!isControlFrame) {
        break;
      }
      readControlFrame();
    }
  }

  /**
   * A special source which knows how to read a message body across one or more frames. Control
   * frames that occur between fragments will be processed. If the message payload is masked this
   * will unmask as it's being processed.
   */
  final class FramedMessageSource implements Source {
    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (closed) throw new IOException("closed");
      if (messageClosed) throw new IllegalStateException("closed");

      if (frameBytesRead == frameLength) {
        if (isFinalFrame) return -1; // We are exhausted and have no continuations.

        readUntilNonControlFrame();
        if (opcode != OPCODE_CONTINUATION) {
          throw new ProtocolException("Expected continuation opcode. Got: " + toHexString(opcode));
        }
        if (isFinalFrame && frameLength == 0) {
          return -1; // Fast-path for empty final frame.
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
