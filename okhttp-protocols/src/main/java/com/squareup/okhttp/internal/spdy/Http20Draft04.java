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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

final class Http20Draft04 implements Variant {
  @Override public SpdyReader newReader(InputStream in) {
    return new Reader(in);
  }

  @Override public SpdyWriter newWriter(OutputStream out) {
    return new Writer(out);
  }

  static final class Reader implements SpdyReader {
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
      boolean r = (w2 & 0x80000000) != 0;
      int streamId = (w2 & 0x7fffffff);

      throw new UnsupportedOperationException("TODO");
    }

    @Override public void close() throws IOException {
      in.close();
    }
  }

  static final class Writer implements SpdyWriter {
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

    @Override public synchronized void headers(int flags, int streamId, List<String> nameValueBlock)
        throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void rstStream(int streamId, int statusCode) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void data(boolean outFinished, int streamId, byte[] data)
        throws IOException {
      data(outFinished, streamId, data, 0, data.length);
    }

    @Override public void data(boolean outFinished, int streamId, byte[] data, int offset,
        int byteCount) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void settings(int flags, Settings settings) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void noop() throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void ping(int flags, int id) throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    @Override public synchronized void goAway(int flags, int lastGoodStreamId, int statusCode)
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
