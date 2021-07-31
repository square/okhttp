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
@file:Suppress("BlockingMethodInNonBlockingContext")
package okhttp.regression.compare

import io.envoyproxy.envoymobile.Engine
import io.envoyproxy.envoymobile.RequestHeadersBuilder
import io.envoyproxy.envoymobile.RequestMethod
import io.envoyproxy.envoymobile.StreamPrototype
import java.io.IOException
import java.lang.IllegalArgumentException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer
import okio.Okio
import okio.Pipe

class EnvoyInterceptor(val engine: Engine) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    return runBlocking(Dispatchers.IO) {
      makeRequest(engine, chain.request())
    }
  }
}

@ExperimentalCoroutinesApi
suspend fun makeRequest(engine: Engine, request: Request) =
  suspendCancellableCoroutine<Response> { continuation ->
    val responseBuilder = Response.Builder()
      .request(request)
      .protocol(if (request.isHttps) Protocol.QUIC else Protocol.HTTP_1_1)
      .sentRequestAtMillis(System.currentTimeMillis())

    val bodyPipe = Pipe(1024L * 1024L)
    val bodySink = Okio.buffer(bodyPipe.sink())
    val bodySource = Okio.buffer(bodyPipe.source())

    var contentType: MediaType?

    val streamPrototype: StreamPrototype = engine
      .streamClient()
      .newStreamPrototype()
      .setOnResponseHeaders { responseHeaders, endStream ->
        val headers = responseHeaders.toHeaders()

        contentType = headers.get("content-type")?.let {
          MediaType.parse(it)
        }

        responseBuilder
          .code(responseHeaders.httpStatus ?: 0)
          .message(responseHeaders.httpStatus.toString())
          .receivedResponseAtMillis(System.currentTimeMillis())
          .headers(headers)

        if (!endStream) {
          responseBuilder.body(ResponseBody.create(contentType, -1, bodySource))
        }

        continuation.resume(responseBuilder.build(), onCancellation = {})
      }
      .setOnResponseTrailers { responseTrailers ->
        println("Dropping trailers " + responseTrailers.toHeaders())
      }
      .setOnResponseData { data, endStream ->
        bodySink.write(data)

        if (endStream) {
          bodySink.close()
        }
      }
      .setOnError { error ->
        // TODO how to signal error correctly?
        bodySource.close()

        continuation.resumeWithException(
          IOException(
            "${error.errorCode}: ${error.message}",
            error.cause
          )
        )
      }
      .setOnCancel {
        continuation.cancel(CancellationException("underlying connection was cancelled"))
      }

    val body = request.body()

    val requestHeaders = RequestHeadersBuilder(
      request.envoyMethod,
      if (request.isHttps) "https" else "http",
      request.url().host(),
      request.url().encodedPath()
    ).apply {
      request.headers().toMultimap().forEach { (name, values) ->
        values.forEach { value ->
          add(name, value)
        }
      }
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

    continuation.invokeOnCancellation { stream.cancel() }
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
    return when (this.method()) {
      "GET" -> RequestMethod.GET
      "POST" -> RequestMethod.POST
      "PUT" -> RequestMethod.PUT
      "DELETE" -> RequestMethod.DELETE
      else -> throw IllegalArgumentException("Unsupported method ${method()}")
    }
  }
