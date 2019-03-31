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

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.http.HttpHeaders
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.INFO
import okio.Buffer
import okio.GzipSource
import java.io.EOFException
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * An OkHttp interceptor which logs request and response information. Can be applied as an
 * [application interceptor][OkHttpClient.interceptors] or as a [OkHttpClient.networkInterceptors].
 *
 * The format of the logs created by this class should not be considered stable and may
 * change slightly between releases. If you need a stable logging format, use your own interceptor.
 */
class HttpLoggingInterceptor @JvmOverloads constructor(
  private val logger: Logger = Logger.DEFAULT
) : Interceptor {

  @Volatile private var headersToRedact = emptySet<String>()

  @Volatile private var level = Level.NONE

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
    BODY
  }

  interface Logger {
    fun log(message: String)

    companion object {
      /** A [Logger] defaults output appropriate for the current platform.  */
      @JvmField
      val DEFAULT: Logger = object : Logger {
        override fun log(message: String) {
          Platform.get().log(INFO, message, null)
        }
      }
    }
  }

  fun redactHeader(name: String) {
    val newHeadersToRedact = TreeSet(String.CASE_INSENSITIVE_ORDER)
    newHeadersToRedact.addAll(headersToRedact)
    newHeadersToRedact.add(name)
    headersToRedact = newHeadersToRedact
  }

  /** Change the level at which this interceptor logs.  */
  fun setLevel(level: Level) = apply {
    this.level = level
  }

  fun getLevel(): Level = level

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val level = this.level

    val request = chain.request()
    if (level == Level.NONE) {
      return chain.proceed(request)
    }

    val logBody = level == Level.BODY
    val logHeaders = logBody || level == Level.HEADERS

    val requestBody = request.body()

    val connection = chain.connection()
    var requestStartMessage = ("--> ${request.method()} ${request.url()}${if (connection != null) " " + connection.protocol() else ""}")
    if (!logHeaders && requestBody != null) {
      requestStartMessage += " (${requestBody.contentLength()}-byte body)"
    }
    logger.log(requestStartMessage)

    if (logHeaders) {
      if (requestBody != null) {
        // Request body headers are only present when installed as a network interceptor. Force
        // them to be included (when available) so there values are known.
        requestBody.contentType()?.let {
          logger.log("Content-Type: $it")
        }
        if (requestBody.contentLength() != -1L) {
          logger.log("Content-Length: ${requestBody.contentLength()}")
        }
      }

      val headers = request.headers()
      var i = 0
      val count = headers.size()
      while (i < count) {
        val name = headers.name(i)
        // Skip headers from the request body as they are explicitly logged above.
        if (!"Content-Type".equals(name, ignoreCase = true) && !"Content-Length".equals(name,
                ignoreCase = true)) {
          logHeader(headers, i)
        }
        i++
      }

      if (!logBody || requestBody == null) {
        logger.log("--> END ${request.method()}")
      } else if (bodyHasUnknownEncoding(request.headers())) {
        logger.log("--> END ${request.method()} (encoded body omitted)")
      } else if (requestBody.isDuplex) {
        logger.log("--> END ${request.method()} (duplex request body omitted)")
      } else {
        val buffer = Buffer()
        requestBody.writeTo(buffer)

        val contentType = requestBody.contentType()
        val charset: Charset = contentType?.charset(UTF8) ?: UTF8

        logger.log("")
        if (isPlaintext(buffer)) {
          logger.log(buffer.readString(charset))
          logger.log("--> END ${request.method()} (${requestBody.contentLength()}-byte body)")
        } else {
          logger.log(
              "--> END ${request.method()} (binary ${requestBody.contentLength()}-byte body omitted)")
        }
      }
    }

    val startNs = System.nanoTime()
    val response: Response
    try {
      response = chain.proceed(request)
    } catch (e: Exception) {
      logger.log("<-- HTTP FAILED: $e")
      throw e
    }

    val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    val responseBody = response.body()!!
    val contentLength = responseBody.contentLength()
    val bodySize = if (contentLength != -1L) "$contentLength-byte" else "unknown-length"
    logger.log(
        "<-- ${response.code()}${if (response.message().isEmpty()) "" else ' ' + response.message()} ${response.request().url()} (${tookMs}ms${if (!logHeaders) ", $bodySize body" else ""})")

    if (logHeaders) {
      val headers = response.headers()
      for (i in 0 until headers.size()) {
        logHeader(headers, i)
      }

      if (!logBody || !HttpHeaders.hasBody(response)) {
        logger.log("<-- END HTTP")
      } else if (bodyHasUnknownEncoding(response.headers())) {
        logger.log("<-- END HTTP (encoded body omitted)")
      } else {
        val source = responseBody.source()
        source.request(java.lang.Long.MAX_VALUE) // Buffer the entire body.
        var buffer = source.buffer

        var gzippedLength: Long? = null
        if ("gzip".equals(headers["Content-Encoding"], ignoreCase = true)) {
          gzippedLength = buffer.size
          GzipSource(buffer.clone()).use { gzippedResponseBody ->
            buffer = Buffer()
            buffer.writeAll(gzippedResponseBody)
          }
        }

        val contentType = responseBody.contentType()
        val charset: Charset = contentType?.charset(UTF8) ?: UTF8

        if (!isPlaintext(buffer)) {
          logger.log("")
          logger.log("<-- END HTTP (binary ${buffer.size}-byte body omitted)")
          return response
        }

        if (contentLength != 0L) {
          logger.log("")
          logger.log(buffer.clone().readString(charset))
        }

        if (gzippedLength != null) {
          logger.log("<-- END HTTP (${buffer.size}-byte, $gzippedLength-gzipped-byte body)")
        } else {
          logger.log("<-- END HTTP (${buffer.size}-byte body)")
        }
      }
    }

    return response
  }

  private fun logHeader(headers: Headers, i: Int) {
    val value = if (headersToRedact.contains(headers.name(i))) "██" else headers.value(i)
    logger.log(headers.name(i) + ": " + value)
  }

  companion object {
    private val UTF8 = StandardCharsets.UTF_8

    /**
     * Returns true if the body in question probably contains human readable text. Uses a small sample
     * of code points to detect unicode control characters commonly used in binary file signatures.
     */
    @JvmStatic
    fun isPlaintext(buffer: Buffer): Boolean {
      try {
        val prefix = Buffer()
        val byteCount = if (buffer.size < 64) buffer.size else 64
        buffer.copyTo(prefix, 0, byteCount)
        for (i in 0..15) {
          if (prefix.exhausted()) {
            break
          }
          val codePoint = prefix.readUtf8CodePoint()
          if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
            return false
          }
        }
        return true
      } catch (e: EOFException) {
        return false // Truncated UTF-8 sequence.
      }
    }

    private fun bodyHasUnknownEncoding(headers: Headers): Boolean {
      val contentEncoding = headers["Content-Encoding"]
      return (contentEncoding != null
          && !contentEncoding.equals("identity", ignoreCase = true)
          && !contentEncoding.equals("gzip", ignoreCase = true))
    }
  }
}
