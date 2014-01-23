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

  // http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-6.5
  static Settings defaultSettings(boolean client) {
    Settings settings = new Settings();
    settings.set(Settings.HEADER_TABLE_SIZE, 0, 4096);
    if (client) { // client specifies whether or not it accepts push.
      settings.set(Settings.ENABLE_PUSH, 0, 1);
    }
    settings.set(Settings.INITIAL_WINDOW_SIZE, 0, 65535);
    return settings;
  }

  private static final byte[] CONNECTION_HEADER =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(Util.UTF_8);

  static final int TYPE_DATA = 0x0;
  static final int TYPE_HEADERS = 0x1;
  static final int TYPE_PRIORITY = 0x2;
  static final int TYPE_RST_STREAM = 0x3;
  static final int TYPE_SETTINGS = 0x4;
  static final int TYPE_PUSH_PROMISE = 0x5;
  static final int TYPE_PING = 0x6;
  static final int TYPE_GOAWAY = 0x7;
  static final int TYPE_WINDOW_UPDATE = 0x9;
  static final int TYPE_CONTINUATION = 0xa;

  static final int FLAG_END_STREAM = 0x1;

  /** Used for headers and continuation. */
  static final int FLAG_END_HEADERS = 0x4;
  static final int FLAG_END_PUSH_PROMISE = 0x4;
  static final int FLAG_PRIORITY = 0x8;
  static final int FLAG_ACK = 0x1;

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
      this.continuation = new ContinuationInputStream(this.in);
      this.client = client;
      this.hpackReader = new HpackDraft05.Reader(client, headerTableSize, continuation);
    }

    @Override public void readConnectionHeader() throws IOException {
      if (client) return; // Nothing to read; servers don't send connection headers!
      byte[] connectionHeader = new byte[CONNECTION_HEADER.length];
      in.readFully(connectionHeader);
      if (!Arrays.equals(connectionHeader, CONNECTION_HEADER)) {
        throw ioException("Expected a connection header but was "
            + Arrays.toString(connectionHeader));
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

      // boolean r = (w1 & 0xc0000000) != 0; // Reserved.
      short length = (short) ((w1 & 0x3fff0000) >> 16); // 14-bit unsigned.
      if (length < 0 || length > 16383) {
        throw new IOException("FRAME_SIZE_ERROR max size is 16383: " + length);
      }
      byte type = (byte) ((w1 & 0xff00) >> 8);
      byte flags = (byte) (w1 & 0xff);
      // boolean r = (w2 & 0x80000000) != 0; // Reserved.
      int streamId = (w2 & 0x7fffffff);

      switch (type) {
        case TYPE_DATA:
          readData(handler, length, flags, streamId);
          return true;

        case TYPE_HEADERS:
          readHeaders(handler, length, flags, streamId);
          return true;

        case TYPE_PRIORITY:
          readPriority(handler, length, flags, streamId);
          return true;

        case TYPE_RST_STREAM:
          readRstStream(handler, length, flags, streamId);
          return true;

        case TYPE_SETTINGS:
          readSettings(handler, length, flags, streamId);
          return true;

        case TYPE_PUSH_PROMISE:
          readPushPromise(handler, length, flags, streamId);
          return true;

        case TYPE_PING:
          readPing(handler, length, flags, streamId);
          return true;

        case TYPE_GOAWAY:
          readGoAway(handler, length, flags, streamId);
          return true;

        case TYPE_WINDOW_UPDATE:
          readWindowUpdate(handler, length, flags, streamId);
          return true;
      }

      throw new UnsupportedOperationException(Integer.toBinaryString(type));
    }

    private void readHeaders(Handler handler, short length, byte flags, int streamId)
        throws IOException {
      if (streamId == 0) throw ioException("PROTOCOL_ERROR: TYPE_HEADERS streamId == 0");

      boolean endHeaders = (flags & FLAG_END_HEADERS) != 0;
      boolean endStream = (flags & FLAG_END_STREAM) != 0;
      int priority = ((flags & FLAG_PRIORITY) != 0) ? in.readInt() & 0x7fffffff : -1;

      List<Header> headerBlock = readHeaderBlock(length, endHeaders, streamId);

      handler.headers(false, endStream, streamId, -1, priority, headerBlock,
          HeadersMode.HTTP_20_HEADERS);
    }

    private List<Header> readHeaderBlock(short length, boolean endHeaders, int streamId)
        throws IOException {
      continuation.bytesLeft = length;
      continuation.endHeaders = endHeaders;
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
      boolean endHeaders = (flags & FLAG_END_PUSH_PROMISE) != 0;

      int promisedStreamId = in.readInt() & 0x7fffffff;
      List<Header> headerBlock = readHeaderBlock(length, endHeaders, streamId);

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
      // ACK the settings frame.
      out.writeInt(0 | (TYPE_SETTINGS & 0xff) << 8 | (FLAG_ACK & 0xff));
      out.writeInt(0);
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
    public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
        throws IOException {
      hpackBuffer.reset();
      hpackWriter.writeHeaders(requestHeaders);
      int type = TYPE_PUSH_PROMISE;
      // TODO: implement CONTINUATION
      int length = hpackBuffer.size();
      checkFrameSize(length);
      int flags = FLAG_END_HEADERS;
      out.writeInt((length & 0x3fff) << 16 | (type & 0xff) << 8 | (flags & 0xff));
      out.writeInt(streamId & 0x7fffffff);
      out.writeInt(promisedStreamId & 0x7fffffff);
      hpackBuffer.writeTo(out);
    }

    private void headers(boolean outFinished, int streamId, int priority,
        List<Header> headerBlock) throws IOException {
      hpackBuffer.reset();
      hpackWriter.writeHeaders(headerBlock);
      int type = TYPE_HEADERS;
      // TODO: implement CONTINUATION
      int length = hpackBuffer.size();
      checkFrameSize(length);
      int flags = FLAG_END_HEADERS;
      if (outFinished) flags |= FLAG_END_STREAM;
      if (priority != -1) flags |= FLAG_PRIORITY;
      out.writeInt((length & 0x3fff) << 16 | (type & 0xff) << 8 | (flags & 0xff));
      out.writeInt(streamId & 0x7fffffff);
      if (priority != -1) out.writeInt(priority & 0x7fffffff);
      hpackBuffer.writeTo(out);
    }

    @Override public synchronized void rstStream(int streamId, ErrorCode errorCode)
        throws IOException {
      if (errorCode.spdyRstCode == -1) throw new IllegalArgumentException();
      int flags = 0;
      int type = TYPE_RST_STREAM;
      int length = 4;
      out.writeInt((length & 0x3fff) << 16 | (type & 0xff) << 8 | (flags & 0xff));
      out.writeInt(streamId & 0x7fffffff);
      out.writeInt(errorCode.httpCode);
      out.flush();
    }

    @Override public void data(boolean outFinished, int streamId, byte[] data) throws IOException {
      data(outFinished, streamId, data, 0, data.length);
    }

    @Override public synchronized void data(boolean outFinished, int streamId, byte[] data,
        int offset, int byteCount) throws IOException {
      int flags = 0;
      if (outFinished) flags |= FLAG_END_STREAM;
      // TODO: Implement looping strategy.
      sendDataFrame(streamId, flags, data, offset, byteCount);
    }

    void sendDataFrame(int streamId, int flags, byte[] data, int offset, int byteCount)
        throws IOException {
      checkFrameSize(byteCount);
      out.writeInt((byteCount & 0x3fff) << 16 | (TYPE_DATA & 0xff) << 8 | (flags & 0xff));
      out.writeInt(streamId & 0x7fffffff);
      out.write(data, offset, byteCount);
    }

    @Override public synchronized void settings(Settings settings) throws IOException {
      int type = TYPE_SETTINGS;
      int length = settings.size() * 8;
      int flags = 0;
      int streamId = 0;
      out.writeInt((length & 0x3fff) << 16 | (type & 0xff) << 8 | (flags & 0xff));
      out.writeInt(streamId & 0x7fffffff);
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
      out.writeInt(8 << 16 | (TYPE_PING & 0xff) << 8 | ((ack ? FLAG_ACK : 0) & 0xff));
      out.writeInt(0); // connection-level
      out.writeInt(payload1);
      out.writeInt(payload2);
    }

    @Override
    public synchronized void goAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
        throws IOException {
      if (errorCode.httpCode == -1) {
        throw new IllegalArgumentException("errorCode.httpCode == -1");
      }
      int length = 8 + debugData.length;
      checkFrameSize(length);
      out.writeInt((length & 0x3fff) << 16 | (TYPE_GOAWAY & 0xff) << 8);
      out.writeInt(0); // connection-level
      out.writeInt(lastGoodStreamId);
      out.writeInt(errorCode.httpCode);
      if (debugData.length > 0) {
        out.write(debugData);
      }
      out.flush();
    }

    @Override public synchronized void windowUpdate(int streamId, long increment)
        throws IOException {
      if (increment == 0 || increment > 0x7fffffffL) {
        throw new IllegalArgumentException(
            "windowSizeIncrement must be between 1 and 0x7fffffff: " + increment);
      }
      out.writeInt(4 << 16 | (TYPE_WINDOW_UPDATE & 0xff) << 8); // No flags.
      out.writeInt(streamId);
      out.writeInt((int) increment);
    }

    @Override public void close() throws IOException {
      out.close();
    }
  }

  private static void checkFrameSize(int bytes) throws IOException {
    if (bytes > 16383) {
      throw new IllegalArgumentException("FRAME_SIZE_ERROR max size is 16383: " + bytes);
    }
  }

  private static IOException ioException(String message, Object... args) throws IOException {
    throw new IOException(String.format(message, args));
  }

  /**
   * Decompression of the header block occurs above the framing layer.  This
   * class lazily reads continuation frames as they are needed by
   * {@link HpackDraft05.Reader#readHeaders()}.
   */
  static final class ContinuationInputStream extends InputStream {
    private final DataInputStream in;

    short bytesLeft;
    boolean endHeaders;
    int streamId;

    ContinuationInputStream(DataInputStream in) {
      this.in = in;
    }

    @Override public int read() throws IOException {
      if (bytesLeft == 0) {
        if (endHeaders) {
          return -1;
        } else {
          readContinuationHeader();
        }
      }
      bytesLeft--;
      int result = in.read();
      if (result == -1) throw new EOFException();
      return result;
    }

    @Override public int read(byte[] dst, int offset, int byteCount) throws IOException {
      if (byteCount > bytesLeft) {
        if (endHeaders) {
          throw new EOFException(
              String.format("Attempted to read %s bytes, when only %s left", byteCount, bytesLeft));
        } else {
          int beforeContinuation = bytesLeft;
          Util.readFully(in, dst, offset, bytesLeft);
          readContinuationHeader();
          int afterContinuation = byteCount - beforeContinuation;
          offset += beforeContinuation;
          bytesLeft -= afterContinuation;
          Util.readFully(in, dst, offset, afterContinuation);
          return byteCount;
        }
      } else {
        bytesLeft -= byteCount;
        Util.readFully(in, dst, offset, byteCount);
        return byteCount;
      }
    }

    private void readContinuationHeader() throws IOException {
      int w1 = in.readInt();
      int w2 = in.readInt();

      // boolean r = (w1 & 0xc0000000) != 0; // Reserved.
      bytesLeft = (short) ((w1 & 0x3fff0000) >> 16); // 14-bit unsigned.
      if (bytesLeft < 0 || bytesLeft > 16383) {
        throw new IOException("FRAME_SIZE_ERROR max size is 16383: " + bytesLeft);
      }
      int newType = (w1 & 0xff00) >> 8;
      endHeaders = (w1 & 0xff & FLAG_END_HEADERS) != 0;

      // boolean u = (w2 & 0x80000000) != 0; // Unused.
      int newStreamId = (w2 & 0x7fffffff);

      if (newType != TYPE_CONTINUATION) {
        throw ioException("TYPE_CONTINUATION didn't have FLAG_END_HEADERS");
      }
      if (newStreamId != streamId) throw ioException("TYPE_CONTINUATION streamId changed");
    }
  }
}
