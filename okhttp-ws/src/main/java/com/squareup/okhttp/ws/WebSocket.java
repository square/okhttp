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
package com.squareup.okhttp.ws;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;

/** Blocking interface to write to a web socket. */
public interface WebSocket {
  /** The format of a message payload. */
  enum PayloadType {
    /** UTF8-encoded text data. */
    TEXT,
    /** Arbitrary binary data. */
    BINARY
  }

  /** Begin reading messages from the peer and notifying {@code listener}. */
  void start(Listener listener);

  /**
   * Stream a message payload of the specified {code type}.
   * <p>
   * You must call {@link BufferedSink#close() close()} to complete the message. Calls to
   * {@link BufferedSink#flush() flush()} write a frame fragment. The message may be empty.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  BufferedSink newMessageSink(WebSocket.PayloadType type);

  /**
   * Send a message payload of the specified {@code type}.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  void sendMessage(WebSocket.PayloadType type, Buffer payload) throws IOException;

  /**
   * Send a ping with optional payload.
   *
   * @throws IllegalStateException if already closed.
   */
  void sendPing(Buffer payload) throws IOException;

  /**
   * Send a close frame to the peer.
   * <p>
   * The {@link Listener} will continue to get messages until its {@link Listener#onClose
   * onClose()} method is called.
   * <p>
   * It is an error to call this method before calling close on an active writer. Calling this
   * method more than once has no effect.
   *
   * @throws IllegalStateException if already closed.
   */
  void close(int code, String reason) throws IOException;

  /** Listener for peer messages on a connected {@link WebSocket}. */
  interface Listener {
    /**
     * Called when a message is received. The {@code type} indicates whether the
     * {@code payload} should be interpreted as UTF-8 text or binary data.
     *
     * <p>Implementations <strong>must</strong> call {@code source.close()} before returning. This
     * indicates completion of parsing the message payload and will consume any remaining bytes in
     * the message.
     */
    void onMessage(BufferedSource payload, PayloadType type) throws IOException;

    /**
     * Called when a pong is received. This is usually a result of calling {@link
     * WebSocket#sendPing(Buffer)} but might also be unsolicited.
     */
    void onPong(Buffer payload);

    /**
     * Called when the peer sends a close message. This may have been initiated
     * from a call to {@link WebSocket#close(int, String) close()} or as an unprompted
     * message from the peer.
     *
     * @param code The <a href="http://tools.ietf.org/html/rfc6455#section-7.4.1">RFC-compliant</a>
     * status code.
     * @param reason Reason for close or an empty string.
     */
    void onClose(int code, String reason);

    void onFailure(IOException e);
  }
}
