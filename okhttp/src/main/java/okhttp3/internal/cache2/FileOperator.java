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
package okhttp3.internal.cache2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import okio.Buffer;
import okio.Okio;

/**
 * Read and write a target file. Unlike Okio's built-in {@linkplain Okio#source(java.io.File) file
 * source} and {@linkplain Okio#sink(java.io.File) file sink} this class offers:
 *
 * <ul>
 *   <li><strong>Read/write:</strong> read and write using the same operator.
 *   <li><strong>Random access:</strong> access any position within the file.
 *   <li><strong>Shared channels:</strong> read and write a file channel that's shared between
 *       multiple operators. Note that although the underlying {@code FileChannel} may be shared,
 *       each {@code FileOperator} should not be.
 * </ul>
 */
final class FileOperator {
  private static final int BUFFER_SIZE = 8192;

  private final byte[] byteArray = new byte[BUFFER_SIZE];
  private final ByteBuffer byteBuffer = ByteBuffer.wrap(byteArray);
  private final FileChannel fileChannel;

  public FileOperator(FileChannel fileChannel) {
    this.fileChannel = fileChannel;
  }

  /** Write {@code byteCount} bytes from {@code source} to the file at {@code pos}. */
  public void write(long pos, Buffer source, long byteCount) throws IOException {
    if (byteCount < 0 || byteCount > source.size()) throw new IndexOutOfBoundsException();

    while (byteCount > 0L) {
      try {
        // Write bytes to the byte[], and tell the ByteBuffer wrapper about 'em.
        int toWrite = (int) Math.min(BUFFER_SIZE, byteCount);
        source.read(byteArray, 0, toWrite);
        byteBuffer.limit(toWrite);

        // Copy bytes from the ByteBuffer to the file.
        do {
          int bytesWritten = fileChannel.write(byteBuffer, pos);
          pos += bytesWritten;
        } while (byteBuffer.hasRemaining());

        byteCount -= toWrite;
      } finally {
        byteBuffer.clear();
      }
    }
  }

  /**
   * Copy {@code byteCount} bytes from the file at {@code pos} into to {@code source}. It is the
   * caller's responsibility to make sure there are sufficient bytes to read: if there aren't this
   * method throws an {@link EOFException}.
   */
  public void read(long pos, Buffer sink, long byteCount) throws IOException {
    if (byteCount < 0) throw new IndexOutOfBoundsException();

    while (byteCount > 0L) {
      try {
        // Read up to byteCount bytes.
        byteBuffer.limit((int) Math.min(BUFFER_SIZE, byteCount));
        if (fileChannel.read(byteBuffer, pos) == -1) throw new EOFException();
        int bytesRead = byteBuffer.position();

        // Write those bytes to sink.
        sink.write(byteArray, 0, bytesRead);
        pos += bytesRead;
        byteCount -= bytesRead;
      } finally {
        byteBuffer.clear();
      }
    }
  }
}
