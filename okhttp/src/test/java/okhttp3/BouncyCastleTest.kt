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

import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.Security

class BouncyCastleTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public val platform = PlatformRule()

  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  private lateinit var client: OkHttpClient

  @Before fun setUp() {
//    platform.assumeConscrypt()
    client = clientTestRule.newClient()
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

    var x: String? = null

    client = client.newBuilder().addNetworkInterceptor {
      x = it.connection()!!.socket().javaClass.simpleName
      it.proceed(it.request())
    }.build()

    client.newCall(request).execute().use {
      assertThat(it.handshake!!.cipherSuite).isEqualTo(
          CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256)
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(it.handshake!!.tlsVersion).isEqualTo(TlsVersion.TLS_1_2)
    }

    assertEquals("ProvSSLSocketWrap_9", x)
  }

  //  @Test
//  fun testBuildIfSupported() {
//    val actual = ConscryptPlatform.buildIfSupported()
//    assertThat(actual).isNotNull
//  }
  companion object {
    init {
      Security.removeProvider("SunEC")
      Security.removeProvider("SunJSSE")

      Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
      Security.insertProviderAt(BouncyCastleProvider(), 1)
      Security.removeProvider(BouncyCastleJsseProvider.PROVIDER_NAME)
      Security.insertProviderAt(BouncyCastleJsseProvider(), 2)
    }
  }
}
