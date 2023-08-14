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

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.commonContentLength
import okhttp3.internal.commonIsDuplex
import okhttp3.internal.commonIsOneShot
import okhttp3.internal.commonToRequestBody
import okio.BufferedSink
import okio.ByteString
import okio.internal.commonAsUtf8ToByteArray

actual abstract class RequestBody {
  actual abstract fun contentType(): MediaType?

  actual open fun contentLength(): Long = commonContentLength()

  actual abstract fun writeTo(sink: BufferedSink)

  actual open fun isDuplex(): Boolean = commonIsDuplex()

  actual open fun isOneShot(): Boolean = commonIsOneShot()

  actual companion object {
    actual fun String.toRequestBody(contentType: MediaType?): RequestBody {
      val bytes = commonAsUtf8ToByteArray()

      val resolvedContentType = if (contentType != null && contentType.parameter("charset") == null) {
        "$contentType; charset=utf-8".toMediaTypeOrNull()
      } else {
        contentType
      }

      return bytes.toRequestBody(resolvedContentType, 0, bytes.size)
    }

    actual fun ByteString.toRequestBody(contentType: MediaType?): RequestBody =
      commonToRequestBody(contentType)

    actual fun ByteArray.toRequestBody(
      contentType: MediaType?,
      offset: Int,
      byteCount: Int
    ): RequestBody = commonToRequestBody(contentType, offset, byteCount)
  }
}
