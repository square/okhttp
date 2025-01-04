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
import kotlin.test.assertFailsWith
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test

class ResponseJvmTest {
  @Test
  fun testEmptyByDefaultIfTrailersNotSet() {
    val response = newResponse("".toResponseBody())

    assertThat(response.trailers()).isEmpty()
  }

  @Test
  fun testFailsIfTrailersNotSet() {
    val response =
      newResponse("".toResponseBody()) {
        // All live paths (Http1, Http2) in OkHttp do this
        trailers { error("trailers not available") }
      }

    assertFailsWith<IllegalStateException>(message = "trailers not available") {
      response.trailers()
    }
  }

  @Test
  fun worksIfTrailersSet() {
    val response =
      newResponse("".toResponseBody()) {
        trailers {
          Headers.headersOf("a", "b")
        }
      }

    assertThat(response.trailers()["a"]).isEqualTo("b")
  }

  @Test fun peekAfterReadingResponse() {
    val response = newResponse(responseBody("abc"))
    assertThat(response.body.string()).isEqualTo("abc")

    assertFailsWith<IllegalStateException> {
      response.peekBody(3)
    }
  }

  /**
   * Returns a new response body that refuses to be read once it has been closed. This is true of
   * most [BufferedSource] instances, but not of [Buffer].
   */
  private fun responseBody(content: String): ResponseBody {
    val data = Buffer().writeUtf8(content)
    val source: Source =
      object : Source {
        var closed = false

        override fun close() {
          closed = true
        }

        override fun read(
          sink: Buffer,
          byteCount: Long,
        ): Long {
          check(!closed)
          return data.read(sink, byteCount)
        }

        override fun timeout(): Timeout {
          return Timeout.NONE
        }
      }
    return source.buffer().asResponseBody(null, -1)
  }

  private fun newResponse(
    responseBody: ResponseBody,
    code: Int = 200,
    fn: Response.Builder.() -> Unit = {},
  ): Response {
    return Response.Builder()
      .request(
        Request.Builder()
          .url("https://example.com/")
          .build(),
      )
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("OK")
      .body(responseBody)
      .apply { fn() }
      .build()
  }
}
