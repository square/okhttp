/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Read and write http/2 v09 frames.
 * http://tools.ietf.org/html/draft-ietf-httpbis-http2-09
 */
public final class Http20Draft09 implements Variant {

  @Override public Protocol getProtocol() {
    return Protocol.HTTP_2;
  }

  private static final byte[] CONNECTION_HEADER =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(Util.UTF_8);

  static final byte TYPE_DATA = 0x0;
  static final byte TYPE_HEADERS = 0x1;
  static final byte TYPE_PRIORITY = 0x2;
  static final byte TYPE_RST_STREAM = 0x3;
  static final byte TYPE_SETTINGS = 0x4;
  static final byte TYPE_PUSH_PROMISE = 0x5;
  static final byte TYPE_PING = 0x6;
  static final byte TYPE_GOAWAY = 0x7;
  static final byte TYPE_WINDOW_UPDATE = 0x9;
  static final byte TYPE_CONTINUATION = 0xa;

  static final byte FLAG_NONE = 0x0;
  static final byte FLAG_ACK = 0x1;
  static final byte FLAG_END_STREAM = 0x1;
  static final byte FLAG_END_HEADERS = 0x4; // Used for headers and continuation.
  static final byte FLAG_END_PUSH_PROMISE = 0x4;
  static final byte FLAG_PRIORITY = 0x8;

  @Override public FrameReader newReader(InputStream in, boolean client) {
    return new Reader(in, 4096, client);
  }

  @Override public FrameWriter newWriter(OutputStream out, boolean client) {
    return new Writer(out, client);
  }

  static final class Reader implements FrameReader {
    private final DataInputStream in;
    private final ContinuationInputStream continuation;
    private final boolean client;

    // Visible for testing.
    final HpackDraft05.Reader hpackReader;

    Reader(InputStream in, int headerTableSize, boolean client) {
      this.in = new DataInputStream(in);
      this.client = client;
      this.continuation = new ContinuationInputStream(this.in);
      this.hpackReader = new HpackDraft05.Reader(client, headerTableSize, continuation);
    }

    @Override public void readConnectionHeader() throws IOException {
      if (client) return; // Nothing to read; servers don't send connection headers!
      byte[] connectionHeader = new byte[CONNECTION_HEADER.length];
      Util.readFully(in, connectionHeader);
      if (!Arrays.equals(connectionHeader, CONNECTION_HEADER)) {
        throw ioException("Expected a connection header but was %s",
            Arrays.toString(connectionHeader));
      }
    }

    @Override public boolean nextFrame(Handler handler) throws IOException {
      int w1;
      try {
        w1 = in.readInt();
      } catch (IOException e) {
        return false; // This might be a normal socket close.
      }

      int w2 = in.readInt();

      // boolean r = (w1 & 0xc0000000) != 0; // Reserved: Ignore first 2 bits.
      short length = (short) ((w1 & 0x3fff0000) >> 16); // 14-bit unsigned == max 16383
      byte type = (byte) ((w1 & 0xff00) >> 8);
      byte flags = (byte) (w1 & 0xff);
      // boolean r = (w2 & 0x80000000) != 0; // Reserved: Ignore first bit.
      int streamId = (w2 & 0x7fffffff); // 31-bit opaque identifier.

      switch (type) {
        case TYPE_DATA:
          readData(handler, length, flags, streamId);
          break;

        case TYPE_HEADERS:
          readHeaders(handler, length, flags, streamId);
          break;

        case TYPE_PRIORITY:
          readPriority(handler, length, flags, streamId);
          break;

        case TYPE_RST_STREAM:
          readRstStream(handler, length, flags, streamId);
          break;

        case TYPE_SETTINGS:
          readSettings(handler, length, flags, streamId);
          break;

        case TYPE_PUSH_PROMISE:
          readPushPromise(handler, length, flags, streamId);
          break;

        case TYPE_PING:
          readPing(handler, length, flags, streamId);
          break;

        case TYPE_GOAWAY:
          readGoAway(handler, length, flags, streamId);
          break;

        case TYPE_WINDOW_UPDATE:
          readWindowUpdate(handler, length, flags, streamId);
          break;

        default:
          // Implementations MUST ignore frames of unsupported or unrecognized types.
          Util.skipByReading(in, length);
      }
      return true;
    }

    private void readHeaders(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (streamId == 0) throw ioException("PROTOCOL_ERROR: TYPE_HEADERS streamId == 0");

      boolean endStream = (flags & FLAG_END_STREAM) != 0;

      int priority = -1;
      if ((flags & FLAG_PRIORITY) != 0) {
        priority = in.readInt() & 0x7fffffff;
        length -= 4; // account for above read.
      }

      List<Header> headerBlock = readHeaderBlock(length, flags, streamId);

      handler.headers(false, endStream, streamId, -1, priority, headerBlock,
          HeadersMode.HTTP_20_HEADERS);
    }

    private List<Header> readHeaderBlock(short length, byte flags, int streamId)
        throws IOException {
      continuation.length = continuation.left = length;
      continuation.flags = flags;
      continuation.streamId = streamId;

      hpackReader.readHeaders();
      hpackReader.emitReferenceSet();
      // TODO: Concat multi-value headers with 0x0, except COOKIE, which uses 0x3B, 0x20.
      // http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3
      return hpackReader.getAndReset();
    }

    private void readData(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      boolean inFinished = (flags & FLAG_END_STREAM) != 0;
      // TODO: checkState open or half-closed (local) or raise STREAM_CLOSED
      handler.data(inFinished, streamId, in, length);
    }

    private void readPriority(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (length != 4) throw ioException("TYPE_PRIORITY length: %d != 4", length);
      if (streamId == 0) throw ioException("TYPE_PRIORITY streamId == 0");
      int w1 = in.readInt();
      // boolean r = (w1 & 0x80000000) != 0; // Reserved.
      int priority = (w1 & 0x7fffffff);
      handler.priority(streamId, priority);
    }

    private void readRstStream(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (length != 4) throw ioException("TYPE_RST_STREAM length: %d != 4", length);
      if (streamId == 0) throw ioException("TYPE_RST_STREAM streamId == 0");
      int errorCodeInt = in.readInt();
      ErrorCode errorCode = ErrorCode.fromHttp2(errorCodeInt);
      if (errorCode == null) {
        throw ioException("TYPE_RST_STREAM unexpected error code: %d", errorCodeInt);
      }
      handler.rstStream(streamId, errorCode);
    }

    private void readSettings(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if ((flags & FLAG_ACK) != 0) {
        if (length != 0) throw ioException("FRAME_SIZE_ERROR ack frame should be empty!");
      }

      if (length % 8 != 0) throw ioException("TYPE_SETTINGS length %% 8 != 0: %s", length);
      if (streamId != 0) throw ioException("TYPE_SETTINGS streamId != 0");
      Settings settings = new Settings();
      for (int i = 0; i < length; i += 8) {
        int w1 = in.readInt();
        int value = in.readInt();
        // int r = (w1 & 0xff000000) >>> 24; // Reserved.
        int id = w1 & 0xffffff;
        settings.set(id, 0, value);
      }
      handler.settings(false, settings);
      if (settings.getHeaderTableSize() >= 0) {
        hpackReader.maxHeaderTableByteCount(settings.getHeaderTableSize());
      }
    }

    private void readPushPromise(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (streamId == 0) {
        throw ioException("PROTOCOL_ERROR: TYPE_PUSH_PROMISE streamId == 0");
      }
      int promisedStreamId = in.readInt() & 0x7fffffff;
      length -= 4; // account for above read.
      List<Header> headerBlock = readHeaderBlock(length, flags, streamId);
      handler.pushPromise(streamId, promisedStreamId, headerBlock);
    }

    private void readPing(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (length != 8) throw ioException("TYPE_PING length != 8: %s", length);
      if (streamId != 0) throw ioException("TYPE_PING streamId != 0");
      int payload1 = in.readInt();
      int payload2 = in.readInt();
      boolean ack = (flags & FLAG_ACK) != 0;
      handler.ping(ack, payload1, payload2);
    }

    private void readGoAway(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (length < 8) throw ioException("TYPE_GOAWAY length < 8: %s", length);
      if (streamId != 0) throw ioException("TYPE_GOAWAY streamId != 0");
      int lastStreamId = in.readInt();
      int errorCodeInt = in.readInt();
      int opaqueDataLength = length - 8;
      ErrorCode errorCode = ErrorCode.fromHttp2(errorCodeInt);
      if (errorCode == null) {
        throw ioException("TYPE_GOAWAY unexpected error code: %d", errorCodeInt);
      }
      byte[] debugData = Util.EMPTY_BYTE_ARRAY;
      if (opaqueDataLength > 0) { // Must read debug data in order to not corrupt the connection.
        debugData = new byte[opaqueDataLength];
        Util.readFully(in, debugData);
      }
      handler.goAway(lastStreamId, errorCode, debugData);
    }

    private void readWindowUpdate(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (length != 4) throw ioException("TYPE_WINDOW_UPDATE length !=4: %s", length);
      long increment = (in.readInt() & 0x7fffffff);
      if (increment == 0) throw ioException("windowSizeIncrement was 0", increment);
      handler.windowUpdate(streamId, increment);
    }

    @Override public void close() throws IOException {
      in.close();
    }
  }

  static final class Writer implements FrameWriter {
    private final DataOutputStream out;
    private final boolean client;
    private final ByteArrayOutputStream hpackBuffer;
    private final HpackDraft05.Writer hpackWriter;

    Writer(OutputStream out, boolean client) {
      this.out = new DataOutputStream(out);
      this.client = client;
      this.hpackBuffer = new ByteArrayOutputStream();
      this.hpackWriter = new HpackDraft05.Writer(hpackBuffer);
    }

    @Override public synchronized void flush() throws IOException {
      out.flush();
    }

    @Override public synchronized void ackSettings() throws IOException {
      int length = 0;
      byte type = TYPE_SETTINGS;
      byte flags = FLAG_ACK;
      int streamId = 0;
      frameHeader(length, type, flags, streamId);
    }

    @Override public synchronized void connectionHeader() throws IOException {
      if (!client) return; // Nothing to write; servers don't send connection headers!
      out.write(CONNECTION_HEADER);
    }

    @Override
    public synchronized void synStream(boolean outFinished, boolean inFinished, int streamId,
        int associatedStreamId, int priority, int slot, List<Header> headerBlock)
        throws IOException {
      if (inFinished) throw new UnsupportedOperationException();
      headers(outFinished, streamId, priority, headerBlock);
    }

    @Override public synchronized void synReply(boolean outFinished, int streamId,
        List<Header> headerBlock) throws IOException {
      headers(outFinished, streamId, -1, headerBlock);
    }

    @Override public synchronized void headers(int streamId, List<Header> headerBlock)
        throws IOException {
      headers(false, streamId, -1, headerBlock);
    }

    @Override
    public synchronized void pushPromise(int streamId, int promisedStreamId,
        List<Header> requestHeaders) throws IOException {
      hpackBuffer.reset();
      hpackWriter.writeHeaders(requestHeaders);

      int length = 4 + hpackBuffer.size();
      byte type = TYPE_PUSH_PROMISE;
      byte flags = FLAG_END_HEADERS;
      frameHeader(length, type, flags, streamId); // TODO: CONTINUATION
      out.writeInt(promisedStreamId & 0x7fffffff);
      hpackBuffer.writeTo(out);
    }

    private void headers(boolean outFinished, int streamId, int priority,
        List<Header> headerBlock) throws IOException {
      hpackBuffer.reset();
      hpackWriter.writeHeaders(headerBlock);

      int length = hpackBuffer.size();
      byte type = TYPE_HEADERS;
      byte flags = FLAG_END_HEADERS;
      if (outFinished) flags |= FLAG_END_STREAM;
      if (priority != -1) flags |= FLAG_PRIORITY;
      if (priority != -1) length += 4;
      frameHeader(length, type, flags, streamId); // TODO: CONTINUATION
      if (priority != -1) out.writeInt(priority & 0x7fffffff);
      hpackBuffer.writeTo(out);
    }

    @Override public synchronized void rstStream(int streamId, ErrorCode errorCode)
        throws IOException {
      if (errorCode.spdyRstCode == -1) throw new IllegalArgumentException();

      int length = 4;
      byte type = TYPE_RST_STREAM;
      byte flags = FLAG_NONE;
      frameHeader(length, type, flags, streamId);
      out.writeInt(errorCode.httpCode);
      out.flush();
    }

    @Override public synchronized void data(boolean outFinished, int streamId, byte[] data)
        throws IOException {
      data(outFinished, streamId, data, 0, data.length);
    }

    @Override public synchronized void data(boolean outFinished, int streamId, byte[] data,
        int offset, int byteCount) throws IOException {
      byte flags = FLAG_NONE;
      if (outFinished) flags |= FLAG_END_STREAM;
      dataFrame(streamId, flags, data, offset, byteCount); // TODO: Implement looping strategy
    }

    void dataFrame(int streamId, byte flags, byte[] data, int offset, int length)
        throws IOException {
      byte type = TYPE_DATA;
      frameHeader(length, type, flags, streamId);
      out.write(data, offset, length);
    }

    @Override public synchronized void settings(Settings settings) throws IOException {
      int length = settings.size() * 8;
      byte type = TYPE_SETTINGS;
      byte flags = FLAG_NONE;
      int streamId = 0;
      frameHeader(length, type, flags, streamId);
      for (int i = 0; i < Settings.COUNT; i++) {
        if (!settings.isSet(i)) continue;
        out.writeInt(i & 0xffffff);
        out.writeInt(settings.get(i));
      }
    }

    @Override public synchronized void noop() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override public synchronized void ping(boolean ack, int payload1, int payload2)
        throws IOException {
      int length = 8;
      byte type = TYPE_PING;
      byte flags = ack ? FLAG_ACK : FLAG_NONE;
      int streamId = 0;
      frameHeader(length, type, flags, streamId);
      out.writeInt(payload1);
      out.writeInt(payload2);
    }

    @Override
    public synchronized void goAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
        throws IOException {
      if (errorCode.httpCode == -1) throw illegalArgument("errorCode.httpCode == -1");
      int length = 8 + debugData.length;
      byte type = TYPE_GOAWAY;
      byte flags = FLAG_NONE;
      int streamId = 0;
      frameHeader(length, type, flags, streamId);
      out.writeInt(lastGoodStreamId);
      out.writeInt(errorCode.httpCode);
      if (debugData.length > 0) {
        out.write(debugData);
      }
    }

    @Override public synchronized void windowUpdate(int streamId, long windowSizeIncrement)
        throws IOException {
      if (windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL) {
        throw illegalArgument("windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: %s",
            windowSizeIncrement);
      }
      int length = 4;
      byte type = TYPE_WINDOW_UPDATE;
      byte flags = FLAG_NONE;
      frameHeader(length, type, flags, streamId);
      out.writeInt((int) windowSizeIncrement);
    }

    @Override public void close() throws IOException {
      out.close();
    }

    private void frameHeader(int length, byte type, byte flags, int streamId)
        throws IOException {
      if (length > 16383) throw illegalArgument("FRAME_SIZE_ERROR length > 16383: %s", length);
      if ((streamId & 0x80000000) == 1) throw illegalArgument("(streamId & 0x80000000) == 1: %s",
          streamId);
      out.writeInt((length & 0x3fff) << 16 | (type & 0xff) << 8 | (flags & 0xff));
      out.writeInt(streamId & 0x7fffffff);
    }
  }

  private static IllegalArgumentException illegalArgument(String message, Object... args) {
    throw new IllegalArgumentException(String.format(message, args));
  }

  private static IOException ioException(String message, Object... args) throws IOException {
    throw new IOException(String.format(message, args));
  }

  /**
   * Decompression of the header block occurs above the framing layer.  This class lazily reads
   * continuation frames as they are needed by {@link HpackDraft05.Reader#readHeaders()}.
   */
  static final class ContinuationInputStream extends InputStream {
    private final DataInputStream in;

    int length;
    byte flags;
    int streamId;

    int left;

    ContinuationInputStream(DataInputStream in) {
      this.in = in;
    }

    @Override public int read() throws IOException {
      if (left == 0) {
        if (endHeaders()) {
          throw eofReading(1);
        } else {
          readContinuationHeader();
        }
      }
      left--;
      return in.read();
    }

    @Override public int available() throws IOException {
      if (left == 0) {
        if (endHeaders()) {
          return 0;
        } else {
          readContinuationHeader();
        }
      }
      return left;
    }

    @Override public int read(byte[] dst, int offset, int byteCount) throws IOException {
      if (byteCount > left) {
        if (endHeaders()) {
          throw eofReading(byteCount);
        } else {
          int beforeContinuation = left;
          Util.readFully(in, dst, offset, beforeContinuation);
          readContinuationHeader();
          int afterContinuation = byteCount - beforeContinuation;
          offset += beforeContinuation;
          Util.readFully(in, dst, offset, afterContinuation);
          left -= afterContinuation;
          return byteCount;
        }
      } else {
        Util.readFully(in, dst, offset, byteCount);
        left -= byteCount;
        return byteCount;
      }
    }

    private EOFException eofReading(int byteCount) throws EOFException {
      int read = length - left;
      throw new EOFException(
          String.format("EOF reading %s more bytes; read %s/%s of frame.", byteCount, read,
              length));
    }

    private void readContinuationHeader() throws IOException {
      int previousStreamId = streamId;
      int w1 = in.readInt();
      int w2 = in.readInt();
      length = left = (short) ((w1 & 0x3fff0000) >> 16);
      byte type = (byte) ((w1 & 0xff00) >> 8);
      flags = (byte) (w1 & 0xff);
      streamId = (w2 & 0x7fffffff);
      if (type != TYPE_CONTINUATION) throw ioException("%s != TYPE_CONTINUATION", type);
      if (streamId != previousStreamId) throw ioException("TYPE_CONTINUATION streamId changed");
    }

    private boolean endHeaders() {
      return (flags & FLAG_END_HEADERS) != 0;
    }
  }
}
