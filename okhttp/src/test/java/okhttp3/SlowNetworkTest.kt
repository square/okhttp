/*
 * Copyright (C) 2020 Square, Inc.
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

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import okhttp3.testing.PlatformRule
import okio.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SlowNetworkTest {
  @JvmField
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private lateinit var client: OkHttpClient
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server

    client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .callTimeout(15.seconds)
        .connectTimeout(15.seconds)
        .build()

    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  @Test
  fun slowRequests() {
    repeat(100) {
      server.enqueue(
        MockResponse.Builder()
          .socketPolicy(SocketPolicy.DelayAccept(10.milliseconds))
          .build(),
      )
    }

    (1..100).map {
      client.newCall(Request(server.url("/"))).enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            response.body.string()
          }
        },
      )
    }
  }
}
