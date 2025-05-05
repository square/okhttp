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

import junit.framework.TestCase.fail
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import okio.IOException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.Socket
import org.assertj.core.api.Assertions.assertThat

class SocketFailureTest {
  @get:Rule
  val platform = PlatformRule()

  @get:Rule
  val clientTestRule = OkHttpClientTestRule()

  val listener = SocketClosingEventListener()

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

  @Before
  fun setUp() {
    server = MockWebServer()
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
    call1.execute().use { response -> response.body?.string() }

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

    try {
      call2.execute()
      fail()
    } catch (ioe: IOException) {
      assertThat(ioe.message).isEqualTo("Socket closed")
    }
  }
}
