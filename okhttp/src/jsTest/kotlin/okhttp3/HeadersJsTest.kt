/*
 * Copyright (C) 2022 Square, Inc.
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
import kotlin.test.Test

class HeadersJsTest {
  @Test
  fun nameIndexesAreStrict() {
    val headers = Headers.headersOf("a", "b", "c", "d")
    assertThat(headers.name(-1)).isEqualTo(undefined)
    assertThat(headers.name(0)).isEqualTo("a")
    assertThat(headers.name(1)).isEqualTo("c")
    assertThat(headers.name(2)).isEqualTo(undefined)
  }

  @Test
  fun valueIndexesAreStrict() {
    val headers = Headers.headersOf("a", "b", "c", "d")
    assertThat(headers.value(-1)).isEqualTo(undefined)
    assertThat(headers.value(0)).isEqualTo("b")
    assertThat(headers.value(1)).isEqualTo("d")
    assertThat(headers.value(2)).isEqualTo(undefined)
  }
}
