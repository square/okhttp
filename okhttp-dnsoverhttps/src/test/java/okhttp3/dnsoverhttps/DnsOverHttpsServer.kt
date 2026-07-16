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
package okhttp3.dnsoverhttps

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okio.Buffer
import okio.ByteString.Companion.decodeBase64

/**
 * Handles DNS calls using in-memory records.
 */
internal class DnsOverHttpsServer : Dispatcher() {
  private val data = ConcurrentHashMap<String, List<ResourceRecord>>()

  var extraHeaders: Headers = Headers.headersOf()
  val requests = LinkedBlockingDeque<Pair<RecordedRequest, DnsMessage>>()
  var override: MockResponse? = null

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

  fun takeRequest(): Pair<RecordedRequest, DnsMessage> = requests.take()

  fun pollRequest(): Pair<RecordedRequest, DnsMessage>? = requests.poll()

  override fun dispatch(request: RecordedRequest): MockResponse {
    val override = this.override
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
    requests.put(request to dnsRequest)

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
}
