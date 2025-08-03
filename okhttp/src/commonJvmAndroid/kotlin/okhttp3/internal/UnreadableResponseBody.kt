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

import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

internal class UnreadableResponseBody(
  private val mediaType: MediaType?,
  private val contentLength: Long,
) : ResponseBody(),
  Source {
  override fun contentType() = mediaType

  override fun contentLength() = contentLength

  override fun source() = buffer()

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long =
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

  override fun timeout() = Timeout.NONE

  override fun close() {
  }
}

fun Response.stripBody(): Response =
  newBuilder()
    .body(UnreadableResponseBody(body.contentType(), body.contentLength()))
    .build()
