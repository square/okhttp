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

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class ParseResponseWithMoshi {
  private val client = OkHttpClient()
  private val moshi = Moshi.Builder().build()
  private val gistJsonAdapter = moshi.adapter(Gist::class.java)

  fun run() {
    val request = Request.Builder()
        .url("https://api.github.com/gists/c2a7c39532239ff261be")
        .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      val gist = gistJsonAdapter.fromJson(response.body!!.source())

      for ((key, value) in gist!!.files!!) {
        println(key)
        println(value.content)
      }
    }
  }

  @JsonClass(generateAdapter = true)
  data class Gist(var files: Map<String, GistFile>?)

  @JsonClass(generateAdapter = true)
  data class GistFile(var content: String?)
}

fun main() {
  ParseResponseWithMoshi().run()
}
