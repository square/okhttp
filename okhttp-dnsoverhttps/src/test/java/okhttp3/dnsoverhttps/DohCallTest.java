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

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import okhttp3.Cache;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static okhttp3.dnsoverhttps.DnsOverHttps.DNS_MESSAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class DohCallTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private final OkHttpClient bootstrapClient =
      new OkHttpClient.Builder().protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
          .build();
  private final Dns dns = buildLocalhost(bootstrapClient);

  @Before public void setUp() {
    server.setProtocols(bootstrapClient.protocols());
  }

  @After public void tearDown() {
  }

  @Test public void getOne() throws Exception {
    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c00050001"
            + "00000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010"
            + "0010000003b00049df00112"));

    List<InetAddress> result = dns.lookup("google.com");

    assertEquals(singletonList(address("157.240.1.18")), result);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("/lookup?ct&dns=AAABAAACAAAAAAAABmdvb2dsZQNjb20AAAEAAQZnb29nbGUDY29t"
        + "AAAcAAE", recordedRequest.getPath());
  }

  @Test public void getIpv6() throws Exception {
    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d00001c0001c00c00050001"
            + "00000a1b000603617069c012c0300005000100000b1f000c04737461720463313072c012c042001c0"
            + "0010000003b00102a032880f0290011faceb00c00000002"));

    List<InetAddress> result = dns.lookup("google.com");

    assertEquals(singletonList(address("2a03:2880:f029:11:face:b00c:0:2")), result);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("/lookup?ct&dns=AAABAAACAAAAAAAABmdvb2dsZQNjb20AAAEAAQZnb29nbGUDY29t"
        + "AAAcAAE", recordedRequest.getPath());
  }

  @Test public void failure() throws Exception {
    server.enqueue(dnsResponse(
        "0000818300010000000100000e7364666c6b686673646c6b6a64660265650000010001c01b00060001"
            + "000007070038026e7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e657"
            + "4c01b5adb12c100000e10000003840012750000000e10"));

    try {
      List<InetAddress> result = dns.lookup("google.com");
      fail();
    } catch (UnknownHostException uhe) {
      assertEquals("google.com: NXDOMAIN", uhe.getMessage());
    }

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("/lookup?ct&dns=AAABAAACAAAAAAAABmdvb2dsZQNjb20AAAEAAQZnb29nbGUDY29t"
        + "AAAcAAE", recordedRequest.getPath());
  }

  @Test public void usesCache() throws Exception {
    Cache cache = new Cache(new File("./target/DohCallTest.cache"), 100 * 1024);
    OkHttpClient cachedClient = bootstrapClient.newBuilder().cache(cache).build();
    DnsOverHttps cachedDns = buildLocalhost(cachedClient);

    server.enqueue(dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c00050001"
            + "00000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010"
            + "0010000003b00049df00112")
        .setHeader("cache-control", "private, max-age=298"));

    List<InetAddress> result = cachedDns.lookup("google.com");

    assertEquals(singletonList(address("157.240.1.18")), result);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("/lookup?ct&dns=AAABAAACAAAAAAAABmdvb2dsZQNjb20AAAEAAQZnb29nbGUDY29t"
        + "AAAcAAE", recordedRequest.getPath());

    result = cachedDns.lookup("google.com");
    assertEquals(singletonList(address("157.240.1.18")), result);
  }

  private MockResponse dnsResponse(String s) {
    return new MockResponse()
        .setBody(new Buffer().write(ByteString.decodeHex(s)))
        .addHeader("content-type", "application/dns-message")
        .addHeader("content-length", s.length() / 2);
  }

  DnsOverHttps buildLocalhost(OkHttpClient bootstrapClient) {
    HttpUrl url = server.url("/lookup?ct");
    return new DnsOverHttps(bootstrapClient, url, null, true, "GET", DNS_MESSAGE);
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
