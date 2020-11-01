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
package okhttp3.brotli.internal

import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.promisesBody
import okio.GzipSource
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream

fun uncompress(response: Response): Response {
  if (!response.promisesBody()) {
    return response
  }
  val body = response.body ?: return response
  val encoding = response.header("Content-Encoding") ?: return response

  val decompressedSource = when {
    encoding.equals("br", ignoreCase = true) ->
      BrotliInputStream(body.source().inputStream()).source().buffer()
    encoding.equals("gzip", ignoreCase = true) ->
      GzipSource(body.source()).buffer()
    else -> return response
  }

  return response.newBuilder()
    .removeHeader("Content-Encoding")
    .removeHeader("Content-Length")
    .body(decompressedSource.asResponseBody(body.contentType(), -1))
    .build()
}
