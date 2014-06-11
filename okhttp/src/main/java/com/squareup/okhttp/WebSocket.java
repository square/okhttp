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
package com.squareup.okhttp;

import java.io.IOException;
import okio.BufferedSink;

/** Blocking interface to connect and write to a web socket. */
public interface WebSocket {
  /** The format of a message payload. */
  enum PayloadType {
    /** UTF8-encoded text data. */
    TEXT,
    /** Arbitrary binary data. */
    BINARY
  }

  /**
   * Connects the web socket and blocks until the response can be processed. Once connected all
   * messages from the server are sent to the {@code listener}.
   * <p>
   * Note that transport-layer success (receiving a HTTP response code,
   * headers and body) does not necessarily indicate application-layer success:
   * {@code response} may still indicate an unhappy HTTP response code like 404
   * or 500.
   *
   * @throws IOException if the request could not be executed due to
   *     a connectivity problem or timeout. Because networks can
   *     fail during an exchange, it is possible that the remote server
   *     accepted the request before the failure.
   *
   * @throws IllegalStateException when the web socket has already been connected.
   */
  Response connect(WebSocketListener listener) throws IOException;

  /** The HTTP request which initiated this web socket. */
  Request request();

  /**
   * Stream a message payload to the server of specified {code type}.
   * <p>
   * You must call {@link BufferedSink#close() close()} to complete the message. Calls to
   * {@link BufferedSink#flush() flush()} write a frame fragment. The message may be empty.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  BufferedSink messageWriter(PayloadType type);

  /**
   * Send a close frame to the server.
   * <p>
   * Calls to {@linkplain #messageWriter create a writer} will throw an exception after calling
   * this method. The {@link WebSocketListener} will continue to get messages until its
   * {@link WebSocketListener#onClose onClose()} method is called.
   * <p>
   * It is an error to call this method before connecting or before calling close on an active
   * writer. Calling this method more than once has no effect.
   */
  void close();

  /** True if this web socket is closed and can no longer be written to. */
  boolean isClosed();
}
