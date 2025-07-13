/*
 * Copyright (C) 2020 Square, Inc.
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

import java.util.concurrent.TimeUnit.MILLISECONDS
import mockwebserver3.SocketEffect
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.SocketEffect.CloseStream
import mockwebserver3.SocketEffect.ShutdownConnection
import okio.Buffer
import okio.ByteString

internal fun Dispatcher.wrap(): mockwebserver3.Dispatcher {
  if (this is QueueDispatcher) return this.delegate

  val delegate = this
  return object : mockwebserver3.Dispatcher() {
    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse = delegate.dispatch(request.unwrap()).wrap()

    override fun peek(): mockwebserver3.MockResponse = delegate.peek().wrap()

    override fun close() {
      delegate.shutdown()
    }
  }
}

internal fun MockResponse.wrap(): mockwebserver3.MockResponse {
  val result = mockwebserver3.MockResponse.Builder()
  val copyFromWebSocketListener = webSocketListener
  if (copyFromWebSocketListener != null) {
    result.webSocketUpgrade(copyFromWebSocketListener)
  }

  val body = getBody()
  if (body != null) result.body(body)

  for (pushPromise in pushPromises) {
    result.addPush(pushPromise.wrap())
  }

  result.settings(settings)
  result.status(status)
  result.headers(headers)
  result.trailers(trailers)

  when (socketPolicy) {
    SocketPolicy.EXPECT_CONTINUE, SocketPolicy.CONTINUE_ALWAYS -> result.add100Continue()
    SocketPolicy.UPGRADE_TO_SSL_AT_END -> result.inTunnel()
    SocketPolicy.SHUTDOWN_SERVER_AFTER_RESPONSE -> result.shutdownServer(true)
    SocketPolicy.KEEP_OPEN -> Unit
    SocketPolicy.DISCONNECT_AT_END -> result.onResponseEnd(ShutdownConnection)
    SocketPolicy.DISCONNECT_AT_START -> result.onRequestStart(CloseSocket())
    SocketPolicy.DISCONNECT_AFTER_REQUEST -> result.onResponseStart(CloseSocket())
    SocketPolicy.DISCONNECT_DURING_REQUEST_BODY -> result.onRequestBody(CloseSocket())
    SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY -> result.onResponseBody(CloseSocket())
    SocketPolicy.DO_NOT_READ_REQUEST_BODY -> result.doNotReadRequestBody()
    SocketPolicy.FAIL_HANDSHAKE -> result.failHandshake()
    SocketPolicy.SHUTDOWN_INPUT_AT_END ->
      result.onResponseEnd(
        CloseSocket(
          closeSocket = false,
          shutdownInput = true,
        ),
      )
    SocketPolicy.SHUTDOWN_OUTPUT_AT_END ->
      result.onResponseEnd(
        CloseSocket(
          closeSocket = false,
          shutdownOutput = true,
        ),
      )
    SocketPolicy.STALL_SOCKET_AT_START -> result.onRequestStart(SocketEffect.Stall)
    SocketPolicy.NO_RESPONSE -> result.onResponseStart(SocketEffect.Stall)
    SocketPolicy.RESET_STREAM_AT_START -> result.onRequestStart(CloseStream(http2ErrorCode))
  }

  result.throttleBody(throttleBytesPerPeriod, getThrottlePeriod(MILLISECONDS), MILLISECONDS)
  result.bodyDelay(getBodyDelay(MILLISECONDS), MILLISECONDS)
  result.headersDelay(getHeadersDelay(MILLISECONDS), MILLISECONDS)
  return result.build()
}

private fun PushPromise.wrap(): mockwebserver3.PushPromise =
  mockwebserver3.PushPromise(
    method = method,
    path = path,
    headers = headers,
    response = response.wrap(),
  )

internal fun mockwebserver3.RecordedRequest.unwrap(): RecordedRequest =
  RecordedRequest(
    requestLine = requestLine,
    headers = headers,
    chunkSizes = chunkSizes ?: listOf(),
    bodySize = bodySize,
    body = Buffer().write(body ?: ByteString.EMPTY),
    sequenceNumber = exchangeIndex,
    failure = failure,
    method = method,
    path = target,
    handshake = handshake,
    requestUrl = url,
  )
