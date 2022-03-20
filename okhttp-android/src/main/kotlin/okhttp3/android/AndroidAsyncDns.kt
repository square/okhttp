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

package okhttp3.android

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import okhttp3.AsyncDns

/**
 * DNS implementation based on android.net.DnsResolver, which submits a request for
 * A or AAAA records, and returns the addresses or exception.
 *
 * Two instances must be used to get all results for an address.
 *
 * @param network network to use, if not selects the default network.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AndroidAsyncDns(
  private val dnsClass: AsyncDns.DnsClass,
  private val network: Network? = null,
) : AsyncDns {
  @RequiresApi(Build.VERSION_CODES.Q)
  private val resolver = DnsResolver.getInstance()
  private val executor = Executors.newSingleThreadExecutor()

  override fun query(hostname: String, callback: AsyncDns.Callback) {
    resolver.query(
      network, hostname, dnsClass.type, DnsResolver.FLAG_EMPTY, executor, null,
      object : DnsResolver.Callback<List<InetAddress>> {
        override fun onAnswer(addresses: List<InetAddress>, rCode: Int) {
          callback.onResponse(hostname, addresses)
        }

        override fun onError(e: DnsResolver.DnsException) {
          callback.onFailure(hostname, UnknownHostException(e.message).apply {
            initCause(e)
          })
        }
      }
    )
  }

  companion object {
    val IPv4 = AndroidAsyncDns(dnsClass = AsyncDns.DnsClass.IPV4)
    val IPv6 = AndroidAsyncDns(dnsClass = AsyncDns.DnsClass.IPV6)
  }
}
