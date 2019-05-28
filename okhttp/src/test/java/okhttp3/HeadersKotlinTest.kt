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
package okhttp3

import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.time.Instant
import java.util.Date

class HeadersKotlinTest {
  @Test fun getOperator() {
    val headers = headersOf("a", "b", "c", "d")
    assertThat(headers["a"]).isEqualTo("b")
    assertThat(headers["c"]).isEqualTo("d")
    assertThat(headers["e"]).isNull()
  }

  @Test fun iteratorOperator() {
    val headers = headersOf("a", "b", "c", "d")

    val pairs = mutableListOf<Pair<String, String>>()
    for ((name, value) in headers) {
      pairs += name to value
    }

    assertThat(pairs).containsExactly("a" to "b", "c" to "d")
  }

  @Test fun builderGetOperator() {
    val builder = Headers.Builder()
    builder.add("a", "b")
    builder.add("c", "d")
    assertThat(builder["a"]).isEqualTo("b")
    assertThat(builder["c"]).isEqualTo("d")
    assertThat(builder["e"]).isNull()
  }

  @Test fun builderSetOperator() {
    val builder = Headers.Builder()
    builder["a"] = "b"
    builder["c"] = "d"
    builder["e"] = Date(0L)
    builder["g"] = Instant.EPOCH
    assertThat(builder.get("a")).isEqualTo("b")
    assertThat(builder.get("c")).isEqualTo("d")
    assertThat(builder.get("e")).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    assertThat(builder.get("g")).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
  }
}
