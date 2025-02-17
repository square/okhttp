/*
 * Copyright (C) 2024 Square, Inc.
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

package okhttp3.internal

import kotlin.jvm.JvmOverloads
import okio.Buffer
import okio.ForwardingSource
import okio.Source

/**
 * Return a new [Source] whose [read function][Source.read] returns -1 after [byteCount]
 * bytes have been read.
 *
 * @param onReadExhausted Callback invoked once when the end of bytes has been reached. It receives
 * `true` if the end of bytes was because the underlying stream did not contain enough bytes and
 * `false` if [byteCount] bytes were successfully read.
 */
@JvmOverloads
internal fun Source.limit(
  byteCount: Long,
  onReadExhausted: (eof: Boolean) -> Unit = {},
): Source {
  require(byteCount >= 0) { "byteCount < 0: $byteCount" }
  return FixedLengthSource(this, byteCount, onReadExhausted, truncate = true)
}

internal class FixedLengthSource(
  delegate: Source,
  private var bytesRemaining: Long,
  onReadExhausted: (eof: Boolean) -> Unit,
  private val truncate: Boolean,
) : ForwardingSource(delegate) {
  /** `null` once invoked. */
  private var onReadExhausted: ((eof: Boolean) -> Unit)? = onReadExhausted

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    val requestBytes =
      if (truncate) {
        if (bytesRemaining == 0L) {
          // If the limit was 0 we want to wait until the first call to this function before
          // triggering the callback.
          onReadExhausted?.invoke(false)
          onReadExhausted = null
          return -1L
        }
        minOf(bytesRemaining, byteCount)
      } else {
        byteCount
      }
    val readBytes = super.read(sink, requestBytes)
    if (readBytes == -1L) {
      onReadExhausted!!(true)
      onReadExhausted = null
      return -1L
    }
    bytesRemaining -= readBytes
    if (bytesRemaining == 0L) {
      onReadExhausted!!(false)
      onReadExhausted = null
    }
    return readBytes
  }
}
