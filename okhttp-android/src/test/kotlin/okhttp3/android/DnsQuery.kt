/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.android

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import okhttp3.AsyncDns

internal fun dnsQuery(dns: AndroidAsyncDns, hostname: String): Pair<List<InetAddress>, Exception?> {
  val allAddresses = mutableListOf<InetAddress>()
  var exception: Exception? = null
  val latch = CountDownLatch(1)

  dns.query(
    hostname,
    object : AsyncDns.Callback {
      override fun onResponse(
        hostname: String,
        addresses: List<InetAddress>,
      ) {
        allAddresses.addAll(addresses)
        latch.countDown()
      }

      override fun onFailure(
        hostname: String,
        e: IOException,
      ) {
        exception = e
        latch.countDown()
      }
    },
  )

  latch.await()

  return Pair(allAddresses, exception)
}
