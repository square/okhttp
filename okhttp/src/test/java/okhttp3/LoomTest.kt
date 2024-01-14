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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class LoomTest {
  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  @JvmField
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private lateinit var server: MockWebServer

  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp(server: MockWebServer) {
    platform.assumeLoom()

    this.server = server

    client =
      clientTestRule.newClientBuilder()
        .dispatcher(Dispatcher(newVirtualThreadPerTaskExecutor()))
        .build()
  }

  private fun newVirtualThreadPerTaskExecutor(): ExecutorService {
    return Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor").invoke(null) as ExecutorService
  }

  @Test
  fun testRequest() {
    server.enqueue(MockResponse())

    val request = Request(server.url("/"))

    client.newCall(request).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }

  @Test
  fun testIfSupported() {
    assertThat(platform.isLoom()).isTrue()
  }
}
