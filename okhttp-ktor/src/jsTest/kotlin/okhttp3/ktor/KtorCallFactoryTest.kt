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

package okhttp3.ktor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import assertk.fail
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.utils.io.CancellationException
import kotlin.test.Ignore
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import okio.use

class KtorCallFactoryTest {
  val httpbin = "https://nghttp2.org/httpbin/"

  val ktorClient = HttpClient(Js)

  val client = KtorCallFactory(ktorClient)

  @Test
  fun suspendCall() = runTest {
    val request = Request("$httpbin/get")

    val call = client.newCall(request)

    val response = call.executeAsync()

    assertThat(response.code).isEqualTo(200)

    response.use {
      val body = withContext(Dispatchers.Default) {
        it.body.string()
      }
      assertThat(body).startsWith("{\n")
      assertThat(body).contains("headers")
      assertThat(body).endsWith("\n}\n")
    }
  }

  @Test
  fun suspendCallAsync() = runTest {
    val request = Request("$httpbin/get")

    val call = client.newCall(request)

    val responseAsync = async {
      call.executeAsync()
    }

    val response = responseAsync.await()

    assertThat(response.code).isEqualTo(200)

    response.use {
      val body = it.body.string()
      assertThat(body).startsWith("{\n")
      assertThat(body).contains("headers")
      assertThat(body).endsWith("\n}\n")
    }
  }

  @Test
  fun enqueueCall() = runTest {
    val request = Request("$httpbin/get")

    val call = client.newCall(request)
    val result = CompletableDeferred<Response>()

    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        result.completeExceptionally(e)
      }

      override fun onResponse(call: Call, response: Response) {
        result.complete(response)
      }
    })

    val response = result.await()

    assertThat(response.code).isEqualTo(200)

    response.use {
      val body = it.body.string()
      assertThat(body).startsWith("{\n")
      assertThat(body).contains("headers")
      assertThat(body).endsWith("\n}\n")
    }
  }

  @Test
  fun delayThenCancel() = runTest {
    val request = Request("$httpbin/delay/10")

    val call = client.newCall(request)

    val responseAsync = async {
      call.executeAsync()
    }

    // TODO make this deterministic without delay
    delay(100)

    assertThat(call.isExecuted()).isTrue()
    assertThat(call.isCanceled()).isFalse()

    call.cancel()

    try {
      responseAsync.await()
      fail("Expected cancellation")
    } catch (e: CancellationException) {
      // expected
    }

    assertThat(call.isExecuted()).isTrue()
    assertThat(call.isCanceled()).isTrue()
  }

  @Test
  fun headers() = runTest {
    val request = Request("$httpbin/get", Headers.headersOf("name1", "value1"))

    val call = client.newCall(request)

    val response = call.executeAsync()

    assertThat(response.code).isEqualTo(200)

    // restricted on browser mode
    assertThat(response.headers.size).isGreaterThan(2)
    assertThat(response.headers["content-type"]).isEqualTo("application/json")

    response.use {
      val body = it.body.string()
      assertThat(body.lowercase()).contains("\"name1\": \"value1\"")
    }
  }

  @Test
  fun requestResponseBody() = runTest {
    val requestBodyText = "ABCD"
    val textPlain = "text/plain".toMediaType()
    val requestBody = requestBodyText.toRequestBody(textPlain)
    val request =
      Request("$httpbin/anything", method = "PUT", body = requestBody)

    val call = client.newCall(request)

    val response = call.executeAsync()

    assertThat(response.code).isEqualTo(200)

    response.use {
      val body = it.body.string()
      assertThat(body).contains("\"data\": \"ABCD\"")
      // May have charset
      assertThat(body).contains("\"Content-Type\": \"text/plain")
      assertThat(body).contains("\"method\": \"PUT\"")
    }
  }

  @Test
  @Ignore
  fun caching() = runTest {
    TODO()
  }
}
