/*
 * Copyright (C) 2015 Square, Inc.
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

package okhttp3.logging

import java.io.IOException
import java.nio.charset.Charset
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import okhttp3.CompressionInterceptor
import okhttp3.CompressionInterceptor.DecompressionAlgorithm
import okhttp3.Gzip
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.charsetOrUtf8
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.promisesBody
import okhttp3.internal.platform.Platform
import okhttp3.logging.internal.isProbablyUtf8
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.GzipSource
import okio.Source
import okio.buffer

/**
 * An OkHttp interceptor which logs request and response information. Can be applied as an
 * [application interceptor][OkHttpClient.interceptors] or as a [OkHttpClient.networkInterceptors].
 *
 * The format of the logs created by this class should not be considered stable and may
 * change slightly between releases. If you need a stable logging format, use your own interceptor.
 */
class HttpLoggingInterceptor
  @JvmOverloads
  constructor(
    private val logger: Logger = Logger.DEFAULT,
  ) : Interceptor {
    @Volatile
    private var headersToRedact = emptySet<String>()

    @Volatile
    private var queryParamsNameToRedact = emptySet<String>()

    @set:JvmName("level")
    @Volatile
    var level = Level.NONE

    var requestLevel: (Request) -> Level = { level }

    enum class Level {
      /** No logs. */
      NONE,

      /**
       * Logs request and response lines.
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1 (3-byte body)
       *
       * <-- 200 OK (22ms, 6-byte body)
       * ```
       */
      BASIC,

      /**
       * Logs request and response lines and their respective headers.
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1
       * Host: example.com
       * Content-Type: plain/text
       * Content-Length: 3
       * --> END POST
       *
       * <-- 200 OK (22ms)
       * Content-Type: plain/text
       * Content-Length: 6
       * <-- END HTTP
       * ```
       */
      HEADERS,

      /**
       * Logs request and response lines and their respective headers and bodies (if present).
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1
       * Host: example.com
       * Content-Type: plain/text
       * Content-Length: 3
       *
       * Hi?
       * --> END POST
       *
       * <-- 200 OK (22ms)
       * Content-Type: plain/text
       * Content-Length: 6
       *
       * Hello!
       * <-- END HTTP
       * ```
       */
      BODY,

      /**
       * Logs streaming request and response lines.
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1
       * <-- 200 OK (22ms, unknown-length body)
       * > request A
       *
       * < response B
       *
       * > request C
       *
       * < response D
       *
       * --> END POST
       * <-- END HTTP
       * ```
       */
      STREAMING,
    }

    fun interface Logger {
      fun log(message: String)

      companion object {
        /** A [Logger] defaults output appropriate for the current platform. */
        @JvmField
        val DEFAULT: Logger = DefaultLogger()

        private class DefaultLogger : Logger {
          override fun log(message: String) {
            Platform.get().log(message)
          }
        }
      }
    }

    fun redactHeader(name: String) {
      val newHeadersToRedact = TreeSet(String.CASE_INSENSITIVE_ORDER)
      newHeadersToRedact += headersToRedact
      newHeadersToRedact += name
      headersToRedact = newHeadersToRedact
    }

    fun redactQueryParams(vararg name: String) {
      val newQueryParamsNameToRedact = TreeSet(String.CASE_INSENSITIVE_ORDER)
      newQueryParamsNameToRedact += queryParamsNameToRedact
      newQueryParamsNameToRedact.addAll(name)
      queryParamsNameToRedact = newQueryParamsNameToRedact
    }

    /**
     * Sets the level and returns this.
     *
     * This was deprecated in OkHttp 4.0 in favor of the [level] val. In OkHttp 4.3 it is
     * un-deprecated because Java callers can't chain when assigning Kotlin vals. (The getter remains
     * deprecated).
     */
    fun setLevel(level: Level) =
      apply {
        this.level = level
      }

    @JvmName("-deprecated_level")
    @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "level"),
      level = DeprecationLevel.ERROR,
    )
    fun getLevel(): Level = level

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
      var request = chain.request()

      val level = this.requestLevel(request)

      if (level == Level.NONE) {
        return chain.proceed(request)
      }

      val logHeaders = level == Level.BODY || level == Level.HEADERS

      val decompressionAlgorithms = findCompressionAlgorithms(chain)

      val connection = chain.connection()

      val requestBody = request.body
      var requestStartMessage =
        ("--> ${request.method} ${redactUrl(request.url)}${if (connection != null) " " + connection.protocol() else ""}")
      if (!logHeaders && level != Level.STREAMING && requestBody != null) {
        requestStartMessage += " (${requestBody.contentLength()}-byte body)"
      }
      logger.log(requestStartMessage)

      if (level != Level.BASIC) {
        request = logRequest(request, level, decompressionAlgorithms)
      }

      val startNs = System.nanoTime()
      var response: Response
      try {
        response = chain.proceed(request)
      } catch (e: Exception) {
        logger.log("<-- HTTP FAILED: $e")
        throw e
      }

      val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

      val responseBody = response.body
      val contentLength = responseBody.contentLength()
      val bodySize = if (contentLength != -1L) "$contentLength-byte" else "unknown-length"
      logger.log(
        buildString {
          append("<-- ${response.code}")
          if (response.message.isNotEmpty()) append(" ${response.message}")
          append(" ${redactUrl(response.request.url)} (${tookMs}ms")
          if (!logHeaders) append(", $bodySize body")
          append(")")
        },
      )

      return if (level != Level.BASIC) {
        logResponse(
          response,
          decompressionAlgorithms,
          startNs,
          contentLength,
        )
      } else {
        response
      }
    }

    private fun findCompressionAlgorithms(chain: Interceptor.Chain): List<DecompressionAlgorithm> {
      val compressionInterceptor =
        (chain.call() as? RealCall)?.client?.interceptors?.find { it is CompressionInterceptor } as? CompressionInterceptor

      return compressionInterceptor?.algorithms?.toList() ?: listOf(Gzip)
    }

    private fun logRequest(
      request: Request,
      level: Level,
      decompressionAlgorithms: List<DecompressionAlgorithm>,
    ): Request {
      val requestBody = request.body

      if (level == Level.HEADERS || level == Level.BODY) {
        val headers = request.headers
        if (requestBody != null) {
          // Request body headers are only present when installed as a network interceptor. When not
          // already present, force them to be included (if available) so their values are known.
          requestBody.contentType()?.let {
            if (headers["Content-Type"] == null) {
              logger.log("Content-Type: $it")
            }
          }
          if (requestBody.contentLength() != -1L) {
            if (headers["Content-Length"] == null) {
              logger.log("Content-Length: ${requestBody.contentLength()}")
            }
          }
        }

        for (i in 0 until headers.size) {
          logHeader(headers, i)
        }
      }

      if (level == Level.HEADERS || requestBody == null) {
        logger.log("--> END ${request.method}")
      } else if (level == Level.STREAMING) {
        return streamRequestBody(request)
      } else if (level == Level.BODY) {
        if (requestBody.isDuplex()) {
          logger.log("--> END ${request.method} (duplex request body omitted)")
        } else if (requestBody.isOneShot()) {
          logger.log("--> END ${request.method} (one-shot body omitted)")
        } else {
          val decompressor = findCompressionAlgorithm(request.headers, decompressionAlgorithms)

          if (decompressor == null) {
            logger.log("--> END ${request.method} (unknown encoded body omitted)")
            return request
          }

          var buffer = Buffer()
          requestBody.writeTo(buffer)

          var compressedLength: Long? = null
          if (decompressor != Identity) {
            compressedLength = buffer.size
            decompressor.decompress(buffer).use { uncompressedResponseBody ->
              buffer = Buffer()
              buffer.writeAll(uncompressedResponseBody)
            }
          }

          val charset: Charset = requestBody.contentType().charsetOrUtf8()

          logger.log("")
          if (!buffer.isProbablyUtf8()) {
            logger.log(
              "--> END ${request.method} (binary ${requestBody.contentLength()}-byte body omitted)",
            )
          } else if (compressedLength != null) {
            logger.log("--> END ${request.method} (${buffer.size}-byte, $compressedLength-${decompressor.encoding}-byte body)")
          } else {
            logger.log(buffer.readString(charset))
            logger.log("--> END ${request.method} (${requestBody.contentLength()}-byte body)")
          }
        }
      }

      return request
    }

    private fun logResponse(
      response: Response,
      decompressionAlgorithms: List<DecompressionAlgorithm>,
      startNs: Long,
      contentLength: Long,
    ): Response {
      val logBody = level == Level.BODY
      val logStreamed = level == Level.STREAMING
      val logHeaders = logBody || level == Level.HEADERS
      val responseBody = response.body

      if (logHeaders) {
        val headers = response.headers
        for (i in 0 until headers.size) {
          logHeader(headers, i)
        }
      }

      if (level == Level.HEADERS || !response.promisesBody()) {
        logger.log("<-- END HTTP")
      } else if (logStreamed) {
        return streamResponseBody(response, decompressionAlgorithms)
      } else if (logBody) {
        if (bodyIsEventStream(response)) {
          logger.log("<-- END HTTP (event-stream)")
        } else {
          val decompressor = findCompressionAlgorithm(response.headers, decompressionAlgorithms)

          if (decompressor == null) {
            logger.log("<-- END HTTP (unknown encoded body omitted)")
            return response
          }

          val source = responseBody.source()
          source.request(Long.MAX_VALUE) // Buffer the entire body.

          val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

          var buffer = source.buffer

          var compressedLength: Long? = null
          if (decompressor != Identity) {
            compressedLength = buffer.size
            GzipSource(buffer.clone()).use { gzippedResponseBody ->
              buffer = Buffer()
              buffer.writeAll(gzippedResponseBody)
            }
          }

          val charset: Charset = responseBody.contentType().charsetOrUtf8()

          if (!buffer.isProbablyUtf8()) {
            logger.log("")
            logger.log("<-- END HTTP (${totalMs}ms, binary ${buffer.size}-byte body omitted)")
            return response
          }

          if (contentLength != 0L) {
            logger.log("")
            logger.log(buffer.clone().readString(charset))
          }

          logger.log(
            buildString {
              append("<-- END HTTP (${totalMs}ms, ${buffer.size}-byte")
              if (compressedLength != null) append(", $compressedLength-${decompressor.encoding}-byte")
              append(" body)")
            },
          )
        }
      }
      return response
    }

    private fun streamRequestBody(request: Request): Request =
      request
        .newBuilder()
        .method(request.method, StreamingRequestBody(request, logger))
        .build()

    private fun streamResponseBody(
      response: Response,
      decompressionAlgorithms: List<DecompressionAlgorithm>,
    ): Response {
      val decompressor = findCompressionAlgorithm(response.headers, decompressionAlgorithms)

      if (decompressor == null) {
        logger.log("--> END HTTP (encoded streaming body omitted)")
        return response
      }

      return response
        .newBuilder()
        .body(StreamingResponseBody(response, decompressor, logger))
        .removeHeader("Content-Encoding")
        .removeHeader("Content-Length")
        .build()
    }

    internal fun redactUrl(url: HttpUrl): String {
      if (queryParamsNameToRedact.isEmpty() || url.querySize == 0) {
        return url.toString()
      }
      return url
        .newBuilder()
        .query(null)
        .apply {
          for (i in 0 until url.querySize) {
            val parameterName = url.queryParameterName(i)
            val newValue = if (parameterName in queryParamsNameToRedact) "██" else url.queryParameterValue(i)

            addEncodedQueryParameter(parameterName, newValue)
          }
        }.toString()
    }

    private fun logHeader(
      headers: Headers,
      i: Int,
    ) {
      val value = if (headers.name(i) in headersToRedact) "██" else headers.value(i)
      logger.log(headers.name(i) + ": " + value)
    }

    private fun findCompressionAlgorithm(
      headers: Headers,
      decompressionAlgorithms: List<DecompressionAlgorithm>,
    ): DecompressionAlgorithm? {
      val contentEncoding = headers["Content-Encoding"] ?: return Identity

      if (contentEncoding.equals("identity", ignoreCase = true)) return Identity

      return decompressionAlgorithms.find { contentEncoding.equals(it.encoding, ignoreCase = true) }
    }

    object Identity : DecompressionAlgorithm {
      override val encoding: String
        get() = "identity"

      override fun decompress(compressedSource: BufferedSource): Source = compressedSource
    }

    private fun bodyIsEventStream(response: Response): Boolean {
      val contentType = response.body.contentType()
      return contentType != null && contentType.type == "text" && contentType.subtype == "event-stream"
    }

    companion object
  }

private class StreamingRequestBody(
  val request: Request,
  val logger: HttpLoggingInterceptor.Logger,
) : RequestBody() {
  val body = request.body!!
  val charset: Charset = body.contentType().charsetOrUtf8()

  override fun contentType(): MediaType? = body.contentType()

  override fun writeTo(sink: BufferedSink) {
    val sink =
      object : ForwardingSink(sink) {
        override fun write(
          source: Buffer,
          byteCount: Long,
        ) {
          logger.log("> " + source.copy().readString(charset))
          super.write(source, byteCount)
        }

        override fun close() {
          super.close()
          logger.log("--> END ${request.method} (streaming body)")
        }
      }.buffer()

    body.writeTo(sink)
  }

  override fun contentLength(): Long = -1

  override fun isDuplex(): Boolean = body.isDuplex()

  override fun isOneShot(): Boolean = body.isOneShot()
}

private class StreamingResponseBody(
  val response: Response,
  val decompressor: DecompressionAlgorithm,
  val logger: HttpLoggingInterceptor.Logger,
) : ResponseBody() {
  val buffer = Buffer()
  val charset: Charset = response.body.contentType().charsetOrUtf8()

  override fun contentType(): MediaType? = response.body.contentType()

  override fun contentLength(): Long = -1

  override fun source(): BufferedSource {
    val decompressedSource = decompressor.decompress(response.body.source())

    val source: Source =
      object : ForwardingSource(decompressedSource) {
        override fun read(
          sink: Buffer,
          byteCount: Long,
        ): Long {
          val count = super.read(buffer, byteCount)

          if (count == -1L) {
            logger.log("<-- END HTTP (streaming body)")
          } else {
            logger.log("< " + buffer.copy().readString(charset))
            sink.write(buffer, count)
          }

          return count
        }
      }

    return source.buffer()
  }

  override fun close() {
    response.body.close()
    buffer.clear()
  }
}
