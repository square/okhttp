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

package okhttp3.internal

import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import okhttp3.CacheControl
import okhttp3.Challenge
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.HTTP_MOVED_PERM
import okhttp3.internal.http.HTTP_MOVED_TEMP
import okhttp3.internal.http.HTTP_MULT_CHOICE
import okhttp3.internal.http.HTTP_PROXY_AUTH
import okhttp3.internal.http.HTTP_SEE_OTHER
import okhttp3.internal.http.HTTP_UNAUTHORIZED
import okhttp3.internal.http.StatusLine.Companion.HTTP_PERM_REDIRECT
import okhttp3.internal.http.StatusLine.Companion.HTTP_TEMP_REDIRECT
import okio.Buffer
import okio.IOException

/**
 * Returns true if the code is in [200..300), which means the request was successfully received,
 * understood, and accepted.
 */
val Response.isSuccessful: Boolean
  get() = code in 200..299

fun Response.commonHeaders(name: String): List<String> = headers.values(name)

@JvmOverloads
fun Response.commonHeader(name: String, defaultValue: String?): String? = headers[name] ?: defaultValue

/**
 * Peeks up to [byteCount] bytes from the response body and returns them as a new response
 * body. If fewer than [byteCount] bytes are in the response body, the full response body is
 * returned. If more than [byteCount] bytes are in the response body, the returned value
 * will be truncated to [byteCount] bytes.
 *
 * It is an error to call this method after the body has been consumed.
 *
 * **Warning:** this method loads the requested bytes into memory. Most applications should set
 * a modest limit on `byteCount`, such as 1 MiB.
 */
@Throws(IOException::class)
fun Response.commonPeekBody(byteCount: Long): ResponseBody {
  val peeked = body!!.source().peek()
  val buffer = Buffer()
  peeked.request(byteCount)
  buffer.write(peeked, minOf(byteCount, peeked.buffer.size))
  return buffer.asResponseBody(body.contentType(), buffer.size)
}

fun Response.commonNewBuilder(): Response.Builder = Response.Builder(this)

/** Returns true if this response redirects to another resource. */
val Response.commonIsRedirect: Boolean
  get() = when (code) {
    HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE, HTTP_MOVED_PERM, HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> true
    else -> false
  }

/**
 * Returns the cache control directives for this response. This is never null, even if this
 * response contains no `Cache-Control` header.
 */
val Response.commonCacheControl: CacheControl
  get() {
    var result = lazyCacheControl
    if (result == null) {
      result = CacheControl.parse(headers)
      lazyCacheControl = result
    }
    return result
  }

/**
 * Closes the response body. Equivalent to `body().close()`.
 *
 * It is an error to close a response that is not eligible for a body. This includes the
 * responses returned from [cacheResponse], [networkResponse], and [priorResponse].
 */
fun Response.commonClose() {
  checkNotNull(body) { "response is not eligible for a body and must not be closed" }.close()
}

fun Response.commonToString(): String =
  "Response{protocol=$protocol, code=$code, message=$message, url=${request.url}}"

fun Response.Builder.commonRequest(request: Request) = apply {
  this.request = request
}

fun Response.Builder.commonProtocol(protocol: Protocol) = apply {
  this.protocol = protocol
}

fun Response.Builder.commonCode(code: Int) = apply {
  this.code = code
}

fun Response.Builder.commonMessage(message: String) = apply {
  this.message = message
}

/**
 * Sets the header named [name] to [value]. If this request already has any headers
 * with that name, they are all replaced.
 */
fun Response.Builder.commonHeader(name: String, value: String) = apply {
  headers[name] = value
}

/**
 * Adds a header with [name] to [value]. Prefer this method for multiply-valued
 * headers like "Set-Cookie".
 */
fun Response.Builder.commonAddHeader(name: String, value: String) = apply {
  headers.add(name, value)
}

/** Removes all headers named [name] on this builder. */
fun Response.Builder.commonRemoveHeader(name: String) = apply {
  headers.removeAll(name)
}

/** Removes all headers on this builder and adds [headers]. */
fun Response.Builder.commonHeaders(headers: Headers) = apply {
  this.headers = headers.newBuilder()
}

fun Response.Builder.commonBody(body: ResponseBody?) = apply {
  this.body = body
}

fun Response.Builder.commonNetworkResponse(networkResponse: Response?) = apply {
  checkSupportResponse("networkResponse", networkResponse)
  this.networkResponse = networkResponse
}

fun Response.Builder.commonCacheResponse(cacheResponse: Response?) = apply {
  checkSupportResponse("cacheResponse", cacheResponse)
  this.cacheResponse = cacheResponse
}

private fun checkSupportResponse(name: String, response: Response?) {
  response?.apply {
    require(body == null) { "$name.body != null" }
    require(networkResponse == null) { "$name.networkResponse != null" }
    require(cacheResponse == null) { "$name.cacheResponse != null" }
    require(priorResponse == null) { "$name.priorResponse != null" }
  }
}

fun Response.Builder.commonPriorResponse(priorResponse: Response?) = apply {
  checkPriorResponse(priorResponse)
  this.priorResponse = priorResponse
}

private fun checkPriorResponse(response: Response?) {
  response?.apply {
    require(body == null) { "priorResponse.body != null" }
  }
}
