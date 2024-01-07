/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import java.net.InetAddress
import java.net.UnknownHostException
import okio.Buffer

class FakeDns : Dns {
  private val hostAddresses: MutableMap<String, List<InetAddress>> = mutableMapOf()
  private val requestedHosts: MutableList<String> = mutableListOf()
  private var nextAddress = 0xff000064L // 255.0.0.100 in IPv4; ::ff00:64 in IPv6.

  /** Sets the results for `hostname`.  */
  operator fun set(
    hostname: String,
    addresses: List<InetAddress>,
  ): FakeDns {
    hostAddresses[hostname] = addresses
    return this
  }

  /** Clears the results for `hostname`.  */
  fun clear(hostname: String): FakeDns {
    hostAddresses.remove(hostname)
    return this
  }

  @Throws(UnknownHostException::class)
  fun lookup(
    hostname: String,
    index: Int,
  ): InetAddress {
    return hostAddresses[hostname]!![index]
  }

  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    requestedHosts.add(hostname)
    return hostAddresses[hostname] ?: throw UnknownHostException()
  }

  fun assertRequests(vararg expectedHosts: String?) {
    assertThat(requestedHosts).containsExactly(*expectedHosts)
    requestedHosts.clear()
  }

  /** Allocates and returns `count` fake IPv4 addresses like [255.0.0.100, 255.0.0.101].  */
  fun allocate(count: Int): List<InetAddress> {
    val from = nextAddress
    nextAddress += count
    return (from until nextAddress)
      .map {
        return@map InetAddress.getByAddress(
          Buffer().writeInt(it.toInt()).readByteArray(),
        )
      }
  }

  /** Allocates and returns `count` fake IPv6 addresses like [::ff00:64, ::ff00:65].  */
  fun allocateIpv6(count: Int): List<InetAddress> {
    val from = nextAddress
    nextAddress += count
    return (from until nextAddress)
      .map {
        return@map InetAddress.getByAddress(
          Buffer().writeLong(0L).writeLong(it).readByteArray(),
        )
      }
  }
}
