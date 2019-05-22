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

import com.baulsupp.okurl.brotli.BrotliInterceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import okio.ByteString.Companion.encodeUtf8

class BrotliInterceptorTest {
  @Test
  fun testUncompress() {
    val s =
        "1bce00009c05ceb9f028d14e416230f718960a537b0922d2f7b6adef56532c08dff44551516690131494db" +
            "6021c7e3616c82c1bc2416abb919aaa06e8d30d82cc2981c2f5c900bfb8ee29d5c03deb1c0dacff80e" +
            "abe82ba64ed250a497162006824684db917963ecebe041b352a3e62d629cc97b95cac24265b175171e" +
            "5cb384cd0912aeb5b5dd9555f2dd1a9b20688201"

    val response = response("https://httpbin.org/brotli", s.decodeHex()) {
      header("Content-Encoding", "br")
    }

    val uncompressed = BrotliInterceptor.uncompress(response)

    val responseString = uncompressed.body()?.string()
    assertThat(responseString).contains("\"brotli\": true,")
    assertThat(responseString).contains("\"Accept-Encoding\": \"br\"")
  }

  @Test
  fun testNoUncompress() {
    val response = response("https://httpbin.org/brotli", "XXXX".encodeUtf8())

    val same = BrotliInterceptor.uncompress(response)

    val responseString = same.body()?.string()
    assertThat(responseString).isEqualTo("XXXX")
  }

  @Test
  fun testFailsUncompress() {
    val response = response("https://httpbin.org/brotli", "bb919aaa06e8".decodeHex()) {
      header("Content-Encoding", "br")
    }

    try {
      val failingResponse = BrotliInterceptor.uncompress(response)
      failingResponse.body()?.string()

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
        .body(ResponseBody.create(MediaType.get("text/plain"), bodyHex))
        .code(200)
        .message("OK")
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_2)
        .apply(fn)
        .build()
  }
}