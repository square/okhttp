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
import okio.GzipSource
import okio.Source
import okio.buffer

/**
 * Transparent Compressed response support.
 *
 * The algorithm map will be turned into a heading such as "Accept-Encoding: br;q=1.0, gzip;q=0.8, *;q=0.1"
 *
 * If [algorithms] is empty returns "identity" disabling any later compression.
 *
 * See https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Accept-Encoding
 */
open class CompressionInterceptor(
  val algorithms: Map<DecompressionAlgorithm, Double?>,
) : Interceptor {
  constructor(
    vararg algorithms: DecompressionAlgorithm,
  ) : this(
    algorithms.associateWithTo(LinkedHashMap()) {
      null
    },
  )

  internal val acceptEncoding =
    if (algorithms.isEmpty()) {
      "identity"
    } else {
      algorithms
        .map { (algorithm, priority) ->
          if (priority != null) {
            "${algorithm.encoding};q=$priority"
          } else {
            algorithm.encoding
          }
        }.joinToString(separator = ", ")
    }

  private val algorithmIndex = algorithms.keys.toTypedArray()

  override fun intercept(chain: Interceptor.Chain): Response =
    if (chain.request().header("Accept-Encoding") == null) {
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
  fun decompress(response: Response): Response {
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

  internal fun lookupDecompressor(encoding: String): DecompressionAlgorithm? {
    val algorithm = algorithmIndex.find { it.encoding.equals(encoding, ignoreCase = true) } ?: return null

    if (algorithm == Wildcard || algorithm == Identity) {
      return null
    }

    return algorithm
  }

  /**
   * A decompression algorithm such as Gzip. Must provide the Accept-Encoding value and decompress a Source.
   */
  interface DecompressionAlgorithm {
    val encoding: String

    fun decompress(compressedSource: BufferedSource): Source
  }

  companion object {
    /**
     * Request "gzip" compression.
     */
    val Gzip =
      object : DecompressionAlgorithm {
        override val encoding: String = "gzip"

        override fun decompress(compressedSource: BufferedSource): Source = GzipSource(compressedSource)
      }

    /**
     * Request the response body to be uncompressed.
     */
    val Identity =
      object : DecompressionAlgorithm {
        override val encoding: String = "identity"

        override fun decompress(compressedSource: BufferedSource): Source = compressedSource
      }

    /**
     * Declare support for any compression encoding scheme. However the response will not be decompressed.
     */
    val Wildcard =
      object : DecompressionAlgorithm {
        override val encoding: String = "*"

        override fun decompress(compressedSource: BufferedSource): Source = compressedSource
      }
  }
}
