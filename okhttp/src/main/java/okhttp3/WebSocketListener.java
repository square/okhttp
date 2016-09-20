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
package okhttp3;

import java.io.IOException;
import okio.Buffer;

/**
 * Listener for server-initiated messages on a connected {@link WebSocket}. All callbacks will be
 * called on a single thread.
 *
 * <h2>Lifecycle Rules</h2>
 * <ul>
 * <li>Either {@link #onOpen} or {@link #onFailure} will be called first depending on if the web
 * socket was successfully opened or if there was an error connecting to the server or parsing its
 * response.</li>
 * <li>After {@link #onOpen} is called, {@link #onFailure} can be called at any time. No more
 * callbacks will follow a call to {@link #onFailure}.</li>
 * <li>After {@link #onOpen} is called, {@link #onMessage} and {@link #onPong} will be called for
 * each message and pong frame, respectively. Note: {@link #onPong} may be called while {@link
 * #onMessage} is reading the message because pong frames may interleave in the message body.</li>
 * <li>After {@link #onOpen} is called, {@link #onClose} may be called once. No calls to {@link
 * #onMessage} or {@link #onPong} will follow a call to {@link #onClose}.</li>
 * <li>{@link #onFailure} will be called if any of the other callbacks throws an exception.</li>
 * </ul>
 */
public interface WebSocketListener {
  /**
   * Called when the request has successfully been upgraded to a web socket. <b>Do not</b> use this
   * callback to write to the web socket. Start a new thread or use another thread in your
   * application.
   */
  void onOpen(WebSocket webSocket, Response response);

  /**
   * Called when a server message is received. The {@code type} indicates whether the {@code
   * payload} should be interpreted as UTF-8 text or binary data.
   *
   * <p>Implementations <strong>must</strong> call {@code source.close()} before returning. This
   * indicates completion of parsing the message payload and will consume any remaining bytes in the
   * message.
   *
   * <p>The {@linkplain ResponseBody#contentType() content type} of {@code message} will be either
   * {@link WebSocket#TEXT} or {@link WebSocket#BINARY} which indicates the format of the message.
   */
  void onMessage(ResponseBody message) throws IOException;

  /**
   * Called when a server pong is received. This is usually a result of calling {@link
   * WebSocket#sendPing(Buffer)} but might also be unsolicited directly from the server.
   */
  void onPong(Buffer payload);

  /**
   * Called when the server sends a close message. This may have been initiated from a call to
   * {@link WebSocket#close(int, String) close()} or as an unprompted message from the server.
   * If you did not explicitly call {@link WebSocket#close(int, String) close()}, you do not need
   * to do so in response to this callback. A matching close frame is automatically sent back to
   * the server.
   *
   * @param code The <a href="http://tools.ietf.org/html/rfc6455#section-7.4.1">RFC-compliant</a>
   * status code.
   * @param reason Reason for close or an empty string.
   */
  void onClose(int code, String reason);

  /**
   * Called when the transport or protocol layer of this web socket errors during communication, or
   * when another listener callback throws an exception. If the web socket was successfully
   * {@linkplain #onOpen opened} before this callback, it will have been closed automatically and
   * future interactions with it will throw {@link IOException}.
   *
   * @param response Non-null when the failure is because of an unexpected HTTP response (e.g.,
   * failed upgrade, non-101 response code, etc.).
   */
  void onFailure(Throwable t, Response response);
}
