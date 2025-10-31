/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp.android.test

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.android.AndroidNetworkPinning
import okhttp3.internal.connection.RealCall
import okhttp3.internal.platform.PlatformRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slow")
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class AndroidNetworkPinningTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension
  public val clientTestRule = OkHttpClientTestRule()

  val applicationContext = ApplicationProvider.getApplicationContext<Context>()
  val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)

  val pinning = AndroidNetworkPinning()

  private var client: OkHttpClient =
    clientTestRule
      .newClientBuilder()
      .addInterceptor(pinning)
      .addInterceptor {
        it.proceed(
          it
            .request()
            .newBuilder()
            .header("second-decorator", "true")
            .build(),
        )
      }.addInterceptor {
        val call = (it.call() as RealCall)
        val dns = call.client.dns
        it
          .proceed(it.request())
          .newBuilder()
          .header("used-dns", dns.javaClass.simpleName)
          .build()
      }.build()

  @StartStop
  private val server = MockWebServer()

  @BeforeEach
  fun setup() {
    // Needed because of Platform.resetForTests
    PlatformRegistry.applicationContext = applicationContext

    connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), pinning.networkCallback)
  }

  @Test
  fun testDefaultRequest() {
    server.enqueue(MockResponse(200, body = "Hello"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertNotEquals("AndroidDns", response.header("used-dns"))
      assertEquals("true", response.request.header("second-decorator"))
    }
  }

  @Test
  fun testPinnedRequest() {
    server.enqueue(MockResponse(200, body = "Hello"))

    val network = connectivityManager.activeNetwork

    assumeTrue(network != null)

    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .tag<Network>(network)
        .build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals("AndroidDns", response.header("used-dns"))
      assertEquals("true", response.request.header("second-decorator"))
    }
  }
}
