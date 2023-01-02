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

@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import mockwebserver3.junit5.internal.MockWebServerExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import mockwebserver3.SocketPolicy.DisconnectAfterRequest
import org.junit.jupiter.api.BeforeEach

@ExtendWith(MockWebServerExtension::class)
class SuspendCallTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private var client = clientTestRule.newClientBuilder().build()

  private lateinit var server: MockWebServer

  val request by lazy { Request(server.url("/")) }

  @BeforeEach
  fun setup(server: MockWebServer) {
    this.server = server
  }

  @Test
  fun suspendCall() {
    runTest {
      server.enqueue(MockResponse(body = "abc"))

      val call = client.newCall(request)

      call.executeAsync().use {
        withContext(Dispatchers.IO) {
          assertThat(it.body.string()).isEqualTo("abc")
        }
      }
    }
  }

  @Test
  fun timeoutCall() {
    runTest {
      server.enqueue(
        MockResponse.Builder()
          .bodyDelay(5, TimeUnit.SECONDS)
          .body("abc")
          .build()
      )

      val call = client.newCall(request)

      try {
        withTimeout(1.seconds) {
          call.executeAsync().use {
            withContext(Dispatchers.IO) {
              it.body.string()
            }
            fail("No expected to get response")
          }
        }
      } catch (to: TimeoutCancellationException) {
        // expected
      }

      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Test
  fun cancelledCall() {
    runTest {
      server.enqueue(
        MockResponse.Builder()
          .bodyDelay(5, TimeUnit.SECONDS)
          .body("abc")
          .build()
      )

      val call = client.newCall(request)

      try {
        call.executeAsync().use {
          call.cancel()
          withContext(Dispatchers.IO) {
            it.body.string()
          }
          fail("No expected to get response")
        }
      } catch (ioe: IOException) {
        // expected
      }

      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Test
  fun failedCall() {
    runTest {
      server.enqueue(
        MockResponse(
          body = "abc",
          socketPolicy = DisconnectAfterRequest,
        )
      )

      val call = client.newCall(request)

      try {
        call.executeAsync().use {
          withContext(Dispatchers.IO) {
            it.body.string()
          }
        }
        fail("No expected to get response")
      } catch (ioe: IOException) {
        // expected
      }
    }
  }
}
