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
package com.google.net.cronet.okhttptransportU.internal

import android.net.http.HttpEngine
import android.net.http.UrlRequest
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Converts OkHttp requests to Cronet requests.  */
@RequiresApi(34)
class RequestConverter(
  private val cronetEngine: HttpEngine,
  internal val requestBodyConverter: RequestBodyConverter,
  internal val responseConverter: ResponseConverter
) {
  /**
   * Converts OkHttp's [Request] to a corresponding Cronet's [UrlRequest].
   *
   *
   * Since Cronet doesn't have a notion of a Response, which is handled entirely from the
   * callbacks, this method also returns a [Future] like object the
   * caller should use to obtain the matching [Response] for the given request. For example:
   *
   * <pre>
   * RequestResponseConverter converter = ...
   * CronetRequestAndOkHttpResponse reqResp = converter.convert(okHttpRequest);
   * reqResp.getRequest.start();
   *
   * // Will block until status code, headers... are available
   * Response okHttpResponse = reqResp.getResponse();
   *
   * // use OkHttp Response as usual
  </pre> *
   */
  fun create(chain: Interceptor.Chain): CompletableCronetRequest {
    val okHttpRequest = chain.request()

    val completableCronetRequest = CompletableCronetRequest(chain, responseConverter)

    // The OkHttp request callback methods are lightweight, the heavy lifting is done by OkHttp /
    // app owned threads. Use a direct executor to avoid extra thread hops.
    val builder = cronetEngine
      .newUrlRequestBuilder(
        okHttpRequest.url.toString(), completableCronetRequest.callback, directExecutor)
      .allowDirectExecutor()
    builder.setHttpMethod(okHttpRequest.method)
    for (i in 0 until okHttpRequest.headers.size) {
      builder.addHeader(okHttpRequest.headers.name(i), okHttpRequest.headers.value(i))
    }
    val body = okHttpRequest.body
    if (body != null) {
      if (okHttpRequest.header(CONTENT_LENGTH_HEADER_NAME) == null && body.contentLength() != -1L) {
        builder.addHeader(CONTENT_LENGTH_HEADER_NAME, body.contentLength().toString())
      }
      if (body.contentLength() != 0L) {
        if (body.contentType() != null) {
          builder.addHeader(CONTENT_TYPE_HEADER_NAME, body.contentType().toString())
        } else if (okHttpRequest.header(CONTENT_TYPE_HEADER_NAME) == null) {
          // Cronet always requires content-type to be present when a body is present. Use a generic
          // value if one isn't provided.
          builder.addHeader(CONTENT_TYPE_HEADER_NAME, CONTENT_TYPE_HEADER_DEFAULT_VALUE)
        } // else use the header
        builder.setUploadDataProvider(
          requestBodyConverter.convertRequestBody(body, chain.writeTimeoutMillis()),
          Dispatchers.IO.asExecutor())
      }
    }

    completableCronetRequest.setRequest(builder.build())

    return completableCronetRequest
  }

  companion object {
    private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
    private const val CONTENT_TYPE_HEADER_NAME = "Content-Type"
    private const val CONTENT_TYPE_HEADER_DEFAULT_VALUE = "application/octet-stream"
    private val directExecutor = Executor(Runnable::run)
  }
}
