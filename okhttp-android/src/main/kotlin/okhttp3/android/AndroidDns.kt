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
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.AsyncDns

@RequiresApi(Build.VERSION_CODES.Q)
class AndroidDns(val network: Network? = null) : AsyncDns {
  @RequiresApi(Build.VERSION_CODES.Q)
  private val resolver = DnsResolver.getInstance()
  private val executor = Executors.newSingleThreadExecutor()

  override fun query(hostname: String, callback: AsyncDns.Callback) {
    val executing = AtomicInteger(2)

    resolver.query(
      network, hostname, DnsResolver.TYPE_A, DnsResolver.FLAG_EMPTY, executor, null,
      callback(callback, AsyncDns.DnsClass.IPV4, executing)
    )
    resolver.query(
      network, hostname, DnsResolver.TYPE_AAAA, DnsResolver.FLAG_EMPTY, executor, null,
      callback(callback, AsyncDns.DnsClass.IPV6, executing)
    )
  }

  private fun callback(
    callback: AsyncDns.Callback,
    dnsClass: AsyncDns.DnsClass,
    executing: AtomicInteger
  ) = object : DnsResolver.Callback<List<InetAddress>> {
    override fun onAnswer(addresses: List<InetAddress>, rCode: Int) {
      callback.onAddressResults(dnsClass, addresses)

      synchronized(executing) {
        if (executing.decrementAndGet() == 0) {
          callback.onComplete()
        }
      }
    }

    override fun onError(e: DnsResolver.DnsException) {
      callback.onError(dnsClass, UnknownHostException(e.message).apply {
        initCause(e)
      })

      synchronized(executing) {
        if (executing.decrementAndGet() == 0) {
          callback.onComplete()
        }
      }
    }
  }
}
