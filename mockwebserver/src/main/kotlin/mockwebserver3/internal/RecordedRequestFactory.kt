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
package mockwebserver3.internal

import java.io.IOException
import java.net.Inet6Address
import java.net.ProtocolException
import java.net.Socket
import javax.net.ssl.SSLSocket
import mockwebserver3.RecordedRequest
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.platform.Platform
import okio.ByteString

internal fun RecordedRequest(
  requestLine: RequestLine,
  headers: Headers,
  chunkSizes: List<Int>,
  bodySize: Long,
  body: ByteString?,
  sequenceNumber: Int,
  socket: Socket,
  failure: IOException? = null,
): RecordedRequest {
  val handshake: Handshake?
  val handshakeServerNames: List<String>
  if (socket is SSLSocket) {
    try {
      handshake = socket.session.handshake()
      handshakeServerNames = Platform.get().getHandshakeServerNames(socket)
    } catch (e: IOException) {
      throw IllegalArgumentException(e)
    }
  } else {
    handshake = null
    handshakeServerNames = listOf()
  }

  val requestUrl =
    when (requestLine.method) {
      "CONNECT" -> "${socket.scheme}://${requestLine.target}/".toHttpUrlOrNull()
      else -> null
    }
      ?: requestLine.target.toHttpUrlOrNull()
      ?: requestUrl(socket, requestLine, headers)

  return RecordedRequest(
    sequenceNumber = sequenceNumber,
    handshake = handshake,
    handshakeServerNames = handshakeServerNames,
    method = requestLine.method,
    target = requestLine.target,
    version = requestLine.version,
    url = requestUrl,
    headers = headers,
    body = body,
    bodySize = bodySize,
    chunkSizes = chunkSizes,
    failure = failure,
  )
}

internal fun decodeRequestLine(requestLine: String?): RequestLine {
  val parts =
    when {
      requestLine != null -> requestLine.split(' ', limit = 3)
      else -> return DEFAULT_REQUEST_LINE
    }

  if (parts.size != 3) {
    throw ProtocolException("unexpected request line: $requestLine")
  }

  return RequestLine(
    method = parts[0],
    target = parts[1],
    version = parts[2],
  )
}

internal class RequestLine(
  val method: String,
  val target: String,
  val version: String,
) {
  override fun toString() = "$method $target $version"
}

internal val DEFAULT_REQUEST_LINE =
  RequestLine(
    method = "GET",
    target = "/",
    version = "HTTP/1.1",
  )

private val Socket.scheme: String
  get() =
    when (this) {
      is SSLSocket -> "https"
      else -> "http"
    }

private fun requestUrl(
  socket: Socket,
  requestLine: RequestLine,
  headers: Headers,
): HttpUrl {
  val hostAndPort =
    headers[":authority"]
      ?: headers["Host"]
      ?: when (val inetAddress = socket.localAddress) {
        is Inet6Address -> "[${inetAddress.hostAddress}]:${socket.localPort}"
        else -> "${inetAddress.hostAddress}:${socket.localPort}"
      }

  // For OPTIONS, the request target may be a '*', like 'OPTIONS * HTTP/1.1'.
  val path =
    when {
      requestLine.method == "OPTIONS" && requestLine.target == "*" -> "/"
      else -> requestLine.target
    }

  return "${socket.scheme}://$hostAndPort$path".toHttpUrl()
}
