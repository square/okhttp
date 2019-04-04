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
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.DeflaterSink
import java.io.Closeable
import java.io.IOException
import java.util.zip.Deflater

private val EMPTY_DEFLATE_BLOCK = "000000ffff".decodeHex()
private const val LAST_OCTETS_COUNT_TO_REMOVE_AFTER_DEFLATION = 4

class MessageDeflater(private val contextTakeover: Boolean) : Closeable {
  private val sink = Buffer()
  private val deflater = Deflater(
      Deflater.DEFAULT_COMPRESSION, true /* omit zlib header */)
  private val deflaterSink = DeflaterSink(sink, deflater)
  private val source = Buffer()

  /**
   * Applies deflation to the [sourceByteString], as it is described in
   * [rfc7692#section-7.2.1](https://tools.ietf.org/html/rfc7692#section-7.2.1).
   */
  @Throws(IOException::class)
  fun deflate(sourceByteString: ByteString): Buffer {
    if (!contextTakeover) {
      deflater.reset()
    }

    source.write(sourceByteString)
    source.readAll(deflaterSink)
    deflaterSink.flush()

    return sink.applyPostDeflate()
  }

  @Throws(IOException::class)
  override fun close() = deflaterSink.close()

  private fun Buffer.applyPostDeflate() = apply {
    if (endsWithEmptyDeflateBlock()) {
      val newSize = size - LAST_OCTETS_COUNT_TO_REMOVE_AFTER_DEFLATION
      readAndWriteUnsafe().use { cursor ->
        cursor.resizeBuffer(newSize)
      }
    } else {
      // Same as adding EMPTY_DEFLATE_BLOCK and then removing 4 bytes
      writeByte(0x00)
    }
  }

  private fun Buffer.endsWithEmptyDeflateBlock(): Boolean = rangeEquals(
      size - EMPTY_DEFLATE_BLOCK.size, EMPTY_DEFLATE_BLOCK)
}
