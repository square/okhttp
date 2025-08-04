/*
 * Copyright (C) 2025 Square, Inc.
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

/**
 * An adverse action to take on a socket, intended to exercise failure modes in the calling code.
 */
public sealed interface SocketEffect {
  /**
   * Close the TCP socket that carries this request.
   *
   * Using this as [MockResponse.onResponseEnd] is the default for HTTP/1.0.
   */
  public class CloseSocket(
    public val closeSocket: Boolean = true,
    public val shutdownInput: Boolean = false,
    public val shutdownOutput: Boolean = false,
  ) : SocketEffect

  /**
   * On HTTP/2, send a [GOAWAY frame](https://tools.ietf.org/html/rfc7540#section-6.8) immediately
   * after the response and will close the connection when the client's socket is exhausted.
   *
   * On HTTP/1 this closes the socket.
   */
  public object ShutdownConnection : SocketEffect

  /**
   * On HTTP/2 this will send the error code on the stream.
   *
   * On HTTP/1 this closes the socket.
   */
  public class CloseStream(
    public val http2ErrorCode: Int = 0,
  ) : SocketEffect

  /** Stop processing this. */
  public object Stall : SocketEffect
}
