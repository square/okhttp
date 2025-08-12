/*
 * Copyright 2022 Google LLC
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
package okhttp3.android.httpengine

import android.os.Build
import androidx.annotation.RequiresExtension
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
internal abstract class HttpEngineTransportResponseBody protected constructor(private val delegate: ResponseBody) :
  ResponseBody() {
  override fun contentType(): MediaType? {
    return delegate.contentType()
  }

  override fun contentLength(): Long {
    return delegate.contentLength()
  }

  override fun source(): BufferedSource {
    return delegate.source()
  }

  override fun close() {
    delegate.close()
    customCloseHook()
  }

  abstract fun customCloseHook()
}
