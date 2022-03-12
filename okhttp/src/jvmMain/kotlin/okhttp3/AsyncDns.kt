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
 * The main implementations will typically be implemented using specific Dns libraries such as
 *  * Android DnsResolver
 *  * OkHttp DnsOverHttps
 *  * dnsjava Resolver
 *
 * Implementations of this interface must be safe for concurrent use.
 *
 * The interface may be extended by specific AsyncDns implementations with additional records types,
 * so as SCVB/HTTPS records, which may not be generally applicable.
 */
interface AsyncDns {
  /**
   * Query dns records for `hostname`, in the order they are received.
   */
  fun query(hostname: String, callback: Callback)

  /**
   * Callback to receive results from the Dns Queries.
   */
  interface Callback {
    /**
     * Return addresses for a single dns class, IPv4 (A) or IPv6 (AAAA).
     *
     * This method may be called multiple times, including with the same dnsClass,
     * before a final onComplete or onError.
     */
    fun onAddressResults(dnsClass: DnsClass, addresses: List<InetAddress>)

    /**
     * Returns an error for a single dns class, the overall request may still succeed.
     */
    fun onError(dnsClass: DnsClass, e: IOException)

    /**
     * Returns a final signal that indicates no more calls to onAddressResults or onError
     * should be expected.
     */
    fun onComplete()
  }

  /**
   * Adapt an AsyncDns implementation to Dns, waiting until onComplete is received
   * and returning results if available.
   */
  fun asDns(): Dns = Dns { hostname ->
    val allAddresses = mutableListOf<InetAddress>()
    val allExceptions = mutableListOf<IOException>()
    val latch = CountDownLatch(1)

    query(hostname, object : Callback {
      override fun onAddressResults(dnsClass: DnsClass, addresses: List<InetAddress>) {
        allAddresses.addAll(addresses)
      }

      override fun onComplete() {
        latch.countDown()
      }

      override fun onError(dnsClass: DnsClass, e: IOException) {
        allExceptions.add(e)
      }
    })

    latch.await()

    if (allAddresses.isEmpty()) {
      val first = allExceptions.firstOrNull() ?: UnknownHostException("No results for $hostname")

      allExceptions.drop(1).forEach {
        first.addSuppressed(it)
      }

      throw first
    }

    allAddresses
  }

  /**
   * Class of Dns addresses, such that clients that treat these differently, such
   * as attempting IPv6 first, can make such decisions.
   */
  enum class DnsClass(val type: Int) {
    IPV4(TYPE_A),
    IPV6(TYPE_AAAA);
  }

  companion object {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
  }
}
