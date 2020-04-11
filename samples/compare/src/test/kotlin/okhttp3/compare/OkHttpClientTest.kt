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
package okhttp3.compare

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * OkHttp.
 *
 * https://square.github.io/okhttp/
 */
class OkHttpClientTest {
  @get:Rule val platform = PlatformRule()

  @JvmField @Rule val server = MockWebServer()

  private val client = OkHttpClient()

  @Test fun get() {
    platform.assumeNotBouncyCastle()

    server.enqueue(MockResponse()
        .setBody("hello, OkHttp"))

    val request = Request.Builder()
        .url(server.url("/"))
        .header("Accept", "text/plain")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body!!.string()).isEqualTo("hello, OkHttp")

    val recorded = server.takeRequest()
    assertThat(recorded.getHeader("Accept")).isEqualTo("text/plain")
    assertThat(recorded.getHeader("Accept-Encoding")).isEqualTo("gzip")
    assertThat(recorded.getHeader("Connection")).isEqualTo("Keep-Alive")
    assertThat(recorded.getHeader("User-Agent")).matches("okhttp/.*")
  }
}
