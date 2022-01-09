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
import kotlin.jvm.JvmOverloads
import kotlin.test.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.IOException
import okio.Source
import okio.buffer

class ResponseBodyTest {
  @Test
  fun sourceEmpty() {
    val body = body("")
    val source = body.source()
    assertThat(source.exhausted()).isTrue()
    assertThat(source.readUtf8()).isEqualTo("")
  }

  @Test
  fun sourceClosesUnderlyingSource() {
    var closed = false

    val body: ResponseBody = object : ResponseBody() {
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
    val body: ResponseBody = object : ResponseBody() {
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

  companion object {
    @JvmOverloads
    fun body(hex: String, charset: String? = null): ResponseBody {
      val mediaType = if (charset == null) null else "any/thing; charset=$charset".toMediaType()
      return hex.decodeHex().toResponseBody(mediaType)
    }
  }
}

abstract class ForwardingSource(
  val delegate: Source
) : Source {
  override fun read(sink: Buffer, byteCount: Long): Long = delegate.read(sink, byteCount)

  override fun timeout() = delegate.timeout()

  override fun close() = delegate.close()
}
