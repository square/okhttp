/*
 * Copyright (C) 2014 Square, Inc.
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

import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Platform
import org.assertj.core.api.Assertions.assertThat
import org.conscrypt.Conscrypt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ConscryptTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public val platform = PlatformRule.conscrypt()

  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  private lateinit var client: OkHttpClient

  @Before fun setUp() {
    platform.assumeConscrypt()
    client = clientTestRule.newClient()
  }

  @Test
  fun testTrustManager() {
    assertThat(Conscrypt.isConscrypt(Platform.get().platformTrustManager())).isTrue()
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      Assume.assumeNoException(uhe)
    }
  }

  @Test
  fun testMozilla() {
    assumeNetwork()

    val request = Request.Builder().url("https://mozilla.org/robots.txt").build()

    client.newCall(request).execute().use {
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }
  }

  @Test
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
  fun testBuildIfSupported() {
    val actual = ConscryptPlatform.buildIfSupported()
    assertThat(actual).isNotNull
  }

  @Test
  fun testVersion() {
    assertTrue(ConscryptPlatform.atLeastVersion(1, 4, 9))
    assertTrue(ConscryptPlatform.atLeastVersion(2))
    assertTrue(ConscryptPlatform.atLeastVersion(2, 1))
    assertTrue(ConscryptPlatform.atLeastVersion(2, 1, 0))
    assertFalse(ConscryptPlatform.atLeastVersion(2, 1, 1))
    assertFalse(ConscryptPlatform.atLeastVersion(2, 2))
    assertFalse(ConscryptPlatform.atLeastVersion(9))
  }
}
