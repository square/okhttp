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
import okhttp3.ech.EchConfig
import okhttp3.internal.connection.RealCall
import okio.ByteString
import okio.IOException

/**
 * Resolves [hostname] for this call.
 *
 * This is the internal, call-aware entry point for DNS. When the platform provides an [AsyncDns]
 * it is used (and any HTTPS/SVCB Encrypted Client Hello configuration is captured onto
 * [RealCall.echConfig]); otherwise the call is dropped and the public, call-less [dns] is used.
 * That keeps ECH attached to the resolution even though the public [Dns] interface can't carry it.
 */
internal fun RealCall.resolveAddresses(
  dns: Dns,
  hostname: String,
): List<InetAddress> {
  val asyncDns = client.asyncDns ?: return dns.lookup(hostname)

  val echMode = echMode ?: client.echModeConfiguration.echMode(hostname).also { echMode = it }

  val addresses = mutableListOf<InetAddress>()
  val failures = mutableListOf<IOException>()
  var echBytes: ByteString? = null
  val latch = CountDownLatch(1)

  asyncDns.newCall(hostname, addressesOnly = !echMode.attempt).enqueue(
    object : AsyncDns.DnsCallback {
      override fun onResults(
        call: AsyncDns.DnsCall,
        results: List<DnsResult>,
        hasMore: Boolean,
      ) {
        synchronized(addresses) {
          for (result in results) {
            when (result) {
              is DnsResult.Address -> addresses += result.address
              is DnsResult.HttpsService -> if (echBytes == null) echBytes = result.ech
            }
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

  echConfig =
    echBytes?.let { bytes ->
      object : EchConfig {
        override val config: ByteString = bytes
      }
    }

  synchronized(addresses) {
    if (addresses.isNotEmpty()) return addresses.toList()
  }
  throw synchronized(failures) { failures.firstOrNull() }
    ?: UnknownHostException("$asyncDns returned no addresses for $hostname")
}
