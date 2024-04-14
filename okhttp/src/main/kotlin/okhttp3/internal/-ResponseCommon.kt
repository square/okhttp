/*
 * Copyright (C) 2022 Square, Inc.
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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import java.net.HttpURLConnection.HTTP_MOVED_PERM
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_SEE_OTHER
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.HTTP_PERM_REDIRECT
import okhttp3.internal.http.HTTP_TEMP_REDIRECT
import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout
import okio.buffer

internal class UnreadableResponseBody(
  private val mediaType: MediaType?,
  private val contentLength: Long,
) : ResponseBody(), Source {
  override fun contentType() = mediaType

  override fun contentLength() = contentLength

  override fun source() = buffer()

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    throw IllegalStateException(
      """
      |Unreadable ResponseBody! These Response objects have bodies that are stripped:
      | * Response.cacheResponse
      | * Response.networkResponse
      | * Response.priorResponse
      | * EventSourceListener
      | * WebSocketListener
      |(It is safe to call contentType() and contentLength() on these response bodies.)
      """.trimMargin(),
    )
  }

  override fun timeout() = Timeout.NONE

  override fun close() {
  }
}

fun Response.stripBody(): Response {
  return newBuilder()
    .body(UnreadableResponseBody(body.contentType(), body.contentLength()))
    .build()
}

val Response.commonIsSuccessful: Boolean
  get() = code in 200..299

fun Response.commonHeaders(name: String): List<String> = headers.values(name)

@JvmOverloads
fun Response.commonHeader(
  name: String,
  defaultValue: String?,
): String? = headers[name] ?: defaultValue

@Throws(IOException::class)
fun Response.commonPeekBody(byteCount: Long): ResponseBody {
  val peeked = body.source().peek()
  val buffer = Buffer()
  peeked.request(byteCount)
  buffer.write(peeked, minOf(byteCount, peeked.buffer.size))
  return buffer.asResponseBody(body.contentType(), buffer.size)
}

fun Response.commonNewBuilder(): Response.Builder = Response.Builder(this)

val Response.commonIsRedirect: Boolean
  get() =
    when (code) {
      HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE, HTTP_MOVED_PERM, HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> true
      else -> false
    }

val Response.commonCacheControl: CacheControl
  get() {
    var result = lazyCacheControl
    if (result == null) {
      result = CacheControl.parse(headers)
      lazyCacheControl = result
    }
    return result
  }

fun Response.commonClose() {
  body.close()
}

fun Response.commonToString(): String = "Response{protocol=$protocol, code=$code, message=$message, url=${request.url}}"

fun Response.Builder.commonRequest(request: Request) =
  apply {
    this.request = request
  }

fun Response.Builder.commonProtocol(protocol: Protocol) =
  apply {
    this.protocol = protocol
  }

fun Response.Builder.commonCode(code: Int) =
  apply {
    this.code = code
  }

fun Response.Builder.commonMessage(message: String) =
  apply {
    this.message = message
  }

fun Response.Builder.commonHeader(
  name: String,
  value: String,
) = apply {
  headers[name] = value
}

fun Response.Builder.commonAddHeader(
  name: String,
  value: String,
) = apply {
  headers.add(name, value)
}

fun Response.Builder.commonRemoveHeader(name: String) =
  apply {
    headers.removeAll(name)
  }

fun Response.Builder.commonHeaders(headers: Headers) =
  apply {
    this.headers = headers.newBuilder()
  }

fun Response.Builder.commonTrailers(trailersFn: (() -> Headers)) =
  apply {
    this.trailersFn = trailersFn
  }

fun Response.Builder.commonBody(body: ResponseBody) =
  apply {
    this.body = body
  }

fun Response.Builder.commonNetworkResponse(networkResponse: Response?) =
  apply {
    checkSupportResponse("networkResponse", networkResponse)
    this.networkResponse = networkResponse
  }

fun Response.Builder.commonCacheResponse(cacheResponse: Response?) =
  apply {
    checkSupportResponse("cacheResponse", cacheResponse)
    this.cacheResponse = cacheResponse
  }

private fun checkSupportResponse(
  name: String,
  response: Response?,
) {
  response?.apply {
    require(networkResponse == null) { "$name.networkResponse != null" }
    require(cacheResponse == null) { "$name.cacheResponse != null" }
    require(priorResponse == null) { "$name.priorResponse != null" }
  }
}

fun Response.Builder.commonPriorResponse(priorResponse: Response?) =
  apply {
    this.priorResponse = priorResponse
  }
