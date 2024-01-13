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

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClientTestRule
import okhttp3.RecordingEventListener
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources.createFactory
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest

@Tag("Slowish")
class EventSourceHttpTest {
  @RegisterExtension
  val platform = PlatformRule()
  private lateinit var server: MockWebServer

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private val eventListener = RecordingEventListener()
  private val listener = EventSourceRecorder()
  private var client =
    clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(eventListener))
      .build()

  @BeforeEach
  fun before(server: MockWebServer) {
    this.server = server
  }

  @AfterEach
  fun after() {
    listener.assertExhausted()
  }

  @Test
  fun event() {
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
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
  }

  @RetryingTest(5)
  fun cancelInEventShortCircuits() {
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
    newEventSource()
    listener.assertOpen()
    listener.assertFailure("canceled")
  }

  @Test
  fun badContentType() {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/plain")
        .build(),
    )
    newEventSource()
    listener.assertFailure("Invalid content-type: text/plain")
  }

  @Test
  fun badResponseCode() {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        )
        .setHeader("content-type", "text/event-stream")
        .code(401)
        .build(),
    )
    newEventSource()
    listener.assertFailure(null)
  }

  @Test
  fun fullCallTimeoutDoesNotApplyOnceConnected() {
    client =
      client.newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build()
    server.enqueue(
      MockResponse.Builder()
        .bodyDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .body("data: hey\n\n")
        .build(),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
  }

  @Test
  fun fullCallTimeoutAppliesToSetup() {
    client =
      client.newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build()
    server.enqueue(
      MockResponse.Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .body("data: hey\n\n")
        .build(),
    )
    newEventSource()
    listener.assertFailure("timeout")
  }

  @Test
  fun retainsAccept() {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        )
        .setHeader("content-type", "text/event-stream")
        .build(),
    )
    newEventSource("text/plain")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
    assertThat(server.takeRequest().headers["Accept"]).isEqualTo("text/plain")
  }

  @Test
  fun setsMissingAccept() {
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
    newEventSource()
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
    assertThat(server.takeRequest().headers["Accept"])
      .isEqualTo("text/event-stream")
  }

  @Test
  fun eventListenerEvents() {
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
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
    assertThat(eventListener.recordedEventTypes()).containsExactly(
      "CallStart",
      "ProxySelectStart",
      "ProxySelectEnd",
      "DnsStart",
      "DnsEnd",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "RequestHeadersStart",
      "RequestHeadersEnd",
      "ResponseHeadersStart",
      "ResponseHeadersEnd",
      "ResponseBodyStart",
      "ResponseBodyEnd",
      "ConnectionReleased",
      "CallEnd",
    )
  }

  private fun newEventSource(accept: String? = null): EventSource {
    val builder =
      Request.Builder()
        .url(server.url("/"))
    if (accept != null) {
      builder.header("Accept", accept)
    }
    val request = builder.build()
    val factory = createFactory(client)
    return factory.newEventSource(request, listener)
  }
}
