/*
 * Copyright (C) 2011 Google Inc.
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

package okhttp3.mockwebserver

import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.TlsVersion
import okio.Buffer
import java.io.IOException
import java.net.Inet6Address
import java.net.Socket
import javax.net.ssl.SSLSocket

/** An HTTP request that came into the mock web server. */
class RecordedRequest(
  @get:JvmName("requestLine") val requestLine: String,

  /** All headers. */
  @get:JvmName("headers") val headers: Headers,

  /**
   * The sizes of the chunks of this request's body, or an empty list if the request's body
   * was empty or unchunked.
   */
  @get:JvmName("chunkSizes") val chunkSizes: List<Int>,

  /** The total size of the body of this POST request (before truncation).*/
  @get:JvmName("bodySize") val bodySize: Long,

  /** The body of this POST request. This may be truncated. */
  @get:JvmName("body") val body: Buffer,

  /**
   * The index of this request on its HTTP connection. Since a single HTTP connection may
   * serve multiple requests, each request is assigned its own sequence number.
   */
  @get:JvmName("sequenceNumber") val sequenceNumber: Int,

  internal val socket: Socket
) {
  @get:JvmName("method") val method: String?
  @get:JvmName("path") val path: String?

  /**
   * The TLS handshake of the connection that carried this request, or null if the request
   * was received without TLS.
   */
  @get:JvmName("handshake") val handshake: Handshake?
  @get:JvmName("requestUrl") val requestUrl: HttpUrl?

  val utf8Body: String
    @Deprecated(
        message = "Use body().readUtf8() instead.",
        replaceWith = ReplaceWith(expression = "body.readUtf8"),
        level = DeprecationLevel.WARNING)
    get() = body.readUtf8()

  /** Returns the connection's TLS version or null if the connection doesn't use SSL. */
  @get:JvmName("tlsVersion") val tlsVersion: TlsVersion?
    get() = handshake?.tlsVersion

  init {
    if (socket is SSLSocket) {
      try {
        this.handshake = socket.session.handshake()
      } catch (e: IOException) {
        throw IllegalArgumentException(e)
      }
    } else {
      this.handshake = null
    }

    if (requestLine.isNotEmpty()) {
      val methodEnd = requestLine.indexOf(' ')
      val pathEnd = requestLine.indexOf(' ', methodEnd + 1)
      this.method = requestLine.substring(0, methodEnd)
      var path = requestLine.substring(methodEnd + 1, pathEnd)
      if (!path.startsWith("/")) {
        path = "/"
      }
      this.path = path

      val scheme = if (socket is SSLSocket) "https" else "http"
      val inetAddress = socket.localAddress

      var hostname = inetAddress.hostName
      if (inetAddress is Inet6Address) {
        hostname = "[$hostname]"
      }

      val localPort = socket.localPort
      // Allow null in failure case to allow for testing bad requests
      this.requestUrl = "$scheme://$hostname:$localPort$path".toHttpUrlOrNull()
    } else {
      this.requestUrl = null
      this.method = null
      this.path = null
    }
  }

  /** Returns the first header named [name], or null if no such header exists. */
  fun getHeader(name: String): String? {
    return headers.values(name).firstOrNull()
  }

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "requestLine"),
      level = DeprecationLevel.WARNING)
  fun getRequestLine(): String = requestLine

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "headers"),
      level = DeprecationLevel.WARNING)
  fun getHeaders(): Headers = headers

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "chunkSizes"),
      level = DeprecationLevel.WARNING)
  fun getChunkSizes(): List<Int> = chunkSizes

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "bodySize"),
      level = DeprecationLevel.WARNING)
  fun getBodySize(): Long = bodySize

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "body"),
      level = DeprecationLevel.WARNING)
  fun getBody(): Buffer = body

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "sequenceNumber"),
      level = DeprecationLevel.WARNING)
  fun getSequenceNumber(): Int = sequenceNumber

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "method"),
      level = DeprecationLevel.WARNING)
  fun getMethod(): String? = method

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "path"),
      level = DeprecationLevel.WARNING)
  fun getPath(): String? = path

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "handshake"),
      level = DeprecationLevel.WARNING)
  fun getHandshake(): Handshake? = handshake

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "requestUrl"),
      level = DeprecationLevel.WARNING)
  fun getRequestUrl(): HttpUrl? = requestUrl

  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "tlsVersion"),
      level = DeprecationLevel.WARNING)
  fun getTlsVersion(): TlsVersion? = tlsVersion

  override fun toString(): String {
    return requestLine
  }
}
