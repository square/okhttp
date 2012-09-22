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

package libcore.net.spdy;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import libcore.io.Streams;

/**
 * Read version 2 SPDY frames.
 */
final class SpdyReader {
    public static final Charset UTF_8 = Charset.forName("UTF-8");
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

    public final DataInputStream in;
    public int flags;
    public int length;
    public int streamId;
    public int associatedStreamId;
    public int version;
    public int type;
    public int priority;
    public int statusCode;

    public List<String> nameValueBlock;
    private final DataInputStream nameValueBlockIn;
    private int compressedLimit;

    SpdyReader(InputStream in) {
        this.in = new DataInputStream(in);
        this.nameValueBlockIn = newNameValueBlockStream();
    }

    /**
     * Advance to the next frame in the source data. If the frame is of
     * TYPE_DATA, it's the caller's responsibility to read length bytes from
     * the input stream before the next call to nextFrame().
     */
    public int nextFrame() throws IOException {
        int w1;
        try {
            w1 = in.readInt();
        } catch (EOFException e) {
            return SpdyConnection.TYPE_EOF;
        }
        int w2 = in.readInt();

        boolean control = (w1 & 0x80000000) != 0;
        flags = (w2 & 0xff000000) >>> 24;
        length = (w2 & 0xffffff);

        if (control) {
            version = (w1 & 0x7fff0000) >>> 16;
            type = (w1 & 0xffff);

            switch (type) {
            case SpdyConnection.TYPE_SYN_STREAM:
                readSynStream();
                return SpdyConnection.TYPE_SYN_STREAM;

            case SpdyConnection.TYPE_SYN_REPLY:
                readSynReply();
                return SpdyConnection.TYPE_SYN_REPLY;

            case SpdyConnection.TYPE_RST_STREAM:
                readSynReset();
                return SpdyConnection.TYPE_RST_STREAM;

            case SpdyConnection.TYPE_SETTINGS:
                return SpdyConnection.TYPE_SETTINGS;

            default:
                readControlFrame();
                return type;
            }
        } else {
            streamId = w1 & 0x7fffffff;
            return SpdyConnection.TYPE_DATA;
        }
    }

    private void readSynStream() throws IOException {
        int w1 = in.readInt();
        int w2 = in.readInt();
        int s3 = in.readShort();
        streamId = w1 & 0x7fffffff;
        associatedStreamId = w2 & 0x7fffffff;
        priority = s3 & 0xc000 >>> 14;
        // int unused = s3 & 0x3fff;
        nameValueBlock = readNameValueBlock(length - 10);
    }

    private void readSynReply() throws IOException {
        int w1 = in.readInt();
        in.readShort(); // unused
        streamId = w1 & 0x7fffffff;
        nameValueBlock = readNameValueBlock(length - 6);
    }

    private void readSynReset() throws IOException {
        streamId = in.readInt() & 0x7fffffff;
        statusCode = in.readInt();
    }

    private void readControlFrame() throws IOException {
        Streams.skipByReading(in, length);
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
            List<String> entries = new ArrayList<String>();

            int numberOfPairs = nameValueBlockIn.readShort();
            for (int i = 0; i < numberOfPairs; i++) {
                String name = readString();
                String values = readString();
                if (name.length() == 0 || values.length() == 0) {
                    throw new IOException(); // TODO: PROTOCOL ERROR
                }
                entries.add(name);
                entries.add(values);
            }

            if (compressedLimit != 0) {
                Logger.getLogger(getClass().getName())
                        .warning("compressedLimit > 0" + compressedLimit);
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
}
