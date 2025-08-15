/*
 * Copyright 2022 Google LLC
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
package okhttp3.android.httpengine

import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresExtension
import com.google.common.base.Preconditions.checkArgument
import java.util.concurrent.Executors

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
abstract class RequestResponseConverterBasedBuilder<B : RequestResponseConverterBasedBuilder<B, out T>, T>(
  private val httpEngine: HttpEngine,
) {
  private var uploadDataProviderExecutorSize: Int = DEFAULT_THREAD_POOL_SIZE

  // Not setting the default straight away to lazy initialize the object if it ends up not being
  // used.
  private var redirectStrategy: RedirectStrategy = RedirectStrategy.defaultStrategy()

  /**
   * Sets the size of upload data provider executor. The same executor is used for all upload data
   * providers within the interceptor.
   *
   * @see android.net.http.UrlRequest.Builder.setUploadDataProvider
   */
  fun setUploadDataProviderExecutorSize(size: Int): B {
    checkArgument(size > 0, "The number of threads must be positive!")
    uploadDataProviderExecutorSize = size
    return this as B
  }

  /**
   * Sets the strategy for following redirects.
   *
   *
   * Note that the Cronet (i.e. Chromium) wide safeguards will still apply if one attempts to
   * follow redirects too many times.
   */
  fun setRedirectStrategy(redirectStrategy: RedirectStrategy): B {
    this.redirectStrategy = redirectStrategy
    return this as B
  }

  internal abstract fun build(converter: RequestResponseConverter): T

  fun build(): T {
    val converter =
      RequestResponseConverter(
        httpEngine,
        Executors.newFixedThreadPool(uploadDataProviderExecutorSize),
        // There must always be enough executors to blocking-read the OkHttp request bodies
        // otherwise deadlocks can occur.
        RequestBodyConverterImpl.create(Executors.newCachedThreadPool()),
        ResponseConverter(),
        redirectStrategy,
      )

    return build(converter)
  }

  companion object {
    private const val DEFAULT_THREAD_POOL_SIZE = 4
  }
}
