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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

class PostStreaming {
  private val client = OkHttpClient()

  fun run() {
    val requestBody = object : RequestBody() {
      override fun contentType() = MEDIA_TYPE_MARKDOWN

      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("Numbers\n")
        sink.writeUtf8("-------\n")
        for (i in 2..997) {
          sink.writeUtf8(String.format(" * $i = ${factor(i)}\n"))
        }
      }

      private fun factor(n: Int): String {
        for (i in 2 until n) {
          val x = n / i
          if (x * i == n) return "${factor(x)} × $i"
        }
        return n.toString()
      }
    }

    val request = Request.Builder()
        .url("https://api.github.com/markdown/raw")
        .post(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      println(response.body!!.string())
    }
  }

  companion object {
    val MEDIA_TYPE_MARKDOWN = "text/x-markdown; charset=utf-8".toMediaType()
  }
}

fun main() {
  PostStreaming().run()
}
