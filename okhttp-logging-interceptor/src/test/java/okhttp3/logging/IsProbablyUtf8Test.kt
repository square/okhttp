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

import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class IsProbablyUtf8Test {
  @Test fun isProbablyUtf8() {
    assertThat(Buffer().isProbablyUtf8()).isTrue()
    assertThat(Buffer().writeUtf8("abc").isProbablyUtf8()).isTrue()
    assertThat(Buffer().writeUtf8("new\r\nlines").isProbablyUtf8()).isTrue()
    assertThat(Buffer().writeUtf8("white\t space").isProbablyUtf8()).isTrue()
    assertThat(Buffer().writeByte(0x80).isProbablyUtf8()).isTrue()
    assertThat(Buffer().writeByte(0x00).isProbablyUtf8()).isFalse()
    assertThat(Buffer().writeByte(0xc0).isProbablyUtf8()).isFalse()
  }
}
