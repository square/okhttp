/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp3.zstd

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.squareup.zstd.okio.zstdCompress
import java.io.IOException
import kotlin.test.assertFailsWith
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.EMPTY
import okio.ByteString.Companion.encodeUtf8
import okio.Sink
import okio.buffer
import okio.gzip
import org.junit.jupiter.api.Test

class ZstdInterceptorTest {
  val zstdInterceptor = CompressionInterceptor(Zstd, Gzip)

  @Test
  fun testDecompressZstd() {
    val s = "hello zstd world".encodeUtf8().zstdCompress()

    val response =
      response("https://example.com/", s) {
        header("Content-Encoding", "zstd")
      }

    val decompressed = zstdInterceptor.decompress(response)
    assertThat(decompressed.header("Content-Encoding")).isNull()

    val responseString = decompressed.body.string()
    assertThat(responseString).isEqualTo("hello zstd world")
  }

  @Test
  fun testDecompressGzip() {
    val s = "hello gzip world".encodeUtf8().gzipCompress()

    val response =
      response("https://example.com/", s) {
        header("Content-Encoding", "gzip")
      }

    val decompressed = zstdInterceptor.decompress(response)
    assertThat(decompressed.header("Content-Encoding")).isNull()

    val responseString = decompressed.body.string()
    assertThat(responseString).isEqualTo("hello gzip world")
  }

  @Test
  fun testNoDecompress() {
    val s = "hello not compressed world".encodeUtf8()

    val response = response("https://example.com/", s)

    val decompressed = zstdInterceptor.decompress(response)
    assertThat(decompressed.header("Content-Encoding")).isNull()

    val responseString = decompressed.body.string()
    assertThat(responseString).isEqualTo("hello not compressed world")
  }

  @Test
  fun testUnknownAlgorithm() {
    val s = "hello unknown algorithm world".encodeUtf8()

    val response =
      response("https://example.com/", s) {
        header("Content-Encoding", "deflate")
      }

    val decompressed = zstdInterceptor.decompress(response)
    assertThat(decompressed.header("Content-Encoding")).isEqualTo("deflate")

    val responseString = decompressed.body.string()
    assertThat(responseString).isEqualTo("hello unknown algorithm world")
  }

  @Test
  fun testFailsDecompress() {
    val s = "this is not valid zstd".encodeUtf8()

    val response =
      response("https://example.com/", s) {
        header("Content-Encoding", "zstd")
      }

    val decompressed = zstdInterceptor.decompress(response)
    assertThat(decompressed.header("Content-Encoding")).isNull()

    assertFailsWith<IOException> {
      decompressed.body.string()
    }.also { ioe ->
      assertThat(ioe).hasMessage("zstd decompress failed: Unknown frame descriptor")
    }
  }

  @Test
  fun testSkipDecompressNoContentResponse() {
    val response =
      response("https://example.com/", EMPTY) {
        header("Content-Encoding", "zstd")
        code(204)
        message("NO CONTENT")
      }

    val same = zstdInterceptor.decompress(response)

    val responseString = same.body.string()
    assertThat(responseString).isEmpty()
  }

  private fun ByteString.zstdCompress(): ByteString {
    val result = Buffer()
    result.zstdCompress().buffer().use {
      it.write(this@zstdCompress)
    }
    return result.readByteString()
  }

  private fun ByteString.gzipCompress(): ByteString {
    val result = Buffer()
    (result as Sink).gzip().buffer().use {
      it.write(this@gzipCompress)
    }
    return result.readByteString()
  }

  private fun response(
    url: String,
    body: ByteString,
    fn: Response.Builder.() -> Unit = {},
  ): Response =
    Response
      .Builder()
      .body(body.toResponseBody("text/plain".toMediaType()))
      .code(200)
      .message("OK")
      .request(Request.Builder().url(url).build())
      .protocol(Protocol.HTTP_2)
      .apply(fn)
      .build()
}
