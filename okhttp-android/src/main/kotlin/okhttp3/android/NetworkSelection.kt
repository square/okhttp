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

import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.ExperimentalOkHttpApi
import okhttp3.OkHttpClient

@ExperimentalOkHttpApi
object NetworkSelection {
  @RequiresApi(Build.VERSION_CODES.Q)
  fun OkHttpClient.Builder.withNetwork(network: Network?): OkHttpClient.Builder {
    return if (network == null) {
      dns(Dns.ANDROID)
        .socketFactory(SocketFactory.getDefault())
    } else {
      dns(Dns.forNetwork(network))
        .socketFactory(AndroidSocketFactory(network))
    }
  }
}
