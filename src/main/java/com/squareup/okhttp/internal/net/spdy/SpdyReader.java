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

package com.squareup.okhttp.internal.net.spdy;

import com.squareup.okhttp.internal.io.IoUtils;
import com.squareup.okhttp.internal.io.Streams;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Read version 2 SPDY frames.
 */
final class SpdyReader implements Closeable {
    private static final String DICTIONARY_STRING = ""
            + "optionsgetheadpostputdeletetraceacceptaccept-charsetaccept-encodingaccept-"
            + "languageauthorizationexpectfromhostif-modified-sinceif-matchif-none-matchi"
            + "f-rangeif-unmodifiedsincemax-forwardsproxy-authorizationrangerefererteuser"
            + "-agent10010120020120220320420520630030130230330430530630740040140240340440"
            + "5406407408409410411412413414415416417500501502503504505accept-rangesageeta"
            + "glocationproxy-authenticatepublicretry-afterservervarywarningwww-authentic"
            + "ateallowcontent-basecontent-encodingcache-controlconnectiondatetrailertran"
            + "sfer-encodingupgradeviawarningcontent-languagecontent-lengthcontent-locati"
            + "oncontent-md5content-rangecontent-typeetagexpireslast-modifiedset-cookieMo"
            + "ndayTuesdayWednesdayThursdayFridaySaturdaySundayJanFebMarAprMayJunJulAugSe"
            + "pOctNovDecchunkedtext/htmlimage/pngimage/jpgimage/gifapplication/xmlapplic"
            + "ation/xhtmltext/plainpublicmax-agecharset=iso-8859-1utf-8gzipdeflateHTTP/1"
            + ".1statusversionurl\0";
    public static final byte[] DICTIONARY;
    static {
        try {
            DICTIONARY = DICTIONARY_STRING.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

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
                Streams.skipByReading(in, length);
                throw new UnsupportedOperationException("TODO");

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
        int priority = s3 & 0xc000 >>> 14;
        // int unused = s3 & 0x3fff;
        List<String> nameValueBlock = readNameValueBlock(length - 10);
        handler.synStream(flags, streamId, associatedStreamId, priority, nameValueBlock);
    }

    private void readSynReply(Handler handler, int flags, int length) throws IOException {
        int w1 = in.readInt();
        in.readShort(); // unused
        int streamId = w1 & 0x7fffffff;
        List<String> nameValueBlock = readNameValueBlock(length - 6);
        handler.synReply(flags, streamId, nameValueBlock);
    }

    private void readRstStream(Handler handler, int flags, int length) throws IOException {
        if (length != 8) throw ioException("TYPE_RST_STREAM length: %d != 8", length);
        int streamId = in.readInt() & 0x7fffffff;
        int statusCode = in.readInt();
        handler.rstStream(flags, streamId, statusCode);
    }

    private DataInputStream newNameValueBlockStream() {
        // Limit the inflater input stream to only those bytes in the Name/Value block.
        final InputStream throttleStream = new InputStream() {
            @Override public int read() throws IOException {
                return Streams.readSingleByte(this);
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
            int numberOfPairs = nameValueBlockIn.readShort();
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
                Logger.getLogger(getClass().getName())
                        .warning("compressedLimit > 0: " + compressedLimit);
            }

            return entries;
        } catch (DataFormatException e) {
            throw new IOException(e);
        }
    }

    private String readString() throws DataFormatException, IOException {
        int length = nameValueBlockIn.readShort();
        byte[] bytes = new byte[length];
        Streams.readFully(nameValueBlockIn, bytes);
        return new String(bytes, 0, length, "UTF-8");
    }

    private void readPing(Handler handler, int flags, int length) throws IOException {
        if (length != 4) throw ioException("TYPE_PING length: %d != 4", length);
        int id = in.readInt();
        handler.ping(flags, id);
    }

    private void readGoAway(Handler handler, int flags, int length) throws IOException {
        if (length != 4) throw ioException("TYPE_GOAWAY length: %d != 4", length);
        int lastGoodStreamId = in.readInt() & 0x7fffffff;
        handler.goAway(flags, lastGoodStreamId);
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
            // The ID is a 24 bit little-endian value, so 0xabcdefxx becomes 0x00efcdab.
            int id = ((w1 & 0xff000000) >>> 24)
                    | ((w1 & 0xff0000) >>> 8)
                    | ((w1 & 0xff00) << 8);
            int idFlags = (w1 & 0xff);
            settings.set(id, idFlags, value);
        }
        handler.settings(flags, settings);
    }

    private static IOException ioException(String message, Object... args) throws IOException {
        throw new IOException(String.format(message, args));
    }

    @Override public void close() throws IOException {
        IoUtils.closeAll(in, nameValueBlockIn);
    }

    public interface Handler {
        void data(int flags, int streamId, InputStream in, int length) throws IOException;
        void synStream(int flags, int streamId, int associatedStreamId, int priority,
                List<String> nameValueBlock);
        void synReply(int flags, int streamId, List<String> nameValueBlock) throws IOException;
        void rstStream(int flags, int streamId, int statusCode);
        void settings(int flags, Settings settings);
        void noop();
        void ping(int flags, int streamId);
        void goAway(int flags, int lastGoodStreamId);
        // TODO: headers
    }
}
