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
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jetty.client.HttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Jetty HTTP client.
 *
 * https://www.eclipse.org/jetty/documentation/current/http-client.html
 */
class JettyHttpClientTest {
  @JvmField @Rule val server = MockWebServer()

  private val client = HttpClient()

  @Before fun setUp() {
    client.start()
  }

  @After fun tearDown() {
    client.stop()
  }

  @Test fun get() {
    server.enqueue(MockResponse()
        .setBody("hello, Jetty HTTP Client"))

    val request = client.newRequest(server.url("/").toUri())
        .header("Accept", "text/plain")
    val response = request.send()
    assertThat(response.status).isEqualTo(200)
    assertThat(response.contentAsString).isEqualTo("hello, Jetty HTTP Client")

    val recorded = server.takeRequest()
    assertThat(recorded.getHeader("Accept")).isEqualTo("text/plain")
    assertThat(recorded.getHeader("Accept-Encoding")).isEqualTo("gzip")
    assertThat(recorded.getHeader("Connection")).isNull()
    assertThat(recorded.getHeader("User-Agent")).matches("Jetty/.*")
  }
}
