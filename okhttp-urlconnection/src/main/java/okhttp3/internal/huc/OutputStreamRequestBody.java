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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import okhttp3.Body;
import okhttp3.Request;
import okio.BufferedSink;
import okio.Timeout;

/**
 * A request body that's populated by blocking writes to an output stream. The output data is either
 * fully buffered (with {@link BufferedRequestBody}) or streamed (with {@link StreamedRequestBody}).
 * In either case the bytes of the body aren't known until the caller writes them to the output
 * stream.
 */
abstract class OutputStreamRequestBody extends Body {
  private Timeout timeout;
  private long expectedContentLength;
  private OutputStream outputStream;
  boolean closed;

  OutputStreamRequestBody() {
    super(null);
  }

  protected void initOutputStream(final BufferedSink sink, final long expectedContentLength) {
    this.timeout = sink.timeout();
    this.expectedContentLength = expectedContentLength;

    // An output stream that writes to sink. If expectedContentLength is not -1, then this expects
    // exactly that many bytes to be written.
    this.outputStream = new OutputStream() {
      private long bytesReceived;

      @Override public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override public void write(byte[] source, int offset, int byteCount) throws IOException {
        if (closed) throw new IOException("closed"); // Not IllegalStateException!

        if (expectedContentLength != -1L && bytesReceived + byteCount > expectedContentLength) {
          throw new ProtocolException("expected " + expectedContentLength
              + " bytes but received " + bytesReceived + byteCount);
        }

        bytesReceived += byteCount;
        try {
          sink.write(source, offset, byteCount);
        } catch (InterruptedIOException e) {
          throw new SocketTimeoutException(e.getMessage());
        }
      }

      @Override public void flush() throws IOException {
        if (closed) return; // Weird, but consistent with historical behavior.
        sink.flush();
      }

      @Override public void close() throws IOException {
        closed = true;

        if (expectedContentLength != -1L && bytesReceived < expectedContentLength) {
          throw new ProtocolException("expected " + expectedContentLength
              + " bytes but received " + bytesReceived);
        }

        sink.close();
      }
    };
  }

  public final OutputStream outputStream() {
    return outputStream;
  }

  public final Timeout timeout() {
    return timeout;
  }

  public final boolean isClosed() {
    return closed;
  }

  @Override public long contentLength() throws IOException {
    return expectedContentLength;
  }

  public Request prepareToSendRequest(Request request) throws IOException {
    return request;
  }
}
