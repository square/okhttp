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

package com.example.macrobenchmark.target

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.executeAsync
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener
import okio.IOException

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val client = OkHttpClient()

    val cache = Cache(cacheDir.resolve("tmp_cache"), 1000000)

    val clients = buildList {
      add(client)

      add(
        client.newBuilder()
          .protocols(listOf(Protocol.HTTP_1_1))
          .build()
      )

      add(
        client.newBuilder()
          .cache(cache)
          .build()
      )

      add(
        client.newBuilder()
          .addInterceptor(HttpLoggingInterceptor())
          .eventListenerFactory(LoggingEventListener.Factory())
          .build()
      )
    }

    val requests = buildList<Request> {
      add(Request.Builder().url("http://github.com/robots.txt").build())
      add(Request.Builder().url("https://github.com/robots.txt").build())
      add(Request.Builder().url("https://httpbin.org/post").post("{}".toRequestBody()).build())
    }

    lifecycleScope.launchWhenResumed {
      withContext(Dispatchers.IO) {
        repeat(10) {
          requests.forEach { request ->
            client.newCall(request).enqueue(object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                println("${request.url} $e")
              }

              override fun onResponse(call: Call, response: Response) {
                response.use {
                  println("${request.url} ${response.code} ${response.body!!.string()}")
                }
              }
            })
          }
        }
      }
    }

    lifecycleScope.launchWhenResumed {
      withContext(Dispatchers.IO) {
        clients.forEach {
          requests.forEach { request ->
            try {
              val response = client.newCall(request).executeAsync()

              response.use {
                println("${request.url} ${response.code} ${response.body!!.string()}")
              }
            } catch (e: IOException) {
              println("${request.url} $e")
            }
          }
        }
      }
    }

    lifecycleScope.launchWhenResumed {
      withContext(Dispatchers.IO) {
        requests.forEach { request ->
          try {
            val response = client.newCall(request).execute()

            response.use {
              println("${request.url} ${response.code} ${response.body!!.string()}")
            }
          } catch (e: IOException) {
            println("${request.url} $e")
          }
        }
      }
    }
  }
}
