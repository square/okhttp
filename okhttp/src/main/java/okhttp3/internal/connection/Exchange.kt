/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.connection

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealResponseBody
import okhttp3.internal.ws.RealWebSocket
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.buffer
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException

/**
 * Transmits a single HTTP request and a response pair. This layers connection management and events
 * on [ExchangeCodec], which handles the actual I/O.
 */
class Exchange(
  internal val transmitter: Transmitter,
  internal val call: Call,
  internal val eventListener: EventListener,
  private val finder: ExchangeFinder,
  private val codec: ExchangeCodec
) {
  /** Returns true if the request body need not complete before the response body starts. */
  var isDuplex: Boolean = false
    private set

  fun connection(): RealConnection? = codec.connection()

  @Throws(IOException::class)
  fun writeRequestHeaders(request: Request) {
    try {
      eventListener.requestHeadersStart(call)
      codec.writeRequestHeaders(request)
      eventListener.requestHeadersEnd(call, request)
    } catch (e: IOException) {
      eventListener.requestFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  @Throws(IOException::class)
  fun createRequestBody(request: Request, duplex: Boolean): Sink {
    this.isDuplex = duplex
    val contentLength = request.body!!.contentLength()
    eventListener.requestBodyStart(call)
    val rawRequestBody = codec.createRequestBody(request, contentLength)
    return RequestBodySink(rawRequestBody, contentLength)
  }

  @Throws(IOException::class)
  fun flushRequest() {
    try {
      codec.flushRequest()
    } catch (e: IOException) {
      eventListener.requestFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  @Throws(IOException::class)
  fun finishRequest() {
    try {
      codec.finishRequest()
    } catch (e: IOException) {
      eventListener.requestFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  fun responseHeadersStart() {
    eventListener.responseHeadersStart(call)
  }

  @Throws(IOException::class)
  fun readResponseHeaders(expectContinue: Boolean): Response.Builder? {
    try {
      val result = codec.readResponseHeaders(expectContinue)
      result?.initExchange(this)
      return result
    } catch (e: IOException) {
      eventListener.responseFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  fun responseHeadersEnd(response: Response) {
    eventListener.responseHeadersEnd(call, response)
  }

  @Throws(IOException::class)
  fun openResponseBody(response: Response): ResponseBody {
    try {
      eventListener.responseBodyStart(call)
      val contentType = response.header("Content-Type")
      val contentLength = codec.reportedContentLength(response)
      val rawSource = codec.openResponseBodySource(response)
      val source = ResponseBodySource(rawSource, contentLength)
      return RealResponseBody(contentType, contentLength, source.buffer())
    } catch (e: IOException) {
      eventListener.responseFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  @Throws(IOException::class)
  fun trailers(): Headers = codec.trailers()

  fun timeoutEarlyExit() {
    transmitter.timeoutEarlyExit()
  }

  @Throws(SocketException::class)
  fun newWebSocketStreams(): RealWebSocket.Streams {
    transmitter.timeoutEarlyExit()
    return codec.connection()!!.newWebSocketStreams(this)
  }

  fun webSocketUpgradeFailed() {
    bodyComplete(-1L, true, true, null)
  }

  fun noNewExchangesOnConnection() {
    codec.connection()!!.noNewExchanges()
  }

  fun cancel() {
    codec.cancel()
  }

  /**
   * Revoke this exchange's access to streams. This is necessary when a follow-up request is
   * required but the preceding exchange hasn't completed yet.
   */
  fun detachWithViolence() {
    codec.cancel()
    transmitter.exchangeMessageDone(this, true, true, null)
  }

  private fun trackFailure(e: IOException) {
    finder.trackFailure()
    codec.connection()!!.trackFailure(e)
  }

  fun <E : IOException?> bodyComplete(
    bytesRead: Long,
    responseDone: Boolean,
    requestDone: Boolean,
    e: E
  ): E {
    if (e != null) {
      trackFailure(e)
    }
    if (requestDone) {
      if (e != null) {
        eventListener.requestFailed(call, e)
      } else {
        eventListener.requestBodyEnd(call, bytesRead)
      }
    }
    if (responseDone) {
      if (e != null) {
        eventListener.responseFailed(call, e)
      } else {
        eventListener.responseBodyEnd(call, bytesRead)
      }
    }
    return transmitter.exchangeMessageDone(this, requestDone, responseDone, e)
  }

  fun noRequestBody() {
    transmitter.exchangeMessageDone(this, true, false, null)
  }

  /** A request body that fires events when it completes. */
  private inner class RequestBodySink internal constructor(
    delegate: Sink,
    /** The exact number of bytes to be written, or -1L if that is unknown. */
    private val contentLength: Long
  ) : ForwardingSink(delegate) {
    private var completed = false
    private var bytesReceived = 0L
    private var closed = false

    @Throws(IOException::class)
    override fun write(source: Buffer, byteCount: Long) {
      check(!closed) { "closed" }
      if (contentLength != -1L && bytesReceived + byteCount > contentLength) {
        throw ProtocolException(
            "expected $contentLength bytes but received ${bytesReceived + byteCount}")
      }
      try {
        super.write(source, byteCount)
        this.bytesReceived += byteCount
      } catch (e: IOException) {
        throw complete(e)
      }
    }

    @Throws(IOException::class)
    override fun flush() {
      try {
        super.flush()
      } catch (e: IOException) {
        throw complete(e)
      }
    }

    @Throws(IOException::class)
    override fun close() {
      if (closed) return
      closed = true
      if (contentLength != -1L && bytesReceived != contentLength) {
        throw ProtocolException("unexpected end of stream")
      }
      try {
        super.close()
        complete(null)
      } catch (e: IOException) {
        throw complete(e)
      }
    }

    private fun <E : IOException?> complete(e: E): E {
      if (completed) return e
      completed = true
      return bodyComplete(bytesReceived, false, true, e)
    }
  }

  /** A response body that fires events when it completes. */
  internal inner class ResponseBodySource(
    delegate: Source,
    private val contentLength: Long
  ) : ForwardingSource(delegate) {
    private var bytesReceived = 0L
    private var completed = false
    private var closed = false

    init {
      if (contentLength == 0L) {
        complete(null)
      }
    }

    @Throws(IOException::class)
    override fun read(sink: Buffer, byteCount: Long): Long {
      check(!closed) { "closed" }
      try {
        val read = delegate.read(sink, byteCount)
        if (read == -1L) {
          complete(null)
          return -1L
        }

        val newBytesReceived = bytesReceived + read
        if (contentLength != -1L && newBytesReceived > contentLength) {
          throw ProtocolException("expected $contentLength bytes but received $newBytesReceived")
        }

        bytesReceived = newBytesReceived
        if (newBytesReceived == contentLength) {
          complete(null)
        }

        return read
      } catch (e: IOException) {
        throw complete(e)
      }
    }

    @Throws(IOException::class)
    override fun close() {
      if (closed) return
      closed = true
      try {
        super.close()
        complete(null)
      } catch (e: IOException) {
        throw complete(e)
      }
    }

    fun <E : IOException?> complete(e: E): E {
      if (completed) return e
      completed = true
      return bodyComplete(bytesReceived, true, false, e)
    }
  }

  companion object {
    fun get(response: Response): Exchange? = response.exchange
  }
}
