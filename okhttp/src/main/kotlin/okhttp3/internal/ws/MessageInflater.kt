/*
 * Copyright (C) 2020 Square, Inc.
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

import java.io.Closeable
import java.io.IOException
import java.util.zip.Inflater
import okio.Buffer
import okio.InflaterSource

private const val OCTETS_TO_ADD_BEFORE_INFLATION = 0x0000ffff

class MessageInflater(
  private val noContextTakeover: Boolean,
) : Closeable {
  private val deflatedBytes = Buffer()

  // Lazily-created.
  private var inflater: Inflater? = null
  private var inflaterSource: InflaterSource? = null

  /** Inflates [buffer] in place as described in RFC 7692 section 7.2.2. */
  @Throws(IOException::class)
  fun inflate(buffer: Buffer) {
    require(deflatedBytes.size == 0L)

    val inflater =
      this.inflater
        ?: Inflater(true).also { this.inflater = it }
    val inflaterSource =
      this.inflaterSource
        ?: InflaterSource(deflatedBytes, inflater).also { this.inflaterSource = it }

    if (noContextTakeover) {
      inflater.reset()
    }

    deflatedBytes.writeAll(buffer)
    deflatedBytes.writeInt(OCTETS_TO_ADD_BEFORE_INFLATION)

    val totalBytesToRead = inflater.bytesRead + deflatedBytes.size

    // We cannot read all, as the source does not close.
    // Instead, we ensure that all bytes from source have been processed by inflater.
    do {
      inflaterSource.readOrInflate(buffer, Long.MAX_VALUE)
    } while (inflater.bytesRead < totalBytesToRead && !inflater.finished())

    // The inflater data was self-terminated and there's unexpected trailing data. Tear it all down
    // so we don't leak that data into the input of the next message.
    if (inflater.bytesRead < totalBytesToRead) {
      deflatedBytes.clear()
      inflaterSource.close()
      this.inflaterSource = null
      this.inflater = null
    }
  }

  @Throws(IOException::class)
  override fun close() {
    inflaterSource?.close()
    inflaterSource = null
    inflater = null
  }
}
