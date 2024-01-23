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

package mockwebserver3

import java.io.IOException
import java.net.Inet6Address
import java.net.Socket
import javax.net.ssl.SSLSocket
import okhttp3.ExperimentalOkHttpApi
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.platform.Platform
import okio.Buffer

/** An HTTP request that came into the mock web server. */
@ExperimentalOkHttpApi
class RecordedRequest(
  val requestLine: String,
  /** All headers. */
  val headers: Headers,
  /**
   * The sizes of the chunks of this request's body, or an empty list if the request's body
   * was empty or unchunked.
   */
  val chunkSizes: List<Int>,
  /** The total size of the body of this POST request (before truncation).*/
  val bodySize: Long,
  /** The body of this POST request. This may be truncated. */
  val body: Buffer,
  /**
   * The index of this request on its HTTP connection. Since a single HTTP connection may serve
   * multiple requests, each request is assigned its own sequence number.
   */
  val sequenceNumber: Int,
  socket: Socket,
  /**
   * The failure MockWebServer recorded when attempting to decode this request. If, for example,
   * the inbound request was truncated, this exception will be non-null.
   */
  val failure: IOException? = null,
) {
  val method: String?
  val path: String?

  /**
   * The TLS handshake of the connection that carried this request, or null if the request was
   * received without TLS.
   */
  val handshake: Handshake?
  val requestUrl: HttpUrl?

  /**
   * Returns the name of the server the client requested via the SNI (Server Name Indication)
   * attribute in the TLS handshake. Unlike the rest of the HTTP exchange, this name is sent in
   * cleartext and may be monitored or blocked by a proxy or other middlebox.
   */
  val handshakeServerNames: List<String>

  init {
    if (socket is SSLSocket) {
      try {
        this.handshake = socket.session.handshake()
        this.handshakeServerNames = Platform.get().getHandshakeServerNames(socket)
      } catch (e: IOException) {
        throw IllegalArgumentException(e)
      }
    } else {
      this.handshake = null
      this.handshakeServerNames = listOf()
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
      val localPort = socket.localPort
      val hostAndPort =
        headers[":authority"]
          ?: headers["Host"]
          ?: when (val inetAddress = socket.localAddress) {
            is Inet6Address -> "[${inetAddress.hostAddress}]:$localPort"
            else -> "${inetAddress.hostAddress}:$localPort"
          }

      // Allow null in failure case to allow for testing bad requests
      this.requestUrl = "$scheme://$hostAndPort$path".toHttpUrlOrNull()
    } else {
      this.requestUrl = null
      this.method = null
      this.path = null
    }
  }

  override fun toString(): String = requestLine
}
