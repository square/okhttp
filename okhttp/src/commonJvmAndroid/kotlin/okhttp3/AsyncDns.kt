/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import okio.IOException

/**
 * An async domain name service that resolves IP addresses for host names.
 *
 * The main implementations will typically be implemented using specific DNS libraries such as
 *  * Android DnsResolver
 *  * OkHttp DnsOverHttps
 *  * dnsjava Resolver
 *
 * Implementations of this interface must be safe for concurrent use.
 */
@ExperimentalOkHttpApi
interface AsyncDns {
  /**
   * Query DNS records for `hostname`, in the order they are received.
   */
  fun query(
    hostname: String,
    callback: Callback,
  )

  /**
   * Callback to receive results from the DNS Queries.
   */
  @ExperimentalOkHttpApi
  interface Callback {
    /**
     * Return addresses for a dns query for a single class of IPv4 (A) or IPv6 (AAAA).
     * May be an empty list indicating that the host is unreachable.
     */
    fun onResponse(
      hostname: String,
      addresses: List<InetAddress>,
    )

    /**
     * Returns an error for the DNS query.
     */
    fun onFailure(
      hostname: String,
      e: IOException,
    )
  }

  /**
   * Class of DNS addresses, such that clients that treat these differently, such
   * as attempting IPv6 first, can make such decisions.
   */
  @ExperimentalOkHttpApi
  enum class DnsClass(val type: Int) {
    IPV4(TYPE_A),
    IPV6(TYPE_AAAA),
  }

  @ExperimentalOkHttpApi
  companion object {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /**
     * Adapt an AsyncDns implementation to Dns, waiting until onComplete is received
     * and returning results if available.
     */
    fun toDns(vararg asyncDns: AsyncDns): Dns =
      Dns { hostname ->
        val allAddresses = mutableListOf<InetAddress>()
        val allExceptions = mutableListOf<IOException>()
        val latch = CountDownLatch(asyncDns.size)

        asyncDns.forEach {
          it.query(
            hostname,
            object : Callback {
              override fun onResponse(
                hostname: String,
                addresses: List<InetAddress>,
              ) {
                synchronized(allAddresses) {
                  allAddresses.addAll(addresses)
                }
                latch.countDown()
              }

              override fun onFailure(
                hostname: String,
                e: IOException,
              ) {
                synchronized(allExceptions) {
                  allExceptions.add(e)
                }
                latch.countDown()
              }
            },
          )
        }

        latch.await()

        // No mutations should be possible after this point
        if (allAddresses.isEmpty()) {
          val first = allExceptions.firstOrNull() ?: UnknownHostException("No results for $hostname")

          allExceptions.drop(1).forEach {
            first.addSuppressed(it)
          }

          throw first
        }

        allAddresses
      }
  }
}
