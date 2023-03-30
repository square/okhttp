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
import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response

/**
 * An OkHttp interceptor that redirects HTTP traffic to use Cronet instead of using the OkHttp
 * network stack.
 *
 *
 * The interceptor should be used as the last application interceptor to ensure that all other
 * interceptors are visited before sending the request on wire and after a response is returned.
 *
 *
 * The interceptor is a plug-and-play replacement for the OkHttp stack for the most part,
 * however, there are some caveats to keep in mind:
 *
 *
 *  1. The entirety of OkHttp core is bypassed. This includes caching configuration and network
 * interceptors.
 *  1. Some response fields are not being populated due to mismatches between Cronet's and
 * OkHttp's architecture. TODO(danstahr): add a concrete list).
 *
 */
@RequiresApi(34)
class CronetInterceptor private constructor(converter: RequestResponseConverter) : Interceptor {
  private val converter: RequestResponseConverter

  init {
    this.converter = converter
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    if (chain.call().isCanceled()) {
      throw IOException("Canceled")
    }
    val request = chain.request()
    val requestAndOkHttpResponse = converter.convert(request, chain.readTimeoutMillis(), chain.writeTimeoutMillis())

    chain.withEventListener(object: EventListener() {
      override fun canceled(call: Call) {
        requestAndOkHttpResponse.request.cancel()
      }
    })

    requestAndOkHttpResponse.request.start()

    return requestAndOkHttpResponse.response
  }

  /** A builder for [CronetInterceptor].  */
  class Builder internal constructor(cronetEngine: HttpEngine) : RequestResponseConverterBasedBuilder<Builder, CronetInterceptor>(cronetEngine) {
    /** Builds the interceptor. The same builder can be used to build multiple interceptors.  */
    override fun build(converter: RequestResponseConverter): CronetInterceptor {
      return CronetInterceptor(converter)
    }
  }

  companion object {

    /** Creates a [CronetInterceptor] builder.  */
    fun newBuilder(cronetEngine: HttpEngine): Builder {
      return Builder(cronetEngine)
    }
  }
}
