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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Write version 2 SPDY frames.
 */
final class SpdyWriter {
    final DataOutputStream out;
    public int flags;
    public int streamId;
    public int associatedStreamId;
    public int priority;
    public int statusCode;

    public List<String> nameValueBlock;
    private final ByteArrayOutputStream nameValueBlockBuffer;
    private final DataOutputStream nameValueBlockOut;

    SpdyWriter(OutputStream out) {
        this.out = new DataOutputStream(out);

        Deflater deflater = new Deflater();
        deflater.setDictionary(SpdyReader.DICTIONARY);
        nameValueBlockBuffer = new ByteArrayOutputStream();
        nameValueBlockOut = new DataOutputStream(
                new DeflaterOutputStream(nameValueBlockBuffer, deflater, true));
    }

    public void synStream() throws IOException {
        writeNameValueBlockToBuffer();
        int length = 10 + nameValueBlockBuffer.size();
        int type = SpdyConnection.TYPE_SYN_STREAM;

        int unused = 0;
        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.writeInt(streamId & 0x7fffffff);
        out.writeInt(associatedStreamId & 0x7fffffff);
        out.writeShort((priority & 0x3) << 30 | (unused & 0x3FFF) << 16);
        nameValueBlockBuffer.writeTo(out);
        out.flush();
    }

    public void synReply() throws IOException {
        writeNameValueBlockToBuffer();
        int type = SpdyConnection.TYPE_SYN_REPLY;
        int length = nameValueBlockBuffer.size() + 6;
        int unused = 0;

        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.writeInt(streamId & 0x7fffffff);
        out.writeShort(unused);
        nameValueBlockBuffer.writeTo(out);
        out.flush();
    }

    public void synReset() throws IOException {
        int type = SpdyConnection.TYPE_RST_STREAM;
        int length = 8;
        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.writeInt(streamId & 0x7fffffff);
        out.writeInt(statusCode);
    }

    public void data(byte[] data) throws IOException {
        int length = data.length;
        out.writeInt(streamId & 0x7fffffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.write(data);
        out.flush();
    }

    private void writeNameValueBlockToBuffer() throws IOException {
        nameValueBlockBuffer.reset();
        int numberOfPairs = nameValueBlock.size() / 2;
        nameValueBlockOut.writeShort(numberOfPairs);
        for (String s : nameValueBlock) {
            nameValueBlockOut.writeShort(s.length());
            nameValueBlockOut.write(s.getBytes(SpdyReader.UTF_8));
        }
        nameValueBlockOut.flush();
    }
}
