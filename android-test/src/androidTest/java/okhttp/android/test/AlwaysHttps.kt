/*
 * Copyright (C) 2025 Block, Inc.
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
package okhttp.android.test

import android.os.Build
import android.security.NetworkSecurityPolicy
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AlwaysHttps(
  policy: Policy,
) : Interceptor {
  val hostPolicy: HostPolicy = policy.hostPolicy

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()

    val updatedRequest =
      if (request.url.scheme == "http" && !hostPolicy.isCleartextTrafficPermitted(request)) {
        request
          .newBuilder()
          .url(
            request.url
              .newBuilder()
              .scheme("https")
              .build(),
          ).build()
      } else {
        request
      }

    return chain.proceed(updatedRequest)
  }

  fun interface HostPolicy {
    fun isCleartextTrafficPermitted(request: Request): Boolean
  }

  enum class Policy {
    Always {
      override val hostPolicy: HostPolicy
        get() = HostPolicy { false }
    },
    Manifest {
      override val hostPolicy: HostPolicy
        get() =
          if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            val networkSecurityPolicy = NetworkSecurityPolicy.getInstance()

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
              HostPolicy { networkSecurityPolicy.isCleartextTrafficPermitted(it.url.host) }
            } else {
              HostPolicy { networkSecurityPolicy.isCleartextTrafficPermitted }
            }
          } else {
            HostPolicy { true }
          }
    }, ;

    abstract val hostPolicy: HostPolicy
  }
}
