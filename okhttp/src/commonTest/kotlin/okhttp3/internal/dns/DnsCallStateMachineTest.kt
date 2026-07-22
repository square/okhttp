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

      query1.respondIpAddresses(
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )

      query2.respondIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )

      query0.respondServiceMetadata(
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

      query1.respondFailure("boom!")

      query2.respondIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )

      query0.respondServiceMetadata(
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
        query2.respondIpAddresses(
          addresses = listOf(InetAddress.getByName("10.20.30.40")),
        )
        query1.respondIpAddresses(
          addresses = listOf(InetAddress.getByName("1:2::3:4")),
        )
      }
      query0.respondServiceMetadata(
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

      takeCancel("lysine.dev", TYPE_HTTPS)
      val query0 = takeQuery("lysine.dev", TYPE_HTTPS)
      takeCancel("lysine.dev", TYPE_AAAA)
      val query1 = takeQuery("lysine.dev", TYPE_AAAA)
      takeCancel("lysine.dev", TYPE_A)
      val query2 = takeQuery("lysine.dev", TYPE_A)

      query0.respondFailure("canceled")
      query1.respondFailure("canceled")
      query2.respondFailure("canceled")

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

      query1.respondIpAddresses(
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("1:2::3:4")),
      )

      call.cancel()

      takeCancel("lysine.dev", TYPE_HTTPS)
      takeCancel("lysine.dev", TYPE_A)

      query2.respondIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )
      takeOnRecordsIpAddresses(
        addresses = listOf(InetAddress.getByName("10.20.30.40")),
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }
}
