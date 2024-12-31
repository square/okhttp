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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ExtensionLifecycleTest(
  server: MockWebServer,
) {
  init {
    assertThat(server).isSameInstanceAs(staticServer)
  }

  private lateinit var instanceServer: MockWebServer

  @RegisterExtension
  val clientTestRule: OkHttpClientTestRule = OkHttpClientTestRule()

  @BeforeEach
  fun beforeEach(server: MockWebServer) {
    instanceServer = server

    assertThat(instanceServer).isNotSameInstanceAs(staticServer)
    assertThat(instanceServer).isSameInstanceAs(server)

    assertThat(server.started).isTrue()
    server.enqueue(MockResponse())
  }

  @AfterEach
  fun afterEach(server: MockWebServer) {
    assertThat(server).isSameInstanceAs(instanceServer)
  }

  @Test
  fun testClient(server: MockWebServer) {
    assertThat(server).isSameInstanceAs(instanceServer)

    clientTestRule.newClient().newCall(Request(server.url("/"))).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }

  @Test
  fun testClient2(server: MockWebServer) {
    assertThat(server).isSameInstanceAs(instanceServer)

    clientTestRule.newClient().newCall(Request(server.url("/"))).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }

  @ParameterizedTest
  @ValueSource(ints = [1, 2])
  fun paramTest(
    instance: Int,
    server: MockWebServer,
  ) {
    assertThat(server).isSameInstanceAs(instanceServer)
  }

  companion object {
    private lateinit var staticServer: MockWebServer

    @JvmStatic
    @BeforeAll
    fun beforeAll(server: MockWebServer) {
      staticServer = server
      assertThat(staticServer.started).isTrue()
    }

    @JvmStatic
    @AfterAll
    fun afterAll(
      server: MockWebServer,
      testInfo: TestInfo,
    ) {
      assertThat(server).isSameInstanceAs(staticServer)
    }
  }
}
