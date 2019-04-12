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
package okhttp3.logging

import okhttp3.logging.HttpLoggingInterceptor.Companion.isUtf8
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class HttpLoggingInterceptorKotlinTest {
  @Test fun isPlaintext() {
    assertThat(Buffer().isUtf8()).isTrue()
    assertThat(Buffer().writeUtf8("abc").isUtf8()).isTrue()
    assertThat(Buffer().writeUtf8("new\r\nlines").isUtf8()).isTrue()
    assertThat(Buffer().writeUtf8("white\t space").isUtf8()).isTrue()
    assertThat(Buffer().writeByte(0x80).isUtf8()).isTrue()
    assertThat(Buffer().writeByte(0x00).isUtf8()).isFalse()
    assertThat(Buffer().writeByte(0xc0).isUtf8()).isFalse()
  }
}
