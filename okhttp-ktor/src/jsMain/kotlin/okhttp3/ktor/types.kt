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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.core.readBytes
import okhttp3.RequestBody
import okhttp3.ResponseBody

actual suspend fun HttpResponse.toHttpResponseBody(): ResponseBody {
  val bytes = this.bodyAsChannel().readRemaining().readBytes()
  val buffer = okio.Buffer()
  buffer.write(bytes)
  return HttpResponseBody(this, buffer)
}

actual suspend fun RequestBody.toHttpRequestBody(): Any {
  val buffer = okio.Buffer()
  this.writeTo(buffer)
  return buffer.readByteArray()
}
