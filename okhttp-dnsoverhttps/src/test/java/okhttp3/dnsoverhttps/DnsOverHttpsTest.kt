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

import app.cash.burst.Burst
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
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Cache
import okhttp3.CallEvent
import okhttp3.CallEvent.CacheHit
import okhttp3.CallEvent.CacheMiss
import okhttp3.Dns2
import okhttp3.EventRecorder
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
@Burst
class DnsOverHttpsTest(
  private val entryPoint: EntryPoint = EntryPoint.NewCall,
) {
  @RegisterExtension
  val platform = PlatformRule()

  private val dnsOverHttpsServer = DnsOverHttpsServer()

  @StartStop
  private val server =
    MockWebServer()
      .apply {
        dispatcher = dnsOverHttpsServer
      }

  private lateinit var dns: DnsOverHttps
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
    dnsOverHttpsServer["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    val result = dns.invoke("lysine.dev")
    assertThat(result).isEqualTo(listOf(address("10.20.30.40")))
    val (httpsRequest, dnsRequest) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))
  }

  @Test
  fun getIpv6() {
    dnsOverHttpsServer["lysine.dev"] =
      listOf(
        InetAddress.getByName("10.20.30.40"),
        InetAddress.getByName("1:2::3:4"),
      )
    dns = buildLocalhost(bootstrapClient, true)
    val result = dns("lysine.dev")
    assertThat(result.size).isEqualTo(2)
    assertThat(result).contains(address("10.20.30.40"))
    assertThat(result).contains(address("1:2::3:4"))
    val (httpsRequest1, dnsRequest1) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("GET")
    val (httpsRequest2, dnsRequest2) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("GET")
    assertThat(listOf(dnsRequest1, dnsRequest2))
      .containsExactlyInAnyOrder(
        queryRequest("lysine.dev", TYPE_A),
        queryRequest("lysine.dev", TYPE_AAAA),
      )
  }

  @Test
  fun failure() {
    assertFailsWith<UnknownHostException> {
      dns("lysine.dev")
    }
    val (httpsRequest, dnsRequest) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))
  }

  @Test
  fun failOnExcessiveResponse() {
    val array = CharArray(128 * 1024 + 2) { '0' }
    dnsOverHttpsServer.override = overrideResponse(String(array))
    try {
      dns("lysine.dev")
      fail<Any>()
    } catch (e: IOException) {
      val rootCause = e.cause ?: e
      assertThat(rootCause).hasMessage("response size exceeds limit (65536 bytes): 65537 bytes")
    }
  }

  @Test
  fun failOnBadResponse() {
    dnsOverHttpsServer.override = overrideResponse("00")
    val e =
      assertFailsWith<IOException> {
        dns("lysine.dev")
      }
    val rootCause = e.cause ?: e
    assertThat(rootCause).isInstanceOf<EOFException>()
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
    dnsOverHttpsServer["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    dnsOverHttpsServer["alternate.lysine.dev"] = listOf(InetAddress.getByName("55.66.77.88"))

    val result1 = cachedDns("lysine.dev")
    assertThat(result1).containsExactly(address("10.20.30.40"))
    val (httpsRequest1, dnsRequest1) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("GET")
    assertThat(dnsRequest1)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    val result2 = cachedDns("lysine.dev")
    assertThat(dnsOverHttpsServer.pollRequest()).isNull()
    assertThat(result2).isEqualTo(listOf(address("10.20.30.40")))

    assertThat(cacheEvents()).containsExactly(CacheHit::class)

    val result3 = cachedDns("alternate.lysine.dev")
    assertThat(result3).containsExactly(address("55.66.77.88"))
    val (httpsRequest2, dnsRequest2) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("GET")
    assertThat(dnsRequest2)
      .isEqualTo(queryRequest("alternate.lysine.dev", TYPE_A))

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
    dnsOverHttpsServer["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    dnsOverHttpsServer["alternate.lysine.dev"] = listOf(InetAddress.getByName("55.66.77.88"))

    val result1 = cachedDns("lysine.dev")
    assertThat(result1).containsExactly(address("10.20.30.40"))
    val (httpsRequest1, _) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("POST")
    assertThat(httpsRequest1.url.encodedQuery)
      .isEqualTo("ct")

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    val result2 = cachedDns("lysine.dev")
    assertThat(dnsOverHttpsServer.pollRequest()).isNull()
    assertThat(result2).isEqualTo(listOf(address("10.20.30.40")))

    assertThat(cacheEvents()).containsExactly(CacheHit::class)

    val result3 = cachedDns("alternate.lysine.dev")
    assertThat(result3).containsExactly(address("55.66.77.88"))
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
        "no-store",
      )
    dnsOverHttpsServer["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    val result1 = cachedDns("lysine.dev")
    assertThat(result1).containsExactly(address("10.20.30.40"))
    val (httpsRequest1, dnsRequest1) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest1.method).isEqualTo("GET")
    assertThat(dnsRequest1)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    val result2 = cachedDns("lysine.dev")
    assertThat(result2).isEqualTo(listOf(address("10.20.30.40")))
    val (httpsRequest2, dnsRequest2) = dnsOverHttpsServer.takeRequest()
    assertThat(httpsRequest2.method).isEqualTo("GET")
    assertThat(dnsRequest2)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

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

  /**
   * Use Burst to call either the blocking API or the non-blocking API, both with a signature that
   * resembles the [InetAddress.getAllByName] API.
   */
  private operator fun DnsOverHttps.invoke(hostname: String): List<InetAddress> =
    when (entryPoint) {
      EntryPoint.Lookup -> {
        lookup(hostname)
      }

      EntryPoint.NewCall -> {
        val future = CompletableFuture<List<InetAddress>>()

        val call = newCall(Dns2.Request(hostname))
        call.enqueue(
          object : Dns2.Callback {
            private val results = mutableListOf<InetAddress>()

            override fun onRecords(
              call: Dns2.Call,
              last: Boolean,
              records: List<Dns2.Record>,
            ) {
              this.results +=
                records
                  .filterIsInstance<Dns2.Record.IpAddress>()
                  .map { it.address }
              if (last) {
                when {
                  results.isNotEmpty() -> future.complete(this.results)
                  else -> future.completeExceptionally(UnknownHostException())
                }
              }
            }

            override fun onFailure(
              call: Dns2.Call,
              e: IOException,
            ) {
              future.completeExceptionally(e)
            }
          },
        )

        try {
          future.get()
        } catch (e: ExecutionException) {
          throw e.cause!!
        }
      }
    }

  enum class EntryPoint {
    Lookup,
    NewCall,
  }

  companion object {
    private fun address(host: String) = InetAddress.getByName(host)
  }
}
