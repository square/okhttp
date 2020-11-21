/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.errors

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException

/**
 *
 */
class ErrorHandlingInterceptor: Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    return try {
      chain.proceed(chain.request())
    } catch (ioe: IOException) {
      throw typedException(ioe)
    }
  }

  fun typedException(ioe: IOException): IOException {
    if (ioe is TypedException) {
      return ioe
    }

    if (ioe is UnknownHostException) {
      return DnsNameNotResolvedException(cause = ioe)
    }

    return ioe
  }
}