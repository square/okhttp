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
package okhttp3.ws;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;

/** Blocking interface to connect and write to a web socket. */
public interface WebSocket {
  /** A {@link MediaType} indicating UTF-8 text frames should be used when sending the message. */
  MediaType TEXT = MediaType.parse("application/vnd.okhttp.websocket+text; charset=utf-8");
  /** A {@link MediaType} indicating binary frames should be used when sending the message. */
  MediaType BINARY = MediaType.parse("application/vnd.okhttp.websocket+binary");

  /**
   * Send a message payload to the server.
   *
   * <p>The {@linkplain RequestBody#contentType() content type} of {@code message} should be either
   * {@link #TEXT} or {@link #BINARY}.
   *
   * @throws IOException if unable to write the message. Clients must call {@link #close} when this
   * happens to ensure resources are cleaned up.
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  void sendMessage(RequestBody message) throws IOException;

  /**
   * Send a ping to the server with optional payload.
   *
   * @throws IOException if unable to write the ping.  Clients must call {@link #close} when this
   * happens to ensure resources are cleaned up.
   * @throws IllegalStateException if already closed.
   */
  void sendPing(Buffer payload) throws IOException;

  /**
   * Send a close frame to the server.
   *
   * <p>The corresponding {@link WebSocketListener} will continue to get messages until its {@link
   * WebSocketListener#onClose onClose()} method is called.
   *
   * <p>It is an error to call this method before calling close on an active writer. Calling this
   * method more than once has no effect.
   *
   * @throws IOException if unable to write the close message. Resources will still be freed.
   * @throws IllegalStateException if already closed.
   */
  void close(int code, String reason) throws IOException;
}
