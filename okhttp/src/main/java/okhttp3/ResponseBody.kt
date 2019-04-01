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

import okhttp3.internal.Util
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import kotlin.text.Charsets.UTF_8

/**
 * A one-shot stream from the origin server to the client application with the raw bytes of the
 * response body. Each response body is supported by an active connection to the webserver. This
 * imposes both obligations and limits on the client application.
 *
 * ### The response body must be closed.
 *
 * Each response body is backed by a limited resource like a socket (live network responses) or
 * an open file (for cached responses). Failing to close the response body will leak resources and
 * may ultimately cause the application to slow down or crash.
 *
 * Both this class and [Response] implement [Closeable]. Closing a response simply
 * closes its response body. If you invoke [Call.execute] or implement [Callback.onResponse] you
 * must close this body by calling any of the following methods:
 *
 * * `Response.close()`
 * * `Response.body().close()`
 * * `Response.body().source().close()`
 * * `Response.body().charStream().close()`
 * * `Response.body().byteStream().close()`
 * * `Response.body().bytes()`
 * * `Response.body().string()`
 *
 * There is no benefit to invoking multiple `close()` methods for the same response body.
 *
 * For synchronous calls, the easiest way to make sure a response body is closed is with a `try`
 * block. With this structure the compiler inserts an implicit `finally` clause that calls
 * [close()][Response.close] for you.
 *
 * ```
 * Call call = client.newCall(request);
 * try (Response response = call.execute()) {
 * ... // Use the response.
 * }
 * ```
 *
 * You can use a similar block for asynchronous calls:
 *
 * ```
 * Call call = client.newCall(request);
 * call.enqueue(new Callback() {
 *   public void onResponse(Call call, Response response) throws IOException {
 *     try (ResponseBody responseBody = response.body()) {
 *     ... // Use the response.
 *     }
 *   }
 *
 *   public void onFailure(Call call, IOException e) {
 *   ... // Handle the failure.
 *   }
 * });
 * ```
 *
 * These examples will not work if you're consuming the response body on another thread. In such
 * cases the consuming thread must call [close] when it has finished reading the response
 * body.
 *
 * ### The response body can be consumed only once.
 *
 * This class may be used to stream very large responses. For example, it is possible to use this
 * class to read a response that is larger than the entire memory allocated to the current process.
 * It can even stream a response larger than the total storage on the current device, which is a
 * common requirement for video streaming applications.
 *
 * Because this class does not buffer the full response in memory, the application may not
 * re-read the bytes of the response. Use this one shot to read the entire response into memory with
 * [bytes] or [string]. Or stream the response with either [source], [byteStream], or [charStream].
 */
abstract class ResponseBody : Closeable {
  /** Multiple calls to [charStream] must return the same instance. */
  private var reader: Reader? = null

  abstract fun contentType(): MediaType?

  /**
   * Returns the number of bytes in that will returned by [bytes], or [byteStream], or -1 if
   * unknown.
   */
  abstract fun contentLength(): Long

  fun byteStream(): InputStream = source().inputStream()

  abstract fun source(): BufferedSource

  /**
   * Returns the response as a byte array.
   *
   * This method loads entire response body into memory. If the response body is very large this
   * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  @Throws(IOException::class)
  fun bytes(): ByteArray {
    val contentLength = contentLength()
    if (contentLength > Integer.MAX_VALUE) {
      throw IOException("Cannot buffer entire body for content length: $contentLength")
    }

    val bytes: ByteArray = source().use(BufferedSource::readByteArray)
    if (contentLength != -1L && contentLength != bytes.size.toLong()) {
      throw IOException(
          "Content-Length ($contentLength) and stream length (${bytes.size}) disagree")
    }
    return bytes
  }

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

  /**
   * Returns the response as a string.
   *
   * If the response starts with a
   * [Byte Order Mark (BOM)](https://en.wikipedia.org/wiki/Byte_order_mark), it is consumed and
   * used to determine the charset of the response bytes.
   *
   * Otherwise if the response has a `Content-Type` header that specifies a charset, that is used
   * to determine the charset of the response bytes.
   *
   * Otherwise the response bytes are decoded as UTF-8.
   *
   * This method loads entire response body into memory. If the response body is very large this
   * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  @Throws(IOException::class)
  fun string(): String = source().use { source ->
    source.readString(charset = Util.bomAwareCharset(source, charset()))
  }

  private fun charset() = contentType()?.charset(UTF_8) ?: UTF_8

  override fun close() = Util.closeQuietly(source())

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
          Util.bomAwareCharset(source, charset)).also {
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

  companion object {

    /**
     * Returns a new response body that transmits [content]. If `contentType` is non-null
     * and lacks a charset, this will use UTF-8.
     */
    @JvmStatic
    fun create(contentType: MediaType?, content: String): ResponseBody {
      var charset: Charset = UTF_8
      var finalContentType: MediaType? = contentType
      if (contentType != null) {
        val resolvedCharset = contentType.charset()
        if (resolvedCharset == null) {
          charset = UTF_8
          finalContentType = MediaType.parse("$contentType; charset=utf-8")
        } else {
          charset = resolvedCharset
        }
      }
      val buffer = Buffer().writeString(content, charset)
      return create(finalContentType, buffer.size, buffer)
    }

    /** Returns a new response body that transmits [content]. */
    @JvmStatic
    fun create(contentType: MediaType?, content: ByteArray): ResponseBody {
      val buffer = Buffer().write(content)
      return create(contentType, content.size.toLong(), buffer)
    }

    /** Returns a new response body that transmits [content]. */
    @JvmStatic
    fun create(contentType: MediaType?, content: ByteString): ResponseBody {
      val buffer = Buffer().write(content)
      return create(contentType, content.size.toLong(), buffer)
    }

    /** Returns a new response body that transmits [content]. */
    @JvmStatic
    fun create(
      contentType: MediaType?,
      contentLength: Long,
      content: BufferedSource
    ): ResponseBody = object : ResponseBody() {
      override fun contentType() = contentType

      override fun contentLength() = contentLength

      override fun source() = content
    }
  }
}
