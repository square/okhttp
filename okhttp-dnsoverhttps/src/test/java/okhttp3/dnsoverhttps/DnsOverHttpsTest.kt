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
package okhttp3.dnsoverhttps

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class DnsOverHttpsTest {
  @RegisterExtension
  val platform = PlatformRule()
  private lateinit var server: MockWebServer
  private lateinit var dns: Dns
  private val cacheFs = FakeFileSystem()
  private val bootstrapClient =
    OkHttpClient.Builder()
      .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
      .build()

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
    server.protocols = bootstrapClient.protocols
    dns = buildLocalhost(bootstrapClient, false)
  }

  @Test
  fun getOne() {
    server.enqueue(
      dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010" +
          "0000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010001000" +
          "0003b00049df00112",
      ),
    )
    val result = dns.lookup("google.com")
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ")
  }

  @Test
  fun getIpv6() {
    server.enqueue(
      dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c0005000" +
          "100000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c0420001000" +
          "10000003b00049df00112",
      ),
    )
    server.enqueue(
      dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d00001c0001c00c0005000" +
          "100000a1b000603617069c012c0300005000100000b1f000c04737461720463313072c012c042001c000" +
          "10000003b00102a032880f0290011faceb00c00000002",
      ),
    )
    dns = buildLocalhost(bootstrapClient, true)
    val result = dns.lookup("google.com")
    assertThat(result.size).isEqualTo(2)
    assertThat(result).contains(address("157.240.1.18"))
    assertThat(result).contains(address("2a03:2880:f029:11:face:b00c:0:2"))
    val request1 = server.takeRequest()
    assertThat(request1.method).isEqualTo("GET")
    val request2 = server.takeRequest()
    assertThat(request2.method).isEqualTo("GET")
    assertThat(listOf(request1.path, request2.path))
      .containsExactlyInAnyOrder(
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ",
        "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AABwAAQ",
      )
  }

  @Test
  fun failure() {
    server.enqueue(
      dnsResponse(
        "0000818300010000000100000e7364666c6b686673646c6b6a64660265650000010001c01b00060001000" +
          "007070038026e7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e6574c01b5adb1" +
          "2c100000e10000003840012750000000e10",
      ),
    )
    try {
      dns.lookup("google.com")
      fail<Any>()
    } catch (uhe: UnknownHostException) {
      assertThat(uhe.message).isEqualTo("google.com: NXDOMAIN")
    }
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ")
  }

  @Test
  fun failOnExcessiveResponse() {
    val array = CharArray(128 * 1024 + 2) { '0' }
    server.enqueue(dnsResponse(String(array)))
    try {
      dns.lookup("google.com")
      fail<Any>()
    } catch (ioe: IOException) {
      assertThat(ioe.message).isEqualTo("google.com")
      val cause = ioe.cause!!
      assertThat(cause).isInstanceOf<IOException>()
      assertThat(cause).hasMessage("response size exceeds limit (65536 bytes): 65537 bytes")
    }
  }

  @Test
  fun failOnBadResponse() {
    server.enqueue(dnsResponse("00"))
    try {
      dns.lookup("google.com")
      fail<Any>()
    } catch (ioe: IOException) {
      assertThat(ioe).hasMessage("google.com")
      assertThat(ioe.cause!!).isInstanceOf<EOFException>()
    }
  }

  // TODO GET preferred order - with tests to confirm this
  // 1. successful fresh cached GET response
  // 2. unsuccessful (404, 500) fresh cached GET response
  // 3. successful network response
  // 4. successful stale cached GET response
  // 5. unsuccessful response
  @Test
  fun usesCache() {
    val cache = Cache(cacheFs, "cache".toPath(), (100 * 1024).toLong())
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns = buildLocalhost(cachedClient, false)

    repeat(2) {
      server.enqueue(
        dnsResponse(
          "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010" +
            "0000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010001000" +
            "0003b00049df00112",
        )
          .newBuilder()
          .setHeader("cache-control", "private, max-age=298")
          .build(),
      )
    }

    var result = cachedDns.lookup("google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    var recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ")

    result = cachedDns.lookup("google.com")
    assertThat(server.takeRequest(1, TimeUnit.MILLISECONDS)).isNull()
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))

    result = cachedDns.lookup("www.google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct&dns=AAABAAABAAAAAAAAA3d3dwZnb29nbGUDY29tAAABAAE")
  }

  @Test
  fun usesCacheEvenForPost() {
    val cache = Cache(cacheFs, "cache".toPath(), (100 * 1024).toLong())
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns = buildLocalhost(cachedClient, false, post = true)
    repeat(2) {
      server.enqueue(
        dnsResponse(
          "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010" +
            "0000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010001000" +
            "0003b00049df00112",
        )
          .newBuilder()
          .setHeader("cache-control", "private, max-age=298")
          .build(),
      )
    }

    var result = cachedDns.lookup("google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    var recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("POST")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct")

    result = cachedDns.lookup("google.com")
    assertThat(server.takeRequest(0, TimeUnit.MILLISECONDS)).isNull()
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))

    result = cachedDns.lookup("www.google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    recordedRequest = server.takeRequest(0, TimeUnit.MILLISECONDS)!!
    assertThat(recordedRequest.method).isEqualTo("POST")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct")
  }

  @Test
  fun usesCacheOnlyIfFresh() {
    val cache = Cache(File("./target/DnsOverHttpsTest.cache"), 100 * 1024L)
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns = buildLocalhost(cachedClient, false)
    server.enqueue(
      dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010" +
          "0000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010001000" +
          "0003b00049df00112",
      )
        .newBuilder()
        .setHeader("cache-control", "max-age=1")
        .build(),
    )
    var result = cachedDns.lookup("google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    var recordedRequest = server.takeRequest(0, TimeUnit.SECONDS)
    assertThat(recordedRequest!!.method).isEqualTo("GET")
    assertThat(recordedRequest.path).isEqualTo(
      "/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ",
    )
    Thread.sleep(2000)
    server.enqueue(
      dnsResponse(
        "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010" +
          "0000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010001000" +
          "0003b00049df00112",
      )
        .newBuilder()
        .setHeader("cache-control", "max-age=1")
        .build(),
    )
    result = cachedDns.lookup("google.com")
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))
    recordedRequest = server.takeRequest(0, TimeUnit.SECONDS)
    assertThat(recordedRequest!!.method).isEqualTo("GET")
    assertThat(recordedRequest.path)
      .isEqualTo("/lookup?ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ")
  }

  private fun dnsResponse(s: String): MockResponse {
    return MockResponse.Builder()
      .body(Buffer().write(s.decodeHex()))
      .addHeader("content-type", "application/dns-message")
      .addHeader("content-length", s.length / 2)
      .build()
  }

  private fun buildLocalhost(
    bootstrapClient: OkHttpClient,
    includeIPv6: Boolean,
    post: Boolean = false,
  ): DnsOverHttps {
    val url = server.url("/lookup?ct")
    return DnsOverHttps.Builder().client(bootstrapClient)
      .includeIPv6(includeIPv6)
      .resolvePrivateAddresses(true)
      .url(url)
      .post(post)
      .build()
  }

  companion object {
    private fun address(host: String) = InetAddress.getByName(host)
  }
}
