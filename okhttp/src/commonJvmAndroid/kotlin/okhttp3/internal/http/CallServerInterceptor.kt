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
package okhttp3.internal.http

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.TrailersSource
import okhttp3.internal.connection.Exchange
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.skipAll
import okhttp3.internal.stripBody
import okio.buffer

/** This is the last interceptor in the chain. It makes a network call to the server. */
class CallServerInterceptor(
  private val forWebSocket: Boolean,
) : Interceptor {
  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    val exchange = realChain.exchange!!
    val request = realChain.request
    val requestBody = request.body
    val sentRequestMillis = System.currentTimeMillis()

    var invokeStartEvent = true
    var responseBuilder: Response.Builder? = null
    var sendRequestException: IOException? = null
    try {
      exchange.writeRequestHeaders(request)

      if (HttpMethod.permitsRequestBody(request.method) && requestBody != null) {
        // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
        // Continue" response before transmitting the request body. If we don't get that, return
        // what we did get (such as a 4xx response) without ever transmitting the request body.
        if ("100-continue".equals(request.header("Expect"), ignoreCase = true)) {
          exchange.flushRequest()
          responseBuilder = exchange.readResponseHeaders(expectContinue = true)
          exchange.responseHeadersStart()
          invokeStartEvent = false
        }
        if (responseBuilder == null) {
          if (requestBody.isDuplex()) {
            // Prepare a duplex body so that the application can send a request body later.
            exchange.flushRequest()
            val bufferedRequestBody = exchange.createRequestBody(request, true).buffer()
            requestBody.writeTo(bufferedRequestBody)
          } else {
            // Write the request body if the "Expect: 100-continue" expectation was met.
            val bufferedRequestBody = exchange.createRequestBody(request, false).buffer()
            requestBody.writeTo(bufferedRequestBody)
            bufferedRequestBody.close()
          }
        } else {
          exchange.noRequestBody()
          if (!exchange.connection.isMultiplexed) {
            // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
            // from being reused. Otherwise we're still obligated to transmit the request body to
            // leave the connection in a consistent state.
            exchange.noNewExchangesOnConnection()
          }
        }
      } else {
        exchange.noRequestBody()
      }

      if (requestBody == null || !requestBody.isDuplex()) {
        exchange.finishRequest()
      }
    } catch (e: IOException) {
      if (e is ConnectionShutdownException) {
        throw e // No request was sent so there's no response to read.
      }
      if (!exchange.hasFailure) {
        throw e // Don't attempt to read the response; we failed to send the request.
      }
      sendRequestException = e
    }

    try {
      if (responseBuilder == null) {
        responseBuilder = exchange.readResponseHeaders(expectContinue = false)!!
        if (invokeStartEvent) {
          exchange.responseHeadersStart()
          invokeStartEvent = false
        }
      }
      var response =
        responseBuilder
          .request(request)
          .handshake(exchange.connection.handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build()
      var code = response.code

      while (shouldIgnoreAndWaitForRealResponse(code, exchange)) {
        responseBuilder = exchange.readResponseHeaders(expectContinue = false)!!
        if (invokeStartEvent) {
          exchange.responseHeadersStart()
        }
        response =
          responseBuilder
            .request(request)
            .handshake(exchange.connection.handshake())
            .sentRequestAtMillis(sentRequestMillis)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
        code = response.code
      }

      exchange.responseHeadersEnd(response)

      response =
        if (forWebSocket && code == 101) {
          // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
          response.stripBody()
        } else {
          val responseBody = exchange.openResponseBody(response)
          response
            .newBuilder()
            .body(responseBody)
            .trailers(
              object : TrailersSource {
                override fun peek() = exchange.peekTrailers()

                override fun get(): Headers {
                  val source = responseBody.source()
                  if (source.isOpen) {
                    source.skipAll()
                  }
                  return peek() ?: error("null trailers after exhausting response body?!")
                }
              },
            ).build()
        }
      if ("close".equals(response.request.header("Connection"), ignoreCase = true) ||
        "close".equals(response.header("Connection"), ignoreCase = true)
      ) {
        exchange.noNewExchangesOnConnection()
      }
      if ((code == 204 || code == 205) && response.body.contentLength() > 0L) {
        throw ProtocolException(
          "HTTP $code had non-zero Content-Length: ${response.body.contentLength()}",
        )
      }
      return response
    } catch (e: IOException) {
      if (sendRequestException != null) {
        sendRequestException.addSuppressed(e)
        throw sendRequestException
      }
      throw e
    }
  }

  private fun shouldIgnoreAndWaitForRealResponse(
    code: Int,
    exchange: Exchange,
  ): Boolean =
    when {
      // Server sent a 100-continue even though we did not request one. Try again to read the
      // actual response status.
      code == 100 -> true

      // Handle Processing (102) & Early Hints (103) and any new codes without failing
      // 100 and 101 are the exceptions with different meanings
      // But Early Hints not currently exposed
      code in (102 until 200) -> true

      else -> false
    }
}
