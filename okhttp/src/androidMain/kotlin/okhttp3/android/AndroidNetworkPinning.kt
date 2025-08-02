/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.android

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.android.internal.AndroidDns

/**
 * Decorator that supports Network Pinning on Android via Request tags.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AndroidNetworkPinning : Call.Decorator {
  private val pinnedClients = Collections.synchronizedMap(mutableMapOf<String, OkHttpClient>())

  /** ConnectivityManager.NetworkCallback that will cleanup after networks are lost. */
  val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onLost(network: Network) {
        pinnedClients.remove(network.toString())
      }
    }

  override fun newCall(chain: Call.Chain): Call {
    val request = chain.request

    val pinnedNetwork = request.tag<Network>() ?: return chain.proceed(request)

    val pinnedClient =
      pinnedClients.computeIfAbsent(pinnedNetwork.toString()) {
        chain.client.withNetwork(network = pinnedNetwork)
      }

    return pinnedClient.newCall(request)
  }

  private fun OkHttpClient.withNetwork(network: Network): OkHttpClient =
    newBuilder()
      .dns(AndroidDns(network))
      .socketFactory(network.socketFactory)
      .apply {
        // Keep decorators after this one in the new client
        val indexOfThisDecorator = callDecorators.indexOf(this@AndroidNetworkPinning)
        callDecorators.subList(0, indexOfThisDecorator + 1).clear()
      }.build()
}
