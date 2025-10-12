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
import okhttp3.ClientForkingInterceptor
import okhttp3.ExperimentalOkHttpApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.internal.AndroidDns
import okhttp3.internal.SuppressSignatureCheck

/**
 * Decorator that supports Network Pinning on Android via Request tags.
 */
@OptIn(ExperimentalOkHttpApi::class)
@RequiresApi(Build.VERSION_CODES.Q)
@SuppressSignatureCheck
class AndroidNetworkPinning : ClientForkingInterceptor<Network>() {
  /** ConnectivityManager.NetworkCallback that will clean up after networks are lost. */
  val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onLost(network: Network) {
        removeClient(network)
      }
    }

  override fun OkHttpClient.Builder.buildForKey(key: Network): OkHttpClient =
    dns(AndroidDns(key))
      .socketFactory(key.socketFactory)
      .apply {
        // Keep interceptors after this one in the new client
        interceptors.subList(interceptors.indexOf(this@AndroidNetworkPinning) + 1, interceptors.size).clear()
      }.build()

  override fun clientKey(request: Request): Network? = request.tag<Network>()
}
