/*
 * Copyright (C) 2025 Square, Inc.
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
package okhttp3.zstd

import com.squareup.zstd.okio.zstdDecompress
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.promisesBody
import okio.buffer
import okio.gzip

/**
 * Transparent ZStandard response support.
 *
 * This must be installed as an application interceptor.
 *
 * Adds `Accept-Encoding: zstd,gzip` to request and checks (and strips) for `Content-Encoding: zstd`
 * in responses.
 *
 * This replaces the transparent gzip compression in OkHttp.
 */
object ZstdInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val originalRequest = chain.request()

    if (originalRequest.header("Accept-Encoding") != null) {
      return chain.proceed(originalRequest)
    }

    val request =
      originalRequest
        .newBuilder()
        .header("Accept-Encoding", "zstd,gzip")
        .build()

    return decompress(chain.proceed(request))
  }

  internal fun decompress(response: Response): Response {
    if (!response.promisesBody()) {
      return response
    }

    val body = response.body
    val encoding = response.header("Content-Encoding") ?: return response

    val decompressedSource =
      when {
        encoding.equals("zstd", ignoreCase = true) -> body.source().zstdDecompress()
        encoding.equals("gzip", ignoreCase = true) -> body.source().gzip()
        else -> return response
      }

    return response
      .newBuilder()
      .removeHeader("Content-Encoding")
      .removeHeader("Content-Length")
      .body(decompressedSource.buffer().asResponseBody(body.contentType(), -1L))
      .build()
  }
}
