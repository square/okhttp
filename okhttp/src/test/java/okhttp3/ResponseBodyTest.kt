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
import kotlin.test.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Source
import okio.buffer

class ResponseBodyTest {
  @Test
  fun sourceEmpty() {
    val mediaType = if (null == null) null else "any/thing; charset=${null}".toMediaType()
    val body = "".decodeHex().toResponseBody(mediaType)
    val source = body.source()
    assertThat(source.exhausted()).isTrue()
    assertThat(source.readUtf8()).isEqualTo("")
  }

  @Test
  fun sourceClosesUnderlyingSource() {
    var closed = false

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
            override fun close() {
              closed = true
              super.close()
            }
          }.buffer()
        }
      }
    body.source().close()
    assertThat(closed).isTrue()
  }

  @Test
  fun throwingUnderlyingSourceClosesQuietly() {
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
              throw IOException("Broken!")
            }
          }.buffer()
        }
      }
    assertThat(body.source().readUtf8()).isEqualTo("hello")
    body.close()
  }

  @Test
  fun unicodeText() {
    val text = "eile oli oliiviõli"
    val body = text.toResponseBody()
    assertThat(body.string()).isEqualTo(text)
  }

  @Test
  fun unicodeTextWithCharset() {
    val text = "eile oli oliiviõli"
    val body = text.toResponseBody("text/plain; charset=UTF-8".toMediaType())
    assertThat(body.string()).isEqualTo(text)
  }

  @Test
  fun unicodeByteString() {
    val text = "eile oli oliiviõli"
    val body = text.toResponseBody()
    assertThat(body.byteString()).isEqualTo(text.encodeUtf8())
  }

  @Test
  fun unicodeByteStringWithCharset() {
    val text = "eile oli oliiviõli".encodeUtf8()
    val body = text.toResponseBody("text/plain; charset=EBCDIC".toMediaType())
    assertThat(body.byteString()).isEqualTo(text)
  }

  @Test
  fun unicodeBytes() {
    val text = "eile oli oliiviõli"
    val body = text.toResponseBody()
    assertThat(body.bytes()).isEqualTo(text.encodeToByteArray())
  }

  @Test
  fun unicodeBytesWithCharset() {
    val text = "eile oli oliiviõli".encodeToByteArray()
    val body = text.toResponseBody("text/plain; charset=EBCDIC".toMediaType())
    assertThat(body.bytes()).isEqualTo(text)
  }
}

abstract class ForwardingSource(
  val delegate: Source,
) : Source {
  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long = delegate.read(sink, byteCount)

  override fun timeout() = delegate.timeout()

  override fun close() = delegate.close()
}
