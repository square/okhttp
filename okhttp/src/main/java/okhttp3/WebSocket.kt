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

/**
 * A non-blocking interface to a web socket. Use the [factory][WebSocket.Factory] to create
 * instances; usually this is [OkHttpClient].
 *
 * ## Web Socket Lifecycle
 *
 * Upon normal operation each web socket progresses through a sequence of states:
 *
 *  * **Connecting:** the initial state of each web socket. Messages may be enqueued but they won't
 *    be transmitted until the web socket is open.
 *
 *  * **Open:** the web socket has been accepted by the remote peer and is fully operational.
 *    Messages in either direction are enqueued for immediate transmission.
 *
 *  * **Closing:** one of the peers on the web socket has initiated a graceful shutdown. The web
 *    socket will continue to transmit already-enqueued messages but will refuse to enqueue new
 *    ones.
 *
 *  * **Closed:** the web socket has transmitted all of its messages and has received all messages
 *    from the peer.
 *
 * Web sockets may fail due to HTTP upgrade problems, connectivity problems, or if either peer
 * chooses to short-circuit the graceful shutdown process:
 *
 *  * **Canceled:** the web socket connection failed. Messages that were successfully enqueued by
 *    either peer may not have been transmitted to the other.
 *
 * Note that the state progression is independent for each peer. Arriving at a gracefully-closed
 * state indicates that a peer has sent all of its outgoing messages and received all of its
 * incoming messages. But it does not guarantee that the other peer will successfully receive all of
 * its incoming messages.
 */
interface WebSocket {
  /** Returns the original request that initiated this web socket. */
  fun request(): Request

  /**
   * Returns the size in bytes of all messages enqueued to be transmitted to the server. This
   * doesn't include framing overhead. It also doesn't include any bytes buffered by the operating
   * system or network intermediaries. This method returns 0 if no messages are waiting in the
   * queue. If may return a nonzero value after the web socket has been canceled; this indicates
   * that enqueued messages were not transmitted.
   */
  fun queueSize(): Long

  /**
   * Attempts to enqueue `text` to be UTF-8 encoded and sent as a the data of a text (type `0x1`)
   * message.
   *
   * This method returns true if the message was enqueued. Messages that would overflow the outgoing
   * message buffer will be rejected and trigger a [graceful shutdown][close] of this web socket.
   * This method returns false in that case, and in any other case where this web socket is closing,
   * closed, or canceled.
   *
   * This method returns immediately.
   */
  fun send(text: String): Boolean

  /**
   * Attempts to enqueue `bytes` to be sent as a the data of a binary (type `0x2`) message.
   *
   * This method returns true if the message was enqueued. Messages that would overflow the outgoing
   * message buffer (16 MiB) will be rejected and trigger a [graceful shutdown][close] of this web
   * socket. This method returns false in that case, and in any other case where this web socket is
   * closing, closed, or canceled.
   *
   * This method returns immediately.
   */
  fun send(bytes: ByteString): Boolean

  /**
   * Attempts to initiate a graceful shutdown of this web socket. Any already-enqueued messages will
   * be transmitted before the close message is sent but subsequent calls to [send] will return
   * false and their messages will not be enqueued.
   *
   * This returns true if a graceful shutdown was initiated by this call. It returns false if
   * a graceful shutdown was already underway or if the web socket is already closed or canceled.
   *
   * @param code Status code as defined by
   *     [Section 7.4 of RFC 6455](http://tools.ietf.org/html/rfc6455#section-7.4).
   * @param reason Reason for shutting down or null.
   * @throws IllegalArgumentException if code is invalid.
   */
  fun close(code: Int, reason: String?): Boolean

  /**
   * Immediately and violently release resources held by this web socket, discarding any enqueued
   * messages. This does nothing if the web socket has already been closed or canceled.
   */
  fun cancel()

  interface Factory {
    /**
     * Creates a new web socket and immediately returns it. Creating a web socket initiates an
     * asynchronous process to connect the socket. Once that succeeds or fails, `listener` will be
     * notified. The caller must either close or cancel the returned web socket when it is no longer
     * in use.
     */
    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket
  }
}
