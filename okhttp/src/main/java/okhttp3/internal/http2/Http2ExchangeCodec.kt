/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.http2

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.headersContentLength
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RequestLine
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http.StatusLine.Companion.HTTP_CONTINUE
import okhttp3.internal.http2.Header.Companion.RESPONSE_STATUS_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_AUTHORITY
import okhttp3.internal.http2.Header.Companion.TARGET_AUTHORITY_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_METHOD
import okhttp3.internal.http2.Header.Companion.TARGET_METHOD_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_PATH
import okhttp3.internal.http2.Header.Companion.TARGET_PATH_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_SCHEME
import okhttp3.internal.http2.Header.Companion.TARGET_SCHEME_UTF8
import okhttp3.internal.immutableListOf
import okio.Sink
import okio.Source
import java.io.IOException
import java.net.ProtocolException
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Encode requests and responses using HTTP/2 frames. */
class Http2ExchangeCodec(
  client: OkHttpClient,
  private val realConnection: RealConnection,
  private val chain: Interceptor.Chain,
  private val connection: Http2Connection
) : ExchangeCodec {
  @Volatile private var stream: Http2Stream? = null

  private val protocol: Protocol = if (Protocol.H2_PRIOR_KNOWLEDGE in client.protocols) {
    Protocol.H2_PRIOR_KNOWLEDGE
  } else {
    Protocol.HTTP_2
  }

  @Volatile
  private var canceled: Boolean = false

  override fun connection(): RealConnection {
    return realConnection
  }

  override fun createRequestBody(request: Request, contentLength: Long): Sink {
    return stream!!.getSink()
  }

  override fun writeRequestHeaders(request: Request) {
    if (stream != null) return

    val hasRequestBody = request.body != null
    val requestHeaders = http2HeadersList(request)
    stream = connection.newStream(requestHeaders, hasRequestBody)
    // We may have been asked to cancel while creating the new stream and sending the request
    // headers, but there was still no stream to close.
    if (canceled) {
      stream!!.closeLater(ErrorCode.CANCEL)
      throw IOException("Canceled")
    }
    stream!!.readTimeout().timeout(chain.readTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
    stream!!.writeTimeout().timeout(chain.writeTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
  }

  override fun flushRequest() {
    connection.flush()
  }

  override fun finishRequest() {
    stream!!.getSink().close()
  }

  override fun readResponseHeaders(expectContinue: Boolean): Response.Builder? {
    val headers = stream!!.takeHeaders()
    val responseBuilder = readHttp2HeadersList(headers, protocol)
    return if (expectContinue && responseBuilder.code == HTTP_CONTINUE) {
      null
    } else {
      responseBuilder
    }
  }

  override fun reportedContentLength(response: Response): Long {
    return response.headersContentLength()
  }

  override fun openResponseBodySource(response: Response): Source {
    return stream!!.source
  }

  override fun trailers(): Headers {
    return stream!!.trailers()
  }

  override fun cancel() {
    canceled = true
    stream?.closeLater(ErrorCode.CANCEL)
  }

  companion object {
    private const val CONNECTION = "connection"
    private const val HOST = "host"
    private const val KEEP_ALIVE = "keep-alive"
    private const val PROXY_CONNECTION = "proxy-connection"
    private const val TRANSFER_ENCODING = "transfer-encoding"
    private const val TE = "te"
    private const val ENCODING = "encoding"
    private const val UPGRADE = "upgrade"

    /** See http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3. */
    private val HTTP_2_SKIPPED_REQUEST_HEADERS = immutableListOf(
        CONNECTION,
        HOST,
        KEEP_ALIVE,
        PROXY_CONNECTION,
        TE,
        TRANSFER_ENCODING,
        ENCODING,
        UPGRADE,
        TARGET_METHOD_UTF8,
        TARGET_PATH_UTF8,
        TARGET_SCHEME_UTF8,
        TARGET_AUTHORITY_UTF8)
    private val HTTP_2_SKIPPED_RESPONSE_HEADERS = immutableListOf(
        CONNECTION,
        HOST,
        KEEP_ALIVE,
        PROXY_CONNECTION,
        TE,
        TRANSFER_ENCODING,
        ENCODING,
        UPGRADE)

    fun http2HeadersList(request: Request): List<Header> {
      val headers = request.headers
      val result = ArrayList<Header>(headers.size + 4)
      result.add(Header(TARGET_METHOD, request.method))
      result.add(Header(TARGET_PATH, RequestLine.requestPath(request.url)))
      val host = request.header("Host")
      if (host != null) {
        result.add(Header(TARGET_AUTHORITY, host)) // Optional.
      }
      result.add(Header(TARGET_SCHEME, request.url.scheme))

      for (i in 0 until headers.size) {
        // header names must be lowercase.
        val name = headers.name(i).toLowerCase(Locale.US)
        if (name !in HTTP_2_SKIPPED_REQUEST_HEADERS ||
            name == TE && headers.value(i) == "trailers") {
          result.add(Header(name, headers.value(i)))
        }
      }
      return result
    }

    /** Returns headers for a name value block containing an HTTP/2 response. */
    fun readHttp2HeadersList(headerBlock: Headers, protocol: Protocol): Response.Builder {
      var statusLine: StatusLine? = null
      val headersBuilder = Headers.Builder()
      for (i in 0 until headerBlock.size) {
        val name = headerBlock.name(i)
        val value = headerBlock.value(i)
        if (name == RESPONSE_STATUS_UTF8) {
          statusLine = StatusLine.parse("HTTP/1.1 $value")
        } else if (name !in HTTP_2_SKIPPED_RESPONSE_HEADERS) {
          headersBuilder.addLenient(name, value)
        }
      }
      if (statusLine == null) throw ProtocolException("Expected ':status' header not present")

      return Response.Builder()
          .protocol(protocol)
          .code(statusLine.code)
          .message(statusLine.message)
          .headers(headersBuilder.build())
    }
  }
}
