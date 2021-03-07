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
import java.util.zip.Deflater
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.DeflaterSink

private val EMPTY_DEFLATE_BLOCK = "000000ffff".decodeHex()
private const val LAST_OCTETS_COUNT_TO_REMOVE_AFTER_DEFLATION = 4

class MessageDeflater(
  private val noContextTakeover: Boolean
) : Closeable {
  private val deflatedBytes = Buffer()
  private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true /* omit zlib header */)
  private val deflaterSink = DeflaterSink(deflatedBytes, deflater)

  /** Deflates [buffer] in place as described in RFC 7692 section 7.2.1. */
  @Throws(IOException::class)
  fun deflate(buffer: Buffer) {
    require(deflatedBytes.size == 0L)

    if (noContextTakeover) {
      deflater.reset()
    }

    deflaterSink.write(buffer, buffer.size)
    deflaterSink.flush()

    if (deflatedBytes.endsWith(EMPTY_DEFLATE_BLOCK)) {
      val newSize = deflatedBytes.size - LAST_OCTETS_COUNT_TO_REMOVE_AFTER_DEFLATION
      deflatedBytes.readAndWriteUnsafe().use { cursor ->
        cursor.resizeBuffer(newSize)
      }
    } else {
      // Same as adding EMPTY_DEFLATE_BLOCK and then removing 4 bytes.
      deflatedBytes.writeByte(0x00)
    }

    buffer.write(deflatedBytes, deflatedBytes.size)
  }

  @Throws(IOException::class)
  override fun close() = deflaterSink.close()

  private fun Buffer.endsWith(suffix: ByteString) = rangeEquals(size - suffix.size, suffix)
}
