/*
 * Copyright (C) 2014 Square, Inc.
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
package okio;

import java.io.IOException;
import java.io.InputStream;

/**
 * A source that keeps a buffer internally so that callers can do small reads
 * without a performance penalty. It also allows clients to read ahead,
 * buffering as much as necessary before consuming input.
 */
public interface BufferedSource extends Source {
  OkBuffer buffer();

  /**
   * Returns true if there are no more bytes in the buffer or the source. This
   * will block until there are bytes to read or the source is definitely
   * exhausted.
   */
  boolean exhausted() throws IOException;

  /**
   * Returns when the buffer contains at least {@code byteCount} bytes. Throws
   * an {@link java.io.EOFException} if the source is exhausted before the
   * required bytes can be read.
   */
  void require(long byteCount) throws IOException;

  byte readByte() throws IOException;

  short readShort() throws IOException;

  int readShortLe() throws IOException;

  int readInt() throws IOException;

  int readIntLe() throws IOException;

  /**
   * Reads and discards {@code byteCount} bytes from {@code source} using {@code
   * buffer} as a buffer. Throws an {@link java.io.EOFException} if the source
   * is exhausted before the requested bytes can be skipped.
   */
  void skip(long byteCount) throws IOException;

  ByteString readByteString(int byteCount) throws IOException;

  String readUtf8(int byteCount) throws IOException;

  /**
   * Returns the index of {@code b} in the buffer, refilling it if necessary
   * until it is found. This reads an unbounded number of bytes into the buffer.
   *
   * @throws java.io.EOFException if the stream is exhausted before the
   *     requested byte is found.
   */
  long seek(byte b) throws IOException;

  /** Returns an input stream that reads from this source. */
  InputStream inputStream();
}
