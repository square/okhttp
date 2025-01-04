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

import java.io.IOException
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test

class DispatcherCleanupTest {
  @Test
  fun testFinish(server: MockWebServer) {
    val okhttp = OkHttpClient()
    val callback =
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {}

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
        }
      }
    repeat(10_000) {
      okhttp.newCall(Request.Builder().url(server.url("/")).build()).enqueue(callback)
    }
    okhttp.dispatcher.executorService.shutdown()
  }
}
