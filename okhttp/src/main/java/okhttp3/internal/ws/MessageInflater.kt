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
package okhttp3.internal.ws

import okio.Buffer
import okio.InflaterSource
import java.io.Closeable
import java.io.IOException
import java.util.zip.Inflater

private const val OCTETS_TO_ADD_BEFORE_INFLATION = 0x0000ffff

class MessageInflater : Closeable {
  private val source = Buffer()
  private val inflater = Inflater(true /* omit zlib header */)
  private val inflaterSource = InflaterSource(source, inflater)

  private var totalBytesToInflate = 0L

  /**
   * Inflates bytes from [buffer] as described in
   * [rfc7692#section-7.2.2](https://tools.ietf.org/html/rfc7692#section-7.2.2).
   * and writes inflated data back to it.
   */
  @Throws(IOException::class)
  fun inflate(buffer: Buffer): Buffer {
    require(source.size == 0L)

    source.writeAll(buffer)
    source.writeInt(OCTETS_TO_ADD_BEFORE_INFLATION)

    totalBytesToInflate += source.size

    return buffer.apply {
      // We cannot read all, as the source does not close.
      // Instead, we ensure that all bytes from source have been processed by inflater.
      while (true) {
        inflaterSource.read(this, Long.MAX_VALUE)
        if (inflater.bytesRead == totalBytesToInflate) {
          break
        }
      }
    }
  }

  @Throws(IOException::class)
  override fun close() = inflaterSource.close()
}
