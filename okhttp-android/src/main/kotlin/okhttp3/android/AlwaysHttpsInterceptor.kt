/*
 * Copyright (c) 2023 Block, Inc.
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

import android.os.Build
import android.security.NetworkSecurityPolicy
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * An interceptor that enforces HTTPS for all requests, to work within Android's network security policy.
 */
class AlwaysHttpsInterceptor(private val requiresHttps: (Request) -> Boolean) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    var request = chain.request()

    if (request.url.scheme == "http") {
      if (requiresHttps(request)) {
        request = request.newBuilder().url(
          request.url.newBuilder().scheme("https").build()
        ).build()
      }
    }

    return chain.proceed(request)
  }

  companion object {
    val Always: AlwaysHttpsInterceptor = AlwaysHttpsInterceptor { true }

    fun AndroidNetworkSecurityPolicy(): AlwaysHttpsInterceptor {
      if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
        val policy = NetworkSecurityPolicy.getInstance()

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
          return AlwaysHttpsInterceptor { !policy.isCleartextTrafficPermitted(it.url.host) }
        } else {
          return AlwaysHttpsInterceptor { !policy.isCleartextTrafficPermitted }
        }
      } else {
        return AlwaysHttpsInterceptor { false }
      }
    }
  }
}
