/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.brotli

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.GzipSource
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream

/**
 * Transparent Brotli response support.
 *
 * Adds Accept-Encoding: br to request and checks (and strips) for Content-Encoding: br in
 * responses.  n.b. this replaces the transparent gzip compression in BridgeInterceptor.
 */
object BrotliInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response =
      if (chain.request().header("Accept-Encoding") == null) {
        val request = chain.request().newBuilder()
            .header("Accept-Encoding", "br,gzip")
            .build()

        val response = chain.proceed(request)

        uncompress(response)
      } else {
        chain.proceed(chain.request())
      }

  internal fun uncompress(response: Response): Response {
    val body = response.body ?: return response
    val encoding = response.header("Content-Encoding") ?: return response

    val decompressedSource = when {
      encoding.equals("br", ignoreCase = true) ->
        BrotliInputStream(body.source().inputStream()).source().buffer()
      encoding.equals("gzip", ignoreCase = true) ->
        GzipSource(body.source()).buffer()
      else -> return response
    }

    return response.newBuilder()
        .removeHeader("Content-Encoding")
        .removeHeader("Content-Length")
        .body(decompressedSource.asResponseBody(body.contentType(), -1))
        .build()
  }
}
