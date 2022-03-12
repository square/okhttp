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

/**
 * Dns implementation based on android.net.DnsResolver, which submits two requests for
 * A and AAAA records, and returns the addresses or exception from each before returning.
 *
 * @param network network to use, if not selects the default network.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AndroidDns(
  val network: Network? = null,
  val dnsClasses: List<AsyncDns.DnsClass> = listOf(AsyncDns.DnsClass.IPV6, AsyncDns.DnsClass.IPV4)
) :
  AsyncDns {
  @RequiresApi(Build.VERSION_CODES.Q)
  private val resolver = DnsResolver.getInstance()
  private val executor = Executors.newSingleThreadExecutor()

  override fun query(hostname: String, callback: AsyncDns.Callback) {
    val executing = AtomicInteger(dnsClasses.size)

    dnsClasses.forEach { dnsClass ->
      resolver.query(
        network, hostname, dnsClass.type, DnsResolver.FLAG_EMPTY, executor, null,
        callback(callback, AsyncDns.DnsClass.IPV4, executing)
      )
    }
  }

  private fun callback(
    callback: AsyncDns.Callback,
    dnsClass: AsyncDns.DnsClass,
    executing: AtomicInteger
  ) = object : DnsResolver.Callback<List<InetAddress>> {
    override fun onAnswer(addresses: List<InetAddress>, rCode: Int) {
      callback.onAddressResults(dnsClass, addresses)

      possiblyComplete()
    }

    override fun onError(e: DnsResolver.DnsException) {
      callback.onError(dnsClass, UnknownHostException(e.message).apply {
        initCause(e)
      })

      possiblyComplete()
    }

    private fun possiblyComplete() {
      // Synchronized to ensure that onComplete is always after the last result sent
      synchronized(executing) {
        if (executing.decrementAndGet() == 0) {
          callback.onComplete()
        }
      }
    }
  }
}
