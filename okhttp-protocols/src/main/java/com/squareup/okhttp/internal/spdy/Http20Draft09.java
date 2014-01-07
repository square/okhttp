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

import com.squareup.okhttp.internal.ByteString;
import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read and write http/2 v09 frames.
 * http://tools.ietf.org/html/draft-ietf-httpbis-http2-09
 */
public final class Http20Draft09 implements Variant {

  @Override public String getProtocol() {
    return "HTTP-draft-09/2.0";
  }

  private static final byte[] CONNECTION_HEADER;
  static {
    try {
      CONNECTION_HEADER = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError();
    }
  }

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

  /** Used for headers, push-promise and continuation. */
  static final int FLAG_END_HEADERS = 0x4;
  static final int FLAG_PRIORITY = 0x8;
  static final int FLAG_ACK = 0x1;
  static final int FLAG_END_FLOW_CONTROL = 0x1;

  @Override public FrameReader newReader(InputStream in, boolean client) {
    return new Reader(in, client);
  }

  @Override public FrameWriter newWriter(OutputStream out, boolean client) {
    return new Writer(out, client);
  }

  static final class Reader implements FrameReader {
    private final DataInputStream in;
    private final boolean client;

    // Visible for testing.
    final HpackDraft05.Reader hpackReader;

    Reader(InputStream in, boolean client) {
      this.in = new DataInputStream(in);
      this.client = client;
      this.hpackReader = new HpackDraft05.Reader(this.in);
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
      int length = (w1 & 0x3fff0000) >> 16; // 14-bit unsigned.
      int type = (w1 & 0xff00) >> 8;
      int flags = w1 & 0xff;
      // boolean r = (w2 & 0x80000000) != 0; // Reserved.
      int streamId = (w2 & 0x7fffffff);

      switch (type) {
        case TYPE_DATA:
          readData(handler, flags, length, streamId);
          return true;

        case TYPE_HEADERS:
          readHeaders(handler, flags, length, streamId);
          return true;

        case TYPE_PRIORITY:
          readPriority(handler, flags, length, streamId);
          return true;

        case TYPE_RST_STREAM:
          readRstStream(handler, flags, length, streamId);
          return true;

        case TYPE_SETTINGS:
          readSettings(handler, flags, length, streamId);
          return true;

        case TYPE_PUSH_PROMISE:
          readPushPromise(handler, flags, length, streamId);
          return true;

        case TYPE_PING:
          readPing(handler, flags, length, streamId);
          return true;

        case TYPE_GOAWAY:
          readGoAway(handler, flags, length, streamId);
          return true;

        case TYPE_WINDOW_UPDATE:
          readWindowUpdate(handler, flags, length, streamId);
          return true;
      }

      throw new UnsupportedOperationException("TODO");
    }

    private void readHeaders(Handler handler, int flags, int length, int streamId)
        throws IOException {
      if (streamId == 0) throw ioException("TYPE_HEADERS streamId == 0");

      boolean inFinished = (flags & FLAG_END_STREAM) != 0;

      NameValueBlockCallback callback = new NameValueBlockCallback();

      while (true) {
        hpackReader.readHeaders(length, callback);

        if ((flags & FLAG_END_HEADERS) != 0) {
          hpackReader.emitReferenceSet(callback);
          int priority = -1; // TODO: priority
          // TODO: update Headers to work with ByteString?
          // TODO: Concat multi-value headers with 0x0, except COOKIE, which uses 0x3B, 0x20.
          // http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3
          handler.headers(false, inFinished, streamId, -1, priority, callback.nameValueBlock,
              HeadersMode.HTTP_20_HEADERS);
          return;
        }

        // Read another continuation frame.
        int w1 = in.readInt();
        int w2 = in.readInt();

        // boolean r = (w1 & 0xc0000000) != 0; // Reserved.
        length = (w1 & 0x3fff0000) >> 16; // 14-bit unsigned.
        int newType = (w1 & 0xff00) >> 8;
        flags = w1 & 0xff;

        // boolean u = (w2 & 0x80000000) != 0; // Unused.
        int newStreamId = (w2 & 0x7fffffff);

        if (newType != TYPE_CONTINUATION) {
          throw ioException("TYPE_CONTINUATION didn't have FLAG_END_HEADERS");
        }
        if (newStreamId != streamId) throw ioException("TYPE_CONTINUATION streamId changed");
      }
    }

    private void readData(Handler handler, int flags, int length, int streamId) throws IOException {
      boolean inFinished = (flags & FLAG_END_STREAM) != 0;
      // TODO: checkState open or half-closed (local) or raise STREAM_CLOSED
      handler.data(inFinished, streamId, in, length);
    }

    private void readPriority(Handler handler, int flags, int length, int streamId)
        throws IOException {
      if (length != 4) throw ioException("TYPE_PRIORITY length: %d != 4", length);
      if (streamId == 0) throw ioException("TYPE_PRIORITY streamId == 0");
      int w1 = in.readInt();
      // boolean r = (w1 & 0x80000000) != 0; // Reserved.
      int priority = (w1 & 0x7fffffff);
      handler.priority(streamId, priority);
    }

    private void readRstStream(Handler handler, int flags, int length, int streamId)
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

    private void readSettings(Handler handler, int flags, int length, int streamId)
        throws IOException {
      if ((flags & FLAG_ACK) != 0) {
        if (length != 0) throw ioException("FRAME_SIZE_ERROR ack frame should be empty!");
        // TODO: signal apply changes
        // http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-6.5.3
        return;
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
    }

    private void readPushPromise(Handler handler, int flags, int length, int streamId) {
      // TODO:
    }

    private void readPing(Handler handler, int flags, int length, int streamId) throws IOException {
      if (length != 8) throw ioException("TYPE_PING length != 8: %s", length);
      if (streamId != 0) throw ioException("TYPE_PING streamId != 0");
      int payload1 = in.readInt();
      int payload2 = in.readInt();
      boolean reply = (flags & FLAG_ACK) != 0;
      handler.ping(reply, payload1, payload2);
    }

    private void readGoAway(Handler handler, int flags, int length, int streamId)
        throws IOException {
      if (length < 8) throw ioException("TYPE_GOAWAY length < 8: %s", length);
      int lastStreamId = in.readInt();
      int errorCodeInt = in.readInt();
      int opaqueDataLength = length - 8;
      ErrorCode errorCode = ErrorCode.fromHttp2(errorCodeInt);
      if (errorCode == null) {
        throw ioException("TYPE_RST_STREAM unexpected error code: %d", errorCodeInt);
      }
      if (Util.skipByReading(in, opaqueDataLength) != opaqueDataLength) {
        throw new IOException("TYPE_GOAWAY opaque data was truncated");
      }
      handler.goAway(lastStreamId, errorCode);
    }

    private void readWindowUpdate(Handler handler, int flags, int length, int streamId)
        throws IOException {
      int w1 = in.readInt();
      // boolean r = (w1 & 0x80000000) != 0; // Reserved.
      int windowSizeIncrement = (w1 & 0x7fffffff);
      boolean endFlowControl = (flags & FLAG_END_FLOW_CONTROL) != 0;
      handler.windowUpdate(streamId, windowSizeIncrement, endFlowControl);
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

    @Override public synchronized void connectionHeader() throws IOException {
      if (!client) return; // Nothing to write; servers don't send connection headers!
      out.write(CONNECTION_HEADER);
    }

    @Override
    public synchronized void synStream(boolean outFinished, boolean inFinished, int streamId,
        int associatedStreamId, int priority, int slot, List<ByteString> nameValueBlock)
        throws IOException {
      if (inFinished) throw new UnsupportedOperationException();
      headers(outFinished, streamId, priority, nameValueBlock);
    }

    @Override public synchronized void synReply(boolean outFinished, int streamId,
        List<ByteString> nameValueBlock) throws IOException {
      headers(outFinished, streamId, -1, nameValueBlock);
    }

    @Override public synchronized void headers(int streamId, List<ByteString> nameValueBlock)
        throws IOException {
      headers(false, streamId, -1, nameValueBlock);
    }

    private void headers(boolean outFinished, int streamId, int priority,
        List<ByteString> nameValueBlock) throws IOException {
      hpackBuffer.reset();
      hpackWriter.writeHeaders(nameValueBlock);
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
      int type = TYPE_DATA;
      int flags = 0;
      if (outFinished) flags |= FLAG_END_STREAM;
      // TODO: Implement looping strategy.
      checkFrameSize(byteCount);
      out.writeInt((byteCount & 0x3fff) << 16 | (type & 0xff) << 8 | (flags & 0xff));
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

    @Override public synchronized void ping(boolean reply, int payload1, int payload2)
        throws IOException {
      // TODO
    }

    @Override public synchronized void goAway(int lastGoodStreamId, ErrorCode errorCode)
        throws IOException {
      // TODO
    }

    @Override public synchronized void windowUpdate(int streamId, int deltaWindowSize)
        throws IOException {
      // TODO
    }

    @Override public void close() throws IOException {
      out.close();
    }
  }

  private static void checkFrameSize(int bytes) throws IOException {
    if (bytes > 16383) throw ioException("FRAME_SIZE_ERROR max size is 16383: %s", bytes);
  }

  private static IOException ioException(String message, Object... args) throws IOException {
    throw new IOException(String.format(message, args));
  }

  static class NameValueBlockCallback implements HpackDraft05.Callback {
    final List<ByteString> nameValueBlock = new ArrayList<ByteString>();

    @Override public void onHeader(ByteString name, ByteString value) {
      nameValueBlock.add(name);
      nameValueBlock.add(value);
    }
  }
}
