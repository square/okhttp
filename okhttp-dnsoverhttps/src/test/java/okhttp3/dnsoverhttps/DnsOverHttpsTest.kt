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
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Cache
import okhttp3.CallEvent
import okhttp3.CallEvent.CacheHit
import okhttp3.CallEvent.CacheMiss
import okhttp3.Dns
import okhttp3.EventRecorder
import okhttp3.Headers.Companion.headersOf
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

  private val dnsOverHttpsServer = DnsOverHttpsServer()

  @StartStop
  private val server =
    MockWebServer()
      .apply {
        dispatcher = dnsOverHttpsServer
      }

  private lateinit var dns: Dns
  private val cacheFs = FakeFileSystem()
  private val eventRecorder = EventRecorder()
  private val bootstrapClient =
    OkHttpClient
      .Builder()
      .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
      .eventListener(eventRecorder.eventListener)
      .build()

  @BeforeEach
  fun setUp() {
    server.protocols = bootstrapClient.protocols
    dns = buildLocalhost(bootstrapClient, false)
  }

  @Test
  fun getOne() {
    dnsOverHttpsServer["google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
      )
    val result = dns.lookup("google.com")
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))
    val (httpsRequest, dnsRequest) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("google.com", TYPE_A))
  }

  @Test
  fun getIpv6() {
    dnsOverHttpsServer["google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("2a03:2880:f029:11:face:b00c:0:2"),
        ),
      )
    dns = buildLocalhost(bootstrapClient, true)
    val result = dns.lookup("google.com")
    assertThat(result.size).isEqualTo(2)
    assertThat(result).contains(address("157.240.1.18"))
    assertThat(result).contains(address("2a03:2880:f029:11:face:b00c:0:2"))
    val (httpsRequest1, dnsRequest1) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("GET")
    val (httpsRequest2, dnsRequest2) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("GET")
    assertThat(listOf(dnsRequest1, dnsRequest2))
      .containsExactlyInAnyOrder(
        queryRequest("google.com", TYPE_A),
        queryRequest("google.com", TYPE_AAAA),
      )
  }

  @Test
  fun failure() {
    assertFailsWith<UnknownHostException> {
      dns.lookup("google.com")
    }
    val (httpsRequest, dnsRequest) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("google.com", TYPE_A))
  }

  @Test
  fun failOnExcessiveResponse() {
    val array = CharArray(128 * 1024 + 2) { '0' }
    dnsOverHttpsServer.override = overrideResponse(String(array))
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
    dnsOverHttpsServer.override = overrideResponse("00")
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

    dnsOverHttpsServer.extraHeaders =
      headersOf(
        "cache-control",
        "private, max-age=298",
      )
    dnsOverHttpsServer["google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
      )
    dnsOverHttpsServer["www.google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
      )

    var result = cachedDns.lookup("google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    val (httpsRequest1, dnsRequest1) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("GET")
    assertThat(dnsRequest1)
      .isEqualTo(queryRequest("google.com", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    result = cachedDns.lookup("google.com")
    assertThat(dnsOverHttpsServer.pollRequest()).isNull()
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))

    assertThat(cacheEvents()).containsExactly(CacheHit::class)

    result = cachedDns.lookup("www.google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    val (httpsRequest2, dnsRequest2) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("GET")
    assertThat(dnsRequest2)
      .isEqualTo(queryRequest("www.google.com", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)
  }

  @Test
  fun usesCacheEvenForPost() {
    val cache = Cache(cacheFs, "cache".toPath(), (100 * 1024).toLong())
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns = buildLocalhost(cachedClient, false, post = true)
    dnsOverHttpsServer.extraHeaders =
      headersOf(
        "cache-control",
        "private, max-age=298",
      )
    dnsOverHttpsServer["google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
      )
    dnsOverHttpsServer["www.google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
      )

    var result = cachedDns.lookup("google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    val (httpsRequest1, _) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("POST")
    assertThat(httpsRequest1.url.encodedQuery)
      .isEqualTo("ct")

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    result = cachedDns.lookup("google.com")
    assertThat(dnsOverHttpsServer.pollRequest()).isNull()
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))

    assertThat(cacheEvents()).containsExactly(CacheHit::class)

    result = cachedDns.lookup("www.google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    val (httpsRequest2, _) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("POST")
    assertThat(httpsRequest2.url.encodedQuery)
      .isEqualTo("ct")

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)
  }

  @Test
  fun usesCacheOnlyIfFresh() {
    val cache = Cache(File("./target/DnsOverHttpsTest.cache"), 100 * 1024L)
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns = buildLocalhost(cachedClient, false)
    dnsOverHttpsServer.extraHeaders =
      headersOf(
        "cache-control",
        "max-age=1",
      )
    dnsOverHttpsServer["google.com"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "star.c10r.facebook.com",
          timeToLive = 59,
          address = InetAddress.getByName("157.240.1.18"),
        ),
      )
    var result = cachedDns.lookup("google.com")
    assertThat(result).containsExactly(address("157.240.1.18"))
    val (httpsRequest1, dnsRequest1) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("GET")
    assertThat(dnsRequest1)
      .isEqualTo(queryRequest("google.com", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    Thread.sleep(2000)
    result = cachedDns.lookup("google.com")
    assertThat(result).isEqualTo(listOf(address("157.240.1.18")))
    val (httpsRequest2, dnsRequest2) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("GET")
    assertThat(dnsRequest2)
      .isEqualTo(queryRequest("google.com", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)
  }

  private fun cacheEvents(): List<KClass<out CallEvent>> =
    eventRecorder
      .recordedEventTypes()
      .filter { "Cache" in it.simpleName!! }
      .also { eventRecorder.clearAllEvents() }

  private fun buildLocalhost(
    bootstrapClient: OkHttpClient,
    includeIPv6: Boolean,
    post: Boolean = false,
  ): DnsOverHttps {
    val url = server.url("/lookup?ct")
    return DnsOverHttps
      .Builder()
      .client(bootstrapClient)
      .includeIPv6(includeIPv6)
      .resolvePrivateAddresses(true)
      .url(url)
      .post(post)
      .build()
  }

  private fun overrideResponse(body: String): MockResponse =
    MockResponse
      .Builder()
      .body(Buffer().write(body.decodeHex()))
      .addHeader("content-type", "application/dns-message")
      .addHeader("content-length", body.length / 2)
      .build()

  private fun queryRequest(
    host: String,
    type: Int,
  ): DnsMessage =
    DnsMessage(
      id = 0,
      flags = 256,
      questions =
        listOf(
          Question(
            name = host,
            type = type,
            `class` = CLASS_IN,
          ),
        ),
    )

  companion object {
    private fun address(host: String) = InetAddress.getByName(host)
  }
}
