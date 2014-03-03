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
  /** Returns this source's internal buffer. */
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

  /** Removes a byte from the front of this buffer and returns it. */
  byte readByte() throws IOException;

  /** Removes a Big-Endian short from the front of this buffer and returns it. */
  short readShort() throws IOException;

  /** Removes a Little-Endian short from the front of this buffer and returns it. */
  int readShortLe() throws IOException;

  /** Removes a Big-Endian int from the front of this buffer and returns it. */
  int readInt() throws IOException;

  /** Removes a Little-Endian int from the front of this buffer and returns it. */
  int readIntLe() throws IOException;

  /**
   * Reads and discards {@code byteCount} bytes from {@code source} using {@code
   * buffer} as a buffer. Throws an {@link java.io.EOFException} if the source
   * is exhausted before the requested bytes can be skipped.
   */
  void skip(long byteCount) throws IOException;

  /** Removes {@code byteCount} bytes from this and returns them as a byte string. */
  ByteString readByteString(long byteCount) throws IOException;

  /**
   * Removes {@code byteCount} bytes from this, decodes them as UTF-8 and
   * returns the string.
   */
  String readUtf8(long byteCount) throws IOException;

  /**
   * Removes and returns characters up to but not including the next line break.
   * A line break is either {@code "\n"} or {@code "\r\n"}; these characters are
   * not included in the result.
   *
   * <p>This method supports two ways to handle the end of the stream:
   * <ul>
   *   <li><strong>Throw on EOF.</strong> Every call must consume either '\r\n'
   *       or '\n'. If these characters are absent in the stream, an {@link
   *       java.io.EOFException} is thrown. Use this for machine-generated data
   *       where a missing line break implies truncated input.
   *   <li><strong>Don't throw, just like BufferedReader.</strong> If the source
   *       doesn't end with a line break then an implicit line break is assumed.
   *       Null is returned once the source is exhausted. Use this for
   *       human-generated data, where a trailing line breaks are optional.
   * </ul>
   */
  String readUtf8Line(boolean throwOnEof) throws IOException;

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
