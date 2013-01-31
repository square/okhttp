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
package com.squareup.okhttp.internal.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.CacheRequest;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;

/** An HTTP message body terminated by the end of the underlying stream. */
final class UnknownLengthHttpInputStream extends AbstractHttpInputStream {
  private boolean inputExhausted;

  UnknownLengthHttpInputStream(InputStream is, CacheRequest cacheRequest, HttpEngine httpEngine)
      throws IOException {
    super(is, httpEngine, cacheRequest);
  }

  @Override public int read(byte[] buffer, int offset, int count) throws IOException {
    checkOffsetAndCount(buffer.length, offset, count);
    checkNotClosed();
    if (in == null || inputExhausted) {
      return -1;
    }
    int read = in.read(buffer, offset, count);
    if (read == -1) {
      inputExhausted = true;
      endOfInput(false);
      return -1;
    }
    cacheWrite(buffer, offset, read);
    return read;
  }

  @Override public int available() throws IOException {
    checkNotClosed();
    return in == null ? 0 : in.available();
  }

  @Override public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    if (!inputExhausted) {
      unexpectedEndOfInput();
    }
  }
}
