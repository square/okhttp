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

import com.squareup.okhttp.internal.Util;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Read spdy/3 frames. */
final class SpdyReader implements Closeable {
  static final byte[] DICTIONARY = ("\u0000\u0000\u0000\u0007options\u0000\u0000\u0000\u0004hea"
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
      + "sdchcharset=utf-8charset=iso-8859-1,utf-,*,enq=0.").getBytes(Util.UTF_8);

  private final DataInputStream in;
  private final DataInputStream nameValueBlockIn;
  private int compressedLimit;

  SpdyReader(InputStream in) {
    this.in = new DataInputStream(in);
    this.nameValueBlockIn = newNameValueBlockStream();
  }

  /**
   * Send the next frame to {@code handler}. Returns true unless there are no
   * more frames on the stream.
   */
  public boolean nextFrame(Handler handler) throws IOException {
    int w1;
    try {
      w1 = in.readInt();
    } catch (IOException e) {
      return false; // This might be a normal socket close.
    }
    int w2 = in.readInt();

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
        case SpdyConnection.TYPE_SYN_STREAM:
          readSynStream(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_SYN_REPLY:
          readSynReply(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_RST_STREAM:
          readRstStream(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_SETTINGS:
          readSettings(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_NOOP:
          if (length != 0) throw ioException("TYPE_NOOP length: %d != 0", length);
          handler.noop();
          return true;

        case SpdyConnection.TYPE_PING:
          readPing(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_GOAWAY:
          readGoAway(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_HEADERS:
          readHeaders(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_WINDOW_UPDATE:
          readWindowUpdate(handler, flags, length);
          return true;

        case SpdyConnection.TYPE_CREDENTIAL:
          Util.skipByReading(in, length);
          throw new UnsupportedOperationException("TODO"); // TODO: implement

        default:
          throw new IOException("Unexpected frame");
      }
    } else {
      int streamId = w1 & 0x7fffffff;
      handler.data(flags, streamId, in, length);
      return true;
    }
  }

  private void readSynStream(Handler handler, int flags, int length) throws IOException {
    int w1 = in.readInt();
    int w2 = in.readInt();
    int s3 = in.readShort();
    int streamId = w1 & 0x7fffffff;
    int associatedStreamId = w2 & 0x7fffffff;
    int priority = (s3 & 0xe000) >>> 13;
    int slot = s3 & 0xff;
    List<String> nameValueBlock = readNameValueBlock(length - 10);
    handler.synStream(flags, streamId, associatedStreamId, priority, slot, nameValueBlock);
  }

  private void readSynReply(Handler handler, int flags, int length) throws IOException {
    int w1 = in.readInt();
    int streamId = w1 & 0x7fffffff;
    List<String> nameValueBlock = readNameValueBlock(length - 4);
    handler.synReply(flags, streamId, nameValueBlock);
  }

  private void readRstStream(Handler handler, int flags, int length) throws IOException {
    if (length != 8) throw ioException("TYPE_RST_STREAM length: %d != 8", length);
    int streamId = in.readInt() & 0x7fffffff;
    int statusCode = in.readInt();
    handler.rstStream(flags, streamId, statusCode);
  }

  private void readHeaders(Handler handler, int flags, int length) throws IOException {
    int w1 = in.readInt();
    int streamId = w1 & 0x7fffffff;
    List<String> nameValueBlock = readNameValueBlock(length - 4);
    handler.headers(flags, streamId, nameValueBlock);
  }

  private void readWindowUpdate(Handler handler, int flags, int length) throws IOException {
    if (length != 8) throw ioException("TYPE_WINDOW_UPDATE length: %d != 8", length);
    int w1 = in.readInt();
    int w2 = in.readInt();
    int streamId = w1 & 0x7fffffff;
    int deltaWindowSize = w2 & 0x7fffffff;
    handler.windowUpdate(flags, streamId, deltaWindowSize);
  }

  private DataInputStream newNameValueBlockStream() {
    // Limit the inflater input stream to only those bytes in the Name/Value block.
    final InputStream throttleStream = new InputStream() {
      @Override public int read() throws IOException {
        return Util.readSingleByte(this);
      }

      @Override public int read(byte[] buffer, int offset, int byteCount) throws IOException {
        byteCount = Math.min(byteCount, compressedLimit);
        int consumed = in.read(buffer, offset, byteCount);
        compressedLimit -= consumed;
        return consumed;
      }

      @Override public void close() throws IOException {
        in.close();
      }
    };

    // Subclass inflater to install a dictionary when it's needed.
    Inflater inflater = new Inflater() {
      @Override
      public int inflate(byte[] buffer, int offset, int count) throws DataFormatException {
        int result = super.inflate(buffer, offset, count);
        if (result == 0 && needsDictionary()) {
          setDictionary(DICTIONARY);
          result = super.inflate(buffer, offset, count);
        }
        return result;
      }
    };

    return new DataInputStream(new InflaterInputStream(throttleStream, inflater));
  }

  private List<String> readNameValueBlock(int length) throws IOException {
    this.compressedLimit += length;
    try {
      int numberOfPairs = nameValueBlockIn.readInt();
      List<String> entries = new ArrayList<String>(numberOfPairs * 2);
      for (int i = 0; i < numberOfPairs; i++) {
        String name = readString();
        String values = readString();
        if (name.length() == 0) throw ioException("name.length == 0");
        if (values.length() == 0) throw ioException("values.length == 0");
        entries.add(name);
        entries.add(values);
      }

      if (compressedLimit != 0) {
        Logger.getLogger(getClass().getName()).warning("compressedLimit > 0: " + compressedLimit);
      }

      return entries;
    } catch (DataFormatException e) {
      throw new IOException(e);
    }
  }

  private String readString() throws DataFormatException, IOException {
    int length = nameValueBlockIn.readInt();
    byte[] bytes = new byte[length];
    Util.readFully(nameValueBlockIn, bytes);
    return new String(bytes, 0, length, "UTF-8");
  }

  private void readPing(Handler handler, int flags, int length) throws IOException {
    if (length != 4) throw ioException("TYPE_PING length: %d != 4", length);
    int id = in.readInt();
    handler.ping(flags, id);
  }

  private void readGoAway(Handler handler, int flags, int length) throws IOException {
    if (length != 8) throw ioException("TYPE_GOAWAY length: %d != 8", length);
    int lastGoodStreamId = in.readInt() & 0x7fffffff;
    int statusCode = in.readInt();
    handler.goAway(flags, lastGoodStreamId, statusCode);
  }

  private void readSettings(Handler handler, int flags, int length) throws IOException {
    int numberOfEntries = in.readInt();
    if (length != 4 + 8 * numberOfEntries) {
      throw ioException("TYPE_SETTINGS length: %d != 4 + 8 * %d", length, numberOfEntries);
    }
    Settings settings = new Settings();
    for (int i = 0; i < numberOfEntries; i++) {
      int w1 = in.readInt();
      int value = in.readInt();
      int idFlags = (w1 & 0xff000000) >>> 24;
      int id = w1 & 0xffffff;
      settings.set(id, idFlags, value);
    }
    handler.settings(flags, settings);
  }

  private static IOException ioException(String message, Object... args) throws IOException {
    throw new IOException(String.format(message, args));
  }

  @Override public void close() throws IOException {
    Util.closeAll(in, nameValueBlockIn);
  }

  public interface Handler {
    void data(int flags, int streamId, InputStream in, int length) throws IOException;

    void synStream(int flags, int streamId, int associatedStreamId, int priority, int slot,
        List<String> nameValueBlock);

    void synReply(int flags, int streamId, List<String> nameValueBlock) throws IOException;
    void headers(int flags, int streamId, List<String> nameValueBlock) throws IOException;
    void rstStream(int flags, int streamId, int statusCode);
    void settings(int flags, Settings settings);
    void noop();
    void ping(int flags, int streamId);
    void goAway(int flags, int lastGoodStreamId, int statusCode);
    void windowUpdate(int flags, int streamId, int deltaWindowSize);
  }
}
