/*
 * Copyright (c) 2024 Block, Inc.
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

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isEqualTo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.AndroidDnsTest.Companion.assumeNetwork
import okhttp3.android.NetworkSelection.withNetwork
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AndroidNetworkSelectionTest {
  private var workingNetwork: Network? = null

  private lateinit var client: OkHttpClient

  @Before
  fun init() {
    assumeTrue("Supported on API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    assumeNetwork()

    val connectivityManager =
      InstrumentationRegistry.getInstrumentation().context.getSystemService(ConnectivityManager::class.java)

    workingNetwork = connectivityManager.activeNetwork

    assumeTrue(workingNetwork != null)

    client =
      OkHttpClient.Builder()
        .withNetwork(network = workingNetwork).build()
  }

  @Test
  fun testRequest() {
    val call = client.newCall(Request("https://www.google.com/robots.txt".toHttpUrl()))

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }
}
