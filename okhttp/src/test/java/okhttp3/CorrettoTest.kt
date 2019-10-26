/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3

import okhttp3.TestUtil.assumeNetwork
import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class CorrettoTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public val platform = PlatformRule.conscrypt()

  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  private val client = clientTestRule.newClient()

  @Before fun setUp() {
    platform.assumeCorretto()
  }

  @Test
  @Ignore
  fun testMozilla() {
    assumeNetwork()

    val request = Request.Builder().url("https://mozilla.org/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }
  }

  @Test
  @Ignore
  fun testGoogle() {
    assumeNetwork()

    val request = Request.Builder().url("https://google.com/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      if (it.handshake!!.tlsVersion != TlsVersion.TLS_1_3) {
        System.err.println("Flaky TLSv1.3 with google")
//    assertThat(it.handshake()!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
      }
    }
  }

  @Test
  fun testIfSupported() {
    assertThat(PlatformRule.isCorrettoSupported).isTrue()
    assertThat(PlatformRule.isCorrettoInstalled).isTrue()
  }
}
