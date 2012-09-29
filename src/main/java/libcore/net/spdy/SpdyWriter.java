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

    public void synStream(int flags, int streamId, int associatedStreamId, int priority,
            List<String> nameValueBlock) throws IOException {
        writeNameValueBlockToBuffer(nameValueBlock);
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

    public void synReply(int flags, int streamId, List<String> nameValueBlock) throws IOException {
        writeNameValueBlockToBuffer(nameValueBlock);
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

    public void synReset(int streamId, int statusCode) throws IOException {
        int flags = 0;
        int type = SpdyConnection.TYPE_RST_STREAM;
        int length = 8;
        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.writeInt(streamId & 0x7fffffff);
        out.writeInt(statusCode);
        out.flush();
    }

    public void data(int flags, int streamId, byte[] data) throws IOException {
        int length = data.length;
        out.writeInt(streamId & 0x7fffffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.write(data);
        out.flush();
    }

    private void writeNameValueBlockToBuffer(List<String> nameValueBlock) throws IOException {
        nameValueBlockBuffer.reset();
        int numberOfPairs = nameValueBlock.size() / 2;
        nameValueBlockOut.writeShort(numberOfPairs);
        for (String s : nameValueBlock) {
            nameValueBlockOut.writeShort(s.length());
            nameValueBlockOut.write(s.getBytes("UTF-8"));
        }
        nameValueBlockOut.flush();
    }

    public void settings(int flags, Settings settings) throws IOException {
        int type = SpdyConnection.TYPE_SETTINGS;
        int size = settings.size();
        int length = 4 + size * 8;
        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.writeInt(size);
        for (int i = 0; i <= Settings.COUNT; i++) {
            if (!settings.isSet(i)) continue;
            int settingsFlags = settings.flags(i);
            // settingId 0x00efcdab and settingFlags 0x12 combine to 0xabcdef12.
            out.writeInt(((i & 0xff0000) >>> 8)
                    | ((i & 0xff00) << 8)
                    | ((i & 0xff) << 24)
                    | (settingsFlags & 0xff));
            out.writeInt(settings.get(i));
        }
        out.flush();
    }

    public void noop() throws IOException {
        int type = SpdyConnection.TYPE_NOOP;
        int length = 0;
        int flags = 0;
        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.flush();
    }

    public void ping(int flags, int id) throws IOException {
        int type = SpdyConnection.TYPE_PING;
        int length = 4;
        out.writeInt(0x80000000 | (SpdyConnection.VERSION & 0x7fff) << 16 | type & 0xffff);
        out.writeInt((flags & 0xff) << 24 | length & 0xffffff);
        out.writeInt(id);
        out.flush();
    }
}
