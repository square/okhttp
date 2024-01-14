/*
 * Copyright (C) 2021 Square, Inc.
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

package okhttp3.curl.internal

import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.curl.Main
import okhttp3.internal.http.StatusLine
import okio.sink

internal fun Main.commonCreateRequest(): Request {
  val request = Request.Builder()

  val requestMethod = method ?: if (data != null) "POST" else "GET"

  val url = url ?: throw IOException("No url provided")

  request.url(url)

  data?.let {
    request.method(requestMethod, it.toRequestBody(mediaType()))
  }

  for (header in headers.orEmpty()) {
    val parts = header.split(':', limit = 2)
    if (!isSpecialHeader(parts[0])) {
      request.header(parts[0], parts[1])
    }
  }
  referer?.let {
    request.header("Referer", it)
  }
  request.header("User-Agent", userAgent)

  return request.build()
}

private fun Main.mediaType(): MediaType? {
  val mimeType =
    headers?.let {
      for (header in it) {
        val parts = header.split(':', limit = 2)
        if ("Content-Type".equals(parts[0], ignoreCase = true)) {
          return@let parts[1].trim()
        }
      }
      return@let null
    } ?: "application/x-www-form-urlencoded"

  return mimeType.toMediaTypeOrNull()
}

private fun isSpecialHeader(s: String): Boolean {
  return s.equals("Content-Type", ignoreCase = true)
}

fun Main.commonRun() {
  client = createClient()
  val request = createRequest()

  try {
    val response = client!!.newCall(request).execute()
    if (showHeaders) {
      println(StatusLine.get(response))
      val headers = response.headers
      for ((name, value) in headers) {
        println("$name: $value")
      }
      println()
    }

    // Stream the response to the System.out as it is returned from the server.
    val out = System.out.sink()
    val source = response.body.source()
    while (!source.exhausted()) {
      out.write(source.buffer, source.buffer.size)
      out.flush()
    }

    response.body.close()
  } catch (e: IOException) {
    e.printStackTrace()
  } finally {
    close()
  }
}
