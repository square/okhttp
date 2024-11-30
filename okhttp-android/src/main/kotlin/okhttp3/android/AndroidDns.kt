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
import okhttp3.Call
import okhttp3.Dns
import okhttp3.ExperimentalOkHttpApi
import okhttp3.android.AndroidDns.DnsClass
import okhttp3.android.internal.AsyncDns
import okhttp3.android.internal.BlockingAsyncDns.Companion.asBlocking
import okhttp3.android.internal.CombinedAsyncDns.Companion.union

/**
 * DNS implementation based on android.net.DnsResolver, which submits a request for
 * A or AAAA records, and returns the addresses or exception.
 *
 * Two instances must be used to get all results for an address.
 *
 * @param network network to use, if not selects the default network.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@ExperimentalOkHttpApi
internal class AndroidDns internal constructor(
  private val dnsClass: DnsClass,
  private val network: Network? = null,
) : AsyncDns {
  @RequiresApi(Build.VERSION_CODES.Q)
  internal val resolver = DnsResolver.getInstance()
  private val executor = Executors.newSingleThreadExecutor()

  override fun query(
    hostname: String,
    originatingCall: Call?,
    callback: AsyncDns.Callback,
  ) {
    try {
      resolver.query(
        network,
        hostname,
        dnsClass.type,
        DnsResolver.FLAG_EMPTY,
        executor,
        null,
        object : DnsResolver.Callback<List<InetAddress>> {
          override fun onAnswer(
            addresses: List<InetAddress>,
            rCode: Int,
          ) {
            callback.onAddresses(hasMore = false, hostname = hostname, addresses = addresses)
          }

          override fun onError(e: DnsResolver.DnsException) {
            callback.onFailure(
              hasMore = false,
              hostname = hostname,
              e =
                UnknownHostException(e.message).apply {
                  initCause(e)
                },
            )
          }
        },
      )
    } catch (e: Exception) {
      // Handle any errors that might leak out
      // https://issuetracker.google.com/issues/319957694
      callback.onFailure(
        hasMore = false,
        hostname,
        UnknownHostException(e.message).apply {
          initCause(e)
        },
      )
    }
  }

  @ExperimentalOkHttpApi
  companion object {
    internal fun forNetwork(network: Network): AsyncDns {
      return union(
        AndroidDns(dnsClass = DnsClass.IPV4, network = network),
        AndroidDns(dnsClass = DnsClass.IPV6, network = network),
      )
    }

    internal const val TYPE_A = 1
    internal const val TYPE_AAAA = 28
  }

  /**
   * Class of DNS addresses, such that clients that treat these differently, such
   * as attempting IPv6 first, can make such decisions.
   */
  @ExperimentalOkHttpApi
  internal enum class DnsClass(val type: Int) {
    IPV4(TYPE_A),
    IPV6(TYPE_AAAA),
  }
}

val Dns.Companion.ANDROID: Dns
  @RequiresApi(Build.VERSION_CODES.Q)
  get() = union(AndroidDns(dnsClass = DnsClass.IPV4), AndroidDns(dnsClass = DnsClass.IPV6)).asBlocking()

@RequiresApi(Build.VERSION_CODES.Q)
fun Dns.Companion.forNetwork(network: Network): Dns = AndroidDns.forNetwork(network).asBlocking()
