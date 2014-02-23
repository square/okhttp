/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.squareup.okhttp.internal.http;

import java.io.IOException;
import java.net.ProtocolException;
import okio.BufferedSink;
import okio.Deadline;
import okio.OkBuffer;
import okio.Sink;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;

/**
 * An HTTP request body that's completely buffered in memory. This allows
 * the post body to be transparently re-sent if the HTTP request must be
 * sent multiple times.
 */
final class RetryableSink implements Sink {
  private boolean closed;
  private final int limit;
  private final OkBuffer content = new OkBuffer();

  public RetryableSink(int limit) {
    this.limit = limit;
  }

  public RetryableSink() {
    this(-1);
  }

  @Override public void close() throws IOException {
    if (closed) return;
    closed = true;
    if (content.byteCount() < limit) {
      throw new ProtocolException(
          "content-length promised " + limit + " bytes, but received " + content.byteCount());
    }
  }

  @Override public void write(OkBuffer source, long byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    checkOffsetAndCount(source.byteCount(), 0, byteCount);
    if (limit != -1 && content.byteCount() > limit - byteCount) {
      throw new ProtocolException("exceeded content-length limit of " + limit + " bytes");
    }
    content.write(source, byteCount);
  }

  @Override public void flush() throws IOException {
  }

  @Override public Sink deadline(Deadline deadline) {
    return this;
  }

  public long contentLength() throws IOException {
    return content.byteCount();
  }

  public void writeToSocket(BufferedSink socketOut) throws IOException {
    // Clone the content; otherwise we won't have data to retry.
    socketOut.write(content.clone(), content.byteCount());
  }
}
