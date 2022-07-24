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
package mockwebserver3.junit5.internal

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@ExtendWith(MockWebServerExtension::class)
class ExtensionConstructorTest(
  private val server: MockWebServer
) {
  init {
    assertThat(server.started).isFalse()
  }

  @RegisterExtension
  val clientTestRule: OkHttpClientTestRule = OkHttpClientTestRule()

  @BeforeEach
  fun setup() {
    assertThat(server.started).isTrue()
    server.enqueue(MockResponse().setResponseCode(200))
  }

  @AfterEach
  fun tearDown() {
    assertThat(server.started).isTrue()
    server.enqueue(MockResponse().setResponseCode(200))
  }

  @Test
  fun testClient() {
    assertThat(server.started).isTrue()
    clientTestRule.newClient().newCall(Request(server.url("/"))).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }
}
