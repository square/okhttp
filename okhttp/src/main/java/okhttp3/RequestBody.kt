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
import okio.BufferedSink
import okio.ByteString
import okio.source
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlin.text.Charsets.UTF_8

abstract class RequestBody {

  /** Returns the Content-Type header for this body. */
  abstract fun contentType(): MediaType?

  /**
   * Returns the number of bytes that will be written to sink in a call to [writeTo],
   * or -1 if that count is unknown.
   */
  @Throws(IOException::class)
  open fun contentLength(): Long = -1

  /** Writes the content of this request to [sink]. */
  @Throws(IOException::class)
  abstract fun writeTo(sink: BufferedSink)

  /**
   * A duplex request body is special in how it is **transmitted** on the network and
   * in the **API contract** between OkHttp and the application.
   *
   * This method returns false unless it is overridden by a subclass.
   *
   * ### Duplex Transmission
   *
   * With regular HTTP calls the request always completes sending before the response may begin
   * receiving. With duplex the request and response may be interleaved! That is, request body bytes
   * may be sent after response headers or body bytes have been received.
   *
   * Though any call may be initiated as a duplex call, only web servers that are specially
   * designed for this nonstandard interaction will use it. As of 2019-01, the only widely-used
   * implementation of this pattern is [gRPC][grpc].
   *
   * Because the encoding of interleaved data is not well-defined for HTTP/1, duplex request
   * bodies may only be used with HTTP/2. Calls to HTTP/1 servers will fail before the HTTP request
   * is transmitted. If you cannot ensure that your client and server both support HTTP/2, do not
   * use this feature.
   *
   * ### Duplex APIs
   *
   * With regular request bodies it is not legal to write bytes to the sink passed to
   * [RequestBody.writeTo] after that method returns. For duplex requests bodies that condition is
   * lifted. Such writes occur on an application-provided thread and may occur concurrently with
   * reads of the [ResponseBody]. For duplex request bodies, [writeTo] should return
   * quickly, possibly by handing off the provided request body to another thread to perform
   * writing.
   *
   * [grpc]: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
   */
  open val isDuplex: Boolean = false

  /**
   * Returns true if this body expects at most one call to [writeTo] and can be transmitted
   * at most once. This is typically used when writing the request body is destructive and it is not
   * possible to recreate the request body after it has been sent.
   *
   * This method returns false unless it is overridden by a subclass.
   *
   * By default OkHttp will attempt to retransmit request bodies when the original request fails
   * due to a stale connection, a client timeout (HTTP 408), a satisfied authorization challenge
   * (HTTP 401 and 407), or a retryable server failure (HTTP 503 with a `Retry-After: 0`
   * header).
   */
  open val isOneShot: Boolean = false

  companion object {

    /**
     * Returns a new request body that transmits [content]. If [contentType] is non-null
     * and lacks a charset, this will use UTF-8.
     */
    @JvmStatic
    fun create(contentType: MediaType?, content: String): RequestBody {
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
      val bytes = content.toByteArray(charset)
      return create(finalContentType, bytes)
    }

    /** Returns a new request body that transmits [content]. */
    @JvmStatic
    fun create(
      contentType: MediaType?,
      content: ByteString
    ): RequestBody = object : RequestBody() {
      override fun contentType() = contentType

      override fun contentLength() = content.size.toLong()

      override fun writeTo(sink: BufferedSink) {
        sink.write(content)
      }
    }

    /** Returns a new request body that transmits [content]. */
    @JvmOverloads
    @JvmStatic
    fun create(
      contentType: MediaType?,
      content: ByteArray,
      offset: Int = 0,
      byteCount: Int = content.size
    ): RequestBody {
      Util.checkOffsetAndCount(content.size.toLong(), offset.toLong(), byteCount.toLong())
      return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = byteCount.toLong()

        override fun writeTo(sink: BufferedSink) {
          sink.write(content, offset, byteCount)
        }
      }
    }

    /** Returns a new request body that transmits the content of [file]. */
    @JvmStatic
    fun create(contentType: MediaType?, file: File): RequestBody = object : RequestBody() {
      override fun contentType() = contentType

      override fun contentLength() = file.length()

      override fun writeTo(sink: BufferedSink) {
        file.source().use { source -> sink.writeAll(source) }
      }
    }
  }
}
