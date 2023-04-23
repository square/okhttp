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

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import okhttp3.internal.charsetOrUtf8
import okhttp3.internal.chooseCharset
import okhttp3.internal.commonAsResponseBody
import okhttp3.internal.commonByteString
import okhttp3.internal.commonBytes
import okhttp3.internal.commonClose
import okhttp3.internal.commonToResponseBody
import okhttp3.internal.readBomAsCharset
import okio.Buffer
import okio.BufferedSource
import okio.ByteString

actual abstract class ResponseBody : Closeable {
  /** Multiple calls to [charStream] must return the same instance. */
  private var reader: Reader? = null

  actual abstract fun contentType(): MediaType?

  actual abstract fun contentLength(): Long

  fun byteStream(): InputStream = source().inputStream()

  actual abstract fun source(): BufferedSource

  @Throws(IOException::class)
  actual fun bytes() = commonBytes()

  @Throws(IOException::class)
  actual fun byteString() = commonByteString()

  /**
   * Returns the response as a character stream.
   *
   * If the response starts with a
   * [Byte Order Mark (BOM)](https://en.wikipedia.org/wiki/Byte_order_mark), it is consumed and
   * used to determine the charset of the response bytes.
   *
   * Otherwise if the response has a `Content-Type` header that specifies a charset, that is used
   * to determine the charset of the response bytes.
   *
   * Otherwise the response bytes are decoded as UTF-8.
   */
  fun charStream(): Reader = reader ?: BomAwareReader(source(), charset()).also {
    reader = it
  }

  @Throws(IOException::class)
  actual fun string(): String = source().use { source ->
    source.readString(charset = source.readBomAsCharset(charset()))
  }

  private fun charset() = contentType().charsetOrUtf8()

  actual override fun close() = commonClose()

  internal class BomAwareReader(
    private val source: BufferedSource,
    private val charset: Charset
  ) : Reader() {

    private var closed: Boolean = false
    private var delegate: Reader? = null

    @Throws(IOException::class)
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
      if (closed) throw IOException("Stream closed")

      val finalDelegate = delegate ?: InputStreamReader(
          source.inputStream(),
          source.readBomAsCharset(charset)).also {
        delegate = it
      }
      return finalDelegate.read(cbuf, off, len)
    }

    @Throws(IOException::class)
    override fun close() {
      closed = true
      delegate?.close() ?: run { source.close() }
    }
  }

  actual companion object {
    @JvmStatic
    @JvmName("create")
    actual fun String.toResponseBody(contentType: MediaType?): ResponseBody {
      val (charset, finalContentType) = contentType.chooseCharset()
      val buffer = Buffer().writeString(this, charset)
      return buffer.asResponseBody(finalContentType, buffer.size)
    }

    @JvmStatic
    @JvmName("create")
    actual fun ByteArray.toResponseBody(contentType: MediaType?): ResponseBody = commonToResponseBody(contentType)

    @JvmStatic
    @JvmName("create")
    actual fun ByteString.toResponseBody(contentType: MediaType?): ResponseBody = commonToResponseBody(contentType)

    @JvmStatic
    @JvmName("create")
    actual fun BufferedSource.asResponseBody(
      contentType: MediaType?,
      contentLength: Long
    ): ResponseBody = commonAsResponseBody(contentType, contentLength)

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.toResponseBody(contentType)",
            imports = ["okhttp3.ResponseBody.Companion.toResponseBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(contentType: MediaType?, content: String): ResponseBody = content.toResponseBody(contentType)

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.toResponseBody(contentType)",
            imports = ["okhttp3.ResponseBody.Companion.toResponseBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(contentType: MediaType?, content: ByteArray): ResponseBody = content.toResponseBody(contentType)

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.toResponseBody(contentType)",
            imports = ["okhttp3.ResponseBody.Companion.toResponseBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(contentType: MediaType?, content: ByteString): ResponseBody = content.toResponseBody(contentType)

    @JvmStatic
    @Deprecated(
        message = "Moved to extension function. Put the 'content' argument first to fix Java",
        replaceWith = ReplaceWith(
            expression = "content.asResponseBody(contentType, contentLength)",
            imports = ["okhttp3.ResponseBody.Companion.asResponseBody"]
        ),
        level = DeprecationLevel.WARNING)
    fun create(
      contentType: MediaType?,
      contentLength: Long,
      content: BufferedSource
    ): ResponseBody = content.asResponseBody(contentType, contentLength)
  }
}
