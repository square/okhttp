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
package okhttp3.mockwebserverwrapper

import java.util.concurrent.TimeUnit.MILLISECONDS

internal fun Dispatcher.wrap(): okhttp3.mockwebserver.Dispatcher {
  if (this is QueueDispatcher) return this.delegate

  val delegate = this
  return object : okhttp3.mockwebserver.Dispatcher() {
    override fun dispatch(
      request: okhttp3.mockwebserver.RecordedRequest
    ): okhttp3.mockwebserver.MockResponse {
      return delegate.dispatch(request.unwrap()).wrap()
    }

    override fun peek(): okhttp3.mockwebserver.MockResponse {
      return delegate.peek().wrap()
    }

    override fun shutdown() {
      delegate.shutdown()
    }
  }
}

internal fun MockResponse.wrap(): okhttp3.mockwebserver.MockResponse {
  val result = okhttp3.mockwebserver.MockResponse()
  val copyFromWebSocketListener = webSocketListener
  if (copyFromWebSocketListener != null) {
    result.withWebSocketUpgrade(copyFromWebSocketListener)
  }

  val body = getBody()
  if (body != null) result.setBody(body)

  for (pushPromise in pushPromises) {
    result.withPush(pushPromise.wrap())
  }

  result.withSettings(settings)
  result.status = status
  result.headers = headers
  result.trailers = trailers
  result.socketPolicy = socketPolicy.wrap()
  result.http2ErrorCode = http2ErrorCode
  result.throttleBody(throttleBytesPerPeriod, getThrottlePeriod(MILLISECONDS), MILLISECONDS)
  result.setBodyDelay(getBodyDelay(MILLISECONDS), MILLISECONDS)
  result.setHeadersDelay(getHeadersDelay(MILLISECONDS), MILLISECONDS)
  return result
}

private fun PushPromise.wrap(): okhttp3.mockwebserver.PushPromise {
  return okhttp3.mockwebserver.PushPromise(
    method = method,
    path = path,
    headers = headers,
    response = response.wrap()
  )
}

internal fun okhttp3.mockwebserver.RecordedRequest.unwrap(): RecordedRequest {
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
    requestUrl = requestUrl
  )
}

private fun SocketPolicy.wrap(): okhttp3.mockwebserver.SocketPolicy {
  return okhttp3.mockwebserver.SocketPolicy.valueOf(name)
}
