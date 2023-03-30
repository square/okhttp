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
import android.net.http.UrlRequest
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody

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
class CronetInterceptor private constructor(converter: RequestResponseConverter) : Interceptor, AutoCloseable {
  private val converter: RequestResponseConverter
  private val activeCalls: MutableMap<Call, UrlRequest?> = ConcurrentHashMap()
  private val scheduledExecutor: ScheduledExecutorService = ScheduledThreadPoolExecutor(1)

  init {
    this.converter = converter

    // TODO(danstahr): There's no other way to know if the call is canceled but polling
    //  (https://github.com/square/okhttp/issues/7164).
    val unusedFuture = scheduledExecutor.scheduleAtFixedRate({
      val activeCallsIterator: MutableIterator<Map.Entry<Call, UrlRequest?>> = activeCalls.entries.iterator()
      while (activeCallsIterator.hasNext()) {
        try {
          val (key, value) = activeCallsIterator.next()
          if (key.isCanceled()) {
            activeCallsIterator.remove()
            value!!.cancel()
          }
        } catch (e: RuntimeException) {
          Log.w(TAG, "Unable to propagate cancellation status", e)
        }
      }
    }, CANCELLATION_CHECK_INTERVAL_MILLIS.toLong(), CANCELLATION_CHECK_INTERVAL_MILLIS.toLong(), TimeUnit.MILLISECONDS)
  }

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    if (chain.call().isCanceled()) {
      throw IOException("Canceled")
    }
    val request = chain.request()
    val requestAndOkHttpResponse = converter.convert(request, chain.readTimeoutMillis(), chain.writeTimeoutMillis())
    activeCalls[chain.call()] = requestAndOkHttpResponse.request
    return try {
      requestAndOkHttpResponse.request.start()
      toInterceptorResponse(requestAndOkHttpResponse.response, chain.call())
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

  override fun close() {
    scheduledExecutor.shutdown()
  }

  /** A builder for [CronetInterceptor].  */
  class Builder internal constructor(cronetEngine: HttpEngine) : RequestResponseConverterBasedBuilder<Builder, CronetInterceptor>(cronetEngine) {
    /** Builds the interceptor. The same builder can be used to build multiple interceptors.  */
    override fun build(converter: RequestResponseConverter): CronetInterceptor {
      return CronetInterceptor(converter)
    }
  }

  fun toInterceptorResponse(response: Response, call: Call): Response {
    checkNotNull(response.body)
    return if (response.body is CronetInterceptorResponseBody) {
      response
    } else response.newBuilder().body(CronetInterceptorResponseBody(response.body, call)).build()
  }

  internal inner class CronetInterceptorResponseBody internal constructor(delegate: ResponseBody, private val call: Call) : CronetTransportResponseBody(delegate) {
    override fun customCloseHook() {
      activeCalls.remove(call)
    }
  }

  companion object {
    private const val TAG = "CronetInterceptor"
    private const val CANCELLATION_CHECK_INTERVAL_MILLIS = 500

    /** Creates a [CronetInterceptor] builder.  */
    fun newBuilder(cronetEngine: HttpEngine): Builder {
      return Builder(cronetEngine)
    }
  }
}
