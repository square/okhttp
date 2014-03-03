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

import java.io.Closeable;
import java.io.IOException;

/**
 * Supplies a stream of bytes. Use this interface to read data from wherever
 * it's located: from the network, storage, or a buffer in memory. Sources may
 * be layered to transform supplied data, such as to decompress, decrypt, or
 * remove protocol framing.
 *
 * <p>Most applications shouldn't operate on a source directly, but rather
 * {@link BufferedSource} which is both more efficient and more convenient. Use
 * {@link Okio#buffer(Source)} to wrap any source with a buffer.
 *
 * <p>Sources are easy to test: just use an {@link OkBuffer} in your tests, and
 * fill it with the data your application is to read.
 *
 * <h3>Comparison with InputStream</h3>
 * This interface is functionally equivalent to {@link java.io.InputStream}.
 *
 * <p>{@code InputStream} requires multiple layers when consumed data is
 * heterogeneous: a {@code DataOutputStream} for primitive values, a {@code
 * BufferedInputStream} for buffering, and {@code InputStreamReader} for
 * strings. This class uses {@code BufferedSource} for all of the above.
 *
 * <p>Source avoids the impossible-to-implement {@link
 * java.io.InputStream#available available()} method. Instead callers specify
 * how many bytes they {@link BufferedSource#require require}.
 *
 * <p>Source omits the unsafe-to-compose {@link java.io.InputStream#mark mark
 * and reset} state that's tracked by {@code InputStream}; callers instead just
 * buffer what they need.
 *
 * <p>When implementing a source, you need not worry about the {@link
 * java.io.InputStream#read single-byte read} method that is awkward to
 * implement efficiently and that returns one of 257 possible values.
 *
 * <p>And source has a stronger {@code skip} method: {@link BufferedSource#skip}
 * won't return prematurely.
 *
 * <h3>Interop with InputStream</h3>
 * Use {@link Okio#source} to adapt an {@code InputStream} to a source. Use
 * {@link BufferedSource#inputStream} to adapt a source to an {@code
 * InputStream}.
 */
public interface Source extends Closeable {
  /**
   * Removes at least 1, and up to {@code byteCount} bytes from this and appends
   * them to {@code sink}. Returns the number of bytes read, or -1 if this
   * source is exhausted.
   */
  long read(OkBuffer sink, long byteCount) throws IOException;

  /**
   * Sets the deadline for all operations on this source.
   * @return this source.
   */
  Source deadline(Deadline deadline);

  /**
   * Closes this source and releases the resources held by this source. It is an
   * error to read a closed source. It is safe to close a source more than once.
   */
  @Override void close() throws IOException;
}
