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

import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException

/**
 * Run with "./gradlew :android-test:connectedCheck -PandroidBuild=true" and make sure ANDROID_SDK_ROOT is set.
 */
class AndroidClientBuilderTest {

  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().context
    client = OkHttpClient.newAndroidBuilder(context) {
      addQuicHint("google.com", 443, 443)
      addQuicHint("www.google.com", 443, 443)
    }
      .build()
  }

  @Test
  fun testRequestExternal() {
    assumeNetwork()

    val call = client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
    }
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      throw TestAbortedException(uhe.message, uhe)
    }
  }
}
