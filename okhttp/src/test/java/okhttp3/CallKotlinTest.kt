/*
 * Copyright (C) 2013 Square, Inc.
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

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.testing.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

class CallKotlinTest {
  @JvmField @Rule val platform = PlatformRule()
  @JvmField @Rule val timeout: TestRule = Timeout(30_000, TimeUnit.MILLISECONDS)
  @JvmField @Rule val server = MockWebServer()
  @JvmField @Rule val clientTestRule = OkHttpClientTestRule()

  private lateinit var client: OkHttpClient

  @Before fun setUp() {
    client = clientTestRule.newClient()
  }

  @Test
  fun legalToExecuteTwiceCloning() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val call = client.newCall(request)
    val response1 = call.execute()

    val cloned = call.clone()
    val response2 = cloned.execute()

    assertThat("abc").isEqualTo(response1.body!!.string())
    assertThat("def").isEqualTo(response2.body!!.string())
  }
}
