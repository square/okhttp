/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.fail
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerInstance
import okhttp3.TestUtil.awaitGarbageCollection
import okhttp3.okio.LoggingFilesystem
import okhttp3.testing.PlatformRule
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Isolated

@Timeout(30)
@Isolated
open class CallWithLogsTest {
  private val fileSystem = FakeFileSystem()

  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  val testLogHandler = TestLogHandler(OkHttpClient::class.java)

  private lateinit var server: MockWebServer
  private lateinit var server2: MockWebServer

  private var listener = RecordingEventListener()
  private var client =
    clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()
  private val cache =
    Cache(
      directory = "/cache".toPath(),
      maxSize = Int.MAX_VALUE.toLong(),
      fileSystem = LoggingFilesystem(fileSystem),
    )

  @BeforeEach
  fun setUp(
    server: MockWebServer,
    @MockWebServerInstance("server2") server2: MockWebServer,
  ) {
    this.server = server
    this.server2 = server2

    platform.assumeNotOpenJSSE()
  }

  @AfterEach
  fun tearDown() {
    cache.close()
    fileSystem.checkNoOpenFiles()
  }

  @Test
  fun exceptionThrownByOnResponseIsRedactedAndLogged() {
    server.enqueue(MockResponse())
    val request = Request(server.url("/secret"))
    client.newCall(request).enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          fail("")
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          throw IOException("a")
        }
      },
    )
    assertThat(testLogHandler.take())
      .isEqualTo("INFO: Callback failure for call to " + server.url("/") + "...")
  }

  @Test
  fun leakedResponseBodyLogsStackTrace() {
    server.enqueue(
      MockResponse(body = "This gets leaked."),
    )
    client =
      clientTestRule.newClientBuilder()
        .connectionPool(ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build()
    val request = Request(server.url("/"))
    client.newCall(request).execute() // Ignore the response so it gets leaked then GC'd.
    awaitGarbageCollection()
    val message = testLogHandler.take()
    assertThat(message).contains(
      "A connection to ${server.url("/")} was leaked. Did you forget to close a response body?",
    )
  }

  @Tag("Slowish")
  @Test
  fun asyncLeakedResponseBodyLogsStackTrace() {
    server.enqueue(MockResponse(body = "This gets leaked."))
    client =
      clientTestRule.newClientBuilder()
        .connectionPool(ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build()
    val request = Request(server.url("/"))
    val latch = CountDownLatch(1)
    client.newCall(request).enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          fail("")
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          // Ignore the response so it gets leaked then GC'd.
          latch.countDown()
        }
      },
    )
    latch.await()
    // There's some flakiness when triggering a GC for objects in a separate thread. Adding a
    // small delay appears to ensure the objects will get GC'd.
    Thread.sleep(200)
    awaitGarbageCollection()
    val message = testLogHandler.take()
    assertThat(message).contains(
      "A connection to ${server.url("/")} was leaked. Did you forget to close a response body?",
    )
  }
}
