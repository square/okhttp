/*
 * Copyright (C) 2022 Block, Inc.
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

import org.assertj.core.api.Assertions.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealCall.Companion.asRealCall
import org.junit.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DecoratorTest {
  @JvmField @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  val client = clientTestRule.newClient()
  val request = Request("https://example.org".toHttpUrl())

  @Test
  fun testDirectRealCall() {
    val call = client.newCall(request)

    assertThat(call.asRealCall()).isInstanceOf(RealCall::class.java)
  }

  @Test
  fun testWrappedRealCall() {
    val call = SimpleWrappedCall(client.newCall(request))

    assertThat(call.asRealCall()).isNull()
  }

  @Test
  fun testDecoratedRealCall() {
    val call = DecoratedCall(client.newCall(request))

    assertThat(call.asRealCall()).isInstanceOf(RealCall::class.java)
  }

  @Test
  fun testDoubleDecoratedRealCall() {
    val call = DecoratedCall(DecoratedCall(client.newCall(request)))

    assertThat(call.asRealCall()).isInstanceOf(RealCall::class.java)
  }

  class SimpleWrappedCall(call: Call): Call by call

  class DecoratedCall(override val decorated: Call): Call by decorated, Decorator<Call>
}
