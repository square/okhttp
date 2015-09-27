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

import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import java.io.IOException;
import okio.Buffer;

/** Listener for server-initiated messages on a connected {@link WebSocket}. */
public interface WebSocketListener {
  /**
   * Called when the request has successfully been upgraded to a web socket. This method is called
   * on the message reading thread to allow setting up any state before the
   * {@linkplain #onMessage message}, {@linkplain #onPong pong}, and {@link #onClose close}
   * callbacks start.
   * <p>
   * <b>Do not</b> use this callback to write to the web socket. Start a new thread or use
   * another thread in your application.
   */
  void onOpen(WebSocket webSocket, Response response);

  /**
   * Called when the transport or protocol layer of this web socket errors during communication.
   *
   * @param response Present when the failure is a direct result of the response (e.g., failed
   * upgrade, non-101 response code, etc.). {@code null} otherwise.
   */
  void onFailure(IOException e, Response response);

  /**
   * Called when a server message is received. The {@code type} indicates whether the
   * {@code payload} should be interpreted as UTF-8 text or binary data.
   *
   * <p>Implementations <strong>must</strong> call {@code source.close()} before returning. This
   * indicates completion of parsing the message payload and will consume any remaining bytes in
   * the message.
   *
   * <p>The {@linkplain ResponseBody#contentType() content type} of {@code message} will be either
   * {@link WebSocket#TEXT} or {@link WebSocket#BINARY} which indicates the format of the message.
   */
  void onMessage(ResponseBody message) throws IOException;

  /**
   * Called when a server pong is received. This is usually a result of calling {@link
   * WebSocket#sendPing(Buffer)} but might also be unsolicited.
   */
  void onPong(Buffer payload);

  /**
   * Called when the server sends a close message. This may have been initiated
   * from a call to {@link WebSocket#close(int, String) close()} or as an unprompted
   * message from the server.
   *
   * @param code The <a href="http://tools.ietf.org/html/rfc6455#section-7.4.1">RFC-compliant</a>
   * status code.
   * @param reason Reason for close or an empty string.
   */
  void onClose(int code, String reason);
}
