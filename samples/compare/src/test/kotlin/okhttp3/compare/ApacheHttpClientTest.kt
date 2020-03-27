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

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Apache HttpClient 5.x.
 *
 * https://hc.apache.org/httpcomponents-client-5.0.x/index.html
 */
class ApacheHttpClientTest {
  @JvmField @Rule val server = MockWebServer()

  private val httpClient = HttpClients.createDefault()

  @After fun tearDown() {
    httpClient.close()
  }

  @Test fun get() {
    server.enqueue(MockResponse()
        .setBody("hello, Apache HttpClient 5.x"))

    val request = HttpGet(server.url("/").toUri())
    request.addHeader("Accept", "text/plain")

    httpClient.execute(request).use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(EntityUtils.toString(response.entity)).isEqualTo("hello, Apache HttpClient 5.x")
    }

    val recorded = server.takeRequest()
    assertThat(recorded.getHeader("Accept")).isEqualTo("text/plain")
    assertThat(recorded.getHeader("Accept-Encoding")).isEqualTo("gzip, x-gzip, deflate")
    assertThat(recorded.getHeader("Connection")).isEqualTo("keep-alive")
    assertThat(recorded.getHeader("User-Agent")).startsWith("Apache-HttpClient/5.0")
  }
}
