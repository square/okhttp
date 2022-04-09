/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
package okhttp3

import okio.IOException
import okio.Timeout

actual interface Call : Cloneable {
  actual fun request(): Request

  /**
   * Invokes the request immediately, and blocks until the response can be processed or is in error.
   *
   * To avoid leaking resources callers should close the [Response] which in turn will close the
   * underlying [ResponseBody].
   *
   * ```java
   * // ensure the response (and underlying response body) is closed
   * try (Response response = client.newCall(request).execute()) {
   *   ...
   * }
   * ```
   *
   * The caller may read the response body with the response's [Response.body] method. To avoid
   * leaking resources callers must [close the response body][ResponseBody] or the response.
   *
   * Note that transport-layer success (receiving a HTTP response code, headers and body) does not
   * necessarily indicate application-layer success: `response` may still indicate an unhappy HTTP
   * response code like 404 or 500.
   *
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   *     problem or timeout. Because networks can fail during an exchange, it is possible that the
   *     remote server accepted the request before the failure.
   * @throws IllegalStateException when the call has already been executed.
   */
  @Throws(IOException::class)
  fun execute(): Response

  actual fun enqueue(responseCallback: Callback)

  actual fun cancel()

  actual fun isExecuted(): Boolean

  actual fun isCanceled(): Boolean

  /**
   * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
   * body, server processing, and reading the response body. If the call requires redirects or
   * retries all must complete within one timeout period.
   *
   * Configure the client's default timeout with [OkHttpClient.Builder.callTimeout].
   */
  fun timeout(): Timeout

  public actual override fun clone(): Call

  actual fun interface Factory {
    actual fun newCall(request: Request): Call
  }
}
