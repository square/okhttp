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
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordDecoder;
import io.netty.handler.codec.dns.DnsRecordEncoder;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import okio.ByteString;

public class DnsRecordCodec {
  private static final InetSocketAddress DUMMY =
      InetSocketAddress.createUnresolved("localhost", 53);

  public static String encodeQuery(String host) throws Exception {
    DatagramDnsQuery query = new DatagramDnsQuery(DUMMY, DUMMY, 0);
    query.setRecursionDesired(true);
    query.addRecord(DnsSection.QUESTION, 0, new DefaultDnsQuestion(host, DnsRecordType.A));
    query.addRecord(DnsSection.QUESTION, 1, new DefaultDnsQuestion(host, DnsRecordType.AAAA));

    //System.out.println("Query: " + query);

    return encode(query);
  }

  public static List<InetAddress> decodeAnswers(ByteString byteString) throws Exception {
    DnsResponse response = decode(Unpooled.wrappedBuffer(byteString.asByteBuffer()));

    //System.out.println("Response: " + response);

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

  private static String encode(DatagramDnsQuery envelope) throws Exception {
    DatagramDnsQuery query = envelope.content();
    ByteBuf buf = Unpooled.buffer(1024);

    encodeHeader(query, buf);
    encodeQuestions(query, buf);
    encodeRecords(query, DnsSection.ADDITIONAL, buf);

    ByteString bytes = ByteString.of(buf.array(), 0, buf.readableBytes());

    return bytes.base64Url().replace("=", "");
  }

  private static void encodeHeader(DnsQuery query, ByteBuf buf) {
    buf.writeShort(query.id());
    int flags = 0;
    flags |= (query.opCode().byteValue() & 0xFF) << 14;
    if (query.isRecursionDesired()) {
      flags |= 1 << 8;
    }
    buf.writeShort(flags);
    buf.writeShort(query.count(DnsSection.QUESTION));
    buf.writeShort(0); // answerCount
    buf.writeShort(0); // authorityResourceCount
    buf.writeShort(query.count(DnsSection.ADDITIONAL));
  }

  private static void encodeQuestions(DnsQuery query, ByteBuf buf) throws Exception {
    final int count = query.count(DnsSection.QUESTION);
    for (int i = 0; i < count; i++) {
      DnsRecordEncoder.DEFAULT.encodeQuestion((DnsQuestion) query.recordAt(DnsSection.QUESTION, i),
          buf);
    }
  }

  private static void encodeRecords(DnsQuery query, DnsSection section, ByteBuf buf)
      throws Exception {
    final int count = query.count(section);
    for (int i = 0; i < count; i++) {
      DnsRecordEncoder.DEFAULT.encodeRecord(query.recordAt(section, i), buf);
    }
  }

  private static DnsResponse decode(ByteBuf buf) throws Exception {
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

    final DnsResponse response = new DatagramDnsResponse(
        DUMMY,
        DUMMY,
        id,
        DnsOpCode.valueOf((byte) (flags >> 11 & 0xf)),
        DnsResponseCode.valueOf((byte) (flags & 0xf)));

    response.setRecursionDesired((flags >> 8 & 1) == 1);
    response.setAuthoritativeAnswer((flags >> 10 & 1) == 1);
    response.setTruncated((flags >> 9 & 1) == 1);
    response.setRecursionAvailable((flags >> 7 & 1) == 1);
    response.setZ(flags >> 4 & 0x7);
    return response;
  }

  private static void decodeQuestions(DnsResponse response, ByteBuf buf, int questionCount)
      throws Exception {
    for (int i = questionCount; i > 0; i--) {
      response.addRecord(DnsSection.QUESTION, DnsRecordDecoder.DEFAULT.decodeQuestion(buf));
    }
  }

  private static void decodeRecords(
      DnsResponse response, DnsSection section, ByteBuf buf, int count) throws Exception {
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
