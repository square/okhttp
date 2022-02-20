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

package okhttp3

import okhttp3.jetty.Http3BridgeInterceptor
import org.eclipse.jetty.http3.client.HTTP3Client

fun main() {
  val http3Client = HTTP3Client()

  val client = OkHttpClient.Builder()
    .addInterceptor(Http3BridgeInterceptor(http3Client))
    .build()

  try {
    http3Client.start()

    val request = Request.Builder()
      .url("https://quic.tech:8443/")
      .build()

    client.newCall(request).execute().use { response ->
      println(response.protocol)
      println(response.code)
    }
  } finally {
    http3Client.stop()
    client.dispatcher.executorService.shutdownNow()
    client.connectionPool.evictAll()
  }
}
