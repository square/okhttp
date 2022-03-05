/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class EarlyHintsTest {
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = true
    recordSslDebug = true
  }

  val responseHeaders = mutableListOf<Response>()

  val eventListener = object : EventListener() {
    override fun responseHeadersEnd(call: Call, response: Response) {
      responseHeaders.add(response)
    }
  }

  private val client = clientTestRule.newClientBuilder()
    .eventListenerFactory(clientTestRule.wrap(eventListener))
    .build()

  @Test
  fun testTestServer() {
    // Not working - https://early-hints.fastlylabs.com/test.png
    val request = Request.Builder().url("https://tradingstrategy.ai")
      .header("User-Agent", "curl/7.77.0")
      .build()

    val response =
      client.newCall(request)
        .execute()

    response.use {
      println(response.code)
      println(response.body?.contentType())
    }

    assertThat(responseHeaders).hasSize(2)

    val response103 = responseHeaders[0]
    assertThat(response103.code).isEqualTo(103)
    assertThat(response103.headers.names()).isEqualTo(setOf("link"))

    val response200 = responseHeaders[1]
    assertThat(response200.code).isEqualTo(200)
    assertThat(response200.headers.size).isGreaterThan(1)

    clientTestRule.logEvents()
  }
}
