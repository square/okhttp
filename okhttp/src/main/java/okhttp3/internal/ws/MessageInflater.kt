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
  private val noContextTakeover: Boolean
) : Closeable {
  private val deflatedBytes = Buffer()
  private val inflater = Inflater(true /* omit zlib header */)
  private val inflaterSource = InflaterSource(deflatedBytes, inflater)

  /** Inflates [buffer] in place as described in RFC 7692 section 7.2.2. */
  @Throws(IOException::class)
  fun inflate(buffer: Buffer) {
    require(deflatedBytes.size == 0L)

    // Handle the empty message special case. The compressed empty message is one byte, '0x00'. We
    // can't use the normal flow here because inflaterSource.read() throws EOFException if the
    // deflated stream isn't complete but there's no bytes to return.
    if (buffer.size == 1L && buffer[0L] == 0.toByte()) {
      buffer.skip(1L)
      return
    }

    if (noContextTakeover) {
      inflater.reset()
    }

    deflatedBytes.writeAll(buffer)
    deflatedBytes.writeInt(OCTETS_TO_ADD_BEFORE_INFLATION)

    val totalBytesToRead = inflater.bytesRead + deflatedBytes.size

    // We cannot read all, as the source does not close.
    // Instead, we ensure that all bytes from source have been processed by inflater.
    do {
      inflaterSource.read(buffer, Long.MAX_VALUE)
    } while (inflater.bytesRead < totalBytesToRead)
  }

  @Throws(IOException::class)
  override fun close() = inflaterSource.close()
}
