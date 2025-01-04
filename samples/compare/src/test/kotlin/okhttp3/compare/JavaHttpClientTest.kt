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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.matches
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect.NORMAL
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Java HTTP Client.
 *
 * https://openjdk.java.net/groups/net/httpclient/intro.html
 *
 * Baseline test if we ned to validate OkHttp behaviour against other popular clients.
 */
class JavaHttpClientTest {
  @JvmField @RegisterExtension
  val platform = PlatformRule()

  @Test fun get(server: MockWebServer) {
    // Not available
    platform.expectFailureOnJdkVersion(8)

    val httpClient =
      HttpClient.newBuilder()
        .followRedirects(NORMAL)
        .build()

    server.enqueue(
      MockResponse.Builder()
        .body("hello, Java HTTP Client")
        .build(),
    )

    val request =
      HttpRequest.newBuilder(server.url("/").toUri())
        .header("Accept", "text/plain")
        .build()

    val response = httpClient.send(request, BodyHandlers.ofString())
    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.body()).isEqualTo("hello, Java HTTP Client")

    val recorded = server.takeRequest()
    assertThat(recorded.headers["Accept"]).isEqualTo("text/plain")
    assertThat(recorded.headers["Accept-Encoding"]).isNull() // No built-in gzip.
    assertThat(recorded.headers["Connection"]).isEqualTo("Upgrade, HTTP2-Settings")
    if (PlatformVersion.majorVersion < 19) {
      assertThat(recorded.headers["Content-Length"]).isEqualTo("0")
    }
    assertThat(recorded.headers["HTTP2-Settings"]).isNotNull()
    assertThat(recorded.headers["Upgrade"]).isEqualTo("h2c") // HTTP/2 over plaintext!
    assertThat(recorded.headers["User-Agent"]!!).matches(Regex("Java-http-client/.*"))
  }
}
