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
package okhttp3.android.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkInfo
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo


@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [30],
)
class NetworkObserverTest {
  private lateinit var connectivityShadow: ShadowConnectivityManager
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var networkObserver: NetworkObserver
  private lateinit var context: Context

  private val listener = TestNetworkListener()

  var wifiNetwork: Network = ShadowNetwork.newInstance(123)
  @Suppress("DEPRECATION")
  var wifiNetworkInfo = ShadowNetworkInfo.newInstance(
    NetworkInfo.DetailedState.CONNECTED,
    ConnectivityManager.TYPE_WIFI,
    0,
    true,
    NetworkInfo.State.CONNECTED
  )

  var lteNetwork: Network = ShadowNetwork.newInstance(124)
  @Suppress("DEPRECATION")
  var lteNetworkInfo = ShadowNetworkInfo.newInstance(
    NetworkInfo.DetailedState.CONNECTED,
    ConnectivityManager.TYPE_MOBILE,
    0,
    true,
    NetworkInfo.State.CONNECTED
  )

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    networkObserver = NetworkObserver(context, listener)

    connectivityManager = context.getSystemService<ConnectivityManager>()!!
    connectivityShadow = Shadows.shadowOf(connectivityManager)
  }

  @Test
  fun testEvents() {
    connectivityShadow.clearAllNetworks()

    assertThat(listener.isOnline).isFalse()

    connectivityShadow.addNetwork(wifiNetwork, wifiNetworkInfo)

    assertThat(listener.isOnline).isTrue()

    connectivityShadow.addNetwork(lteNetwork, lteNetworkInfo)

    assertThat(listener.isOnline).isTrue()

    connectivityShadow.removeNetwork(wifiNetwork)

    assertThat(listener.isOnline).isTrue()

    connectivityShadow.removeNetwork(lteNetwork)

    assertThat(listener.isOnline).isTrue()
  }
}

class TestNetworkListener: NetworkObserver.Listener {
  var isOnline = false

  override fun onConnectivityChange(isOnline: Boolean) {
    this.isOnline = isOnline
  }
}
