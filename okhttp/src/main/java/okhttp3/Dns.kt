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

import okhttp3.Dns.Companion.SYSTEM
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A domain name service that resolves IP addresses for host names. Most applications will use the
 * [system DNS service][SYSTEM], which is the default. Some applications may provide their own
 * implementation to use a different DNS server, to prefer IPv6 addresses, to prefer IPv4 addresses,
 * or to force a specific known IP address.
 *
 * Implementations of this interface must be safe for concurrent use.
 */
interface Dns {
  /**
   * Returns the IP addresses of `hostname`, in the order they will be attempted by OkHttp. If a
   * connection to an address fails, OkHttp will retry the connection with the next address until
   * either a connection is made, the set of IP addresses is exhausted, or a limit is exceeded.
   */
  @Throws(UnknownHostException::class)
  fun lookup(hostname: String): List<InetAddress>

  companion object {
    /**
     * A DNS that uses [InetAddress.getAllByName] to ask the underlying operating system to
     * lookup IP addresses. Most custom [Dns] implementations should delegate to this instance.
     */
    @JvmField
    val SYSTEM = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        try {
          return InetAddress.getAllByName(hostname).toList()
        } catch (e: NullPointerException) {
          throw UnknownHostException("Broken system behaviour for dns lookup of $hostname").apply {
            initCause(e)
          }
        }
      }
    }
  }
}
