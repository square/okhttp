/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3

import okhttp3.internal.commonAsResponseBody
import okhttp3.internal.commonByteString
import okhttp3.internal.commonBytes
import okhttp3.internal.commonClose
import okhttp3.internal.commonToResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.Closeable
import okio.IOException

actual abstract class ResponseBody : Closeable {
  actual abstract fun contentType(): MediaType?

  actual abstract fun contentLength(): Long

  actual abstract fun source(): BufferedSource

  actual fun bytes() = commonBytes()

  actual fun byteString() = commonByteString()

  actual fun string(): String {
    val charset = contentType()?.parameter("charset") ?: "UTF-8"
    if (!charset.equals("UTF-8", ignoreCase = true)) {
      throw IOException("Unsupported encoding '$charset'")
    }
    return source().readUtf8()
  }

  actual override fun close() = commonClose()

  actual companion object {
    actual fun String.toResponseBody(contentType: MediaType?): ResponseBody {
      val buffer = Buffer().writeUtf8(this)
      // TODO ignore charset? fail on non utf-8? override?
      return buffer.asResponseBody(contentType, buffer.size)
    }

    actual fun ByteArray.toResponseBody(contentType: MediaType?): ResponseBody = commonToResponseBody(contentType)

    actual fun ByteString.toResponseBody(contentType: MediaType?): ResponseBody = commonToResponseBody(contentType)

    actual fun BufferedSource.asResponseBody(
      contentType: MediaType?,
      contentLength: Long
    ): ResponseBody = commonAsResponseBody(contentType, contentLength)
  }
}
