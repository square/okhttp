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
import okio.BufferedSource;

import static com.squareup.okhttp.WebSocket.PayloadType;

/** Listener for server-initiated messages on a connected {@link WebSocket}. */
public interface WebSocketListener {
  /**
   * Called when a server message is received. The {@code type} indicates whether the
   * {@code payload} should be interpreted as UTF-8 text or binary data.
   */
  void onMessage(BufferedSource payload, PayloadType type);

  /**
   * Called when the server sends a close message. This may have been initiated
   * from a call to {@link WebSocket#close()} or as an unprompted message from
   * the server.
   *
   * @param code The <a href="http://tools.ietf.org/html/rfc6455#section-7.4.1>RFC-compliant</a>
   * status code or {@code -1} for none.
   * @param reason Optional reason for close. May be {@code null}.
   */
  void onClose(int code, String reason);

  /**
   * Called when the transport-layer of this web socket errors during
   * communication.
   */
  void onFailure(IOException e);
}
