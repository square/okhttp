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
import mockwebserver3.SocketPolicy.DisconnectAfterRequest
import mockwebserver3.SocketPolicy.DisconnectAtEnd
import mockwebserver3.SocketPolicy.DisconnectAtStart
import mockwebserver3.SocketPolicy.DisconnectDuringRequestBody
import mockwebserver3.SocketPolicy.DisconnectDuringResponseBody
import mockwebserver3.SocketPolicy.DoNotReadRequestBody
import mockwebserver3.SocketPolicy.FailHandshake
import mockwebserver3.SocketPolicy.KeepOpen
import mockwebserver3.SocketPolicy.NoResponse
import mockwebserver3.SocketPolicy.ResetStreamAtStart
import mockwebserver3.SocketPolicy.ShutdownInputAtEnd
import mockwebserver3.SocketPolicy.ShutdownOutputAtEnd
import mockwebserver3.SocketPolicy.ShutdownServerAfterResponse
import mockwebserver3.SocketPolicy.StallSocketAtStart

internal fun Dispatcher.wrap(): mockwebserver3.Dispatcher {
  if (this is QueueDispatcher) return this.delegate

  val delegate = this
  return object : mockwebserver3.Dispatcher() {
    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
      return delegate.dispatch(request.unwrap()).wrap()
    }

    override fun peek(): mockwebserver3.MockResponse {
      return delegate.peek().wrap()
    }

    override fun shutdown() {
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
  result.status = status
  result.headers(headers)
  result.trailers(trailers)
  result.socketPolicy =
    when (socketPolicy) {
      SocketPolicy.EXPECT_CONTINUE, SocketPolicy.CONTINUE_ALWAYS -> {
        result.add100Continue()
        KeepOpen
      }
      SocketPolicy.UPGRADE_TO_SSL_AT_END -> {
        result.inTunnel()
        KeepOpen
      }
      else -> wrapSocketPolicy()
    }
  result.throttleBody(throttleBytesPerPeriod, getThrottlePeriod(MILLISECONDS), MILLISECONDS)
  result.bodyDelay(getBodyDelay(MILLISECONDS), MILLISECONDS)
  result.headersDelay(getHeadersDelay(MILLISECONDS), MILLISECONDS)
  return result.build()
}

private fun PushPromise.wrap(): mockwebserver3.PushPromise {
  return mockwebserver3.PushPromise(
    method = method,
    path = path,
    headers = headers,
    response = response.wrap(),
  )
}

internal fun mockwebserver3.RecordedRequest.unwrap(): RecordedRequest {
  return RecordedRequest(
    requestLine = requestLine,
    headers = headers,
    chunkSizes = chunkSizes,
    bodySize = bodySize,
    body = body,
    sequenceNumber = sequenceNumber,
    failure = failure,
    method = method,
    path = path,
    handshake = handshake,
    requestUrl = requestUrl,
  )
}

private fun MockResponse.wrapSocketPolicy(): mockwebserver3.SocketPolicy {
  return when (val socketPolicy = socketPolicy) {
    SocketPolicy.SHUTDOWN_SERVER_AFTER_RESPONSE -> ShutdownServerAfterResponse
    SocketPolicy.KEEP_OPEN -> KeepOpen
    SocketPolicy.DISCONNECT_AT_END -> DisconnectAtEnd
    SocketPolicy.DISCONNECT_AT_START -> DisconnectAtStart
    SocketPolicy.DISCONNECT_AFTER_REQUEST -> DisconnectAfterRequest
    SocketPolicy.DISCONNECT_DURING_REQUEST_BODY -> DisconnectDuringRequestBody
    SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY -> DisconnectDuringResponseBody
    SocketPolicy.DO_NOT_READ_REQUEST_BODY -> DoNotReadRequestBody(http2ErrorCode)
    SocketPolicy.FAIL_HANDSHAKE -> FailHandshake
    SocketPolicy.SHUTDOWN_INPUT_AT_END -> ShutdownInputAtEnd
    SocketPolicy.SHUTDOWN_OUTPUT_AT_END -> ShutdownOutputAtEnd
    SocketPolicy.STALL_SOCKET_AT_START -> StallSocketAtStart
    SocketPolicy.NO_RESPONSE -> NoResponse
    SocketPolicy.RESET_STREAM_AT_START -> ResetStreamAtStart(http2ErrorCode)
    else -> error("Unexpected SocketPolicy: $socketPolicy")
  }
}
