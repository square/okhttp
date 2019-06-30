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

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import okhttp3.Cache;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class DnsOverHttpsTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private final OkHttpClient bootstrapClient =
      new OkHttpClient.Builder().protocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1)).build();
  private Dns dns = buildLocalhost(bootstrapClient, false);

  @Before public void setUp() {
    server.setProtocols(bootstrapClient.protocols());
  }

  @Test public void getOne() throws Exception {
    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c00050001"
            + "00000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010"
            + "0010000003b00049df00112"));

    List<InetAddress> result = dns.lookup("google.com");

    assertThat(result).isEqualTo(singletonList(address("157.240.1.18")));

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("GET");
    assertThat(recordedRequest.getPath()).isEqualTo(
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ");
  }

  @Test public void getIpv6() throws Exception {
    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c00050001"
            + "00000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010"
            + "0010000003b00049df00112"));
    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d00001c0001c00c00050001"
            + "00000a1b000603617069c012c0300005000100000b1f000c04737461720463313072c012c042001c0"
            + "0010000003b00102a032880f0290011faceb00c00000002"));

    dns = buildLocalhost(bootstrapClient, true);

    List<InetAddress> result = dns.lookup("google.com");

    assertThat(result.size()).isEqualTo(2);
    assertThat(result).contains(address("157.240.1.18"));
    assertThat(result).contains(address("2a03:2880:f029:11:face:b00c:0:2"));

    RecordedRequest request1 = server.takeRequest();
    assertThat(request1.getMethod()).isEqualTo("GET");

    RecordedRequest request2 = server.takeRequest();
    assertThat(request2.getMethod()).isEqualTo("GET");

    assertThat(asList(request1.getPath(), request2.getPath())).containsExactlyInAnyOrder(
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ",
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AABwAAQ");
  }

  @Test public void failure() throws Exception {
    server.enqueue(dnsResponse(
        "0000818300010000000100000e7364666c6b686673646c6b6a64660265650000010001c01b00060001"
            + "000007070038026e7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e657"
            + "4c01b5adb12c100000e10000003840012750000000e10"));

    try {
      dns.lookup("google.com");
      fail();
    } catch (UnknownHostException uhe) {
      uhe.printStackTrace();
      assertThat(uhe.getMessage()).isEqualTo("google.com: NXDOMAIN");
    }

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("GET");
    assertThat(recordedRequest.getPath()).isEqualTo(
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ");
  }

  @Test public void failOnExcessiveResponse() {
    char[] array = new char[128 * 1024 + 2];
    Arrays.fill(array, '0');
    server.enqueue(dnsResponse(new String(array)));

    try {
      dns.lookup("google.com");
      fail();
    } catch (IOException ioe) {
      assertThat(ioe.getMessage()).isEqualTo("google.com");
      Throwable cause = ioe.getCause();
      assertThat(cause).isInstanceOf(IOException.class);
      assertThat(cause).hasMessage("response size exceeds limit (65536 bytes): 65537 bytes");
    }
  }

  @Test public void failOnBadResponse() {
    server.enqueue(dnsResponse("00"));

    try {
      dns.lookup("google.com");
      fail();
    } catch (IOException ioe) {
      assertThat(ioe).hasMessage("google.com");
      assertThat(ioe.getCause()).isInstanceOf(EOFException.class);
    }
  }

  // TODO GET preferred order - with tests to confirm this
  // 1. successful fresh cached GET response
  // 2. unsuccessful (404, 500) fresh cached GET response
  // 3. successful network response
  // 4. successful stale cached GET response
  // 5. unsuccessful response

  // TODO how closely to follow POST rules on caching?

  @Test public void usesCache() throws Exception {
    Cache cache = new Cache(new File("./target/DnsOverHttpsTest.cache"), 100 * 1024);
    OkHttpClient cachedClient = bootstrapClient.newBuilder().cache(cache).build();
    DnsOverHttps cachedDns = buildLocalhost(cachedClient, false);

    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c00050001"
            + "00000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010"
            + "0010000003b00049df00112").setHeader("cache-control", "private, max-age=298"));

    List<InetAddress> result = cachedDns.lookup("google.com");

    assertThat(result).containsExactly(address("157.240.1.18"));

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("GET");
    assertThat(recordedRequest.getPath()).isEqualTo(
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ");

    result = cachedDns.lookup("google.com");
    assertThat(result).isEqualTo(singletonList(address("157.240.1.18")));
  }

  private MockResponse dnsResponse(String s) {
    return new MockResponse().setBody(new Buffer().write(ByteString.decodeHex(s)))
        .addHeader("content-type", "application/dns-message")
        .addHeader("content-length", s.length() / 2);
  }

  private DnsOverHttps buildLocalhost(OkHttpClient bootstrapClient, boolean includeIPv6) {
    HttpUrl url = server.url("/lookup?ct");
    return new DnsOverHttps.Builder().client(bootstrapClient)
        .includeIPv6(includeIPv6)
        .resolvePrivateAddresses(true)
        .url(url)
        .build();
  }

  private static InetAddress address(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // impossible for IP addresses
      throw new RuntimeException(e);
    }
  }
}
