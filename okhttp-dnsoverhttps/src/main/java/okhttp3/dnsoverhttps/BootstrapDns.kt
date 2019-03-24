/*
 * Copyright (C) 2018 Square, Inc.
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

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Internal Bootstrap DNS implementation for handling initial connection to DNS over HTTPS server.
 *
 * Returns hardcoded results for the known host.
 */
internal class BootstrapDns(
  private val dnsHostname: String,
  private val dnsServers: List<InetAddress>
) : Dns {
  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    if (this.dnsHostname != hostname) {
      throw UnknownHostException(
          "BootstrapDns called for $hostname instead of $dnsHostname"
      )
    }

    return dnsServers
  }
}
