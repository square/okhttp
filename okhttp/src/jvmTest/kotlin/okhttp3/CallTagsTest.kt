/*
 * Copyright (C) 2025 Square, Inc.
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
import assertk.assertions.isNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CallTagsTest {
  @JvmField @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private var client = clientTestRule.newClient()

  @Test
  fun tagsSeededFromRequest() {
    val request =
      Request
        .Builder()
        .url("https://square.com/".toHttpUrl())
        .tag(Integer::class, 5 as Integer)
        .tag(String::class, "hello")
        .build()
    val call = client.newCall(request)

    // Check the Kotlin-focused APIs.
    assertThat(call.tag(String::class)).isEqualTo("hello")
    assertThat(call.tag(Integer::class)).isEqualTo(5)
    assertThat(call.tag(Boolean::class)).isNull()
    assertThat(call.tag(Any::class)).isNull()

    // Check the Java APIs too.
    assertThat(call.tag(String::class.java)).isEqualTo("hello")
    assertThat(call.tag(Integer::class.java)).isEqualTo(5)
    assertThat(call.tag(Boolean::class.java)).isNull()
    assertThat(call.tag(Any::class.java)).isNull()
  }

  @Test
  fun tagsCanBeComputed() {
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .build()
    val call = client.newCall(request)

    // Check the Kotlin-focused APIs.
    assertThat(call.tag(String::class) { "a" }).isEqualTo("a")
    assertThat(call.tag(String::class) { "b" }).isEqualTo("a")
    assertThat(call.tag(String::class)).isEqualTo("a")

    // Check the Java-focused APIs.
    assertThat(call.tag(Integer::class) { 1 as Integer }).isEqualTo(1)
    assertThat(call.tag(Integer::class) { 2 as Integer }).isEqualTo(1)
    assertThat(call.tag(Integer::class)).isEqualTo(1)
  }

  @Test
  fun computedTagsAreNotRetainedInClone() {
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .build()
    val callA = client.newCall(request)
    assertThat(callA.tag(String::class) { "a" }).isEqualTo("a")
    assertThat(callA.tag(String::class) { "b" }).isEqualTo("a")

    val callB = callA.clone()
    assertThat(callB.tag(String::class) { "c" }).isEqualTo("c")
    assertThat(callB.tag(String::class) { "d" }).isEqualTo("c")
  }
}
