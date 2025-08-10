/*
 * Copyright (c) 2025 Block, Inc.
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.GzipSink
import okio.Source
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CompressionInterceptorTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  val source =
    Buffer().apply {
      write("Hello World".encodeUtf8())
    } as Source

  @Test
  fun emptyDoesntChangeRequestOrResponse() {
    val empty = CompressionInterceptor()
    val client =
      clientTestRule
        .newClientBuilder()
        .addInterceptor(empty)
        .addInterceptor { chain ->
          assertThat(chain.request().header("Accept-Encoding")).isNull()
          Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("Hello".toResponseBody())
            .header("Content-Encoding", "piedpiper")
            .build()
        }.build()

    val response = client.newCall(Request("https://google.com/robots.txt".toHttpUrl())).execute()

    assertThat(response.header("Content-Encoding")).isEqualTo("piedpiper")
    assertThat(response.body.string()).isEqualTo("Hello")
  }

  @Test
  fun gzipThroughCall() {
    val gzip = CompressionInterceptor(Gzip)
    val client =
      clientTestRule
        .newClientBuilder()
        .addInterceptor(gzip)
        .addInterceptor { chain ->
          assertThat(chain.request().header("Accept-Encoding")).isEqualTo("gzip")

          Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(gzip("Hello").asResponseBody())
            .header("Content-Encoding", "gzip")
            .build()
        }.build()

    val response = client.newCall(Request("https://google.com/robots.txt".toHttpUrl())).execute()

    assertThat(response.header("Content-Encoding")).isNull()
    assertThat(response.body.string()).isEqualTo("Hello")
  }

  private fun gzip(data: String): Buffer {
    val result = Buffer()
    val sink = GzipSink(result).buffer()
    sink.writeUtf8(data)
    sink.close()
    return result
  }
}
