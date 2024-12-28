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

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ByteString

fun ByteArray.commonToRequestBody(
  contentType: MediaType?,
  offset: Int,
  byteCount: Int,
): RequestBody {
  checkOffsetAndCount(size.toLong(), offset.toLong(), byteCount.toLong())
  return object : RequestBody() {
    override fun contentType() = contentType

    override fun contentLength() = byteCount.toLong()

    override fun writeTo(sink: BufferedSink) {
      sink.write(this@commonToRequestBody, offset, byteCount)
    }
  }
}

@Suppress("unused")
fun RequestBody.commonContentLength(): Long = -1L

@Suppress("unused")
fun RequestBody.commonIsDuplex(): Boolean = false

@Suppress("unused")
fun RequestBody.commonIsOneShot(): Boolean = false

/** Returns a new request body that transmits this. */
fun ByteString.commonToRequestBody(contentType: MediaType?): RequestBody {
  return object : RequestBody() {
    override fun contentType() = contentType

    override fun contentLength() = size.toLong()

    override fun writeTo(sink: BufferedSink) {
      sink.write(this@commonToRequestBody)
    }
  }
}
