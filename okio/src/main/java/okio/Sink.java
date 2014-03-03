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
 * Receives a stream of bytes. Use this interface to write data wherever it's
 * needed: to the network, storage, or a buffer in memory. Sinks may be layered
 * to transform received data, such as to compress, encrypt, throttle, or add
 * protocol framing.
 *
 * <p>Most application code shouldn't operate on a sink directly, but rather
 * {@link BufferedSink} which is both more efficient and more convenient. Use
 * {@link Okio#buffer(Sink)} to wrap any sink with a buffer.
 *
 * <p>Sinks are easy to test: just use an {@link OkBuffer} in your tests, and
 * read from it to confirm it received the data that was expected.
 *
 * <h3>Comparison with OutputStream</h3>
 * This interface is functionally equivalent to {@link java.io.OutputStream}.
 *
 * <p>{@code OutputStream} requires multiple layers when emitted data is
 * heterogeneous: a {@code DataOutputStream} for primitive values, a {@code
 * BufferedOutputStream} for buffering, and {@code OutputStreamWriter} for
 * charset encoding. This class uses {@code BufferedSink} for all of the above.
 *
 * <p>Sink is also easier to layer: there is no {@link
 * java.io.OutputStream#write(int) single-byte write} method that is awkward to
 * implement efficiently.
 *
 * <h3>Interop with OutputStream</h3>
 * Use {@link Okio#sink} to adapt an {@code OutputStream} to a sink. Use {@link
 * BufferedSink#outputStream} to adapt a sink to an {@code OutputStream}.
 */
public interface Sink extends Closeable {
  /** Removes {@code byteCount} bytes from {@code source} and appends them to this. */
  void write(OkBuffer source, long byteCount) throws IOException;

  /** Pushes all buffered bytes to their final destination. */
  void flush() throws IOException;

  /**
   * Sets the deadline for all operations on this sink.
   * @return this sink.
   */
  Sink deadline(Deadline deadline);

  /**
   * Pushes all buffered bytes to their final destination and releases the
   * resources held by this sink. It is an error to write a closed sink. It is
   * safe to close a sink more than once.
   */
  @Override void close() throws IOException;
}
