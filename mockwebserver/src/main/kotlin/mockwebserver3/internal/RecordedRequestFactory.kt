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
import java.net.Socket
import javax.net.ssl.SSLSocket
import mockwebserver3.RecordedRequest
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.platform.Platform
import okio.Buffer

internal fun RecordedRequest(
  requestLine: String,
  headers: Headers,
  chunkSizes: List<Int>,
  bodySize: Long,
  body: Buffer,
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

  val requestUrl: HttpUrl?
  val method: String?
  val path: String?
  if (requestLine.isNotEmpty()) {
    val methodEnd = requestLine.indexOf(' ')
    val urlEnd = requestLine.indexOf(' ', methodEnd + 1)
    method = requestLine.substring(0, methodEnd)
    var urlPart = requestLine.substring(methodEnd + 1, urlEnd)
    if (!urlPart.startsWith("/")) {
      urlPart = "/"
    }
    path = urlPart

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
    requestUrl = "$scheme://$hostAndPort$path".toHttpUrlOrNull()
  } else {
    requestUrl = null
    method = null
    path = null
  }

  return RecordedRequest(
    sequenceNumber = sequenceNumber,
    handshake = handshake,
    handshakeServerNames = handshakeServerNames,
    requestUrl = requestUrl,
    requestLine = requestLine,
    method = method,
    path = path,
    headers = headers,
    body = body.readByteString(),
    bodySize = bodySize,
    chunkSizes = chunkSizes,
    failure = failure,
  )
}
