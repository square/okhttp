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
package okhttp3.internal

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import okhttp3.AsyncDns
import okhttp3.Dns
import okhttp3.DnsResult
import okio.IOException

/**
 * Adapts an [AsyncDns] to the blocking [Dns] interface, waiting for the final result batch and
 * returning its addresses. HTTPS/SVCB metadata is not representable in [Dns] and is discarded.
 */
internal class BlockingAsyncDns(
  private val asyncDns: AsyncDns,
) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val addresses = mutableListOf<InetAddress>()
    val failures = mutableListOf<IOException>()
    val latch = CountDownLatch(1)

    // Dns can only carry addresses, so skip the HTTPS/SVCB query.
    asyncDns.newCall(hostname, addressesOnly = true).enqueue(
      object : AsyncDns.DnsCallback {
        override fun onResults(
          call: AsyncDns.DnsCall,
          results: List<DnsResult>,
          hasMore: Boolean,
        ) {
          synchronized(addresses) {
            for (result in results) {
              if (result is DnsResult.Address) addresses += result.address
            }
          }
          if (!hasMore) latch.countDown()
        }

        override fun onFailure(
          call: AsyncDns.DnsCall,
          e: IOException,
          hasMore: Boolean,
        ) {
          synchronized(failures) { failures += e }
          if (!hasMore) latch.countDown()
        }
      },
    )

    latch.await()

    synchronized(addresses) {
      if (addresses.isNotEmpty()) return addresses.toList()
    }

    throw synchronized(failures) { failures.firstOrNull() }
      ?: UnknownHostException("$asyncDns returned no addresses for $hostname")
  }
}
