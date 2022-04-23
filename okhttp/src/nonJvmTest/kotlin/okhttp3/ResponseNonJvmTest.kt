/*
 * Copyright (C) 2016 Square, Inc.
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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import okhttp3.ResponseBody.Companion.toResponseBody

class ResponseNonJvmTest {
  @Test
  fun testEmptyIfTrailersNotSet() {
    val response = newResponse("".toResponseBody())

    assertThat(response.trailers()).isEmpty()
  }

  @Test
  fun worksIfTrailersSet() {
    val response = newResponse("".toResponseBody()) {
      trailers {
        Headers.headersOf("a", "b")
      }
    }

    assertThat(response.trailers()["a"]).isEqualTo("b")
  }

  private fun newResponse(
    responseBody: ResponseBody,
    code: Int = 200,
    fn: Response.Builder.() -> Unit = {}
  ): Response {
    return Response.Builder()
      .request(
        Request.Builder()
          .url("https://example.com/")
          .build()
      )
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("OK")
      .body(responseBody)
      .apply { fn() }
      .build()
  }
}
