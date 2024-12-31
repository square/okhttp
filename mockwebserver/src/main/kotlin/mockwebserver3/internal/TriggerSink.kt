/*
 * Copyright (c) 2022 Block, Inc.
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
package mockwebserver3.internal

import okio.Buffer
import okio.Sink

/**
 * A sink that executes [trigger] after [triggerByteCount] bytes are written, and then skips all
 * subsequent bytes.
 */
internal class TriggerSink(
  private val delegate: Sink,
  private val triggerByteCount: Long,
  private val trigger: () -> Unit,
) : Sink by delegate {
  private var bytesWritten = 0L

  override fun write(
    source: Buffer,
    byteCount: Long,
  ) {
    if (byteCount == 0L) return // Avoid double-triggering.

    if (bytesWritten == triggerByteCount) {
      source.skip(byteCount)
      return
    }

    val toWrite = minOf(byteCount, triggerByteCount - bytesWritten)
    bytesWritten += toWrite

    delegate.write(source, toWrite)

    if (bytesWritten == triggerByteCount) {
      trigger()
    }

    source.skip(byteCount - toWrite)
  }
}
