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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class EarlyHintsTest {
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = true
    recordSslDebug = true
  }

  val eventListener = RecordingEventListener()

  private val client = clientTestRule.newClientBuilder()
    .eventListenerFactory(clientTestRule.wrap(eventListener))
    .build()

  @Test
  fun testCloudflare() {
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

    clientTestRule.logEvents()
  }
}
