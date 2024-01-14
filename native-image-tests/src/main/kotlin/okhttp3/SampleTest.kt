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

import assertk.assertThat
import assertk.assertions.isEqualTo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

class SampleTest {
  @JvmField @RegisterExtension
  val clientRule = OkHttpClientTestRule()

  @Test
  fun passingTest() {
    assertThat("hello").isEqualTo("hello")
  }

  @Test
  fun testMockWebServer(server: MockWebServer) {
    server.enqueue(MockResponse(body = "abc"))

    val client = clientRule.newClient()

    client.newCall(Request(url = server.url("/"))).execute().use {
      assertThat(it.body.string()).isEqualTo("abc")
    }
  }

  @Test
  fun testExternalSite() {
    val client = clientRule.newClient()

    client.newCall(Request(url = "https://google.com/robots.txt".toHttpUrl())).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }

  @ParameterizedTest
  @ArgumentsSource(SampleTestProvider::class)
  fun testParams(mode: String) {
  }
}

class SampleTestProvider : SimpleProvider() {
  override fun arguments() = listOf("A", "B")
}
