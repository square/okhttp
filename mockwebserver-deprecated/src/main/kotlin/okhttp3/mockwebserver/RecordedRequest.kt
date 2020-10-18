/*
 * Copyright (C) 2020 Square, Inc.
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

class RecordedRequest {
  val requestLine: String
  val headers: Headers
  val chunkSizes: List<Int>
  val bodySize: Long
  val body: Buffer
  val sequenceNumber: Int
  val failure: IOException?
  val method: String?
  val path: String?
  val handshake: Handshake?
  val requestUrl: HttpUrl?

  @get:JvmName("-deprecated_utf8Body")
  @Deprecated(
      message = "Use body.readUtf8()",
      replaceWith = ReplaceWith("body.readUtf8()"),
      level = DeprecationLevel.ERROR)
  val utf8Body: String
    get() = body.readUtf8()

  val tlsVersion: TlsVersion?
    get() = handshake?.tlsVersion

  internal constructor(
    requestLine: String,
    headers: Headers,
    chunkSizes: List<Int>,
    bodySize: Long,
    body: Buffer,
    sequenceNumber: Int,
    failure: IOException?,
    method: String?,
    path: String?,
    handshake: Handshake?,
    requestUrl: HttpUrl?
  ) {
    this.requestLine = requestLine
    this.headers = headers
    this.chunkSizes = chunkSizes
    this.bodySize = bodySize
    this.body = body
    this.sequenceNumber = sequenceNumber
    this.failure = failure
    this.method = method
    this.path = path
    this.handshake = handshake
    this.requestUrl = requestUrl
  }

  @JvmOverloads
  constructor(
    requestLine: String,
    headers: Headers,
    chunkSizes: List<Int>,
    bodySize: Long,
    body: Buffer,
    sequenceNumber: Int,
    socket: Socket,
    failure: IOException? = null
  ) {
    this.requestLine = requestLine;
    this.headers = headers
    this.chunkSizes = chunkSizes
    this.bodySize = bodySize
    this.body = body
    this.sequenceNumber = sequenceNumber
    this.failure = failure

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
      if (inetAddress is Inet6Address && hostname.contains(':')) {
        // hostname is likely some form representing the IPv6 bytes
        // 2001:0db8:85a3:0000:0000:8a2e:0370:7334
        // 2001:db8:85a3::8a2e:370:7334
        // ::1
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

  @Deprecated(
      message = "Use body.readUtf8()",
      replaceWith = ReplaceWith("body.readUtf8()"),
      level = DeprecationLevel.WARNING)
  fun getUtf8Body(): String = body.readUtf8()

  fun getHeader(name: String): String? = headers.values(name).firstOrNull()

  override fun toString(): String = requestLine
}
