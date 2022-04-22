/*
 * Copyright (C) 2021 Square, Inc.
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
@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp.android.envoy

import io.envoyproxy.envoymobile.Engine
import io.envoyproxy.envoymobile.RequestHeadersBuilder
import io.envoyproxy.envoymobile.RequestMethod
import io.envoyproxy.envoymobile.StreamPrototype
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Pipe
import okio.buffer

class EnvoyInterceptor(private val engine: Engine) : CoroutineInterceptor() {
  override suspend fun interceptSuspend(chain: Interceptor.Chain): Response {
    val request = chain.request()

    // TODO wire up call cancellation when possible
    // https://github.com/square/okhttp/issues/7164

    return suspendCancellableCoroutine { continuation ->
      val responseBuilder = Response.Builder()
        .request(request)
        .sentRequestAtMillis(System.currentTimeMillis())

      val bodyPipe = Pipe(1024L * 1024L)
      val bodySink = bodyPipe.sink.buffer()
      val bodySource = bodyPipe.source.buffer()

      var contentType: MediaType?

      val streamPrototype: StreamPrototype = engine
        .streamClient()
        .newStreamPrototype()
        .setOnResponseHeaders { responseHeaders, endStream, streamIntel ->
          if (chain.call().isCanceled()) {
            continuation.cancel(CancellationException("underlying connection was cancelled"))
            return@setOnResponseHeaders
          }

          val headers = responseHeaders.toHeaders()

          contentType = headers["content-type"]?.toMediaTypeOrNull()

          // TODO check this logic
          val alpn = headers["x-envoy-upstream-alpn"]
          val protocol = if (alpn != null) {
            Protocol.get(alpn)
          } else {
            if (request.isHttps) Protocol.QUIC else Protocol.HTTP_1_1
          }

          responseBuilder
            .protocol(protocol)
            .code(responseHeaders.httpStatus ?: 0)
            .message(responseHeaders.httpStatus.toString())
            .receivedResponseAtMillis(System.currentTimeMillis())
            .headers(headers)

          if (!endStream) {
            responseBuilder.body(bodySource.asResponseBody(contentType, -1))
          }

          continuation.resume(responseBuilder.build(), onCancellation = {})
        }
        .setOnResponseTrailers { responseTrailers, streamIntel ->
          if (chain.call().isCanceled()) {
            continuation.cancel(CancellationException("underlying connection was cancelled"))
            return@setOnResponseTrailers
          }

          println("Dropping trailers " + responseTrailers.toHeaders())
        }
        .setOnResponseData { data, endStream, streamIntel ->
          if (chain.call().isCanceled()) {
            continuation.cancel(CancellationException("underlying connection was cancelled"))
            return@setOnResponseData
          }
          if (endStream) {
            bodySink.close()
          }
        }
        .setOnComplete { finalStreamIntel ->
          if (chain.call().isCanceled()) {
            continuation.cancel(CancellationException("underlying connection was cancelled"))
            return@setOnComplete
          }
          bodySink.close()
        }
        .setOnError { error, finalStreamIntel ->
          if (chain.call().isCanceled()) {
            continuation.cancel(CancellationException("underlying connection was cancelled"))
            return@setOnError
          }

          // TODO how to signal error correctly?
          bodySource.close()

          continuation.resumeWithException(
            IOException(
              "${error.errorCode}: ${error.message}",
              error.cause
            )
          )
        }
        .setOnCancel { finalStreamIntel ->
          continuation.cancel(CancellationException("underlying connection was cancelled"))
          bodyPipe.cancel()
        }

      val body = request.body

      val requestHeaders = RequestHeadersBuilder(
        request.envoyMethod,
        if (request.isHttps) "https" else "http",
        request.url.host,
        request.url.encodedPath
      ).apply {
        // TODO addH2RawDomains for Protocol.H2C?

        request.headers.toMultimap().forEach { (name, values) ->
          values.forEach { value ->
            add(name, value)
          }
        }

        // Set based on protocols
        add("x-envoy-mobile-upstream-protocol", "http3")
      }

      val stream = if (body != null) {
        val requestBodyType = body.contentType()
        if (requestBodyType != null) {
          requestHeaders.add("Content-Type", requestBodyType.toString())
        }

        val stream = streamPrototype
          .start(Executors.newSingleThreadExecutor())
          .sendHeaders(requestHeaders.build(), endStream = false)

        // TODO loop in chunks
        val buffer = Buffer()
        body.writeTo(buffer)
        stream.close(ByteBuffer.wrap(buffer.readByteArray()))

        stream
      } else {
        streamPrototype
          .start(Executors.newSingleThreadExecutor())
          .sendHeaders(requestHeaders.build(), endStream = true)
      }

      continuation.invokeOnCancellation {
        stream.cancel()
        bodyPipe.cancel()
      }
    }
  }
}

private fun io.envoyproxy.envoymobile.Headers.toHeaders(): Headers {
  val builder = Headers.Builder()

  allHeaders().forEach { (key, values) ->
    values.forEach { value ->
      builder.add(key, value)
    }
  }

  return builder.build()
}

private val Request.envoyMethod: RequestMethod
  get() {
    @Suppress("BlockingMethodInNonBlockingContext")
    return when (this.method) {
      "GET" -> RequestMethod.GET
      "POST" -> RequestMethod.POST
      "PUT" -> RequestMethod.PUT
      "DELETE" -> RequestMethod.DELETE
      else -> throw IllegalArgumentException("Unsupported method $method")
    }
  }
