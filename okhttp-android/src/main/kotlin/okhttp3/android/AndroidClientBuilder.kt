/*
 * Copyright (c) 2022 Block, Inc.
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

import android.annotation.SuppressLint
import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor

@SuppressLint("NewApi")
fun OkHttpClient.Companion.newAndroidClient(
  context: Context,
  debug: Boolean,
  clientConfig: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient {
  val clientBuilder = OkHttpClient.Builder()
    .addInterceptor(TracingInterceptor())
//    .addInterceptor(AlwaysHttpsInterceptor.AndroidNetworkSecurityPolicy)
    .apply {
      if (debug) {
//        eventListenerFactory(LoggingEventListener.AndroidLogging())
      }
    }

  with(clientBuilder) {
    clientConfig()
  }

  val cache = Cache(context.cacheDir.resolve("okhttp"), 10_000_000)

  clientBuilder
    .addInterceptor(BrotliInterceptor)
    .connectionPool(ConnectionPool(connectionListener = TracingConnectionListener()))
    .cache(cache)

  return clientBuilder.build()
}
