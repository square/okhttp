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

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.OkHttpClientContext.okHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [30],
  qualifiers = "w221dp-h221dp-small-notlong-round-watch-xhdpi-keyshidden-nonav"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobolectricClientBuilderTest {

  private lateinit var context: Context
  private lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    client = context.okHttpClient
  }

  @Test
  fun testRequestExternal() {
    assumeNetwork()

    val request = Request("https://www.google.com/robots.txt".toHttpUrl())

    val networkRequest = request.newBuilder()
      .cacheControl(CacheControl.FORCE_NETWORK)
      .build()

    val call = client.newCall(networkRequest)

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.networkResponse).isNull()
    }

    val cachedCall = client.newCall(request)

    cachedCall.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.networkResponse).isNotNull()
    }
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      throw AssumptionViolatedException(uhe.message, uhe)
    }
  }
}
