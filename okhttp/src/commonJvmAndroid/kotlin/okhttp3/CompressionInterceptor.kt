/*
 * Copyright (c) 2025 Block, Inc.
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
package okhttp3

import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.promisesBody
import okio.BufferedSource
import okio.GzipSource
import okio.Source
import okio.buffer

/**
 * Transparent Compressed response support.
 *
 * Adds Accept-Encoding to request and checks (and strips) Content-Encoding in
 * responses. n.b. this replaces the transparent gzip compression in BridgeInterceptor so should generally include
 * gzip in the list of algorithms.
 */
open class CompressionInterceptor(
  vararg val algorithms: DecompressionAlgorithm,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response =
    if (chain.request().header("Accept-Encoding") == null) {
      val request =
        chain
          .request()
          .newBuilder()
          .header("Accept-Encoding", algorithms.joinToString(separator = ",") { it.encoding })
          .build()

      val response = chain.proceed(request)

      decompress(response)
    } else {
      chain.proceed(chain.request())
    }

  fun decompress(response: Response): Response {
    if (!response.promisesBody()) {
      return response
    }
    val body = response.body
    val encoding = response.header("Content-Encoding") ?: return response

    val algorithm = algorithms.find { it.encoding.equals(encoding, ignoreCase = true) } ?: return response

    val decompressedSource = with(algorithm) { body.source().decompress().buffer() }

    return response
      .newBuilder()
      .removeHeader("Content-Encoding")
      .removeHeader("Content-Length")
      .body(decompressedSource.asResponseBody(body.contentType(), -1))
      .build()
  }

  interface DecompressionAlgorithm {
    val encoding: String

    fun BufferedSource.decompress(): Source
  }

  companion object {
    val Gzip =
      object : DecompressionAlgorithm {
        override val encoding: String = "gzip"

        override fun BufferedSource.decompress(): Source = GzipSource(this)
      }
  }
}
