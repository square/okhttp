/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.Deflater;

/** Write spdy/3 frames. */
final class SpdyWriter implements Closeable {
  final DataOutputStream out;
  private final ByteArrayOutputStream nameValueBlockBuffer;
  private final DataOutputStream nameValueBlockOut;

  SpdyWriter(OutputStream out) {
    this.out = new DataOutputStream(out);

    Deflater deflater = new Deflater();
    deflater.setDictionary(SpdyReader.DICTIONARY);
    nameValueBlockBuffer = new ByteArrayOutputStream();
    nameValueBlockOut = new DataOutputStream(
        Platform.get().newDeflaterOutputStream(nameValueBlockBuffer, deflater, true));
  }

  public synchronized void synStream(int flags, int streamId, int associatedStreamId, int priority,
      int slot, List<String> nameValueBlock) throws IOException {
    writeNameValueBlockToBuffer(nameValueBlock);
    int length = 10 + nameValueBlockBuffer.size();
    int type = SpdyConnection.TYPE_SYN_STREAM;

    int unused = 0;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(streamId & 0x7fffffff);
    out.writeInt(associatedStreamId & 0x7fffffff);
    out.writeShort((priority & 0x7) << 13 | (unused & 0x1f) << 8 | (slot & 0xff));
    nameValueBlockBuffer.writeTo(out);
    out.flush();
  }

  public synchronized void synReply(int flags, int streamId, List<String> nameValueBlock)
      throws IOException {
    writeNameValueBlockToBuffer(nameValueBlock);
    int type = SpdyConnection.TYPE_SYN_REPLY;
    int length = nameValueBlockBuffer.size() + 4;

    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(streamId & 0x7fffffff);
    nameValueBlockBuffer.writeTo(out);
    out.flush();
  }

  public synchronized void headers(int flags, int streamId, List<String> nameValueBlock)
      throws IOException {
    writeNameValueBlockToBuffer(nameValueBlock);
    int type = SpdyConnection.TYPE_HEADERS;
    int length = nameValueBlockBuffer.size() + 4;

    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(streamId & 0x7fffffff);
    nameValueBlockBuffer.writeTo(out);
    out.flush();
  }

  public synchronized void rstStream(int streamId, int statusCode) throws IOException {
    int flags = 0;
    int type = SpdyConnection.TYPE_RST_STREAM;
    int length = 8;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(streamId & 0x7fffffff);
    out.writeInt(statusCode);
    out.flush();
  }

  public synchronized void data(int flags, int streamId, byte[] data) throws IOException {
    int length = data.length;
    out.writeInt(streamId & 0x7fffffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.write(data);
    out.flush();
  }

  private void writeNameValueBlockToBuffer(List<String> nameValueBlock) throws IOException {
    nameValueBlockBuffer.reset();
    int numberOfPairs = nameValueBlock.size() / 2;
    nameValueBlockOut.writeInt(numberOfPairs);
    for (String s : nameValueBlock) {
      nameValueBlockOut.writeInt(s.length());
      nameValueBlockOut.write(s.getBytes("UTF-8"));
    }
    nameValueBlockOut.flush();
  }

  public synchronized void settings(int flags, Settings settings) throws IOException {
    int type = SpdyConnection.TYPE_SETTINGS;
    int size = settings.size();
    int length = 4 + size * 8;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(size);
    for (int i = 0; i <= Settings.COUNT; i++) {
      if (!settings.isSet(i)) continue;
      int settingsFlags = settings.flags(i);
      out.writeInt((settingsFlags & 0xff) << 24 | (i & 0xffffff));
      out.writeInt(settings.get(i));
    }
    out.flush();
  }

  public synchronized void noop() throws IOException {
    int type = SpdyConnection.TYPE_NOOP;
    int length = 0;
    int flags = 0;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.flush();
  }

  public synchronized void ping(int flags, int id) throws IOException {
    int type = SpdyConnection.TYPE_PING;
    int length = 4;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(id);
    out.flush();
  }

  public synchronized void goAway(int flags, int lastGoodStreamId, int statusCode)
      throws IOException {
    int type = SpdyConnection.TYPE_GOAWAY;
    int length = 8;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(lastGoodStreamId);
    out.writeInt(statusCode);
    out.flush();
  }

  public synchronized void windowUpdate(int streamId, int deltaWindowSize) throws IOException {
    int type = SpdyConnection.TYPE_WINDOW_UPDATE;
    int flags = 0;
    int length = 8;
    out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
    out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
    out.writeInt(streamId);
    out.writeInt(deltaWindowSize);
    out.flush();
  }

  @Override public void close() throws IOException {
    Util.closeAll(out, nameValueBlockOut);
  }
}
