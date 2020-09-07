/*
 * Copyright (C) 2018 Square, Inc.
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okio.ByteString;
import org.junit.Test;

import static okhttp3.dnsoverhttps.DnsRecordCodec.TYPE_A;
import static okhttp3.dnsoverhttps.DnsRecordCodec.TYPE_AAAA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class DnsRecordCodecTest {
  @Test public void testGoogleDotComEncoding() {
    String encoded = encodeQuery("google.com", TYPE_A);

    assertThat(encoded).isEqualTo("AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ");
  }

  private String encodeQuery(String host, int type) {
    return DnsRecordCodec.INSTANCE.encodeQuery(host, type).base64Url().replace("=", "");
  }

  @Test public void testGoogleDotComEncodingWithIPv6() {
    String encoded = encodeQuery("google.com", TYPE_AAAA);

    assertThat(encoded).isEqualTo("AAABAAABAAAAAAAABmdvb2dsZQNjb20AABwAAQ");
  }

  @Test public void testGoogleDotComDecodingFromCloudflare() throws Exception {
    List<InetAddress> encoded = DnsRecordCodec.INSTANCE.decodeAnswers("test.com",
        ByteString.decodeHex("00008180000100010000000006676f6f676c6503636f6d0000010001c00c000100010"
            + "00000430004d83ad54e"));

    assertThat(encoded).containsExactly(InetAddress.getByName("216.58.213.78"));
  }

  @Test public void testGoogleDotComDecodingFromGoogle() throws Exception {
    List<InetAddress> decoded = DnsRecordCodec.INSTANCE.decodeAnswers("test.com",
        ByteString.decodeHex("0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010"
            + "001c00c0005000100000a6d000603617069c012c0300005000100000cde000c04737461720463313072c"
            + "012c042000100010000003b00049df00112"));

    assertThat(decoded).containsExactly(InetAddress.getByName("157.240.1.18"));
  }

  @Test public void testGoogleDotComDecodingFromGoogleIPv6() throws Exception {
    List<InetAddress> decoded = DnsRecordCodec.INSTANCE.decodeAnswers("test.com",
        ByteString.decodeHex("0000818000010003000000000567726170680866616365626f6f6b03636f6d00001c0"
            + "001c00c0005000100000a1b000603617069c012c0300005000100000b1f000c04737461720463313072c"
            + "012c042001c00010000003b00102a032880f0290011faceb00c00000002"));

    assertThat(decoded).containsExactly(InetAddress.getByName("2a03:2880:f029:11:face:b00c:0:2"));
  }

  @Test public void testGoogleDotComDecodingNxdomainFailure() throws Exception {
    try {
      DnsRecordCodec.INSTANCE.decodeAnswers("sdflkhfsdlkjdf.ee", ByteString.decodeHex("000081830001"
          + "0000000100000e7364666c6b686673646c6b6a64660265650000010001c01b00060001000007070038026e"
          + "7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e6574c01b5adb12c100000e1000"
          + "0003840012750000000e10"));
      fail();
    } catch (UnknownHostException uhe) {
      assertThat(uhe.getMessage()).isEqualTo("sdflkhfsdlkjdf.ee: NXDOMAIN");
    }
  }
}
