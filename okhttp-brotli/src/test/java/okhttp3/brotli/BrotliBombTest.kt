/*
 * Copyright (C) 2024 Square, Inc.
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

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.matches
import assertk.assertions.message
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.brotli.internal.uncompress
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.IOException
import okio.buffer
import okio.gzip
import org.junit.jupiter.api.Test

class BrotliBombTest {
  /** https://github.com/square/okhttp/issues/7738 */
  @Test
  fun testDecompressBomb() {
    val response =
      Response.Builder()
        .code(200)
        .message("OK")
        .header("Content-Encoding", "br")
        .request(Request.Builder().url("https://example.com/").build())
        .body(readBrotli10G().toResponseBody())
        .protocol(Protocol.HTTP_2)
        .build()

    val uncompressed = uncompress(response)

    assertFailure {
      uncompressed.body.string()
    }.isInstanceOf<IOException>()
      .message()
      .isNotNull()
      .matches(
        Regex(
          "decompression bomb\\? outputByteCount=\\d+, inputByteCount=\\d+ exceeds max ratio of 100",
        ),
      )
  }

  /** Returns a ByteString that expands to 10 GiB when Brotli-decompressed. */
  private fun readBrotli10G(): ByteString {
    val gzippedBrotliBomb =
      (
        "1f8b0800000000000000edce312bc4711cc7f1df1dba504e29933a93c960b4dd95d" +
          "562919187a06c37dcc9701e8291f2006e568a28cae209b864900cc7768abbafbff4ff2f9e815ef59ebef5fdf4b" +
          "a8ba8a7a5f4dabd996a3cf75aa7cdd8ef0f2b6783da4744e562bcb1b0d9386ec6c66074909d1f3eab5f1163dbe" +
          "5c599e9c660b4967710d14aebe97aa7f4d26b15bd4754db93edd5e594fd158d224a2ba5d9ce44367b9277f9ab7" +
          "8eaa6bdb77a514e986ff7879dbc4a44a4a3747855be3f8fa2c7885a9a4bbb5b29a317c1c2c2c2c2c2c2c2c2c2c" +
          "2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c" +
          "2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c" +
          "2c2c2fe63ecedcfc01fec37798ba7409c690000"
      ).decodeHex()
    val buffer = Buffer()
    buffer.write(gzippedBrotliBomb)
    return (buffer as BufferedSource).gzip().buffer().use {
      it.readByteString()
    }
  }
}
