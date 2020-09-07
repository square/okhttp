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

package okhttp3.mockwebserver

/**
 * What should be done with the incoming socket.
 *
 * Be careful when using values like [DISCONNECT_AT_END], [SHUTDOWN_INPUT_AT_END]
 * and [SHUTDOWN_OUTPUT_AT_END] that close a socket after a response, and where there are
 * follow-up requests. The client is unblocked and free to continue as soon as it has received the
 * entire response body. If and when the client makes a subsequent request using a pooled socket the
 * server may not have had time to close the socket. The socket will be closed at an indeterminate
 * point before or during the second request. It may be closed after client has started sending the
 * request body. If a request body is not retryable then the client may fail the request, making
 * client behavior non-deterministic. Add delays in the client to improve the chances that the
 * server has closed the socket before follow up requests are made.
 */
enum class SocketPolicy {

  /**
   * Shutdown [MockWebServer] after writing response.
   */
  SHUTDOWN_SERVER_AFTER_RESPONSE,

  /**
   * Keep the socket open after the response. This is the default HTTP/1.1 behavior.
   */
  KEEP_OPEN,

  /**
   * Close the socket after the response. This is the default HTTP/1.0 behavior. For HTTP/2
   * connections, this sends a [GOAWAYframe](https://tools.ietf.org/html/rfc7540#section-6.8)
   * immediately after the response and will close the connection when the client's socket
   * is exhausted.
   *
   * See [SocketPolicy] for reasons why this can cause test flakiness and how to avoid it.
   */
  DISCONNECT_AT_END,

  /**
   * Wrap the socket with SSL at the completion of this request/response pair. Used for CONNECT
   * messages to tunnel SSL over an HTTP proxy.
   */
  UPGRADE_TO_SSL_AT_END,

  /**
   * Request immediate close of connection without even reading the request. Use to simulate buggy
   * SSL servers closing connections in response to unrecognized TLS extensions.
   */
  DISCONNECT_AT_START,

  /**
   * Close connection after reading the request but before writing the response. Use this to
   * simulate late connection pool failures.
   */
  DISCONNECT_AFTER_REQUEST,

  /** Close connection after reading half of the request body (if present). */
  DISCONNECT_DURING_REQUEST_BODY,

  /** Close connection after writing half of the response body (if present). */
  DISCONNECT_DURING_RESPONSE_BODY,

  /** Don't trust the client during the SSL handshake. */
  FAIL_HANDSHAKE,

  /**
   * Shutdown the socket input after sending the response. For testing bad behavior.
   *
   * See [SocketPolicy] for reasons why this can cause test flakiness and how to avoid it.
   */
  SHUTDOWN_INPUT_AT_END,

  /**
   * Shutdown the socket output after sending the response. For testing bad behavior.
   *
   * See [SocketPolicy] for reasons why this can cause test flakiness and how to avoid it.
   */
  SHUTDOWN_OUTPUT_AT_END,

  /**
   * After accepting the connection and doing TLS (if configured) don't do HTTP/1.1 or HTTP/2
   * framing. Ignore the socket completely until the server is shut down.
   */
  STALL_SOCKET_AT_START,

  /**
   * Read the request but don't respond to it. Just keep the socket open. For testing read response
   * header timeout issue.
   */
  NO_RESPONSE,

  /**
   * Fail HTTP/2 requests without processing them by sending an [MockResponse.getHttp2ErrorCode].
   */
  RESET_STREAM_AT_START,

  /**
   * Transmit a `HTTP/1.1 100 Continue` response before reading the HTTP request body.
   * Typically this response is sent when a client makes a request with the header `Expect: 100-continue`.
   */
  EXPECT_CONTINUE,

  /**
   * Transmit a `HTTP/1.1 100 Continue` response before reading the HTTP request body even
   * if the client does not send the header `Expect: 100-continue` in its request.
   */
  CONTINUE_ALWAYS
}
