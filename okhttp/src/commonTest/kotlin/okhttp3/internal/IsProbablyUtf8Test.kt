/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test

class IsProbablyUtf8Test {
  @Test fun isProbablyUtf8() {
    assertThat(Buffer().isProbablyUtf8(16L)).isTrue()
    assertThat(Buffer().writeUtf8("abc").isProbablyUtf8(16L)).isTrue()
    assertThat(Buffer().writeUtf8("new\r\nlines").isProbablyUtf8(16L)).isTrue()
    assertThat(Buffer().writeUtf8("white\t space").isProbablyUtf8(16L)).isTrue()
    assertThat(Buffer().writeUtf8("Слава Україні!").isProbablyUtf8(16L)).isTrue()
    assertThat(Buffer().writeByte(0x80).isProbablyUtf8(16L)).isTrue()
    assertThat(Buffer().writeByte(0x00).isProbablyUtf8(16L)).isFalse()
    assertThat(Buffer().writeByte(0xc0).isProbablyUtf8(16L)).isFalse()
  }

  @Test fun doesNotConsumeBuffer() {
    val buffer = Buffer()
    buffer.writeUtf8("hello ".repeat(1024))
    assertThat(buffer.isProbablyUtf8(100L)).isTrue()
    assertThat(buffer.readUtf8()).isEqualTo("hello ".repeat(1024))
  }

  /** Confirm [isProbablyUtf8] doesn't attempt to read the entire stream. */
  @Test fun doesNotReadEntireSource() {
    val unlimitedSource =
      object : Source {
        override fun read(
          sink: Buffer,
          byteCount: Long,
        ): Long {
          sink.writeUtf8("a".repeat(byteCount.toInt()))
          return byteCount
        }

        override fun close() {
        }

        override fun timeout() = Timeout.NONE
      }

    assertThat(unlimitedSource.buffer().isProbablyUtf8(1L)).isTrue()
    assertThat(unlimitedSource.buffer().isProbablyUtf8(1024L)).isTrue()
    assertThat(unlimitedSource.buffer().isProbablyUtf8(1024L * 1024L)).isTrue()
  }
}
