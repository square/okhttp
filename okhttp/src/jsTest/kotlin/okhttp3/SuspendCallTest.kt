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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.use
import kotlin.test.Test

class SuspendCallTest {
  val request = Request.Builder().url("https://example.org/test").build()

  val client = TestCallFactory { call, callback ->
    callbackHandler.invoke(call, callback)
  }

  lateinit var callbackHandler: (Call, Callback) -> Unit

  @Test
  fun suspendCall() = runTest {
    val call = client.newCall(request)

    callbackHandler = { _, callback ->
      callback.onResponse(
        call,
        buildResponse(call) {
          body("abc".toResponseBody())
        }
      )
    }

    val response = call.executeAsync()
    response.use {
      assertThat(it.body.string()).isEqualTo("abc")
    }

    assertThat(response.code).isEqualTo(200)
  }

  private fun buildResponse(
    call: Call,
    fn: Response.Builder.() -> Unit = {}
  ) = Response.Builder()
    .code(200)
    .message("OK")
    .body("".toResponseBody())
    .request(call.request())
    .protocol(Protocol.HTTP_2)
    .apply(fn)
    .build()
}
