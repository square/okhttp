/*
 * Copyright (C) 2025 Block, Inc.
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
package okhttp3.internal.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.Socket
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.testing.PlatformRule
import okio.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class SocketFailureTest {
  @RegisterExtension
  val platform = PlatformRule()

  val listener = SocketClosingEventListener()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private lateinit var server: MockWebServer
  private var client =
    clientTestRule
      .newClientBuilder()
      .eventListener(listener)
      .build()

  class SocketClosingEventListener : EventListener() {
    var shouldClose: Boolean = false
    var lastSocket: Socket? = null

    override fun connectionAcquired(
      call: Call,
      connection: Connection,
    ) {
      lastSocket = connection.socket()
    }

    override fun requestHeadersStart(call: Call) {
      if (shouldClose) {
        lastSocket!!.close()
      }
    }
  }

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
  }

  @Test
  fun socketFailureOnLargeRequestHeaders() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    server.start()

    val call1 =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    call1.execute().use { response -> response.body.string() }

    listener.shouldClose = true
    // Large headers are a likely reason the servers would cut off the connection before it completes sending
    // request headers.
    // 431 "Request Header Fields Too Large"
    val largeHeaders =
      Headers
        .Builder()
        .apply {
          repeat(32) {
            add("name-$it", "value-$it-" + "0".repeat(1024))
          }
        }.build()
    val call2 =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .headers(largeHeaders)
          .build(),
      )

    val exception =
      assertFailsWith<IOException> {
        call2.execute()
      }
    assertThat(exception.message).isEqualTo("Socket closed")
  }
}
