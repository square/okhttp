/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3.jetty

import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Pipe
import okio.buffer
import org.eclipse.jetty.http.HttpFields
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpURI
import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.http.MetaData
import org.eclipse.jetty.http3.api.Session
import org.eclipse.jetty.http3.api.Stream
import org.eclipse.jetty.http3.client.HTTP3Client
import org.eclipse.jetty.http3.frames.GoAwayFrame
import org.eclipse.jetty.http3.frames.HeadersFrame
import org.eclipse.jetty.http3.frames.SettingsFrame
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode

// TODO write optimal version as a Call.Factory
class Http3BridgeInterceptor(val http3Client: HTTP3Client) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val body = request.body
    val call = chain.call()

    return if (request.isHttps) {
      val serverAddress = InetSocketAddress(request.url.host, 443)

      println("Connecting to $serverAddress")

      // TODO some way of connection pooling
      val sessionCF: CompletableFuture<Session.Client> =
        http3Client.connect(serverAddress, object : Session.Client.Listener {
          override fun onPreface(session: Session): MutableMap<Long, Long>? {
            println("onPreface $session")

            return null
          }

          override fun onSettings(session: Session, frame: SettingsFrame) {
            println("onSettings $frame")
          }

          override fun onGoAway(session: Session, frame: GoAwayFrame) {
            println("onGoAway $frame")
          }

          override fun onIdleTimeout(session: Session): Boolean {
            println("onIdleTimeout")
            return super.onIdleTimeout(session)
          }

          override fun onDisconnect(session: Session, error: Long, reason: String) {
            println("onDisconnect $error $reason")
          }

          override fun onFailure(
            session: Session,
            error: Long,
            reason: String,
            failure: Throwable
          ) {
            println("onDisconnect $error $reason $failure")
          }
        })

      val session: Session.Client = sessionCF.get()

      val requestHeaders: HttpFields = HttpFields.build().apply {
        val contentType = body?.contentType()
        if (contentType != null) {
          put(HttpHeader.CONTENT_TYPE, contentType.toString())
        }

        request.headers.forEach { (key, value) ->
          put(key, value)
        }
      }

      val jettyRequest: MetaData.Request = MetaData.Request(
        request.method, HttpURI.from(request.url.toString()), HttpVersion.HTTP_3, requestHeaders
      )

      val headersFrame = HeadersFrame(jettyRequest, body == null)

      val responseBodyPipe = Pipe(64 * 1024)
      val responseBodySink = responseBodyPipe.sink.buffer()

      val responseBuilder = Response.Builder()
        .protocol(Protocol.HTTP_3)
        .request(request)

      val completableFuture = CompletableFuture<Response>()

      val stream = session.newRequest(headersFrame, object : Stream.Client.Listener {
        override fun onResponse(stream: Stream.Client, frame: HeadersFrame) {
          val responseMetadata = frame.metaData as MetaData.Response
          val contentLength = responseMetadata.contentLength
          val contentType: String? = responseMetadata.fields.get("Content-Type")

          val responseBody =
            responseBodyPipe.source.buffer()
              .asResponseBody(contentType?.toMediaType(), contentLength)

          responseBuilder.body(responseBody)
            .code(responseMetadata.status)
            .message(responseMetadata.reason)

          completableFuture.complete(responseBuilder.build())
        }

        override fun onDataAvailable(stream: Stream.Client) {
          val data = stream.readData()

          if (data == null) {
            // No data available now, demand to be called back.
            stream.demand()
          } else {
            // TODO check for partial writes
            val wrote = responseBodySink.write(data.byteBuffer)
            data.complete()

            if (!data.isLast) {
              // Demand to be called back.
              stream.demand()
            }
          }
        }

        override fun onTrailer(stream: Stream.Client, frame: HeadersFrame) {
          // TODO handle trailers, cant provide currently as they come via exchange
        }

        override fun onIdleTimeout(stream: Stream.Client, failure: Throwable): Boolean {
          completableFuture.completeExceptionally(failure)
          // confirm close is correct here
          return super.onIdleTimeout(stream, failure)
        }

        override fun onFailure(stream: Stream.Client, error: Long, failure: Throwable) {
          completableFuture.completeExceptionally(failure)
        }
      }).get()

      // TODO clean up this, efficiency, cancellation
      while (!completableFuture.isDone && !call.isCanceled()) {
        try {
          completableFuture.get(500, TimeUnit.MILLISECONDS)
        } catch (te: TimeoutException) {
          // expected
        }
      }

      if (call.isCanceled()) {
        stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), ClosedChannelException())
      }
      return completableFuture.get()
    } else {
      chain.proceed(request)
    }
  }
}
