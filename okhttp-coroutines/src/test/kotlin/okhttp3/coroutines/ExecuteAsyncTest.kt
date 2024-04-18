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
package okhttp3.coroutines

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.DisconnectAfterRequest
import okhttp3.Callback
import okhttp3.FailingCall
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail

class ExecuteAsyncTest {
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
          .build(),
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
          .build(),
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
        ),
      )

      val call = client.newCall(request)

      assertFailsWith<IOException> {
        call.executeAsync().use {
          withContext(Dispatchers.IO) {
            it.body.string()
          }
        }
      }
    }
  }

  @Test
  fun responseClosedIfCoroutineCanceled() {
    runTest {
      val call = ClosableCall()

      supervisorScope {
        assertFailsWith<CancellationException> {
          coroutineScope {
            call.afterCallbackOnResponse = {
              coroutineContext.job.cancel()
            }
            call.executeAsync()
          }
        }
      }

      assertThat(call.canceled).isTrue()
      assertThat(call.responseClosed).isTrue()
    }
  }

  /** A call that keeps track of whether its response body is closed. */
  private class ClosableCall : FailingCall() {
    private val response =
      Response.Builder()
        .request(Request("https://example.com/".toHttpUrl()))
        .protocol(Protocol.HTTP_1_1)
        .message("OK")
        .code(200)
        .body(
          object : ResponseBody() {
            override fun contentType() = null

            override fun contentLength() = -1L

            override fun source() =
              object : ForwardingSource(Buffer()) {
                override fun close() {
                  responseClosed = true
                }
              }.buffer()
          },
        )
        .build()

    var responseClosed = false
    var canceled = false
    var afterCallbackOnResponse: () -> Unit = {}

    override fun cancel() {
      canceled = true
    }

    override fun enqueue(responseCallback: Callback) {
      responseCallback.onResponse(this, response)
      afterCallbackOnResponse()
    }
  }
}
