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
package okhttp3.internal.http

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okio.BufferedSource

class RealResponseBody(
  /**
   * Use a string to avoid parsing the content type until needed. This also defers problems caused
   * by malformed content types.
   */
  private val contentTypeString: String?,
  private val contentLength: Long,
  private val source: BufferedSource
) : ResponseBody() {

  override fun contentLength(): Long = contentLength

  override fun contentType(): MediaType? = contentTypeString?.toMediaTypeOrNull()

  override fun source(): BufferedSource = source
}
