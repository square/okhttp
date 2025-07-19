/*
 * Copyright (C) 2025 Block, Inc.
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

import java.util.logging.Logger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp.android.test.AlwaysHttps.Policy
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slow")
class AndroidCallDecoratorTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension
  public val clientTestRule =
    OkHttpClientTestRule().apply {
      logger = Logger.getLogger(AndroidCallDecoratorTest::class.java.name)
    }

  private var client: OkHttpClient =
    clientTestRule
      .newClientBuilder()
      .addCallDecorator(AlwaysHttps(Policy.Always))
      .addCallDecorator(OffMainThread)
      .build()

  @StartStop
  private val server = MockWebServer()

  private val handshakeCertificates = localhost()

  @Test
  fun testSecureRequest() {
    enableTls()

    server.enqueue(MockResponse())

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use {
      assertEquals(200, it.code)
    }
  }

  @Test
  fun testInsecureRequestChangedToSecure() {
    enableTls()

    server.enqueue(MockResponse())

    val request =
      Request
        .Builder()
        .url(
          server
            .url("/")
            .newBuilder()
            .scheme("http")
            .build(),
        ).build()

    client.newCall(request).execute().use {
      assertEquals(200, it.code)
      assertEquals("https", it.request.url.scheme)
    }
  }

  private fun enableTls() {
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }
}
