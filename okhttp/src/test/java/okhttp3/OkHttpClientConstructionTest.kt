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

import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.platform.Platform
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension

class OkHttpClientConstructionTest {
  @RegisterExtension
  var platform = PlatformRule()

  @Test fun constructionDoesntTriggerPlatformOrSSL() {
    Platform.resetForTests(platform = ExplosivePlatform { TODO("Avoid call") })

    val client = OkHttpClient()

    assertNotNull(client.toString())

    client.newCall(Request("https://example.org/robots.txt".toHttpUrl()))
  }

  @Test fun cloneDoesntTriggerPlatformOrSSL() {
    Platform.resetForTests(platform = ExplosivePlatform { TODO("Avoid call") })

    val client = OkHttpClient()

    val client2 = client.newBuilder().build()
    assertNotNull(client2.toString())
  }

  @Test fun triggersOnExecute() {
    Platform.resetForTests(platform = ExplosivePlatform { throw NoSuchAlgorithmException() })

    val client = OkHttpClient()

    val call = client.newCall(Request("https://example.org/robots.txt".toHttpUrl()))

    val exception =
      assertThrows<NoSuchAlgorithmException> {
        call.execute()
      }
  }

  class ExplosivePlatform(private val explode: () -> Nothing) : Platform() {
    override fun newSSLContext(): SSLContext {
      explode()
    }

    override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
      explode()
    }

    override fun platformTrustManager(): X509TrustManager {
      explode()
    }
  }
}
