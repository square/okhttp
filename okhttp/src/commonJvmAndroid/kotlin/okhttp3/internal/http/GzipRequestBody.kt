/*
 * Copyright (C) 2025 Square, Inc.
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

import okhttp3.RequestBody
import okio.BufferedSink
import okio.GzipSink
import okio.buffer

internal class GzipRequestBody(
  val delegate: RequestBody,
) : RequestBody() {
  override fun contentType() = delegate.contentType()

  // We don't know the compressed length in advance!
  override fun contentLength() = -1L

  override fun writeTo(sink: BufferedSink) {
    GzipSink(sink).buffer().use(delegate::writeTo)
  }

  override fun isOneShot() = delegate.isOneShot()
}
