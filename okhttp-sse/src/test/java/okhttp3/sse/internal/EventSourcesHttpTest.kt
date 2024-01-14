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
package okhttp3.sse.internal

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.sse.EventSources.processResponse
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class EventSourcesHttpTest {
  @RegisterExtension
  val platform = PlatformRule()
  private lateinit var server: MockWebServer

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val listener = EventSourceRecorder()
  private val client = clientTestRule.newClient()

  @BeforeEach
  fun before(server: MockWebServer) {
    this.server = server
  }

  @AfterEach
  fun after() {
    listener.assertExhausted()
  }

  @Test
  fun processResponse() {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    val request =
      Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    processResponse(response, listener)
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
  }

  @Test
  fun cancelShortCircuits() {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    listener.enqueueCancel() // Will cancel in onOpen().
    val request =
      Request.Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    processResponse(response, listener)
    listener.assertOpen()
    listener.assertFailure("canceled")
  }
}
