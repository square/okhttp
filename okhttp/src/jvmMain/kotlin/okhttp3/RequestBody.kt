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

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import okhttp3.internal.chooseCharset
import okhttp3.internal.commonContentLength
import okhttp3.internal.commonIsDuplex
import okhttp3.internal.commonIsOneShot
import okhttp3.internal.commonToRequestBody
import okio.BufferedSink
import okio.ByteString
import okio.FileSystem
import okio.GzipSink
import okio.Path
import okio.buffer
import okio.source

actual abstract class RequestBody {

  actual abstract fun contentType(): MediaType?

  @Throws(IOException::class)
  actual open fun contentLength(): Long = commonContentLength()

  @Throws(IOException::class)
  actual abstract fun writeTo(sink: BufferedSink)

  actual open fun isDuplex(): Boolean = commonIsDuplex()

  actual open fun isOneShot(): Boolean = commonIsOneShot()

  actual companion object {
    /**
     * Returns a new request body that transmits this string. If [contentType] is non-null and lacks
     * a charset, this will use UTF-8.
     */
    @JvmStatic
    @JvmName("create")
    actual fun String.toRequestBody(contentType: MediaType?): RequestBody {
      val (charset, finalContentType) = contentType.chooseCharset()
      val bytes = toByteArray(charset)
      return bytes.toRequestBody(finalContentType, 0, bytes.size)
    }

    @JvmStatic
    @JvmName("create")
    actual fun ByteString.toRequestBody(contentType: MediaType?): RequestBody =
      commonToRequestBody(contentType)

    /** Returns a new request body that transmits this. */
    @JvmStatic
    @JvmName("create")
    fun FileDescriptor.toRequestBody(contentType: MediaType? = null): RequestBody {
      return object : RequestBody() {
        override fun contentType() = contentType

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
          FileInputStream(this@toRequestBody).use {
            sink.buffer.writeAll(it.source())
          }
        }
      }
    }

    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    actual fun ByteArray.toRequestBody(
      contentType: MediaType?,
      offset: Int,
      byteCount: Int
    ): RequestBody = commonToRequestBody(contentType, offset, byteCount)

    /** Returns a new request body that transmits the content of this. */
    @JvmStatic
    @JvmName("create")
    fun File.asRequestBody(contentType: MediaType? = null): RequestBody {
      return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = length()

        override fun writeTo(sink: BufferedSink) {
          source().use { source -> sink.writeAll(source) }
        }
      }
    }

    /** Returns a new request body that transmits the content of this. */
    @JvmStatic
    @JvmName("create")
    fun Path.asRequestBody(fileSystem: FileSystem, contentType: MediaType? = null): RequestBody {
      return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = fileSystem.metadata(this@asRequestBody).size ?: -1

        override fun writeTo(sink: BufferedSink) {
          fileSystem.source(this@asRequestBody).use { source -> sink.writeAll(source) }
        }
      }
    }

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.toRequestBody(contentType)",
            imports = ["okhttp3.RequestBody.Companion.toRequestBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(contentType: MediaType?, content: String): RequestBody = content.toRequestBody(contentType)

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.toRequestBody(contentType)",
            imports = ["okhttp3.RequestBody.Companion.toRequestBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(
      contentType: MediaType?,
      content: ByteString
    ): RequestBody = content.toRequestBody(contentType)

    @JvmOverloads
    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.toRequestBody(contentType, offset, byteCount)",
            imports = ["okhttp3.RequestBody.Companion.toRequestBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(
      contentType: MediaType?,
      content: ByteArray,
      offset: Int = 0,
      byteCount: Int = content.size
    ): RequestBody = content.toRequestBody(contentType, offset, byteCount)

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'file' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "file.asRequestBody(contentType)",
            imports = ["okhttp3.RequestBody.Companion.asRequestBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(contentType: MediaType?, file: File): RequestBody= file.asRequestBody(contentType)

    /**
     * Returns a gzip version of the RequestBody, with compressed payload.
     * This is not automatic as not all servers support gzip compressed requests.
     *
     * ```
     * val request = Request.Builder().url("...")
     *  .addHeader("Content-Encoding", "gzip")
     *  .post(uncompressedBody.gzip())
     *  .build()
     * ```
     */
    @JvmStatic
    fun RequestBody.gzip(): RequestBody {
      return object : RequestBody() {
        override fun contentType(): MediaType? {
          return this@gzip.contentType()
        }

        override fun contentLength(): Long {
          return -1 // We don't know the compressed length in advance!
        }

        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
          val gzipSink = GzipSink(sink).buffer()
          this@gzip.writeTo(gzipSink)
          gzipSink.close()
        }

        override fun isOneShot(): Boolean {
          return this@gzip.isOneShot()
        }
      }
    }
  }
}
