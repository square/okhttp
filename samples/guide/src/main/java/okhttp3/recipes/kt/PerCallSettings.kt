/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.recipes.kt

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class PerCallSettings {
  private val client = OkHttpClient()

  fun run() {
    val request = Request.Builder()
        .url("http://httpbin.org/delay/1") // This URL is served with a 1 second delay.
        .build()

    // Copy to customize OkHttp for this request.
    val client1 = client.newBuilder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()
    try {
      client1.newCall(request).execute().use { response ->
        println("Response 1 succeeded: $response")
      }
    } catch (e: IOException) {
      println("Response 1 failed: $e")
    }

    // Copy to customize OkHttp for this request.
    val client2 = client.newBuilder()
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build()
    try {
      client2.newCall(request).execute().use { response ->
        println("Response 2 succeeded: $response")
      }
    } catch (e: IOException) {
      println("Response 2 failed: $e")
    }
  }
}

fun main() {
  PerCallSettings().run()
}
