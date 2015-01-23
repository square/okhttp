/*
 * Copyright (C) 2011 Google Inc.
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

package com.squareup.okhttp.mockwebserver;

/**
 * What should be done with the incoming socket.
 *
 * <p>Be careful when using values like {@link #DISCONNECT_AT_END}, {@link #SHUTDOWN_INPUT_AT_END}
 * and {@link #SHUTDOWN_OUTPUT_AT_END} that close a socket after a response, and where there are
 * follow-up requests. The client is unblocked and free to continue as soon as it has received the
 * entire response body. If and when the client makes a subsequent request using a pooled socket the
 * server may not have had time to close the socket. The socket will be closed at an indeterminate
 * point before or during the second request. It may be closed after client has started sending the
 * request body. If a request body is not retryable then the client may fail the request, making
 * client behavior non-deterministic. Add delays in the client to improve the chances that the
 * server has closed the socket before follow up requests are made.
 */
public enum SocketPolicy {

  /**
   * Keep the socket open after the response. This is the default HTTP/1.1
   * behavior.
   */
  KEEP_OPEN,

  /**
   * Close the socket after the response. This is the default HTTP/1.0
   * behavior.
   *
   * <p>See {@link SocketPolicy} for reasons why this can cause test flakiness and how to avoid it.
   */
  DISCONNECT_AT_END,

  /**
   * Wrap the socket with SSL at the completion of this request/response pair.
   * Used for CONNECT messages to tunnel SSL over an HTTP proxy.
   */
  UPGRADE_TO_SSL_AT_END,

  /**
   * Request immediate close of connection without even reading the request. Use
   * to simulate buggy SSL servers closing connections in response to
   * unrecognized TLS extensions.
   */
  DISCONNECT_AT_START,

  /**
   * Close connection after reading the request but before writing the response.
   * Use this to simulate late connection pool failures.
   */
  DISCONNECT_AFTER_REQUEST,

  /** Don't trust the client during the SSL handshake. */
  FAIL_HANDSHAKE,

  /**
   * Shutdown the socket input after sending the response. For testing bad
   * behavior.
   *
   * <p>See {@link SocketPolicy} for reasons why this can cause test flakiness and how to avoid it.
   */
  SHUTDOWN_INPUT_AT_END,

  /**
   * Shutdown the socket output after sending the response. For testing bad
   * behavior.
   *
   * <p>See {@link SocketPolicy} for reasons why this can cause test flakiness and how to avoid it.
   */
  SHUTDOWN_OUTPUT_AT_END,

  /**
   * Don't respond to the request but keep the socket open. For testing
   * read response header timeout issue.
   */
  NO_RESPONSE
}
