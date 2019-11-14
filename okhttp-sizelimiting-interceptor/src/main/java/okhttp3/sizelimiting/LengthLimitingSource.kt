/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.sizelimiting

import okio.Buffer
import okio.Source
import okio.Timeout

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Wrapper for the [okio.Source] class which limits amount of bytes can be read from it.
 *
 * @param source original source to read from
 * @param maxLength data limits in byes
 * @param onLimitExceeded cleanup action we need to run on error
 */
internal class LengthLimitingSource(
  private val source: Source,
  private val maxLength: Long,
  private val onLimitExceeded: () -> Unit?
) : Source {
    private val totalBytesRead = AtomicLong(0)

    @Throws(IOException::class)
    override fun read(
      sink: Buffer,
      byteCount: Long
    ): Long {

        val bytesRead = source.read(sink, byteCount)

        val totalBytes = totalBytesRead.addAndGet(bytesRead)

        if (totalBytes > maxLength) {
            source.use {
                onLimitExceeded()
            }
            throw ContentTooLongException("Response size exceeded allowed $maxLength bytes")
        }

        return bytesRead
    }

    override fun timeout(): Timeout {
        return source.timeout()
    }

    @Throws(IOException::class)
    override fun close() {
        source.close()
    }
}
