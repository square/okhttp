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

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

class SampleTest {
  @JvmField @RegisterExtension val clientRule = OkHttpClientTestRule()

  @Test
  fun failingTest() {
    assertThat("hello").isEqualTo("goodbye")
  }

  @Test
  fun testMockWebServer(server: MockWebServer) {
    server.enqueue(MockResponse().setBody("abc"))

    val client = clientRule.newClient()

    client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
      assertThat(it.body!!.string()).isEqualTo("abc")
    }
  }

  @Test
  fun testExternalSite() {
    val client = clientRule.newClient()

    client.newCall(Request.Builder().url("https://google.com/robots.txt").build()).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }

  @Test
  fun testPublicSuffixes() {
    PublicSuffixDatabase::class.java.getResourceAsStream(PublicSuffixDatabase.PUBLIC_SUFFIX_RESOURCE).use {
      assertThat(it.available()).isGreaterThan(30000)
    }
  }

  @ParameterizedTest
  @ArgumentsSource(SampleTestProvider::class)
  fun testParams(mode: String) {
  }
}

class SampleTestProvider: SimpleProvider() {
  override fun arguments() = listOf("A", "B")
}
