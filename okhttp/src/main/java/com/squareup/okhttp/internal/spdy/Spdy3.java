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

import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ProtocolException;
import java.util.List;
import java.util.zip.Deflater;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.DeflaterSink;
import okio.OkBuffer;
import okio.Okio;

/**
 * Read and write spdy/3.1 frames.
 * http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3-1
 */
final class Spdy3 implements Variant {

  @Override public Protocol getProtocol() {
    return Protocol.SPDY_3;
  }

  static final int TYPE_DATA = 0x0;
  static final int TYPE_SYN_STREAM = 0x1;
  static final int TYPE_SYN_REPLY = 0x2;
  static final int TYPE_RST_STREAM = 0x3;
  static final int TYPE_SETTINGS = 0x4;
  static final int TYPE_PING = 0x6;
  static final int TYPE_GOAWAY = 0x7;
  static final int TYPE_HEADERS = 0x8;
  static final int TYPE_WINDOW_UPDATE = 0x9;

  static final int FLAG_FIN = 0x1;
  static final int FLAG_UNIDIRECTIONAL = 0x2;

  static final int VERSION = 3;

  static final byte[] DICTIONARY;
  static {
    try {
      DICTIONARY = ("\u0000\u0000\u0000\u0007options\u0000\u0000\u0000\u0004hea"
          + "d\u0000\u0000\u0000\u0004post\u0000\u0000\u0000\u0003put\u0000\u0000\u0000\u0006dele"
          + "te\u0000\u0000\u0000\u0005trace\u0000\u0000\u0000\u0006accept\u0000\u0000\u0000"
          + "\u000Eaccept-charset\u0000\u0000\u0000\u000Faccept-encoding\u0000\u0000\u0000\u000Fa"
          + "ccept-language\u0000\u0000\u0000\raccept-ranges\u0000\u0000\u0000\u0003age\u0000"
          + "\u0000\u0000\u0005allow\u0000\u0000\u0000\rauthorization\u0000\u0000\u0000\rcache-co"
          + "ntrol\u0000\u0000\u0000\nconnection\u0000\u0000\u0000\fcontent-base\u0000\u0000"
          + "\u0000\u0010content-encoding\u0000\u0000\u0000\u0010content-language\u0000\u0000"
          + "\u0000\u000Econtent-length\u0000\u0000\u0000\u0010content-location\u0000\u0000\u0000"
          + "\u000Bcontent-md5\u0000\u0000\u0000\rcontent-range\u0000\u0000\u0000\fcontent-type"
          + "\u0000\u0000\u0000\u0004date\u0000\u0000\u0000\u0004etag\u0000\u0000\u0000\u0006expe"
          + "ct\u0000\u0000\u0000\u0007expires\u0000\u0000\u0000\u0004from\u0000\u0000\u0000"
          + "\u0004host\u0000\u0000\u0000\bif-match\u0000\u0000\u0000\u0011if-modified-since"
          + "\u0000\u0000\u0000\rif-none-match\u0000\u0000\u0000\bif-range\u0000\u0000\u0000"
          + "\u0013if-unmodified-since\u0000\u0000\u0000\rlast-modified\u0000\u0000\u0000\blocati"
          + "on\u0000\u0000\u0000\fmax-forwards\u0000\u0000\u0000\u0006pragma\u0000\u0000\u0000"
          + "\u0012proxy-authenticate\u0000\u0000\u0000\u0013proxy-authorization\u0000\u0000"
          + "\u0000\u0005range\u0000\u0000\u0000\u0007referer\u0000\u0000\u0000\u000Bretry-after"
          + "\u0000\u0000\u0000\u0006server\u0000\u0000\u0000\u0002te\u0000\u0000\u0000\u0007trai"
          + "ler\u0000\u0000\u0000\u0011transfer-encoding\u0000\u0000\u0000\u0007upgrade\u0000"
          + "\u0000\u0000\nuser-agent\u0000\u0000\u0000\u0004vary\u0000\u0000\u0000\u0003via"
          + "\u0000\u0000\u0000\u0007warning\u0000\u0000\u0000\u0010www-authenticate\u0000\u0000"
          + "\u0000\u0006method\u0000\u0000\u0000\u0003get\u0000\u0000\u0000\u0006status\u0000"
          + "\u0000\u0000\u0006200 OK\u0000\u0000\u0000\u0007version\u0000\u0000\u0000\bHTTP/1.1"
          + "\u0000\u0000\u0000\u0003url\u0000\u0000\u0000\u0006public\u0000\u0000\u0000\nset-coo"
          + "kie\u0000\u0000\u0000\nkeep-alive\u0000\u0000\u0000\u0006origin100101201202205206300"
          + "302303304305306307402405406407408409410411412413414415416417502504505203 Non-Authori"
          + "tative Information204 No Content301 Moved Permanently400 Bad Request401 Unauthorized"
          + "403 Forbidden404 Not Found500 Internal Server Error501 Not Implemented503 Service Un"
          + "availableJan Feb Mar Apr May Jun Jul Aug Sept Oct Nov Dec 00:00:00 Mon, Tue, Wed, Th"
          + "u, Fri, Sat, Sun, GMTchunked,text/html,image/png,image/jpg,image/gif,application/xml"
          + ",application/xhtml+xml,text/plain,text/javascript,publicprivatemax-age=gzip,deflate,"
          + "sdchcharset=utf-8charset=iso-8859-1,utf-,*,enq=0.").getBytes(Util.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError();
    }
  }

  @Override public FrameReader newReader(BufferedSource source, boolean client) {
    return new Reader(source, client);
  }

  @Override public FrameWriter newWriter(BufferedSink sink, boolean client) {
    return new Writer(sink, client);
  }

  @Override public int maxFrameSize() {
    return 16383;
  }

  /** Read spdy/3 frames. */
  static final class Reader implements FrameReader {
    private final BufferedSource source;
    private final boolean client;
    private final NameValueBlockReader headerBlockReader;

    Reader(BufferedSource source, boolean client) {
      this.source = source;
      this.headerBlockReader = new NameValueBlockReader(this.source);
      this.client = client;
    }

    @Override public void readConnectionHeader() {
    }

    /**
     * Send the next frame to {@code handler}. Returns true unless there are no
     * more frames on the stream.
     */
    @Override public boolean nextFrame(Handler handler) throws IOException {
      int w1;
      int w2;
      try {
        w1 = source.readInt();
        w2 = source.readInt();
      } catch (IOException e) {
        return false; // This might be a normal socket close.
      }

      boolean control = (w1 & 0x80000000) != 0;
      int flags = (w2 & 0xff000000) >>> 24;
      int length = (w2 & 0xffffff);

      if (control) {
        int version = (w1 & 0x7fff0000) >>> 16;
        int type = (w1 & 0xffff);

        if (version != 3) {
          throw new ProtocolException("version != 3: " + version);
        }

        switch (type) {
          case TYPE_SYN_STREAM:
            readSynStream(handler, flags, length);
            return true;

          case TYPE_SYN_REPLY:
            readSynReply(handler, flags, length);
            return true;

          case TYPE_RST_STREAM:
            readRstStream(handler, flags, length);
            return true;

          case TYPE_SETTINGS:
            readSettings(handler, flags, length);
            return true;

          case TYPE_PING:
            readPing(handler, flags, length);
            return true;

          case TYPE_GOAWAY:
            readGoAway(handler, flags, length);
            return true;

          case TYPE_HEADERS:
            readHeaders(handler, flags, length);
            return true;

          case TYPE_WINDOW_UPDATE:
            readWindowUpdate(handler, flags, length);
            return true;

          default:
            source.skip(length);
            return true;
        }
      } else {
        int streamId = w1 & 0x7fffffff;
        boolean inFinished = (flags & FLAG_FIN) != 0;
        handler.data(inFinished, streamId, source, length);
        return true;
      }
    }

    private void readSynStream(Handler handler, int flags, int length) throws IOException {
      int w1 = source.readInt();
      int w2 = source.readInt();
      int s3 = source.readShort();
      int streamId = w1 & 0x7fffffff;
      int associatedStreamId = w2 & 0x7fffffff;
      int priority = (s3 & 0xe000) >>> 13;
      // int slot = s3 & 0xff;
      List<Header> headerBlock = headerBlockReader.readNameValueBlock(length - 10);

      boolean inFinished = (flags & FLAG_FIN) != 0;
      boolean outFinished = (flags & FLAG_UNIDIRECTIONAL) != 0;
      handler.headers(outFinished, inFinished, streamId, associatedStreamId, priority,
          headerBlock, HeadersMode.SPDY_SYN_STREAM);
    }

    private void readSynReply(Handler handler, int flags, int length) throws IOException {
      int w1 = source.readInt();
      int streamId = w1 & 0x7fffffff;
      List<Header> headerBlock = headerBlockReader.readNameValueBlock(length - 4);
      boolean inFinished = (flags & FLAG_FIN) != 0;
      handler.headers(false, inFinished, streamId, -1, -1, headerBlock, HeadersMode.SPDY_REPLY);
    }

    private void readRstStream(Handler handler, int flags, int length) throws IOException {
      if (length != 8) throw ioException("TYPE_RST_STREAM length: %d != 8", length);
      int streamId = source.readInt() & 0x7fffffff;
      int errorCodeInt = source.readInt();
      ErrorCode errorCode = ErrorCode.fromSpdy3Rst(errorCodeInt);
      if (errorCode == null) {
        throw ioException("TYPE_RST_STREAM unexpected error code: %d", errorCodeInt);
      }
      handler.rstStream(streamId, errorCode);
    }

    private void readHeaders(Handler handler, int flags, int length) throws IOException {
      int w1 = source.readInt();
      int streamId = w1 & 0x7fffffff;
      List<Header> headerBlock = headerBlockReader.readNameValueBlock(length - 4);
      handler.headers(false, false, streamId, -1, -1, headerBlock, HeadersMode.SPDY_HEADERS);
    }

    private void readWindowUpdate(Handler handler, int flags, int length) throws IOException {
      if (length != 8) throw ioException("TYPE_WINDOW_UPDATE length: %d != 8", length);
      int w1 = source.readInt();
      int w2 = source.readInt();
      int streamId = w1 & 0x7fffffff;
      long increment = w2 & 0x7fffffff;
      if (increment == 0) throw ioException("windowSizeIncrement was 0", increment);
      handler.windowUpdate(streamId, increment);
    }

    private void readPing(Handler handler, int flags, int length) throws IOException {
      if (length != 4) throw ioException("TYPE_PING length: %d != 4", length);
      int id = source.readInt();
      boolean ack = client == ((id & 1) == 1);
      handler.ping(ack, id, 0);
    }

    private void readGoAway(Handler handler, int flags, int length) throws IOException {
      if (length != 8) throw ioException("TYPE_GOAWAY length: %d != 8", length);
      int lastGoodStreamId = source.readInt() & 0x7fffffff;
      int errorCodeInt = source.readInt();
      ErrorCode errorCode = ErrorCode.fromSpdyGoAway(errorCodeInt);
      if (errorCode == null) {
        throw ioException("TYPE_GOAWAY unexpected error code: %d", errorCodeInt);
      }
      handler.goAway(lastGoodStreamId, errorCode, ByteString.EMPTY);
    }

    private void readSettings(Handler handler, int flags, int length) throws IOException {
      int numberOfEntries = source.readInt();
      if (length != 4 + 8 * numberOfEntries) {
        throw ioException("TYPE_SETTINGS length: %d != 4 + 8 * %d", length, numberOfEntries);
      }
      Settings settings = new Settings();
      for (int i = 0; i < numberOfEntries; i++) {
        int w1 = source.readInt();
        int value = source.readInt();
        int idFlags = (w1 & 0xff000000) >>> 24;
        int id = w1 & 0xffffff;
        settings.set(id, idFlags, value);
      }
      boolean clearPrevious = (flags & Settings.FLAG_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS) != 0;
      handler.settings(clearPrevious, settings);
    }

    private static IOException ioException(String message, Object... args) throws IOException {
      throw new IOException(String.format(message, args));
    }

    @Override public void close() throws IOException {
      headerBlockReader.close();
    }
  }

  /** Write spdy/3 frames. */
  static final class Writer implements FrameWriter {
    private final BufferedSink sink;
    private final OkBuffer headerBlockBuffer;
    private final BufferedSink headerBlockOut;
    private final boolean client;
    private boolean closed;

    Writer(BufferedSink sink, boolean client) {
      this.sink = sink;
      this.client = client;

      Deflater deflater = new Deflater();
      deflater.setDictionary(DICTIONARY);
      headerBlockBuffer = new OkBuffer();
      headerBlockOut = Okio.buffer(new DeflaterSink(headerBlockBuffer, deflater));
    }

    @Override public void ackSettings() {
      // Do nothing: no ACK for SPDY/3 settings.
    }

    @Override
    public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
        throws IOException {
      // Do nothing: no push promise for SPDY/3.
    }

    @Override public synchronized void connectionHeader() {
      // Do nothing: no connection header for SPDY/3.
    }

    @Override public synchronized void flush() throws IOException {
      if (closed) throw new IOException("closed");
      sink.flush();
    }

    @Override public synchronized void synStream(boolean outFinished, boolean inFinished,
        int streamId, int associatedStreamId, int priority, int slot, List<Header> headerBlock)
        throws IOException {
      if (closed) throw new IOException("closed");
      writeNameValueBlockToBuffer(headerBlock);
      int length = (int) (10 + headerBlockBuffer.size());
      int type = TYPE_SYN_STREAM;
      int flags = (outFinished ? FLAG_FIN : 0) | (inFinished ? FLAG_UNIDIRECTIONAL : 0);

      int unused = 0;
      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(streamId & 0x7fffffff);
      sink.writeInt(associatedStreamId & 0x7fffffff);
      sink.writeShort((priority & 0x7) << 13 | (unused & 0x1f) << 8 | (slot & 0xff));
      sink.write(headerBlockBuffer, headerBlockBuffer.size());
      sink.flush();
    }

    @Override public synchronized void synReply(boolean outFinished, int streamId,
        List<Header> headerBlock) throws IOException {
      if (closed) throw new IOException("closed");
      writeNameValueBlockToBuffer(headerBlock);
      int type = TYPE_SYN_REPLY;
      int flags = (outFinished ? FLAG_FIN : 0);
      int length = (int) (headerBlockBuffer.size() + 4);

      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(streamId & 0x7fffffff);
      sink.write(headerBlockBuffer, headerBlockBuffer.size());
      sink.flush();
    }

    @Override public synchronized void headers(int streamId, List<Header> headerBlock)
        throws IOException {
      if (closed) throw new IOException("closed");
      writeNameValueBlockToBuffer(headerBlock);
      int flags = 0;
      int type = TYPE_HEADERS;
      int length = (int) (headerBlockBuffer.size() + 4);

      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(streamId & 0x7fffffff);
      sink.write(headerBlockBuffer, headerBlockBuffer.size());
    }

    @Override public synchronized void rstStream(int streamId, ErrorCode errorCode)
        throws IOException {
      if (closed) throw new IOException("closed");
      if (errorCode.spdyRstCode == -1) throw new IllegalArgumentException();
      int flags = 0;
      int type = TYPE_RST_STREAM;
      int length = 8;
      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(streamId & 0x7fffffff);
      sink.writeInt(errorCode.spdyRstCode);
      sink.flush();
    }

    @Override public synchronized void data(boolean outFinished, int streamId, OkBuffer source)
        throws IOException {
      data(outFinished, streamId, source, (int) source.size());
    }

    @Override public synchronized void data(boolean outFinished, int streamId, OkBuffer source,
        int byteCount) throws IOException {
      int flags = (outFinished ? FLAG_FIN : 0);
      sendDataFrame(streamId, flags, source, byteCount);
    }

    void sendDataFrame(int streamId, int flags, OkBuffer buffer, int byteCount)
        throws IOException {
      if (closed) throw new IOException("closed");
      if (byteCount > 0xffffffL) {
        throw new IllegalArgumentException("FRAME_TOO_LARGE max size is 16Mib: " + byteCount);
      }
      sink.writeInt(streamId & 0x7fffffff);
      sink.writeInt((flags & 0xff) << 24 | byteCount & 0xffffff);
      if (byteCount > 0) {
        sink.write(buffer, byteCount);
      }
    }

    private void writeNameValueBlockToBuffer(List<Header> headerBlock) throws IOException {
      if (headerBlockBuffer.size() != 0) throw new IllegalStateException();
      headerBlockOut.writeInt(headerBlock.size());
      for (int i = 0, size = headerBlock.size(); i < size; i++) {
        ByteString name = headerBlock.get(i).name;
        headerBlockOut.writeInt(name.size());
        headerBlockOut.write(name);
        ByteString value = headerBlock.get(i).value;
        headerBlockOut.writeInt(value.size());
        headerBlockOut.write(value);
      }
      headerBlockOut.flush();
    }

    @Override public synchronized void settings(Settings settings) throws IOException {
      if (closed) throw new IOException("closed");
      int type = TYPE_SETTINGS;
      int flags = 0;
      int size = settings.size();
      int length = 4 + size * 8;
      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(size);
      for (int i = 0; i <= Settings.COUNT; i++) {
        if (!settings.isSet(i)) continue;
        int settingsFlags = settings.flags(i);
        sink.writeInt((settingsFlags & 0xff) << 24 | (i & 0xffffff));
        sink.writeInt(settings.get(i));
      }
      sink.flush();
    }

    @Override public synchronized void ping(boolean reply, int payload1, int payload2)
        throws IOException {
      if (closed) throw new IOException("closed");
      boolean payloadIsReply = client != ((payload1 & 1) == 1);
      if (reply != payloadIsReply) throw new IllegalArgumentException("payload != reply");
      int type = TYPE_PING;
      int flags = 0;
      int length = 4;
      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(payload1);
      sink.flush();
    }

    @Override public synchronized void goAway(int lastGoodStreamId, ErrorCode errorCode,
        byte[] ignored) throws IOException {
      if (closed) throw new IOException("closed");
      if (errorCode.spdyGoAwayCode == -1) {
        throw new IllegalArgumentException("errorCode.spdyGoAwayCode == -1");
      }
      int type = TYPE_GOAWAY;
      int flags = 0;
      int length = 8;
      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(lastGoodStreamId);
      sink.writeInt(errorCode.spdyGoAwayCode);
      sink.flush();
    }

    @Override public synchronized void windowUpdate(int streamId, long increment)
        throws IOException {
      if (closed) throw new IOException("closed");
      if (increment == 0 || increment > 0x7fffffffL) {
        throw new IllegalArgumentException(
            "windowSizeIncrement must be between 1 and 0x7fffffff: " + increment);
      }
      int type = TYPE_WINDOW_UPDATE;
      int flags = 0;
      int length = 8;
      sink.writeInt(0x80000000 | (VERSION & 0x7fff) << 16 | type & 0xffff);
      sink.writeInt((flags & 0xff) << 24 | length & 0xffffff);
      sink.writeInt(streamId);
      sink.writeInt((int) increment);
      sink.flush();
    }

    @Override public synchronized void close() throws IOException {
      closed = true;
      Util.closeAll(sink, headerBlockOut);
    }
  }
}
