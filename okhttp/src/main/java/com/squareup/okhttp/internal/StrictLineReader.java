/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.squareup.okhttp.internal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static com.squareup.okhttp.internal.Util.ISO_8859_1;
import static com.squareup.okhttp.internal.Util.US_ASCII;
import static com.squareup.okhttp.internal.Util.UTF_8;

/**
 * Buffers input from an {@link InputStream} for reading lines.
 *
 * This class is used for buffered reading of lines. For purposes of this class, a line ends with
 * "\n" or "\r\n". End of input is reported by throwing {@code EOFException}. Unterminated line at
 * end of input is invalid and will be ignored, the caller may use {@code hasUnterminatedLine()}
 * to detect it after catching the {@code EOFException}.
 *
 * This class is intended for reading input that strictly consists of lines, such as line-based
 * cache entries or cache journal. Unlike the {@link BufferedReader} which in conjunction with
 * {@link InputStreamReader} provides similar functionality, this class uses different
 * end-of-input reporting and a more restrictive definition of a line.
 *
 * This class supports only charsets that encode '\r' and '\n' as a single byte with value 13
 * and 10, respectively, and the representation of no other character contains these values.
 * We currently check in constructor that the charset is one of US-ASCII, UTF-8 and ISO-8859-1.
 * The default charset is US_ASCII.
 */
public class StrictLineReader implements Closeable {
  private static final byte CR = (byte) '\r';
  private static final byte LF = (byte) '\n';

  private final InputStream in;
  private final Charset charset;

  // Buffered data is stored in {@code buf}. As long as no exception occurs, 0 <= pos <= end
  // and the data in the range [pos, end) is buffered for reading. At end of input, if there is
  // an unterminated line, we set end == -1, otherwise end == pos. If the underlying
  // {@code InputStream} throws an {@code IOException}, end may remain as either pos or -1.
  private byte[] buf;
  private int pos;
  private int end;

  /**
   * Constructs a new {@code StrictLineReader} with the default capacity and charset.
   *
   * @param in the {@code InputStream} to read data from.
   * @throws NullPointerException if {@code in} is null.
   */
  public StrictLineReader(InputStream in) {
    this(in, 8192);
  }

  /**
   * Constructs a new {@code LineReader} with the specified capacity and the default charset.
   *
   * @param in the {@code InputStream} to read data from.
   * @param capacity the capacity of the buffer.
   * @throws NullPointerException if {@code in} is null.
   * @throws IllegalArgumentException for negative or zero {@code capacity}.
   */
  public StrictLineReader(InputStream in, int capacity) {
    this(in, capacity, US_ASCII);
  }

  /**
   * Constructs a new {@code LineReader} with the specified charset and the default capacity.
   *
   * @param in the {@code InputStream} to read data from.
   * @param charset the charset used to decode data.
   * Only US-ASCII, UTF-8 and ISO-8859-1 is supported.
   * @throws NullPointerException if {@code in} or {@code charset} is null.
   * @throws IllegalArgumentException if the specified charset is not supported.
   */
  public StrictLineReader(InputStream in, Charset charset) {
    this(in, 8192, charset);
  }

  /**
   * Constructs a new {@code LineReader} with the specified capacity and charset.
   *
   * @param in the {@code InputStream} to read data from.
   * @param capacity the capacity of the buffer.
   * @param charset the charset used to decode data.
   * Only US-ASCII, UTF-8 and ISO-8859-1 is supported.
   * @throws NullPointerException if {@code in} or {@code charset} is null.
   * @throws IllegalArgumentException if {@code capacity} is negative or zero
   * or the specified charset is not supported.
   */
  public StrictLineReader(InputStream in, int capacity, Charset charset) {
    if (in == null || charset == null) {
      throw new NullPointerException();
    }
    if (capacity < 0) {
      throw new IllegalArgumentException("capacity <= 0");
    }
    if (!(charset.equals(US_ASCII) || charset.equals(UTF_8) || charset.equals(ISO_8859_1))) {
      throw new IllegalArgumentException("Unsupported encoding");
    }

    this.in = in;
    this.charset = charset;
    buf = new byte[capacity];
  }

  /**
   * Closes the reader by closing the underlying {@code InputStream} and
   * marking this reader as closed.
   *
   * @throws IOException for errors when closing the underlying {@code InputStream}.
   */
  @Override
  public void close() throws IOException {
    synchronized (in) {
      if (buf != null) {
        buf = null;
        in.close();
      }
    }
  }

  /**
   * Reads the next line. A line ends with {@code "\n"} or {@code "\r\n"},
   * this end of line marker is not included in the result.
   *
   * @return the next line from the input.
   * @throws IOException for underlying {@code InputStream} errors.
   * @throws EOFException for the end of source stream.
   */
  public String readLine() throws IOException {
    synchronized (in) {
      if (buf == null) {
        throw new IOException("LineReader is closed");
      }

      // Read more data if we are at the end of the buffered data.
      // Though it's an error to read after an exception, we will let {@code fillBuf()}
      // throw again if that happens; thus we need to handle end == -1 as well as end == pos.
      if (pos >= end) {
        fillBuf();
      }
      // Try to find LF in the buffered data and return the line if successful.
      for (int i = pos; i != end; ++i) {
        if (buf[i] == LF) {
          int lineEnd = (i != pos && buf[i - 1] == CR) ? i - 1 : i;
          String res = new String(buf, pos, lineEnd - pos, charset);
          pos = i + 1;
          return res;
        }
      }

      // Let's anticipate up to 80 characters on top of those already read.
      ByteArrayOutputStream out = new ByteArrayOutputStream(end - pos + 80) {
        @Override
        public String toString() {
          int length = (count > 0 && buf[count - 1] == CR) ? count - 1 : count;
          return new String(buf, 0, length, charset);
        }
      };

      while (true) {
        out.write(buf, pos, end - pos);
        // Mark unterminated line in case fillBuf throws EOFException or IOException.
        end = -1;
        fillBuf();
        // Try to find LF in the buffered data and return the line if successful.
        for (int i = pos; i != end; ++i) {
          if (buf[i] == LF) {
            if (i != pos) {
              out.write(buf, pos, i - pos);
            }
            pos = i + 1;
            return out.toString();
          }
        }
      }
    }
  }

  /**
   * Read an {@code int} from a line containing its decimal representation.
   *
   * @return the value of the {@code int} from the next line.
   * @throws IOException for underlying {@code InputStream} errors or conversion error.
   * @throws EOFException for the end of source stream.
   */
  public int readInt() throws IOException {
    String intString = readLine();
    try {
      return Integer.parseInt(intString);
    } catch (NumberFormatException e) {
      throw new IOException("expected an int but was \"" + intString + "\"");
    }
  }

  /**
   * Reads new input data into the buffer. Call only with pos == end or end == -1,
   * depending on the desired outcome if the function throws.
   *
   * @throws IOException for underlying {@code InputStream} errors.
   * @throws EOFException for the end of source stream.
   */
  private void fillBuf() throws IOException {
    int result = in.read(buf, 0, buf.length);
    if (result == -1) {
      throw new EOFException();
    }
    pos = 0;
    end = result;
  }
}

