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

import kotlin.reflect.KClass
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.internal.http.HttpMethod

fun Request.commonHeader(name: String): String? = headers[name]

fun Request.commonHeaders(name: String): List<String> = headers.values(name)

fun Request.commonNewBuilder(): Request.Builder = Request.Builder(this)

fun Request.commonCacheControl(): CacheControl {
  var result = lazyCacheControl
  if (result == null) {
    result = CacheControl.parse(headers)
    lazyCacheControl = result
  }
  return result
}

internal fun canonicalUrl(url: String): String {
  // Silently replace web socket URLs with HTTP URLs.
  return when {
    url.startsWith("ws:", ignoreCase = true) -> {
      "http:${url.substring(3)}"
    }
    url.startsWith("wss:", ignoreCase = true) -> {
      "https:${url.substring(4)}"
    }
    else -> url
  }
}

fun Request.Builder.commonHeader(
  name: String,
  value: String,
) = apply {
  headers[name] = value
}

fun Request.Builder.commonAddHeader(
  name: String,
  value: String,
) = apply {
  headers.add(name, value)
}

fun Request.Builder.commonRemoveHeader(name: String) =
  apply {
    headers.removeAll(name)
  }

fun Request.Builder.commonHeaders(headers: Headers) =
  apply {
    this.headers = headers.newBuilder()
  }

fun Request.Builder.commonCacheControl(cacheControl: CacheControl): Request.Builder {
  val value = cacheControl.toString()
  return when {
    value.isEmpty() -> removeHeader("Cache-Control")
    else -> header("Cache-Control", value)
  }
}

fun Request.Builder.commonGet(): Request.Builder = method("GET", null)

fun Request.Builder.commonHead(): Request.Builder = method("HEAD", null)

fun Request.Builder.commonPost(body: RequestBody): Request.Builder = method("POST", body)

fun Request.Builder.commonDelete(body: RequestBody?): Request.Builder = method("DELETE", body)

fun Request.Builder.commonPut(body: RequestBody): Request.Builder = method("PUT", body)

fun Request.Builder.commonPatch(body: RequestBody): Request.Builder = method("PATCH", body)

fun Request.Builder.commonMethod(
  method: String,
  body: RequestBody?,
): Request.Builder =
  apply {
    require(method.isNotEmpty()) {
      "method.isEmpty() == true"
    }
    if (body == null) {
      require(!HttpMethod.requiresRequestBody(method)) {
        "method $method must have a request body."
      }
    } else {
      require(HttpMethod.permitsRequestBody(method)) {
        "method $method must not have a request body."
      }
    }
    this.method = method
    this.body = body
  }

fun <T : Any> Request.Builder.commonTag(
  type: KClass<T>,
  tag: T?,
) = apply {
  if (tag == null) {
    if (tags.isNotEmpty()) {
      (tags as MutableMap).remove(type)
    }
  } else {
    val mutableTags: MutableMap<KClass<*>, Any> =
      when {
        tags.isEmpty() -> mutableMapOf<KClass<*>, Any>().also { tags = it }
        else -> tags as MutableMap<KClass<*>, Any>
      }
    mutableTags[type] = tag
  }
}

fun Request.commonToString(): String =
  buildString {
    append("Request{method=")
    append(method)
    append(", url=")
    append(url)
    if (headers.size != 0) {
      append(", headers=[")
      headers.forEachIndexed { index, (name, value) ->
        if (index > 0) {
          append(", ")
        }
        append(name)
        append(':')
        append(if (isSensitiveHeader(name)) "██" else value)
      }
      append(']')
    }
    if (tags.isNotEmpty()) {
      append(", tags=")
      append(tags)
    }
    append('}')
  }
