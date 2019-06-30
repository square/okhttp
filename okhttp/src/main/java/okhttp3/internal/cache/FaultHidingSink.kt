/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.cache

import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import java.io.IOException

/** A sink that never throws IOExceptions, even if the underlying sink does. */
internal open class FaultHidingSink(
  delegate: Sink,
  val onException: (IOException) -> Unit
) : ForwardingSink(delegate) {
  private var hasErrors = false

  override fun write(source: Buffer, byteCount: Long) {
    if (hasErrors) {
      source.skip(byteCount)
      return
    }
    try {
      super.write(source, byteCount)
    } catch (e: IOException) {
      hasErrors = true
      onException(e)
    }
  }

  override fun flush() {
    if (hasErrors) {
      return
    }
    try {
      super.flush()
    } catch (e: IOException) {
      hasErrors = true
      onException(e)
    }
  }

  override fun close() {
    if (hasErrors) {
      return
    }
    try {
      super.close()
    } catch (e: IOException) {
      hasErrors = true
      onException(e)
    }
  }
}
