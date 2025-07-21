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
package okhttp3.nativeimage

import assertk.assertThat
import assertk.assertions.isEqualTo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test

class SampleTest {
  private val server = MockWebServer()

  private val client = OkHttpClient()

  @Test
  fun passingTest() {
    assertThat("hello").isEqualTo("hello")
  }

  @Test
  fun testMockWebServer() {
    server.enqueue(MockResponse(body = "abc"))
    server.start()

    client.newCall(Request(url = server.url("/"))).execute().use {
      assertThat(it.body.string()).isEqualTo("abc")
    }
  }

  @Test
  fun testExternalSite() {
    client.newCall(Request(url = "https://google.com/robots.txt".toHttpUrl())).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }
}
