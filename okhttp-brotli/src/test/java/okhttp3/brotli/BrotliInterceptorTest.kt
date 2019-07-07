/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.brotli

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BrotliInterceptorTest {
  @Test
  fun testUncompressBrotli() {
    val s =
        "1bce00009c05ceb9f028d14e416230f718960a537b0922d2f7b6adef56532c08dff44551516690131494db" +
            "6021c7e3616c82c1bc2416abb919aaa06e8d30d82cc2981c2f5c900bfb8ee29d5c03deb1c0dacff80e" +
            "abe82ba64ed250a497162006824684db917963ecebe041b352a3e62d629cc97b95cac24265b175171e" +
            "5cb384cd0912aeb5b5dd9555f2dd1a9b20688201"

    val response = response("https://httpbin.org/brotli", s.decodeHex()) {
      header("Content-Encoding", "br")
    }

    val uncompressed = BrotliInterceptor.uncompress(response)

    val responseString = uncompressed.body?.string()
    assertThat(responseString).contains("\"brotli\": true,")
    assertThat(responseString).contains("\"Accept-Encoding\": \"br\"")
  }

  @Test
  fun testUncompressGzip() {
    val s =
        "1f8b0800968f215d02ff558ec10e82301044ef7c45b3e75269d0c478e340e4a426e007086c4a636c9bb65e" +
            "24fcbb5b484c3cec61deccecee9c3106eaa39dc3114e2cfa377296d8848f117d20369324500d03ba98" +
            "d766b0a3368a0ce83d4f55581b14696c88894f31ba5e1b61bdfa79f7803eaf149a35619f29b3db0b29" +
            "8abcbd54b7b6b97640c965bbfec238d9f4109ceb6edb01d66ba54d6247296441531e445970f627215b" +
            "b22f1017320dd5000000"

    val response = response("https://httpbin.org/gzip", s.decodeHex()) {
      header("Content-Encoding", "gzip")
    }

    val uncompressed = BrotliInterceptor.uncompress(response)

    val responseString = uncompressed.body?.string()
    assertThat(responseString).contains("\"gzipped\": true,")
    assertThat(responseString).contains("\"Accept-Encoding\": \"br,gzip\"")
  }

  @Test
  fun testNoUncompress() {
    val response = response("https://httpbin.org/brotli", "XXXX".encodeUtf8())

    val same = BrotliInterceptor.uncompress(response)

    val responseString = same.body?.string()
    assertThat(responseString).isEqualTo("XXXX")
  }

  @Test
  fun testFailsUncompress() {
    val response = response("https://httpbin.org/brotli", "bb919aaa06e8".decodeHex()) {
      header("Content-Encoding", "br")
    }

    try {
      val failingResponse = BrotliInterceptor.uncompress(response)
      failingResponse.body?.string()

      fail("expected uncompress error")
    } catch (ioe: IOException) {
      assertThat(ioe).hasMessage("Brotli stream decoding failed")
      assertThat(ioe.cause?.javaClass?.simpleName).isEqualTo("BrotliRuntimeException")
    }
  }

  private fun response(
    url: String,
    bodyHex: ByteString,
    fn: Response.Builder.() -> Unit = {}
  ): Response {
    return Response.Builder()
        .body(bodyHex.toResponseBody("text/plain".toMediaType()))
        .code(200)
        .message("OK")
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_2)
        .apply(fn)
        .build()
  }
}
