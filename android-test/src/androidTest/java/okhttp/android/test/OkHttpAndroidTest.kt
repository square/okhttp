/*
 * Copyright (c) 2022 Block, Inc.
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

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.AndroidBuilder
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@ExtendWith(MockWebServerExtension::class)
@Tag("Slow")
class OkHttpAndroidTest {
  private var client: OkHttpClient = OkHttpClient.AndroidBuilder(
    cache = null
  ).build()

  private val handshakeCertificates = localhost()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setup(server: MockWebServer) {
    this.server = server
    enableTls()
  }

  @Test
  fun testRequest() {
    server.enqueue(MockResponse())

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  fun OkHttpClient.close() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
    client.cache?.close()
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }
}
