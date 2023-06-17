/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
package okhttp3.android

import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

class TestLogging {
  @JvmField
  @Rule
  val serverRule = MockWebServerRule()

  @Test
  fun testRequest() {
    OkHttpDebugLogging.enableHttp2()
    OkHttpDebugLogging.enableTaskRunner()

    val client = OkHttpClient.Builder()
      .eventListenerFactory(LoggingEventListener.Factory())
      .connectionPool(ConnectionPool(connectionListener = LoggingConnectionListener()))
      .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
      })
      .build()

    serverRule.server.enqueue(MockResponse())

    val call = client.newCall(Request(serverRule.server.url("/")))

    call.execute().use { response ->
      check(response.code == 200)
    }
  }
}
