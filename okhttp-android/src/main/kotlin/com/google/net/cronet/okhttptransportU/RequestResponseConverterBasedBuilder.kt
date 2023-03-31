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
package com.google.net.cronet.okhttptransportU

import android.net.http.HttpEngine
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

@RequiresApi(34)
abstract class RequestResponseConverterBasedBuilder<S : RequestResponseConverterBasedBuilder<S, T>, T>(
  private val cronetEngine: HttpEngine
) {
  private var uploadDataProviderExecutorSize = DEFAULT_THREAD_POOL_SIZE

  internal var redirectStrategy: RedirectStrategy = RedirectStrategy.defaultStrategy()

  /**
   * Sets the size of upload data provider executor. The same executor is used for all upload data
   * providers within the interceptor.
   *
   * @see android.net.http.UrlRequest.Builder.setUploadDataProvider
   */
  fun setUploadDataProviderExecutorSize(size: Int): S {
    check(size > 0) {
      "The number of threads must be positive!"
    }
    uploadDataProviderExecutorSize = size
    return this as S
  }

  /**
   * Sets the strategy for following redirects.
   *
   *
   * Note that the Cronet (i.e. Chromium) wide safeguards will still apply if one attempts to
   * follow redirects too many times.
   */
  fun setRedirectStrategy(redirectStrategy: RedirectStrategy): S {
    this.redirectStrategy = redirectStrategy
    return this as S
  }

  abstract fun build(converter: RequestResponseConverter): T
  fun build(): T {
    val converter = RequestResponseConverter(
      cronetEngine,
      Executors.newFixedThreadPool(uploadDataProviderExecutorSize),  // There must always be enough executors to blocking-read the OkHttp request bodies
      // otherwise deadlocks can occur.
      RequestBodyConverterImpl.create(Executors.newCachedThreadPool()),
      ResponseConverter(),
      redirectStrategy)
    return build(converter)
  }

  companion object {
    private const val DEFAULT_THREAD_POOL_SIZE = 4
  }
}
