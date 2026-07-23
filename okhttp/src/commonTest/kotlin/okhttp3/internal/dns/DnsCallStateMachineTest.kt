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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import app.cash.burst.Burst
import java.net.InetAddress
import kotlin.test.Test
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi

@Burst
class DnsCallStateMachineTest {
  /** Arbitrary sample values. */
  private val blueIpv6s = listOf(InetAddress.getByName("1:2::3:4"))
  private val blueIpv4s = listOf(InetAddress.getByName("10.20.30.40"))

  @Test
  fun `happy path`(caching: Boolean = true) {
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = caching,
        )
      call.enqueue()

      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      query1.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }
  }

  @Test
  fun `cache already completed values`() =
    testDnsCallStateMachine {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()

      val call0QueryIpv6 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val call0QueryIpv4 = transport.takeQuery("lysine.dev", TYPE_A)

      call0QueryIpv6.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call0.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call0QueryIpv4.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call0.takeOnRecordsIpAddresses(
        last = true,
        addresses = blueIpv4s,
      )

      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()

      call1.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )
      call1.takeOnRecordsIpAddresses(
        last = true,
        addresses = blueIpv4s,
      )
    }

  /** Confirm that two queries to the cache yield a single query to the underlying transport. */
  @Test
  fun `cache in flight calls`() =
    testDnsCallStateMachine {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()

      val call0QueryIpv6 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val call0QueryIpv4 = transport.takeQuery("lysine.dev", TYPE_A)

      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()

      call0QueryIpv6.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call0.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )
      call1.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call0QueryIpv4.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call0.takeOnRecordsIpAddresses(
        last = true,
        addresses = blueIpv4s,
      )
      call1.takeOnRecordsIpAddresses(
        last = true,
        addresses = blueIpv4s,
      )
    }

  @Test
  fun `failure returned last`(caching: Boolean = true) =
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = caching,
        )
      call.enqueue()

      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      query1.respondFailure("boom!")

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        alpnIds = listOf(Protocol.HTTP_2),
      )

      call.takeOnFailure("boom!")
    }

  @Test
  fun `failure is cached`() =
    testDnsCallStateMachine {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeServiceMetadata = false,
        )
      call0.enqueue()

      val queryIpv6 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val queryIpv4 = transport.takeQuery("lysine.dev", TYPE_A)

      queryIpv6.respondFailure("boom!")
      queryIpv4.respondIpAddresses(
        addresses = blueIpv4s,
      )

      call0.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )
      call0.takeOnFailure("boom!")

      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeServiceMetadata = false,
        )
      call1.enqueue()

      call1.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )
      call1.takeOnFailure("boom!")
    }

  /**
   * Confirm that the state machine calls doesn't call any [Dns.Callback] methods until the previous
   * call to a [Dns.Callback] method has returned.
   *
   * Usually this will be a concurrency problem, but we can exercise it just as well by making a
   * re-entrant call on a single thread.
   */
  @Test
  fun `calls to onRecords are serialized`(caching: Boolean = true) =
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = caching,
        )
      call.enqueue()

      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      onNextEvent = {
        query2.respondIpAddresses(
          addresses = blueIpv4s,
        )
        query1.respondIpAddresses(
          addresses = blueIpv6s,
        )
      }
      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        alpnIds = listOf(Protocol.HTTP_2),
      )
      call.takeOnRecordsIpAddresses(
        last = true,
        addresses =
          listOf(
            InetAddress.getByName("10.20.30.40"),
            InetAddress.getByName("1:2::3:4"),
          ),
      )
    }

  /**
   * The implementation still enqueues canceled queries, because that's an easy way to jump to a
   * dispatcher thread to post the failures back to the callback.
   */
  @Test
  fun `cancel before enqueue`() =
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = false,
        )
      call.cancel()
      call.enqueue()

      transport.takeCancel("lysine.dev", TYPE_HTTPS)
      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      transport.takeCancel("lysine.dev", TYPE_AAAA)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      transport.takeCancel("lysine.dev", TYPE_A)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      query0.respondFailure("canceled")
      query1.respondFailure("canceled")
      query2.respondFailure("canceled")

      call.takeOnFailure("canceled")
    }

  @Test
  fun `cancel before enqueue with caching`() =
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
        )
      call.cancel()
      call.enqueue()

      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      query0.respondFailure("canceled")
      query1.respondFailure("canceled")
      query2.respondFailure("canceled")

      call.takeOnFailure("canceled")
    }

  /** Cancels are asynchronous and if the canceled query completes anyway, that's fine. */
  @Test
  fun `cancel ignored if canceled query completes`() =
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = false,
        )
      call.enqueue()

      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      query1.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call.cancel()

      transport.takeCancel("lysine.dev", TYPE_HTTPS)
      transport.takeCancel("lysine.dev", TYPE_A)

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }

  /** When caching, cancels aren't applied to the transport. */
  @Test
  fun `cancel ignored if canceled query completes with caching`() =
    testDnsCallStateMachine {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
        )
      call.enqueue()

      val query0 = transport.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = transport.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = transport.takeQuery("lysine.dev", TYPE_A)

      query1.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call.cancel()

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnFailure("canceled")
    }
}
