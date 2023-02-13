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
 */
package okhttp3.android

import android.content.Context
import android.util.Log
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.android.tracing.TracingConnectionListener
import okhttp3.android.tracing.TracingInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener

fun OkHttpClient.Companion.AndroidBuilder(
  cache: Cache?,
  debugLogging: Boolean = false,
  alwaysHttps: Boolean = true
): OkHttpClient.Builder {
  return OkHttpClient.Builder()
    .apply {
      if (alwaysHttps) {
        addInterceptor(AlwaysOkHttpInterceptor())
      }
    }
    .addInterceptor(TracingInterceptor())
    .connectionPool(ConnectionPool(connectionListener = TracingConnectionListener()))
    .cache(cache)
    .apply {
      if (debugLogging) {
        eventListenerFactory(LoggingEventListener.Factory(HttpLoggingInterceptor.Logger.AndroidLogging()))
      }
    }
}

fun HttpLoggingInterceptor.Logger.Companion.AndroidLogging(
  priority: Int = Log.INFO,
  tag: String = "OkHttp"
) = HttpLoggingInterceptor.Logger { Log.println(priority, tag, it) }

fun Context.buildOkHttpCache(
  name: String = "okhttp",
  maxSize: Long = 10_000_000
): Cache = Cache(cacheDir.resolve(name), maxSize)
