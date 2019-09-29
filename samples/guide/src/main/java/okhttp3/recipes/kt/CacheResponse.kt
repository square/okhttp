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

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class CacheResponse(cacheDirectory: File) {
  private val client: OkHttpClient = OkHttpClient.Builder()
      .cache(Cache(
          directory = cacheDirectory,
          maxSize = 10L * 1024L * 1024L // 1 MiB
      ))
      .build()

  fun run() {
    val request = Request.Builder()
        .url("http://publicobject.com/helloworld.txt")
        .build()

    val response1Body = client.newCall(request).execute().use {
      if (!it.isSuccessful) throw IOException("Unexpected code $it")

      println("Response 1 response:          $it")
      println("Response 1 cache response:    ${it.cacheResponse}")
      println("Response 1 network response:  ${it.networkResponse}")
      return@use it.body!!.string()
    }

    val response2Body = client.newCall(request).execute().use {
      if (!it.isSuccessful) throw IOException("Unexpected code $it")

      println("Response 2 response:          $it")
      println("Response 2 cache response:    ${it.cacheResponse}")
      println("Response 2 network response:  ${it.networkResponse}")
      return@use it.body!!.string()
    }

    println("Response 2 equals Response 1? " + (response1Body == response2Body))
  }
}

fun main() {
  CacheResponse(File("CacheResponse.tmp")).run()
}
