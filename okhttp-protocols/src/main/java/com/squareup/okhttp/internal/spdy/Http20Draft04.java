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

import com.squareup.okhttp.internal.Util;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

final class Http20Draft04 implements Variant {
  static final int TYPE_DATA = 0x0;
  static final int TYPE_HEADERS = 0x1;
  static final int TYPE_PRIORITY = 0x2;
  static final int TYPE_RST_STREAM = 0x3;
  static final int TYPE_SETTINGS = 0x4;
  static final int TYPE_PUSH_PROMISE = 0x5;
  static final int TYPE_PING = 0x6;
  static final int TYPE_GOAWAY = 0x7;
  static final int TYPE_WINDOW_UPDATE = 0x9;

  static final int FLAG_END_STREAM = 0x1;
  static final int FLAG_END_HEADERS = 0x4;
  static final int FLAG_PRIORITY = 0x8;
  static final int FLAG_PONG = 0x1;
  static final int FLAG_END_FLOW_CONTROL = 0x1;

  @Override public FrameReader newReader(InputStream in, boolean client) {
    return new Reader(in);
  }

  @Override public FrameWriter newWriter(OutputStream out, boolean client) {
    return new Writer(out);
  }

  static final class Reader implements FrameReader {
    private final DataInputStream in;

    Reader(InputStream in) {
      this.in = new DataInputStream(in);
    }

    @Override public boolean nextFrame(Handler handler) throws IOException {
      int w1;
      try {
        w1 = in.readInt();
      } catch (IOException e) {
        return false; // This might be a normal socket close.
      }
      int w2 = in.readInt();

      int length = w1 & 0xffff;
      int type = (w1 & 0xff0000) >> 16;
      int flags = (w1 & 0xff000000) >> 24;
      // boolean r = (w2 & 0x80000000) != 0; // Reserved.
      int streamId = (w2 & 0x7fffffff);

      switch (type) {
        case TYPE_DATA:
          readData(handler, flags, length, streamId);
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

    private void readData(Handler handler, int flags, int length, int streamId) throws IOException {
      boolean inFinished = (flags & FLAG_END_STREAM) != 0;
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
      boolean reply = (flags & FLAG_PONG) != 0;
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

    private static IOException ioException(String message, Object... args) throws IOException {
      throw new IOException(String.format(message, args));
    }

    @Override public void close() throws IOException {
      in.close();
    }
  }

  static final class Writer implements FrameWriter {
    private final DataOutputStream out;

    Writer(OutputStream out) {
      this.out = new DataOutputStream(out);
    }

    @Override public synchronized void flush() throws IOException {
      out.flush();
    }

    @Override public synchronized void connectionHeader() {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void synStream(boolean outFinished, boolean inFinished,
        int streamId, int associatedStreamId, int priority, int slot, List<String> nameValueBlock)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void synReply(boolean outFinished, int streamId,
        List<String> nameValueBlock) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void headers(int streamId, List<String> nameValueBlock)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void rstStream(int streamId, ErrorCode errorCode)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public void data(boolean outFinished, int streamId, byte[] data) throws IOException {
      data(outFinished, streamId, data, 0, data.length);
    }

    @Override public synchronized void data(boolean outFinished, int streamId, byte[] data,
        int offset, int byteCount) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void settings(Settings settings) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void noop() throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void ping(boolean reply, int payload1, int payload2)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void goAway(int lastGoodStreamId, ErrorCode errorCode)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void windowUpdate(int streamId, int deltaWindowSize)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public void close() throws IOException {
      out.close();
    }
  }
}
