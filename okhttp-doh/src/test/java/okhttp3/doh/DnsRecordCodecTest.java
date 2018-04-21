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

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import okio.ByteString;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DnsRecordCodecTest {
  @Test public void testGoogleDotComEncoding() throws Exception {
    String encoded = DnsRecordCodec.encodeQuery("google.com", false);

    assertEquals("AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ", encoded);
  }

  @Test public void testGoogleDotComEncodingWithIPv6() throws Exception {
    String encoded = DnsRecordCodec.encodeQuery("google.com", true);

    assertEquals("AAABAAACAAAAAAAABmdvb2dsZQNjb20AAAEAAQZnb29nbGUDY29tAAAcAAE", encoded);
  }

  @Test public void testGoogleDotComDecodingFromCloudflare() throws Exception {
    List<InetAddress> encoded = DnsRecordCodec.decodeAnswers(ByteString.decodeHex(
        "00008180000100010000000006676f6f676c6503636f6d0000010001c00c00010001000000430004d83ad54e"));

    assertEquals(Collections.singletonList(InetAddress.getByName("216.58.213.78")), encoded);
  }

  @Test public void testGoogleDotComDecodingFromGoogle() throws Exception {
    List<InetAddress> encoded = DnsRecordCodec.decodeAnswers(ByteString.decodeHex(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c0005000100000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c042000100010000003b00049df00112"));

    assertEquals(Collections.singletonList(InetAddress.getByName("157.240.1.18")), encoded);
  }
}
