/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.dnsoverhttps.internal.CLASS_IN
import okhttp3.dnsoverhttps.internal.DnsMessage
import okhttp3.dnsoverhttps.internal.DnsMessageReader
import okhttp3.dnsoverhttps.internal.DnsMessageWriter
import okhttp3.dnsoverhttps.internal.Question
import okhttp3.dnsoverhttps.internal.ResourceRecord
import okhttp3.dnsoverhttps.internal.TYPE_A
import okhttp3.dnsoverhttps.internal.TYPE_AAAA
import okhttp3.dnsoverhttps.internal.TYPE_HTTPS
import okio.Buffer
import okio.ByteString.Companion.decodeBase64

/**
 * Handles DNS calls using in-memory records.
 */
class FakeDns : Dns {
  private val data = ConcurrentHashMap<String, List<ResourceRecord>>()

  var extraHeaders: Headers = Headers.headersOf()
  private val requests = LinkedBlockingDeque<Request>()
  val nextSequenceIndex = AtomicInteger(0)
  val sequenceIndexToOverride = ConcurrentHashMap<Int, MockResponse>()
  private var nextAddress = 0xff000064L // 255.0.0.100 in IPv4; ::ff00:64 in IPv6.

  /** Returns a MockWebServer dispatcher that serves DNS over HTTPS records. */
  val dispatcher =
    object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val sequenceIndex = nextSequenceIndex.getAndIncrement()
        val override = sequenceIndexToOverride.remove(sequenceIndex)
        if (override != null) return override

        val requestBody = request.body
        val queryParameter = request.url.queryParameter("dns")
        val encodedDnsQuery =
          when {
            requestBody != null -> Buffer().write(requestBody)
            queryParameter != null -> Buffer().write(queryParameter.decodeBase64()!!)
            else -> return MockResponse(code = 400)
          }

        val dnsRequest = DnsMessageReader(encodedDnsQuery).read()
        requests.put(Request.DnsOverHttpsRequest(request, dnsRequest))

        val dnsResponse = invoke(dnsRequest)

        val body = Buffer()
        DnsMessageWriter(body).write(dnsResponse)

        return MockResponse
          .Builder()
          .addHeader("content-type", "application/dns-message")
          .apply {
            for ((name, value) in extraHeaders) {
              addHeader(name, value)
            }
          }.body(body)
          .build()
      }
    }

  operator fun set(
    hostname: String,
    records: List<ResourceRecord>,
  ) {
    data[hostname] = records
  }

  @JvmName("set-inetAddresses") // Avoid raw types collision.
  operator fun set(
    hostname: String,
    inetAddresses: List<InetAddress>,
  ) {
    set(
      hostname,
      inetAddresses.map { inetAddress ->
        ResourceRecord.IpAddress(
          name = hostname,
          timeToLive = 5,
          address = inetAddress,
        )
      },
    )
  }

  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    requests.put(Request.FunctionCall(hostname))
    return get(hostname)
  }

  /** Note that this request is not recorded. */
  @Throws(UnknownHostException::class)
  operator fun get(hostname: String): List<InetAddress> {
    val records = data[hostname] ?: throw UnknownHostException()
    return records
      .filterIsInstance<ResourceRecord.IpAddress>()
      .map { it.address }
  }

  /** Clears the results for `hostname`. */
  fun clear(hostname: String) {
    data.remove(hostname)
  }

  fun takeRequest(): Request = requests.take()

  fun pollRequest(): Request? = requests.poll()

  fun takeAllRequests(): List<Request> =
    buildList {
      while (true) {
        val pair = pollRequest()
        add(pair ?: break)
      }
    }

  /**
   * Takes all requests received thus far (for all question types), and asserts that they match
   * [expectedHosts].
   */
  fun assertRequests(vararg expectedHosts: String?) {
    val actualHostnames = takeAllRequests().map { it.hostname }
    assertThat(actualHostnames).containsExactly(*expectedHosts)
  }

  /** Allocates and returns `count` fake IPv4 addresses like [255.0.0.100, 255.0.0.101].  */
  fun allocate(count: Int): List<InetAddress> {
    val from = nextAddress
    nextAddress += count
    return (from until nextAddress)
      .map {
        return@map InetAddress.getByAddress(
          Buffer().writeInt(it.toInt()).readByteArray(),
        )
      }
  }

  /** Allocates and returns `count` fake IPv6 addresses like [::ff00:64, ::ff00:65].  */
  fun allocateIpv6(count: Int): List<InetAddress> {
    val from = nextAddress
    nextAddress += count
    return (from until nextAddress)
      .map {
        return@map InetAddress.getByAddress(
          Buffer().writeLong(0L).writeLong(it).readByteArray(),
        )
      }
  }

  fun invoke(request: DnsMessage): DnsMessage {
    val answers =
      buildList {
        for (question in request.questions) {
          val records = data[question.name] ?: continue
          for (record in records) {
            if (!record.matches(question)) continue
            add(record)
          }
        }
      }

    //     QR = 1 (Response)
    // OPCODE = 0 (standard query)
    //     RD = 1 (Recursion Desired)
    //     RA = 1 (Recursion Available)
    //  RCODE = 0 (success)
    //           QR OPCODE AA TC RD RA   Z RCODE
    val flags = 0b1___0000__0__0__1__1_000__0000

    return DnsMessage(
      id = request.id,
      flags = flags,
      questions = request.questions,
      answers = answers,
    )
  }

  private fun ResourceRecord.matches(question: Question): Boolean {
    if (question.`class` != CLASS_IN) return false

    return when (question.type) {
      TYPE_A -> (this as? ResourceRecord.IpAddress)?.address is Inet4Address
      TYPE_AAAA -> (this as? ResourceRecord.IpAddress)?.address is Inet6Address
      TYPE_HTTPS -> this is ResourceRecord.Https
      else -> false
    }
  }

  sealed interface Request {
    val hostname: String

    data class DnsOverHttpsRequest(
      val httpRequest: RecordedRequest,
      val dnsRequest: DnsMessage,
    ) : Request {
      override val hostname: String
        get() = dnsRequest.questions.single().name
    }

    data class FunctionCall(
      override val hostname: String,
    ) : Request
  }
}
