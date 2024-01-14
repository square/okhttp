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

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem

class PostPath {
  private val client = OkHttpClient()
  private val fileSystem = FakeFileSystem()
  val path = "test.json".toPath()

  fun run() {
    fileSystem.write(path) {
      writeUtf8("{}")
    }

    val request =
      Request.Builder()
        .url("https://httpbin.org/anything")
        .put(path.asRequestBody(fileSystem, MEDIA_TYPE_JSON))
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      fileSystem.sink(path).use {
        response.body.source().readAll(it)
      }

      println(fileSystem.source(path).buffer().readUtf8())
    }
  }

  companion object {
    val MEDIA_TYPE_JSON = "application/json".toMediaType()
  }
}

fun main() {
  PostPath().run()
}
