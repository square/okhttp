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
package okhttp3.dnsoverhttps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.net.InetAddress

/**
 * A domain name service that resolves IP addresses for host names. Most applications will use the
 * [system DNS service][SYSTEM], which is the default. Some applications may provide their own
 * implementation to use a different DNS server, to prefer IPv6 addresses, to prefer IPv4 addresses,
 * or to force a specific known IP address.
 *
 * Implementations of this interface must be safe for concurrent use.
 */
interface AsyncDns {
  /**
   * Returns the IP addresses of `hostname`, in the order they will be attempted by OkHttp. If a
   * connection to an address fails, OkHttp will retry the connection with the next address until
   * either a connection is made, the set of IP addresses is exhausted, or a limit is exceeded.
   */
  fun lookup(hostname: String): Flow<InetAddress>

  fun toDns() = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      return runBlocking {
        this@AsyncDns.lookup(hostname).toList()
      }
    }
  }

  companion object {
    val SYSTEM: AsyncDns = AsyncDnsSystem()
    private class AsyncDnsSystem : AsyncDns {
      override fun lookup(hostname: String): Flow<InetAddress> {
        val system = Dns.SYSTEM
        return flow {
          withContext(Dispatchers.IO) {
            emitAll(system.lookup(hostname).asFlow())
          }
        }
      }
    }
  }
}