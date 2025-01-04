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
import assertk.assertions.isTrue
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.and
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Test

class ResponseBodyJvmTest {
  @Test
  fun stringEmpty() {
    val body = body("")
    assertThat(body.string()).isEqualTo("")
  }

  @Test
  fun stringLooksLikeBomButTooShort() {
    val body = body("000048")
    assertThat(body.string()).isEqualTo("\u0000\u0000H")
  }

  @Test
  fun stringDefaultsToUtf8() {
    val body = body("68656c6c6f")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringExplicitCharset() {
    val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringBomOverridesExplicitCharset() {
    val body = body("0000feff00000068000000650000006c0000006c0000006f", "utf-8")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringBomUtf8() {
    val body = body("efbbbf68656c6c6f")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringBomUtf16Be() {
    val body = body("feff00680065006c006c006f")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringBomUtf16Le() {
    val body = body("fffe680065006c006c006f00")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringBomUtf32Be() {
    val body = body("0000feff00000068000000650000006c0000006c0000006f")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringBomUtf32Le() {
    val body = body("fffe000068000000650000006c0000006c0000006f000000")
    assertThat(body.string()).isEqualTo("hello")
  }

  @Test
  fun stringClosesUnderlyingSource() {
    val closed = AtomicBoolean()
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 5
        }

        override fun source(): BufferedSource {
          val source = Buffer().writeUtf8("hello")
          return object : ForwardingSource(source) {
            @Throws(IOException::class)
            override fun close() {
              closed.set(true)
              super.close()
            }
          }.buffer()
        }
      }
    assertThat(body.string()).isEqualTo("hello")
    assertThat(closed.get()).isTrue()
  }

  @Test
  fun readerEmpty() {
    val body = body("")
    assertThat(exhaust(body.charStream())).isEqualTo("")
  }

  @Test
  fun readerLooksLikeBomButTooShort() {
    val body = body("000048")
    assertThat(exhaust(body.charStream())).isEqualTo("\u0000\u0000H")
  }

  @Test
  fun readerDefaultsToUtf8() {
    val body = body("68656c6c6f")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerExplicitCharset() {
    val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerBomUtf8() {
    val body = body("efbbbf68656c6c6f")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerBomUtf16Be() {
    val body = body("feff00680065006c006c006f")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerBomUtf16Le() {
    val body = body("fffe680065006c006c006f00")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerBomUtf32Be() {
    val body = body("0000feff00000068000000650000006c0000006c0000006f")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerBomUtf32Le() {
    val body = body("fffe000068000000650000006c0000006c0000006f000000")
    assertThat(exhaust(body.charStream())).isEqualTo("hello")
  }

  @Test
  fun readerClosedBeforeBomClosesUnderlyingSource() {
    val closed = AtomicBoolean()
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 5
        }

        override fun source(): BufferedSource {
          val body = body("fffe680065006c006c006f00")
          return object : ForwardingSource(body.source()) {
            @Throws(IOException::class)
            override fun close() {
              closed.set(true)
              super.close()
            }
          }.buffer()
        }
      }
    body.charStream().close()
    assertThat(closed.get()).isTrue()
  }

  @Test
  fun readerClosedAfterBomClosesUnderlyingSource() {
    val closed = AtomicBoolean()
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 5
        }

        override fun source(): BufferedSource {
          val body = body("fffe680065006c006c006f00")
          return object : ForwardingSource(body.source()) {
            @Throws(IOException::class)
            override fun close() {
              closed.set(true)
              super.close()
            }
          }.buffer()
        }
      }
    val reader = body.charStream()
    assertThat(reader.read()).isEqualTo('h'.code)
    reader.close()
    assertThat(closed.get()).isTrue()
  }

  @Test
  fun sourceSeesBom() {
    val body = "efbbbf68656c6c6f".decodeHex().toResponseBody()
    val source = body.source()
    assertThat(source.readByte() and 0xff).isEqualTo(0xef)
    assertThat(source.readByte() and 0xff).isEqualTo(0xbb)
    assertThat(source.readByte() and 0xff).isEqualTo(0xbf)
    assertThat(source.readUtf8()).isEqualTo("hello")
  }

  @Test
  fun bytesEmpty() {
    val body = body("")
    assertThat(body.bytes().size).isEqualTo(0)
  }

  @Test
  fun bytesSeesBom() {
    val body = body("efbbbf68656c6c6f")
    val bytes = body.bytes()
    assertThat(bytes[0] and 0xff).isEqualTo(0xef)
    assertThat(bytes[1] and 0xff).isEqualTo(0xbb)
    assertThat(bytes[2] and 0xff).isEqualTo(0xbf)
    assertThat(String(bytes, 3, 5, StandardCharsets.UTF_8)).isEqualTo("hello")
  }

  @Test
  fun bytesClosesUnderlyingSource() {
    val closed = AtomicBoolean()
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 5
        }

        override fun source(): BufferedSource {
          val source = Buffer().writeUtf8("hello")
          return object : ForwardingSource(source) {
            @Throws(IOException::class)
            override fun close() {
              closed.set(true)
              super.close()
            }
          }.buffer()
        }
      }
    assertThat(body.bytes().size).isEqualTo(5)
    assertThat(closed.get()).isTrue()
  }

  @Test
  fun bytesThrowsWhenLengthsDisagree() {
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 10
        }

        override fun source(): BufferedSource {
          return Buffer().writeUtf8("hello")
        }
      }
    assertFailsWith<IOException> {
      body.bytes()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "Content-Length (10) and stream length (5) disagree",
      )
    }
  }

  @Test
  fun bytesThrowsMoreThanIntMaxValue() {
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return Int.MAX_VALUE + 1L
        }

        override fun source(): BufferedSource {
          throw AssertionError()
        }
      }
    assertFailsWith<IOException> {
      body.bytes()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "Cannot buffer entire body for content length: 2147483648",
      )
    }
  }

  @Test
  fun byteStringEmpty() {
    val body = body("")
    assertThat(body.byteString()).isEqualTo(ByteString.EMPTY)
  }

  @Test
  fun byteStringSeesBom() {
    val body = body("efbbbf68656c6c6f")
    val actual = body.byteString()
    val expected: ByteString = "efbbbf68656c6c6f".decodeHex()
    assertThat(actual).isEqualTo(expected)
  }

  @Test
  fun byteStringClosesUnderlyingSource() {
    val closed = AtomicBoolean()
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 5
        }

        override fun source(): BufferedSource {
          val source = Buffer().writeUtf8("hello")
          return object : ForwardingSource(source) {
            @Throws(IOException::class)
            override fun close() {
              closed.set(true)
              super.close()
            }
          }.buffer()
        }
      }
    assertThat(body.byteString().size).isEqualTo(5)
    assertThat(closed.get()).isTrue()
  }

  @Test
  fun byteStringThrowsWhenLengthsDisagree() {
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 10
        }

        override fun source(): BufferedSource {
          return Buffer().writeUtf8("hello")
        }
      }
    assertFailsWith<IOException> {
      body.byteString()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "Content-Length (10) and stream length (5) disagree",
      )
    }
  }

  @Test
  fun byteStringThrowsMoreThanIntMaxValue() {
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return Int.MAX_VALUE + 1L
        }

        override fun source(): BufferedSource {
          throw AssertionError()
        }
      }
    assertFailsWith<IOException> {
      body.byteString()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "Cannot buffer entire body for content length: 2147483648",
      )
    }
  }

  @Test
  fun byteStreamEmpty() {
    val body = body("")
    val bytes = body.byteStream()
    assertThat(bytes.read()).isEqualTo(-1)
  }

  @Test
  fun byteStreamSeesBom() {
    val body = body("efbbbf68656c6c6f")
    val bytes = body.byteStream()
    assertThat(bytes.read()).isEqualTo(0xef)
    assertThat(bytes.read()).isEqualTo(0xbb)
    assertThat(bytes.read()).isEqualTo(0xbf)
    assertThat(exhaust(InputStreamReader(bytes, StandardCharsets.UTF_8))).isEqualTo("hello")
  }

  @Test
  fun byteStreamClosesUnderlyingSource() {
    val closed = AtomicBoolean()
    val body: ResponseBody =
      object : ResponseBody() {
        override fun contentType(): MediaType? {
          return null
        }

        override fun contentLength(): Long {
          return 5
        }

        override fun source(): BufferedSource {
          val source = Buffer().writeUtf8("hello")
          return object : ForwardingSource(source) {
            @Throws(IOException::class)
            override fun close() {
              closed.set(true)
              super.close()
            }
          }.buffer()
        }
      }
    body.byteStream().close()
    assertThat(closed.get()).isTrue()
  }

  @Test
  fun unicodeTextWithUnsupportedEncoding() {
    val text = "eile oli oliiviõli"
    val body = text.toResponseBody("text/plain; charset=unknown".toMediaType())
    assertThat(body.string()).isEqualTo(text)
  }

  companion object {
    @JvmOverloads
    fun body(
      hex: String,
      charset: String? = null,
    ): ResponseBody {
      val mediaType = if (charset == null) null else "any/thing; charset=$charset".toMediaType()
      return hex.decodeHex().toResponseBody(mediaType)
    }

    fun exhaust(reader: Reader): String {
      val builder = StringBuilder()
      val buf = CharArray(10)
      var read: Int
      while (reader.read(buf).also { read = it } != -1) {
        builder.appendRange(buf, 0, read)
      }
      return builder.toString()
    }
  }
}
