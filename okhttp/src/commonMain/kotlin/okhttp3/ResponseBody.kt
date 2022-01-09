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

package okhttp3

import okio.BufferedSource
import okio.ByteString
import okio.Closeable
import okio.IOException

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
 * ```java
 * Call call = client.newCall(request);
 * try (Response response = call.execute()) {
 * ... // Use the response.
 * }
 * ```
 *
 * You can use a similar block for asynchronous calls:
 *
 * ```java
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
expect abstract class ResponseBody constructor() : Closeable {
  abstract fun contentType(): MediaType?

  /**
   * Returns the number of bytes in that will returned by [bytes], or [byteStream], or -1 if
   * unknown.
   */
  abstract fun contentLength(): Long

  abstract fun source(): BufferedSource

  /**
   * Returns the response as a byte array.
   *
   * This method loads entire response body into memory. If the response body is very large this
   * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  @Throws(IOException::class)
  fun bytes(): ByteArray

  /**
   * Returns the response as a [ByteString].
   *
   * This method loads entire response body into memory. If the response body is very large this
   * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
   * possibility for your response.
   */
  @Throws(IOException::class)
  fun byteString(): ByteString

  /**
   * Returns the response as a string.
   *
   * On Non JVM Platforms, this only supports the UTF-8 encoding, all other encodings will
   * be treated as unknown.
   *
   * On JVM Platforms:
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
  fun string(): String

  override fun close()

  companion object {
    /**
     * Returns a new response body that transmits this string. If [contentType] is non-null and
     * has a charset other than utf-8 the behaviour differs by platform.
     *
     * On the JVM the encoding will be used instead of utf-8.
     *
     * On non JVM platforms, this method will fail for encodings other than utf-8.
     */
    fun String.toResponseBody(contentType: MediaType? = null): ResponseBody

    /** Returns a new response body that transmits this byte array. */
    fun ByteArray.toResponseBody(contentType: MediaType? = null): ResponseBody

    /** Returns a new response body that transmits this byte string. */
    fun ByteString.toResponseBody(contentType: MediaType? = null): ResponseBody

    /** Returns a new response body that transmits this source. */
    fun BufferedSource.asResponseBody(
      contentType: MediaType? = null,
      contentLength: Long = -1L
    ): ResponseBody
  }
}
