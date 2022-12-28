/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3.ktor

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.util.flattenForEach
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody

suspend fun Request.toHttpRequestBuilder(): HttpRequestBuilder {
  return HttpRequestBuilder().apply {
    url.takeFrom(this@toHttpRequestBuilder.url.toString())

    headers {
      this@toHttpRequestBuilder.headers.forEach { (name, value) ->
        append(name, value)
      }
    }

    val requestBody = this@toHttpRequestBuilder.body
    if (requestBody != null) {
      setBody(requestBody.toHttpRequestBody())

      val contentType = requestBody.contentType()
      if (contentType != null) {
        contentType(contentType.toContentType())
      }
    }

    method = HttpMethod(this@toHttpRequestBuilder.method)

    // TODO other fields, cacheControl
  }
}

suspend fun HttpResponse.toResponse(request: Request): Response {
  return Response.Builder()
    .apply {
      code(this@toResponse.status.value)
      message(this@toResponse.status.description)
      request(request)

      // TODO how to determine correct type
      protocol(Protocol.HTTP_1_1)

      this@toResponse.headers.flattenForEach { name, value ->
        addHeader(name, value)
      }

      body(this@toResponse.toHttpResponseBody())
    }
    .build()
}

fun ContentType.toMediaType(): MediaType = this.toString().toMediaType()

fun MediaType.toContentType(): ContentType = ContentType.parse(toString())

expect suspend fun HttpResponse.toHttpResponseBody(): ResponseBody

expect suspend fun RequestBody.toHttpRequestBody(): Any

