/*
 * Copyright (C) 2014 Square, Inc.
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

package okhttp3.dnsoverhttps;

import java.io.EOFException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okio.Buffer;
import okio.ByteString;

/**
 * Trivial Dns Encoder/Decoder, basically ripped from Netty full implementation.
 */
class DnsRecordCodec {
  private static final byte SERVFAIL = 2;
  private static final byte NXDOMAIN = 3;
  private static final int TYPE_A = 0x0001;
  private static final int TYPE_AAAA = 0x001c;
  private static final int TYPE_PTR = 0x000c;
  private static final Charset ASCII = Charset.forName("ASCII");

  private DnsRecordCodec() {
  }

  public static ByteString encodeQuery(String host, boolean includeIPv6) {
    Buffer buf = new Buffer();

    buf.writeShort(0); // query id
    buf.writeShort(256); // flags with recursion
    buf.writeShort(includeIPv6 ? 2 : 1); // question count
    buf.writeShort(0); // answerCount
    buf.writeShort(0); // authorityResourceCount
    buf.writeShort(0); // additional

    Buffer nameBuf = new Buffer();
    final String[] labels = host.split("\\.");
    for (String label : labels) {
      nameBuf.writeByte(label.length());
      nameBuf.writeString(label, ASCII);
    }
    nameBuf.writeByte(0); // end

    nameBuf.copyTo(buf, 0, nameBuf.size());
    buf.writeShort(1); // A
    buf.writeShort(1); // CLASS_IN

    if (includeIPv6) {
      nameBuf.copyTo(buf, 0, nameBuf.size());
      buf.writeShort(0x001c); // AAAA
      buf.writeShort(1); // CLASS_IN
    }

    return buf.readByteString();
  }

  public static List<InetAddress> decodeAnswers(String hostname, ByteString byteString)
      throws Exception {
    //System.out.println("Response: " + byteString.hex());

    List<InetAddress> result = new ArrayList<>();

    Buffer buf = new Buffer();
    buf.write(byteString);
    buf.readShort(); // query id

    final int flags = buf.readShort() & 0xffff;
    if (flags >> 15 == 0) {
      throw new IllegalArgumentException("not a response");
    }

    byte responseCode = (byte) (flags & 0xf);

    //System.out.println("Code: " + responseCode);
    if (responseCode == NXDOMAIN) {
      throw new UnknownHostException(hostname + ": NXDOMAIN");
    } else if (responseCode == SERVFAIL) {
      throw new UnknownHostException(hostname + ": SERVFAIL");
    }

    final int questionCount = buf.readShort() & 0xffff;
    final int answerCount = buf.readShort() & 0xffff;
    buf.readShort(); // authority record count
    buf.readShort(); // additional record count

    for (int i = 0; i < questionCount; i++) {
      consumeName(buf); // name
      buf.readShort(); // type
      buf.readShort(); // class
    }

    for (int i = 0; i < answerCount; i++) {
      consumeName(buf); // name

      int type = buf.readShort() & 0xffff;
      buf.readShort(); // class
      final long ttl = buf.readInt() & 0xffffffffL; // ttl
      final int length = buf.readShort() & 0xffff;

      if (type == TYPE_A || type == TYPE_AAAA) {
        byte[] bytes = new byte[length];
        buf.read(bytes);
        result.add(InetAddress.getByAddress(bytes));
      } else {
        buf.skip(length);
      }
    }

    return result;
  }

  private static void consumeName(Buffer in) throws EOFException {
    // 0 - 63 bytes
    int length = in.readByte();

    if (length < 0) {
      // compressed name pointer, first two bits are 1
      // drop second byte of compression offset
      in.skip(1);
    } else {
      while (length > 0) {
        // skip each part of the domain name
        length = in.readByte();
      }
    }
  }
}
