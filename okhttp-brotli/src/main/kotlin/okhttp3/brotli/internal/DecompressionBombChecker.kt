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
package okhttp3.brotli.internal

import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import okio.Source

/** Fails decompression if the ratio is too high. */
internal class DecompressionBombChecker(
  private val maxRatio: Long,
) {
  private var inputByteCount = 0L
  private var outputByteCount = 0L

  fun wrapInput(source: Source): Source {
    return object : ForwardingSource(source) {
      override fun read(
        sink: Buffer,
        byteCount: Long,
      ): Long {
        val result = super.read(sink, byteCount)
        if (result == -1L) return result
        inputByteCount += result
        return result
      }
    }
  }

  fun wrapOutput(source: Source): Source {
    return object : ForwardingSource(source) {
      override fun read(
        sink: Buffer,
        byteCount: Long,
      ): Long {
        val result = super.read(sink, byteCount)
        if (result != -1L) {
          outputByteCount += result

          if (outputByteCount > inputByteCount * maxRatio) {
            throw IOException(
              "decompression bomb? outputByteCount=$outputByteCount, " +
                "inputByteCount=$inputByteCount exceeds max ratio of $maxRatio",
            )
          }
        }

        return result
      }
    }
  }
}
