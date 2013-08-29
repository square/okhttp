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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Buffers input from an {@link InputStream} for reading lines.
 *
 * <p>This class is used for buffered reading of lines. For purposes of this class, a line ends with
 * "\n" or "\r\n". End of input is reported by throwing {@code EOFException}. Unterminated line at
 * end of input is invalid and will be ignored, the caller may use {@code hasUnterminatedLine()}
 * to detect it after catching the {@code EOFException}.
 *
 * <p>This class is intended for reading input that strictly consists of lines, such as line-based
 * cache entries or cache journal. Unlike the {@link java.io.BufferedReader} which in conjunction
 * with {@link java.io.InputStreamReader} provides similar functionality, this class uses different
 * end-of-input reporting and a more restrictive definition of a line.
 *
 * <p>This class supports only charsets that encode '\r' and '\n' as a single byte with value 13
 * and 10, respectively, and the representation of no other character contains these values.
 * US-ASCII, UTF-8 and ISO-8859-1 have this property. By default we use US-ASCII in
 * {@link #readLine()}, use {@code reader.readLinRef().toString(charsetName)} for other charsets.
 */
public class StrictLineReader implements Closeable {
  private static final byte CR = (byte) '\r';
  private static final byte LF = (byte) '\n';

  private final InputStream in;

  /*
   * Buffered data is stored in {@code buf}. As long as no exception occurs, 0 <= pos <= end
   * and the data in the range [pos, end) is buffered for reading. At end of input, if there is
   * an unterminated line, we set end == -1, otherwise end == pos. If the underlying
   * {@code InputStream} throws an {@code IOException}, end may remain as either pos or -1.
   */
  private byte[] buf;
  private int pos;
  private int end;

  /*
   * We're reusing the same ByteSequence for each {@code readLineRef()} to avoid extra allocations.
   * For lines that go across {@code fillBuf()}, we're trying to reuse a single byte array but
   * we may need to grow that array via {@code line.append(buf, pos, x)}.
   */
  private ByteSequence line;
  private byte[] splitLineData;

  /**
   * Constructs a new {@code LineReader} with the default capacity.
   *
   * @param in the {@code InputStream} to read data from.
   * @throws NullPointerException if {@code in} is null.
   */
  public StrictLineReader(InputStream in) {
    this(in, 8192);
  }

  /**
   * Constructs a new {@code LineReader} with the specified capacity.
   *
   * @param in the {@code InputStream} to read data from.
   * @param capacity the capacity of the buffer.
   * @throws NullPointerException if {@code in} is null.
   * @throws IllegalArgumentException if {@code capacity} is negative or zero.
   */
  public StrictLineReader(InputStream in, int capacity) {
    if (in == null) {
      throw new NullPointerException();
    }
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity <= 0");
    }

    this.in = in;
    buf = new byte[capacity];
    line = new ByteSequence();
  }

  /**
   * Closes the reader by closing the underlying {@code InputStream} and
   * marking this reader as closed.
   *
   * @throws IOException for errors when closing the underlying {@code InputStream}.
   */
  @Override public void close() throws IOException {
    if (buf != null) {
      buf = null;
      line = null;
      splitLineData = null;
      in.close();
    }
  }

  /**
   * Reads the next line reference. The reference is valid until next call to any
   * method of this reader. A line ends with {@code "\n"} or {@code "\r\n"},
   * this end of line marker is not included in the result.
   *
   * <p>The caller may overwrite the underlying data of the returned reference. However,
   * overwriting the underlying byte array outside the range specified by the reference
   * leads to undefined behavior. This is useful for in-place decoding.
   *
   * @return the next line from the input.
   * @throws IOException for underlying {@code InputStream} errors.
   * @throws EOFException for the end of source stream.
   */
  public ByteSequence readLineRef() throws IOException {
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
        line.reset(buf, pos, lineEnd - pos);
        pos = i + 1;
        return line;
      }
    }

    // Let's anticipate up to 80 characters on top of those already read.
    line.reset(splitLineData != null ? splitLineData : new byte[end - pos + 80], 0, 0);

    while (true) {
      line.append(buf, pos, end - pos);
      // Mark unterminated line in case fillBuf throws EOFException or IOException.
      end = -1;
      fillBuf();
      // Try to find LF in the buffered data and return the line if successful.
      for (int i = pos; i != end; ++i) {
        if (buf[i] == LF) {
          if (i != pos) {
            line.append(buf, pos, i - pos);
          }
          pos = i + 1;
          int len = line.length();
          if (len != 0 && line.byteAt(len - 1) == CR) {
            line.truncate(len - 1);
          }
          splitLineData = line.data();  // reuse next time
          return line;
        }
      }
    }
  }

  /**
   * Reads the next line as ASCII string. A line ends with {@code "\n"} or {@code "\r\n"},
   * this end of line marker is not included in the result.
   *
   * @return the next line from the input.
   * @throws IOException for underlying {@code InputStream} errors.
   * @throws EOFException for the end of source stream.
   */
  public String readLine() throws IOException {
    return readLineRef().toString("US-ASCII");
  }

  /**
   * Read an {@code int} from a line containing its decimal representation.
   *
   * @return the value of the {@code int} from the next line.
   * @throws IOException for underlying {@code InputStream} errors or conversion error.
   * @throws EOFException for the end of source stream.
   */
  public int readInt() throws IOException {
    ByteSequence line = readLineRef();
    try {
      return line.toInt();
    } catch (NumberFormatException e) {
      throw new IOException("expected an int but was \"" + line + "\"");
    }
  }

  /**
   * Reads new input data into the buffer. Call only with pos == end or end == -1,
   * depending on the desired outcome if the function throws.
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
