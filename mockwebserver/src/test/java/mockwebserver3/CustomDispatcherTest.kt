/*
 * Copyright (C) 2012 Google Inc.
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
package mockwebserver3

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(30)
class CustomDispatcherTest {
  private lateinit var mockWebServer: MockWebServer

  @BeforeEach
  fun setUp(mockWebServer: MockWebServer) {
    this.mockWebServer = mockWebServer
  }

  @Test
  fun simpleDispatch() {
    val requestsMade = mutableListOf<RecordedRequest>()
    val dispatcher: Dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          requestsMade.add(request)
          return MockResponse()
        }
      }
    assertThat(requestsMade.size).isEqualTo(0)
    mockWebServer.dispatcher = dispatcher
    val url = mockWebServer.url("/").toUrl()
    val conn = url.openConnection() as HttpURLConnection
    conn.responseCode // Force the connection to hit the "server".
    // Make sure our dispatcher got the request.
    assertThat(requestsMade.size).isEqualTo(1)
  }

  @Test
  fun outOfOrderResponses() {
    val firstResponseCode = AtomicInteger()
    val secondResponseCode = AtomicInteger()
    val secondRequest = "/bar"
    val firstRequest = "/foo"
    val latch = CountDownLatch(1)
    val dispatcher: Dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.path == firstRequest) {
            latch.await()
          }
          return MockResponse()
        }
      }
    mockWebServer.dispatcher = dispatcher
    val startsFirst = buildRequestThread(firstRequest, firstResponseCode)
    startsFirst.start()
    val endsFirst = buildRequestThread(secondRequest, secondResponseCode)
    endsFirst.start()
    endsFirst.join()
    // First response is still waiting.
    assertThat(firstResponseCode.get()).isEqualTo(0)
    // Second response is done.
    assertThat(secondResponseCode.get()).isEqualTo(200)
    latch.countDown()
    startsFirst.join()
    // And now it's done!
    assertThat(firstResponseCode.get()).isEqualTo(200)
    // (Still done).
    assertThat(secondResponseCode.get()).isEqualTo(200)
  }

  private fun buildRequestThread(
    path: String,
    responseCode: AtomicInteger,
  ): Thread {
    return Thread {
      val url = mockWebServer.url(path).toUrl()
      val conn: HttpURLConnection
      try {
        conn = url.openConnection() as HttpURLConnection
        responseCode.set(conn.responseCode) // Force the connection to hit the "server".
      } catch (ignored: IOException) {
      }
    }
  }
}
