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
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.IOException
import okio.use

internal fun ResponseBody.commonBytes() = commonConsumeSource(BufferedSource::readByteArray) { it.size }

internal fun ResponseBody.commonByteString() = commonConsumeSource(BufferedSource::readByteString) { it.size }

internal inline fun <T : Any> ResponseBody.commonConsumeSource(
  consumer: (BufferedSource) -> T,
  sizeMapper: (T) -> Int,
): T {
  val contentLength = contentLength()
  if (contentLength > Int.MAX_VALUE) {
    throw IOException("Cannot buffer entire body for content length: $contentLength")
  }

  val bytes = source().use(consumer)
  val size = sizeMapper(bytes)
  if (contentLength != -1L && contentLength != size.toLong()) {
    throw IOException("Content-Length ($contentLength) and stream length ($size) disagree")
  }
  return bytes
}

internal fun ResponseBody.commonClose() = source().closeQuietly()

internal fun ByteArray.commonToResponseBody(contentType: MediaType?): ResponseBody {
  return Buffer()
    .write(this)
    .asResponseBody(contentType, size.toLong())
}

internal fun ByteString.commonToResponseBody(contentType: MediaType?): ResponseBody {
  return Buffer()
    .write(this)
    .asResponseBody(contentType, size.toLong())
}

internal fun BufferedSource.commonAsResponseBody(
  contentType: MediaType?,
  contentLength: Long,
): ResponseBody =
  object : ResponseBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun source(): BufferedSource = this@commonAsResponseBody
  }
