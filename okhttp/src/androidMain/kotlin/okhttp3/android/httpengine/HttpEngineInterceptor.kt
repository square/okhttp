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

import android.net.http.UrlRequest
import android.os.Build
import androidx.annotation.RequiresExtension
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * An OkHttp interceptor that redirects HTTP traffic to use Cronet instead of using the OkHttp
 * network stack.
 *
 * The interceptor should be used as the last application interceptor to ensure that all other
 * interceptors are visited before sending the request on wire and after a response is returned.
 *
 * The interceptor is a plug-and-play replacement for the OkHttp stack for the most part,
 * however, there are some caveats to keep in mind:
 *
 *  1. The majority of OkHttp core is bypassed. This includes all network
 * interceptors.
 *  2. Some response fields are not being populated due to mismatches between Cronet's and
 * OkHttp's architecture.
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
internal class HttpEngineInterceptor(
  httpEngineDecorator: HttpEngineCallDecorator,
) : Interceptor {
  private val converter: RequestResponseConverter = RequestResponseConverter.build(httpEngineDecorator)
  private val activeCalls: MutableMap<Call, UrlRequest> = ConcurrentHashMap<Call, UrlRequest>()

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    if (chain.call().isCanceled()) {
      throw IOException("Canceled")
    }

    val request = chain.request()

    val requestAndOkHttpResponse: RequestResponseConverter.CronetRequestAndOkHttpResponse =
      converter.convert(request)

    activeCalls[chain.call()] = requestAndOkHttpResponse.request

    try {
      requestAndOkHttpResponse.request.start()
      return toInterceptorResponse(requestAndOkHttpResponse.response, chain.call())
    } catch (e: RuntimeException) {
      // If the response is retrieved successfully the caller is responsible for closing
      // the response, which will remove it from the active calls map.
      activeCalls.remove(chain.call())
      throw e
    } catch (e: IOException) {
      activeCalls.remove(chain.call())
      throw e
    }
  }

  private fun toInterceptorResponse(
    response: Response,
    call: Call,
  ): Response {
    checkNotNull(response.body)

    if (response.body is HttpEngineInterceptorResponseBody) {
      return response
    }

    return response
      .newBuilder()
      .body(HttpEngineInterceptorResponseBody(response.body, call))
      .build()
  }

  fun cancelCall(call: Call) {
    activeCalls.remove(call)
  }

  private inner class HttpEngineInterceptorResponseBody(
    delegate: ResponseBody,
    private val call: Call,
  ) : HttpEngineTransportResponseBody(delegate) {
    override fun customCloseHook() {
      activeCalls.remove(call)
    }
  }
}
