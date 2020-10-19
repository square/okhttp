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
package mockwebserver3

import okhttp3.Headers

/** An HTTP request initiated by the server. */
class PushPromise(
  @get:JvmName("method") val method: String,
  @get:JvmName("path") val path: String,
  @get:JvmName("headers") val headers: Headers,
  @get:JvmName("response") val response: MockResponse
) {

  @JvmName("-deprecated_method")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "method"),
      level = DeprecationLevel.ERROR)
  fun method() = method

  @JvmName("-deprecated_path")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "path"),
      level = DeprecationLevel.ERROR)
  fun path() = path

  @JvmName("-deprecated_headers")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "headers"),
      level = DeprecationLevel.ERROR)
  fun headers() = headers

  @JvmName("-deprecated_response")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "response"),
      level = DeprecationLevel.ERROR)
  fun response() = response
}
