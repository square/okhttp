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
package okhttp3.android.tracing

import androidx.tracing.trace
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Tracing implementation of Interceptor that marks each Call in a perfetto
 * trace.
 */
class TracingInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    return trace(chain.request().tracingTag) {
      chain.proceed(chain.request())
    }
  }

  val Request.tracingTag: String
    get() = url.encodedPath.take(127)
}
