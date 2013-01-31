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

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;

/**
 * An input stream for the body of an HTTP response.
 *
 * <p>Since a single socket's input stream may be used to read multiple HTTP
 * responses from the same server, subclasses shouldn't close the socket stream.
 *
 * <p>A side effect of reading an HTTP response is that the response cache
 * is populated. If the stream is closed early, that cache entry will be
 * invalidated.
 */
abstract class AbstractHttpInputStream extends InputStream {
  protected final InputStream in;
  protected final HttpEngine httpEngine;
  private final CacheRequest cacheRequest;
  private final OutputStream cacheBody;
  protected boolean closed;

  AbstractHttpInputStream(InputStream in, HttpEngine httpEngine, CacheRequest cacheRequest)
      throws IOException {
    this.in = in;
    this.httpEngine = httpEngine;

    OutputStream cacheBody = cacheRequest != null ? cacheRequest.getBody() : null;

    // some apps return a null body; for compatibility we treat that like a null cache request
    if (cacheBody == null) {
      cacheRequest = null;
    }

    this.cacheBody = cacheBody;
    this.cacheRequest = cacheRequest;
  }

  /**
   * read() is implemented using read(byte[], int, int) so subclasses only
   * need to override the latter.
   */
  @Override public final int read() throws IOException {
    return Util.readSingleByte(this);
  }

  protected final void checkNotClosed() throws IOException {
    if (closed) {
      throw new IOException("stream closed");
    }
  }

  protected final void cacheWrite(byte[] buffer, int offset, int count) throws IOException {
    if (cacheBody != null) {
      cacheBody.write(buffer, offset, count);
    }
  }

  /**
   * Closes the cache entry and makes the socket available for reuse. This
   * should be invoked when the end of the body has been reached.
   */
  protected final void endOfInput(boolean streamCancelled) throws IOException {
    if (cacheRequest != null) {
      cacheBody.close();
    }
    httpEngine.release(streamCancelled);
  }

  /**
   * Calls abort on the cache entry and disconnects the socket. This
   * should be invoked when the connection is closed unexpectedly to
   * invalidate the cache entry and to prevent the HTTP connection from
   * being reused. HTTP messages are sent in serial so whenever a message
   * cannot be read to completion, subsequent messages cannot be read
   * either and the connection must be discarded.
   *
   * <p>An earlier implementation skipped the remaining bytes, but this
   * requires that the entire transfer be completed. If the intention was
   * to cancel the transfer, closing the connection is the only solution.
   */
  protected final void unexpectedEndOfInput() {
    if (cacheRequest != null) {
      cacheRequest.abort();
    }
    httpEngine.release(true);
  }
}
