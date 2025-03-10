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
package okhttp3.android.internal

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import okhttp3.Dns
import okio.IOException

internal class BlockingAsyncDns(val asyncDns: AsyncDns) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val allAddresses = Collections.synchronizedSet(LinkedHashSet<InetAddress>())
    val allExceptions = Collections.synchronizedList(mutableListOf<IOException>())

    val latch = CountDownLatch(1)

    asyncDns.query(
      hostname,
      null,
      object : AsyncDns.Callback {
        override fun onAddresses(
          hasMore: Boolean,
          hostname: String,
          addresses: List<InetAddress>,
        ) {
          allAddresses.addAll(addresses)

          if (!hasMore) {
            latch.countDown()
          }
        }

        override fun onFailure(
          hasMore: Boolean,
          hostname: String,
          e: IOException,
        ) {
          allExceptions.add(e)

          if (!hasMore) {
            latch.countDown()
          }
        }
      },
    )

    latch.await()

    // No mutations should be possible after this point
    if (allAddresses.isEmpty()) {
      val first = allExceptions.firstOrNull() ?: UnknownHostException("No results for $hostname")

      allExceptions.drop(1).forEach {
        first.addSuppressed(it)
      }

      throw first
    }

    return allAddresses.toList()
  }

  companion object {
    /** Returns a [Dns] that blocks until all async results are available. */
    open fun AsyncDns.asBlocking(): Dns {
      return BlockingAsyncDns(this)
    }
  }
}
