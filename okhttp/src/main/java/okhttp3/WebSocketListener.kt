/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3

import okio.ByteString

abstract class WebSocketListener {
  /**
   * Invoked when a web socket has been accepted by the remote peer and may begin transmitting
   * messages.
   */
  open fun onOpen(webSocket: WebSocket, response: Response) {
  }

  /** Invoked when a text (type `0x1`) message has been received. */
  open fun onMessage(webSocket: WebSocket, text: String) {
  }

  /** Invoked when a binary (type `0x2`) message has been received. */
  open fun onMessage(webSocket: WebSocket, bytes: ByteString) {
  }

  /**
   * Invoked when the remote peer has indicated that no more incoming messages will be transmitted.
   */
  open fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
  }

  /**
   * Invoked when both peers have indicated that no more messages will be transmitted and the
   * connection has been successfully released. No further calls to this listener will be made.
   */
  open fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
  }

  /**
   * Invoked when a web socket has been closed due to an error reading from or writing to the
   * network. Both outgoing and incoming messages may have been lost. No further calls to this
   * listener will be made.
   */
  open fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
  }
}
