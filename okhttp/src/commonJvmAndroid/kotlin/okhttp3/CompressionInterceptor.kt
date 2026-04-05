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
 */
package okhttp3

import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.promisesBody
import okio.BufferedSource
import okio.Source
import okio.buffer

/**
 * Transparent Compressed response support.
 *
 * The algorithm map will be turned into a heading such as "Accept-Encoding: br, gzip"
 *
 * If [algorithms] is empty this interceptor has no effect. To disable compression set
 * a specific "Accept-Encoding: identity" or similar.
 *
 * See https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Accept-Encoding
 */
open class CompressionInterceptor(
  vararg val algorithms: DecompressionAlgorithm,
) : Interceptor {
  internal val acceptEncoding =
    algorithms
      .map {
        it.encoding
      }.joinToString(separator = ", ")

  override fun intercept(chain: Interceptor.Chain): Response =
    if (algorithms.isNotEmpty() && chain.request().header("Accept-Encoding") == null) {
      val request =
        chain
          .request()
          .newBuilder()
          .header("Accept-Encoding", acceptEncoding)
          .build()

      val response = chain.proceed(request)

      decompress(response)
    } else {
      chain.proceed(chain.request())
    }

  /**
   * Returns a decompressed copy of the Response, typically via a streaming Source.
   * If no known decompression or the response is not compressed, returns the response unmodified.
   */
  internal fun decompress(response: Response): Response {
    if (!response.promisesBody()) {
      return response
    }
    val body = response.body
    val encoding = response.header("Content-Encoding") ?: return response

    val algorithm = lookupDecompressor(encoding) ?: return response

    val decompressedSource = algorithm.decompress(body.source()).buffer()

    return response
      .newBuilder()
      .removeHeader("Content-Encoding")
      .removeHeader("Content-Length")
      .body(decompressedSource.asResponseBody(body.contentType(), -1))
      .build()
  }

  internal fun lookupDecompressor(encoding: String): DecompressionAlgorithm? =
    algorithms.find {
      it.encoding.equals(encoding, ignoreCase = true)
    }

  /**
   * A decompression algorithm such as Gzip. Must provide the Accept-Encoding value and decompress a Source.
   */
  interface DecompressionAlgorithm {
    val encoding: String

    fun decompress(compressedSource: BufferedSource): Source
  }
}
