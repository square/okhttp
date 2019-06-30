/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.cache2

import okio.Buffer
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Read and write a target file. Unlike Okio's built-in `Okio.source(java.io.File file)` and `Okio.sink(java.io.File file)`
 * this class offers:
 *
 *  * **Read/write:** read and write using the same operator.
 *  * **Random access:** access any position within the file.
 *  * **Shared channels:** read and write a file channel that's shared between
 * multiple operators. Note that although the underlying [FileChannel] may be shared,
 * each [FileOperator] should not be.
 */
internal class FileOperator(
  private val fileChannel: FileChannel
) {

  /** Write [byteCount] bytes from [source] to the file at [pos]. */
  @Throws(IOException::class)
  fun write(pos: Long, source: Buffer, byteCount: Long) {
    if (byteCount < 0L || byteCount > source.size) {
      throw IndexOutOfBoundsException()
    }
    var mutablePos = pos
    var mutableByteCount = byteCount

    while (mutableByteCount > 0L) {
      val bytesWritten = fileChannel.transferFrom(source, mutablePos, mutableByteCount)
      mutablePos += bytesWritten
      mutableByteCount -= bytesWritten
    }
  }

  /**
   * Copy [byteCount] bytes from the file at [pos] into `source`. It is the
   * caller's responsibility to make sure there are sufficient bytes to read: if there aren't this
   * method throws an `EOFException`.
   */
  fun read(pos: Long, sink: Buffer, byteCount: Long) {
    if (byteCount < 0L) {
      throw IndexOutOfBoundsException()
    }
    var mutablePos = pos
    var mutableByteCount = byteCount

    while (mutableByteCount > 0L) {
      val bytesRead = fileChannel.transferTo(mutablePos, mutableByteCount, sink)
      mutablePos += bytesRead
      mutableByteCount -= bytesRead
    }
  }
}
