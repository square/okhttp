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

package okhttp3.ktor

import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentLength
import io.ktor.http.contentType
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource

class HttpResponseBody(
  private val httpResponse: HttpResponse,
  private val source: BufferedSource
) : ResponseBody() {
  override fun contentType(): MediaType? = httpResponse.contentType()?.toMediaType()

  override fun contentLength(): Long = httpResponse.contentLength() ?: -1

  override fun source(): BufferedSource = source
}
