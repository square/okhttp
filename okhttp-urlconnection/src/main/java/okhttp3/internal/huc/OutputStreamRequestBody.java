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
package okhttp3.internal.huc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ProtocolException;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;

/**
 * A request body that's written by application code to a target output stream. As bytes are written
 * to this stream they are either immediately transmitted to the remote webserver (via {@link
 * StreamingRequestBody}) or they're buffered in memory and transmitted all at once (via {@link
 * BufferedRequestBody}).
 */
abstract class OutputStreamRequestBody extends RequestBody {
  protected final Buffer buffer = new Buffer();
  private final OutputStream outputStream;

  /** The content length if known, or -1 if it is unknown. */
  private final long expectedContentLength;

  /**
   * The total number of bytes written to the output stream. Should match {@link
   * #expectedContentLength} when the stream is closed.
   */
  private long bytesReceived;

  /** True if no further bytes will be written to the output stream. */
  protected boolean closed;

  OutputStreamRequestBody(final long expectedContentLength) {
    this.expectedContentLength = expectedContentLength;
    this.outputStream = new OutputStream() {
      @Override public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override public void write(byte[] source, int offset, int byteCount) throws IOException {
        synchronized (OutputStreamRequestBody.this) {
          // TODO(jwilson): backpressure if buffer is large?

          if (OutputStreamRequestBody.this.closed) throw new IOException("closed");

          if (expectedContentLength != -1L && bytesReceived + byteCount > expectedContentLength) {
            throw new ProtocolException("expected " + expectedContentLength
                + " bytes but received " + bytesReceived + byteCount);
          }

          bytesReceived += byteCount;
          buffer.write(source, offset, byteCount);

          OutputStreamRequestBody.this.notifyAll();
        }
      }

      @Override public void close() throws IOException {
        OutputStreamRequestBody.this.close();
      }
    };
  }

  final OutputStream outputStream() {
    return outputStream;
  }

  synchronized final void close() throws IOException {
    if (expectedContentLength != -1L && bytesReceived != expectedContentLength) {
      throw new ProtocolException("expected " + expectedContentLength
          + " bytes but received " + bytesReceived);
    }
    closed = true;
    notifyAll();
  }

  @Override public long contentLength() throws IOException {
    return expectedContentLength;
  }

  @Override public final MediaType contentType() {
    return null; // We let the caller provide this in a regular header.
  }
}
