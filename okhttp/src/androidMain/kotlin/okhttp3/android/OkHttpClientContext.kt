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
import okhttp3.OkHttpClient
import okhttp3.android.AndroidOkHttpClientBuilder.androidBuilder
import okhttp3.internal.platform.ContextAwarePlatform
import okhttp3.internal.platform.Platform

/**
 * App Singleton instance of OkHttp. Should be used as default client for general traffic. Or as a root client
 * adapted via [OkHttpClient.newBuilder]. May not be appropriate for critical security requests because of shared
 * interceptors/listeners.
 *
 * Apps can customise the instance by implementing [OkHttpClientFactory] in the [android.app.Application].
 */
object OkHttpClientContext {
  private val _okHttpClient: OkHttpClient by lazy {
    val context =
      checkNotNull((Platform.get() as ContextAwarePlatform).applicationContext) { "OkHttp initializer not run" }
    newOkHttpClient(context)
  }

  /**
   * Acccesor for the app singleton instance of OkHttp. The context here is not actually used, rather the app instance
   * configured via [okhttp3.internal.platform.PlatformInitializer] is used instead.
   */
  val Context.okHttpClient: OkHttpClient
    get() = _okHttpClient

  private fun newOkHttpClient(context: Context): OkHttpClient {
    val okHttpClientFactory = context.applicationContext as? OkHttpClientFactory
    return okHttpClientFactory?.newOkHttpClient() ?: OkHttpClient.androidBuilder(
      context = context,
      cacheDir = null,
    ).build()
  }
}
