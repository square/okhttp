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

package okhttp3.doh;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordDecoder;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.CharsetUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okio.Buffer;
import okio.ByteString;

public class DnsRecordCodec {
  private static final InetSocketAddress DUMMY =
      InetSocketAddress.createUnresolved("localhost", 53);

  public static String encodeQuery(String host, boolean includeIPv6) {
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
      nameBuf.writeString(label, StandardCharsets.US_ASCII);
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

    String encoded = buf.readByteString().base64Url().replace("=", "");

    //System.out.println("Query: " + encoded);

    return encoded;
  }

  public static List<InetAddress> decodeAnswers(String hostname, ByteString byteString)
      throws Exception {
    //System.out.println("Response: " + byteString.hex());

    List<InetAddress> result = new ArrayList<>();

    ByteBuf buf = Unpooled.wrappedBuffer(byteString.asByteBuffer());
    buf.readUnsignedShort(); // query id

    final int flags = buf.readUnsignedShort();
    if (flags >> 15 == 0) {
      throw new IllegalArgumentException("not a response");
    }

    byte responseCode = (byte) (flags & 0xf);

    //System.out.println("Code: " + responseCode);
    if (responseCode == DnsResponseCode.NXDOMAIN.intValue()) {
      throw new UnknownHostException(hostname + ": NXDOMAIN");
    } else if (responseCode == DnsResponseCode.SERVFAIL.intValue()) {
      throw new UnknownHostException(hostname + ": SERVFAIL");
    }

    final int questionCount = buf.readUnsignedShort();
    final int answerCount = buf.readUnsignedShort();
    buf.readUnsignedShort(); // authority record count
    buf.readUnsignedShort(); // additional record count

    for (int i = 0; i <  questionCount; i++) {
      decodeNameDirect(buf); // name
      buf.readUnsignedShort(); // type
      buf.readUnsignedShort(); // class
    }

    for (int i = 0; i < answerCount; i++) {
      decodeNameDirect(buf); // name

      int type = buf.readUnsignedShort();
      final int aClass = buf.readUnsignedShort(); // class
      final long ttl = buf.readUnsignedInt(); // ttl
      final int length = buf.readUnsignedShort();
      final int offset = buf.readerIndex();

      buf.readerIndex(offset + length);

      if (type == DnsRecordType.A.intValue() || type == DnsRecordType.AAAA.intValue()) {
        ByteBuf content = buf.retainedDuplicate().setIndex(offset, offset + length);
        byte[] bytes = new byte[content.readableBytes()];
        content.readBytes(bytes);
        result.add(InetAddress.getByAddress(bytes));
      }
    }

    return result;
  }

  private static String decodeNameDirect(ByteBuf in) {
    int position = -1;
    int checked = 0;
    final int end = in.writerIndex();

    final StringBuilder name = new StringBuilder();
    while (in.isReadable()) {
      final int len = in.readUnsignedByte();
      final boolean pointer = (len & 0xc0) == 0xc0;
      if (pointer) {
        if (position == -1) {
          position = in.readerIndex() + 1;
        }

        if (!in.isReadable()) {
          throw new CorruptedFrameException("truncated pointer in a name");
        }

        final int next = (len & 0x3f) << 8 | in.readUnsignedByte();
        if (next >= end) {
          throw new CorruptedFrameException("name has an out-of-range pointer");
        }
        in.readerIndex(next);

        // check for loops
        checked += 2;
        if (checked >= end) {
          throw new CorruptedFrameException("name contains a loop.");
        }
      } else if (len != 0) {
        if (!in.isReadable(len)) {
          throw new CorruptedFrameException("truncated label in a name");
        }
        name.append(in.toString(in.readerIndex(), len, CharsetUtil.UTF_8)).append('.');
        in.skipBytes(len);
      } else { // len == 0
        break;
      }
    }

    if (position != -1) {
      in.readerIndex(position);
    }

    if (name.length() == 0) {
      return ".";
    }

    if (name.charAt(name.length() - 1) != '.') {
      name.append('.');
    }

    return name.toString();
  }

  public static List<InetAddress> decodeAnswersNetty(String hostname, ByteString byteString)
      throws Exception {
    //System.out.println("Response: " + byteString.hex());

    ByteBuf buf = Unpooled.wrappedBuffer(byteString.asByteBuffer());
    buf.readUnsignedShort(); // query id

    final int flags = buf.readUnsignedShort();
    if (flags >> 15 == 0) {
      throw new CorruptedFrameException("not a response");
    }

    final DnsResponse response1 =
        new DatagramDnsResponse(DUMMY, DUMMY, 0, DnsOpCode.valueOf((byte) (flags >> 11 & 0xf)),
            DnsResponseCode.valueOf((byte) (flags & 0xf)));

    response1.setRecursionDesired((flags >> 8 & 1) == 1);
    response1.setAuthoritativeAnswer((flags >> 10 & 1) == 1);
    response1.setTruncated((flags >> 9 & 1) == 1);
    response1.setRecursionAvailable((flags >> 7 & 1) == 1);
    response1.setZ(flags >> 4 & 0x7);
    final DnsResponse response = response1;

    final int questionCount = buf.readUnsignedShort();
    final int answerCount = buf.readUnsignedShort();
    buf.readUnsignedShort(); // authority record count
    buf.readUnsignedShort(); // additional record count

    for (int i1 = questionCount; i1 > 0; i1--) {
      decodeQuestion(buf);
    }

    decodeRecords(response, DnsSection.ANSWER, buf, answerCount);

    //System.out.println("Response: " + response);

    //System.out.println("Code: " + response.code());
    if (response.code() == DnsResponseCode.NXDOMAIN) {
      throw new UnknownHostException(hostname + ": NXDOMAIN");
    } else if (response.code() == DnsResponseCode.SERVFAIL) {
      throw new UnknownHostException(hostname + ": SERVFAIL");
    }
    // TODO check for SERVFAIL or NXDOMAIN

    int recordCount = response.count(DnsSection.ANSWER);
    List<InetAddress> result = new ArrayList<>(recordCount);
    for (int i = 0; i < recordCount; i++) {
      DnsRecord answer = response.recordAt(DnsSection.ANSWER, i);
      if (answer.type() == DnsRecordType.A || answer.type() == DnsRecordType.AAAA) {
        DnsRawRecord record = (DnsRawRecord) answer;
        ByteBuf content = record.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.readBytes(bytes);
        result.add(InetAddress.getByAddress(bytes));
      }
    }
    return result;
  }

  // Netty methods - TODO reimplement as minimal fixed without netty dependency

  /*
   * Copyright 2015 The Netty Project
   *
   * The Netty Project licenses this file to you under the Apache License,
   * version 2.0 (the "License"); you may not use this file except in compliance
   * with the License. You may obtain a copy of the License at:
   *
   *   http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
   * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
   * License for the specific language governing permissions and limitations
   * under the License.
   */

  private static DnsResponse decodeNetty(ByteBuf buf) throws Exception {
    final DnsResponse response = newResponse(buf);
    final int questionCount = buf.readUnsignedShort();
    final int answerCount = buf.readUnsignedShort();
    final int authorityRecordCount = buf.readUnsignedShort();
    final int additionalRecordCount = buf.readUnsignedShort();

    decodeQuestions(response, buf, questionCount);
    decodeRecords(response, DnsSection.ANSWER, buf, answerCount);
    decodeRecords(response, DnsSection.AUTHORITY, buf, authorityRecordCount);
    decodeRecords(response, DnsSection.ADDITIONAL, buf, additionalRecordCount);

    return response;
  }

  private static DnsResponse newResponse(ByteBuf buf) {
    final int id = buf.readUnsignedShort();

    final int flags = buf.readUnsignedShort();
    if (flags >> 15 == 0) {
      throw new CorruptedFrameException("not a response");
    }

    final DnsResponse response =
        new DatagramDnsResponse(DUMMY, DUMMY, id, DnsOpCode.valueOf((byte) (flags >> 11 & 0xf)),
            DnsResponseCode.valueOf((byte) (flags & 0xf)));

    response.setRecursionDesired((flags >> 8 & 1) == 1);
    response.setAuthoritativeAnswer((flags >> 10 & 1) == 1);
    response.setTruncated((flags >> 9 & 1) == 1);
    response.setRecursionAvailable((flags >> 7 & 1) == 1);
    response.setZ(flags >> 4 & 0x7);
    return response;
  }

  private static void decodeQuestions(DnsResponse response, ByteBuf buf, int questionCount) {
    for (int i = questionCount; i > 0; i--) {
      response.addRecord(DnsSection.QUESTION, decodeQuestion(buf));
    }
  }

  public static final DnsQuestion decodeQuestion(ByteBuf in) {
    String name = decodeName(in);
    DnsRecordType type = DnsRecordType.valueOf(in.readUnsignedShort());
    int qClass = in.readUnsignedShort();
    return new DefaultDnsQuestion(name, type, qClass);
  }

  /**
   * Retrieves a domain name given a buffer containing a DNS packet. If the
   * name contains a pointer, the position of the buffer will be set to
   * directly after the pointer's index after the name has been read.
   *
   * @param in the byte buffer containing the DNS packet
   * @return the domain name for an entry
   */
  public static String decodeName(ByteBuf in) {
    int position = -1;
    int checked = 0;
    final int end = in.writerIndex();
    final int readable = in.readableBytes();

    // Looking at the spec we should always have at least enough readable bytes to read a byte here but it seems
    // some servers do not respect this for empty names. So just workaround this and return an empty name in this
    // case.
    //
    // See:
    // - https://github.com/netty/netty/issues/5014
    // - https://www.ietf.org/rfc/rfc1035.txt , Section 3.1
    if (readable == 0) {
      return ".";
    }

    final StringBuilder name = new StringBuilder(readable << 1);
    while (in.isReadable()) {
      final int len = in.readUnsignedByte();
      final boolean pointer = (len & 0xc0) == 0xc0;
      if (pointer) {
        if (position == -1) {
          position = in.readerIndex() + 1;
        }

        if (!in.isReadable()) {
          throw new CorruptedFrameException("truncated pointer in a name");
        }

        final int next = (len & 0x3f) << 8 | in.readUnsignedByte();
        if (next >= end) {
          throw new CorruptedFrameException("name has an out-of-range pointer");
        }
        in.readerIndex(next);

        // check for loops
        checked += 2;
        if (checked >= end) {
          throw new CorruptedFrameException("name contains a loop.");
        }
      } else if (len != 0) {
        if (!in.isReadable(len)) {
          throw new CorruptedFrameException("truncated label in a name");
        }
        name.append(in.toString(in.readerIndex(), len, CharsetUtil.UTF_8)).append('.');
        in.skipBytes(len);
      } else { // len == 0
        break;
      }
    }

    if (position != -1) {
      in.readerIndex(position);
    }

    if (name.length() == 0) {
      return ".";
    }

    if (name.charAt(name.length() - 1) != '.') {
      name.append('.');
    }

    return name.toString();
  }

  private static void decodeRecords(DnsResponse response, DnsSection section, ByteBuf buf,
      int count) throws Exception {
    for (int i = count; i > 0; i--) {
      final DnsRecord r = DnsRecordDecoder.DEFAULT.decodeRecord(buf);
      if (r == null) {
        // Truncated response
        break;
      }

      response.addRecord(section, r);
    }
  }
}
