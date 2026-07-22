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

import java.io.IOException
import java.net.InetAddress
import kotlin.test.Test
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi

class DnsCallStateMachineTest {
  @Test
  fun `happy path`() =
    testDnsCallStateMachine(
      request = Dns.Request(hostname = "lysine.dev"),
    ) {
      enqueue()

      val query0 = takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = takeQuery("lysine.dev", TYPE_A)

      respondIpAddresses(
        query = query1.query,
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )

      respondIpAddresses(
        query = query2.query,
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )

      respondServiceMetadata(
        query = query0.query,
        alpnIds = listOf("h2"),
      )
      takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }

  @Test
  fun `failure returned last`() =
    testDnsCallStateMachine(
      request = Dns.Request(hostname = "lysine.dev"),
    ) {
      enqueue()

      val query0 = takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = takeQuery("lysine.dev", TYPE_A)

      respondFailure(
        query = query1.query,
        e = IOException("boom!"),
      )

      respondIpAddresses(
        query = query2.query,
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )

      respondServiceMetadata(
        query = query0.query,
        alpnIds = listOf("h2"),
      )
      takeOnRecordsServiceMetadata(
        alpnIds = listOf(Protocol.HTTP_2),
      )

      takeOnFailure("boom!")
    }

  /**
   * Confirm that the state machine calls doesn't call any [Dns.Callback] methods until the previous
   * call to a [Dns.Callback] method has returned.
   *
   * Usually this will be a concurrency problem, but we can exercise it just as well by making a
   * re-entrant call on a single thread.
   */
  @Test
  fun `calls to onRecords are serialized`() =
    testDnsCallStateMachine(request = Dns.Request(hostname = "lysine.dev")) {
      enqueue()

      val query0 = takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = takeQuery("lysine.dev", TYPE_A)

      onNextEvent = {
        respondIpAddresses(
          query = query2.query,
          addresses = listOf(InetAddress.getByName("10.20.30.40")),
        )
        respondIpAddresses(
          query = query1.query,
          addresses = listOf(InetAddress.getByName("1:2::3:4")),
        )
      }
      respondServiceMetadata(
        query = query0.query,
        alpnIds = listOf("h2"),
      )
      takeOnRecordsServiceMetadata(
        alpnIds = listOf(Protocol.HTTP_2),
      )
      takeOnRecordsIpAddresses(
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
    testDnsCallStateMachine(
      request = Dns.Request(hostname = "lysine.dev"),
    ) {
      call.cancel()
      enqueue()

      val query0 = takeCancel("lysine.dev", TYPE_HTTPS)
      takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = takeCancel("lysine.dev", TYPE_AAAA)
      takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = takeCancel("lysine.dev", TYPE_A)
      takeQuery("lysine.dev", TYPE_A)

      respondFailure(query0.query, IOException("canceled"))
      respondFailure(query1.query, IOException("canceled"))
      respondFailure(query2.query, IOException("canceled"))

      takeOnFailure("canceled")
    }

  /** Cancels are asynchronous and if the canceled query completes anyway, that's fine. */
  @Test
  fun `cancel ignored if canceled query completes`() =
    testDnsCallStateMachine(
      request = Dns.Request(hostname = "lysine.dev"),
    ) {
      enqueue()

      val query0 = takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = takeQuery("lysine.dev", TYPE_A)

      respondIpAddresses(
        query = query1.query,
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )

      call.cancel()

      takeCancel("lysine.dev", TYPE_HTTPS)
      takeCancel("lysine.dev", TYPE_A)

      respondIpAddresses(
        query = query2.query,
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )

      respondServiceMetadata(
        query = query0.query,
        alpnIds = listOf("h2"),
      )
      takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }
}
