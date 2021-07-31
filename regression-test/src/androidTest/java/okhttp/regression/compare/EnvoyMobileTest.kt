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
package okhttp.regression.compare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.envoyproxy.envoymobile.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.lang.System.currentTimeMillis
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.Headers
import okio.Buffer
import okio.Okio
import okio.Pipe

/**
 * Envoy Mobile.
 *
 * https://github.com/envoyproxy/envoy-mobile
 */
@RunWith(AndroidJUnit4::class)
class EnvoyMobileTest {
  @Test
  fun get() {

    val application = ApplicationProvider.getApplicationContext<Application>()

    val engine = AndroidEngineBuilder(application, baseConfiguration = Standard())
      .addLogLevel(LogLevel.TRACE)
      .setOnEngineRunning { println("Envoy async internal setup completed") }
      .setLogger { println(it) }
      .build()

    val request = Request.Builder().url("https://quic.aiortc.org/10").build()
    runBlocking {
      printRequest(engine, request)

      printRequest(engine, request)

      printRequest(engine, request)
    }
  }

  private suspend fun printRequest(engine: Engine, request: Request) {
    val response = makeRequest(engine, request)

    println(response)
    println(response.headers())
    println(response.body()?.contentType())
    println(response.body()?.string())
  }

  private suspend fun makeRequest(engine: Engine, request: Request) =
    suspendCancellableCoroutine<Response> { continuation ->
      val responseBuilder = Response.Builder()
        .request(request)
        .protocol(if (request.isHttps) Protocol.QUIC else Protocol.HTTP_1_1)
        .sentRequestAtMillis(currentTimeMillis())

      val bodyPipe = Pipe(1024L * 1024L)
      val bodySink = Okio.buffer(bodyPipe.sink())
      val bodySource = Okio.buffer(bodyPipe.source())

      val requestHeaders = RequestHeadersBuilder(
        RequestMethod.GET,
        if (request.isHttps) "https" else "http",
        request.url().host(),
        request.url().encodedPath()
      ).addUpstreamHttpProtocol(if (request.isHttps) UpstreamHttpProtocol.HTTP2 else UpstreamHttpProtocol.HTTP1)
        .build()

      var contentType: MediaType?

      val streamPrototype: StreamPrototype = engine
        .streamClient()
        .newStreamPrototype()
        .setOnResponseHeaders { responseHeaders, _ ->
          val headers = responseHeaders.toHeaders()

          contentType = headers.get("content-type")?.let {
            MediaType.parse(it)
          }

          responseBuilder
            .code(responseHeaders.httpStatus ?: 0)
            .message(responseHeaders.httpStatus.toString())
            .receivedResponseAtMillis(currentTimeMillis())
            .headers(headers)

          responseBuilder.body(ResponseBody.create(contentType, -1, bodySource))

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
          continuation.resumeWithException(
            IOException(
              "${error.errorCode}: ${error.message}",
              error.cause
            )
          )
        }.setOnCancel {
          continuation.cancel(CancellationException("underlying connection was cancelled"))
        }

      val stream = streamPrototype
        .start(Executors.newSingleThreadExecutor())
        .sendHeaders(requestHeaders, true)

      continuation.invokeOnCancellation { stream.cancel() }
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
