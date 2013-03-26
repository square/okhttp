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
import java.io.OutputStream;
import java.net.CacheRequest;

interface Transport {
  /**
   * Returns an output stream where the request body can be written. The
   * returned stream will of one of two types:
   * <ul>
   * <li><strong>Direct.</strong> Bytes are written to the socket and
   * forgotten. This is most efficient, particularly for large request
   * bodies. The returned stream may be buffered; the caller must call
   * {@link #flushRequest} before reading the response.</li>
   * <li><strong>Buffered.</strong> Bytes are written to an in memory
   * buffer, and must be explicitly flushed with a call to {@link
   * #writeRequestBody}. This allows HTTP authorization (401, 407)
   * responses to be retransmitted transparently.</li>
   * </ul>
   */
  // TODO: don't bother retransmitting the request body? It's quite a corner
  // case and there's uncertainty whether Firefox or Chrome do this
  OutputStream createRequestBody() throws IOException;

  /** This should update the HTTP engine's sentRequestMillis field. */
  void writeRequestHeaders() throws IOException;

  /**
   * Sends the request body returned by {@link #createRequestBody} to the
   * remote peer.
   */
  void writeRequestBody(RetryableOutputStream requestBody) throws IOException;

  /** Flush the request body to the underlying socket. */
  void flushRequest() throws IOException;

  /** Read response headers and update the cookie manager. */
  ResponseHeaders readResponseHeaders() throws IOException;

  // TODO: make this the content stream?
  InputStream getTransferStream(CacheRequest cacheRequest) throws IOException;

  /** Returns true if the underlying connection can be recycled. */
  boolean makeReusable(boolean streamReusable, OutputStream requestBodyOut,
      InputStream responseBodyIn);
}
