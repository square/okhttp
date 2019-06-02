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
package okhttp3

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * responses coming back in. Typically interceptors add, remove, or transform headers on the request
 * or response.
 */
interface Interceptor {
  @Throws(IOException::class)
  fun intercept(chain: Chain): Response

  companion object {
    /**
     * Constructs an interceptor for a lambda. This compact syntax is most useful for inline
     * interceptors.
     *
     * ```
     * val interceptor = Interceptor { chain: Interceptor.Chain ->
     *     chain.proceed(chain.request())
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (chain: Chain) -> Response): Interceptor =
        object : Interceptor {
          override fun intercept(chain: Chain) = block(chain)
        }
  }

  interface Chain {
    fun request(): Request

    @Throws(IOException::class)
    fun proceed(request: Request): Response

    /**
     * Returns the connection the request will be executed on. This is only available in the chains
     * of network interceptors; for application interceptors this is always null.
     */
    fun connection(): Connection?

    fun call(): Call

    fun connectTimeoutMillis(): Int

    fun withConnectTimeout(timeout: Int, unit: TimeUnit): Chain

    fun readTimeoutMillis(): Int

    fun withReadTimeout(timeout: Int, unit: TimeUnit): Chain

    fun writeTimeoutMillis(): Int

    fun withWriteTimeout(timeout: Int, unit: TimeUnit): Chain
  }
}
