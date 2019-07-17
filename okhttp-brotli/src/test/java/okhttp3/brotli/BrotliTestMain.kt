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
package okhttp3.brotli

import okhttp3.OkHttpClient
import okhttp3.Request

fun main() {
  val client = OkHttpClient.Builder()
      .addInterceptor(BrotliInterceptor)
      .build()

  sendRequest("https://httpbin.org/brotli", client)
  sendRequest("https://httpbin.org/gzip", client)
}

private fun sendRequest(url: String, client: OkHttpClient) {
  val req = Request.Builder().url(url).build()

  client.newCall(req).execute().use {
    println(it.body?.string())
  }
}