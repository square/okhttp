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

package mockwebserver3

import okhttp3.ExperimentalOkHttpApi

/**
 * What should be done with the incoming socket.
 *
 * Be careful when using values like [DisconnectAtEnd], [ShutdownInputAtEnd]
 * and [ShutdownOutputAtEnd] that close a socket after a response, and where there are
 * follow-up requests. The client is unblocked and free to continue as soon as it has received the
 * entire response body. If and when the client makes a subsequent request using a pooled socket the
 * server may not have had time to close the socket. The socket will be closed at an indeterminate
 * point before or during the second request. It may be closed after client has started sending the
 * request body. If a request body is not retryable then the client may fail the request, making
 * client behavior non-deterministic. Add delays in the client to improve the chances that the
 * server has closed the socket before follow up requests are made.
 */
@ExperimentalOkHttpApi
sealed interface SocketPolicy {
  /**
   * Shutdown [MockWebServer] after writing response.
   */
  object ShutdownServerAfterResponse : SocketPolicy

  /**
   * Keep the socket open after the response. This is the default HTTP/1.1 behavior.
   */
  object KeepOpen : SocketPolicy

  /**
   * Close the socket after the response. This is the default HTTP/1.0 behavior. For HTTP/2
   * connections, this sends a [GOAWAYframe](https://tools.ietf.org/html/rfc7540#section-6.8)
   * immediately after the response and will close the connection when the client's socket
   * is exhausted.
   *
   * See [SocketPolicy] for reasons why this can cause test flakiness and how to avoid it.
   */
  object DisconnectAtEnd : SocketPolicy

  /**
   * Request immediate close of connection without even reading the request. Use to simulate buggy
   * SSL servers closing connections in response to unrecognized TLS extensions.
   */
  object DisconnectAtStart : SocketPolicy

  /**
   * Close connection after reading the request but before writing the response. Use this to
   * simulate late connection pool failures.
   */
  object DisconnectAfterRequest : SocketPolicy

  /**
   * Half close connection (InputStream for client) after reading the request but before
   * writing the response. Use this to simulate late connection pool failures.
   */
  object HalfCloseAfterRequest : SocketPolicy

  /** Close connection after reading half of the request body (if present). */
  object DisconnectDuringRequestBody : SocketPolicy

  /** Close connection after writing half of the response body (if present). */
  object DisconnectDuringResponseBody : SocketPolicy

  /**
   * Process the response without even attempting to reading the request body. For HTTP/2 this will
   * send [http2ErrorCode] after the response body or trailers. For HTTP/1 this will close the
   * socket after the response body or trailers.
   */
  class DoNotReadRequestBody(
    val http2ErrorCode: Int = 0,
  ) : SocketPolicy

  /** Don't trust the client during the SSL handshake. */
  object FailHandshake : SocketPolicy

  /**
   * Shutdown the socket input after sending the response. For testing bad behavior.
   *
   * See [SocketPolicy] for reasons why this can cause test flakiness and how to avoid it.
   */
  object ShutdownInputAtEnd : SocketPolicy

  /**
   * Shutdown the socket output after sending the response. For testing bad behavior.
   *
   * See [SocketPolicy] for reasons why this can cause test flakiness and how to avoid it.
   */
  object ShutdownOutputAtEnd : SocketPolicy

  /**
   * After accepting the connection and doing TLS (if configured) don't do HTTP/1.1 or HTTP/2
   * framing. Ignore the socket completely until the server is shut down.
   */
  object StallSocketAtStart : SocketPolicy

  /**
   * Read the request but don't respond to it. Just keep the socket open. For testing read response
   * header timeout issue.
   */
  object NoResponse : SocketPolicy

  /**
   * Fail HTTP/2 requests without processing them by sending [http2ErrorCode].
   */
  @ExperimentalOkHttpApi
  class ResetStreamAtStart(
    val http2ErrorCode: Int = 0,
  ) : SocketPolicy
}
