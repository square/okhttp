/*
 * Copyright (C) 2021 Square, Inc.
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
package okhttp3.internal.okio.zipfilesystem

import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import okio.Source

/**
 * A source that returns [size] bytes of [delegate].
 *
 * This throws an [IOException] if the delegate returns fewer than [size] bytes.
 *
 * If [truncate] is true, this truncates to [size] bytes. Otherwise this requires that [delegate]
 * will return exactly [size] bytes, and will throw an [IOException] if it doesn't.
 */
internal class FixedLengthSource(
  delegate: Source,
  private val size: Long,
  private val truncate: Boolean
) : ForwardingSource(delegate) {
  private var bytesReceived = 0L

  override fun read(sink: Buffer, byteCount: Long): Long {
    // Figure out how many bytes to attempt to read.
    //
    // If we're truncating, we never attempt to read more than what's remaining.
    //
    // Otherwise we expect the underlying source to be exactly the promised size. Read as much as
    // possible and throw an exception if too many bytes are returned.
    val toRead = when {
      bytesReceived > size -> 0L // Already read more than the promised size.
      truncate -> {
        val remaining = size - bytesReceived
        if (remaining == 0L) return -1L // Already read exactly the promised size.
        minOf(byteCount, remaining)
      }
      else -> byteCount
    }

    val result = super.read(sink, toRead)

    if (result != -1L) bytesReceived += result

    // Throw an exception if we received too few bytes or too many.
    if ((bytesReceived < size && result == -1L) || bytesReceived > size) {
      if (result > 0L && bytesReceived > size) {
        // If we received bytes beyond the limit, don't return them to the caller.
        sink.truncateToSize(sink.size - (bytesReceived - size))
      }
      throw IOException("expected $size bytes but got $bytesReceived")
    }

    return result
  }

  private fun Buffer.truncateToSize(newSize: Long) {
    val scratch = Buffer()
    scratch.writeAll(this)
    write(scratch, newSize)
    scratch.clear()
  }
}
