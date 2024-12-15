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
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ByteString.Companion.EMPTY
import okio.Source
import okio.Timeout
import okio.buffer

class ResponseCommonTest {
  @Test fun peekShorterThanResponse() {
    val response = newResponse(responseBody("abcdef"))
    val peekedBody = response.peekBody(3)
    assertThat(peekedBody.string()).isEqualTo("abc")
    assertThat(response.body.string()).isEqualTo("abcdef")
  }

  @Test fun peekLongerThanResponse() {
    val response = newResponse(responseBody("abc"))
    val peekedBody = response.peekBody(6)
    assertThat(peekedBody.string()).isEqualTo("abc")
    assertThat(response.body.string()).isEqualTo("abc")
  }

  @Test fun eachPeakIsIndependent() {
    val response = newResponse(responseBody("abcdef"))
    val p1 = response.peekBody(4)
    val p2 = response.peekBody(2)
    assertThat(response.body.string()).isEqualTo("abcdef")
    assertThat(p1.string()).isEqualTo("abcd")
    assertThat(p2.string()).isEqualTo("ab")
  }

  @Test fun negativeStatusCodeThrowsIllegalStateException() {
    assertFailsWith<IllegalStateException> {
      newResponse(responseBody("set status code -1"), -1)
    }
  }

  @Test fun zeroStatusCodeIsValid() {
    val response = newResponse(responseBody("set status code 0"), 0)
    assertThat(response.code).isEqualTo(0)
  }

  @Test fun defaultResponseBodyIsEmpty() {
    val response =
      Response.Builder()
        .request(
          Request.Builder()
            .url("https://example.com/")
            .build(),
        )
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()
    assertThat(response.body.contentType()).isNull()
    assertThat(response.body.contentLength()).isEqualTo(0L)
    assertThat(response.body.byteString()).isEqualTo(EMPTY)
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
      .build()
  }
}
