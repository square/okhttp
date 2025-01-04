/*
 * Copyright (c) 2024 Block, Inc.
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

import android.content.Context
import android.os.StrictMode
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * [OkHttpClient.Builder] with opinionated defaults on Android.
 */
object AndroidOkHttpClientBuilder {
  /**
   * Create a [OkHttpClient.Builder] with opinionated defaults on Android.
   *
   * @param context The context for accessing resources such as file locations or android services.
   * @param cacheDir lambda for providing a cache dir. Defaults to "okhttp" in [Context.getCacheDir].
   * @param cacheSize the cache size. Defaults to 10MB.
   */
  fun OkHttpClient.Companion.androidBuilder(
    context: Context,
    cacheDir: (() -> File)? = { context.cacheDir.resolve("okhttp") },
    cacheSize: Long = 10_000_000L,
  ): OkHttpClient.Builder {
    return OkHttpClient.Builder().apply {
      if (cacheDir != null) {
        StrictMode.allowThreadDiskWrites().resetAfter {
          cache(Cache(cacheDir(), cacheSize))
        }
      }
    }
  }

  private fun <R> StrictMode.ThreadPolicy.resetAfter(block: () -> R) =
    try {
      block()
    } finally {
      StrictMode.setThreadPolicy(this)
    }
}
