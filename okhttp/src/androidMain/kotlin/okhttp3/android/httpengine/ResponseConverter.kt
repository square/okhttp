/*
 * Copyright 2022 Google LLC
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
package okhttp3.android.httpengine

import android.net.http.UrlResponseInfo
import android.os.Build
import androidx.annotation.RequiresExtension
import com.google.common.base.Ascii
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.Uninterruptibles
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.TlsVersion
import okio.Source
import okio.buffer

/**
 * Converts Cronet's responses (or, more precisely, its chunks as they come from Cronet's [ ]), to OkHttp's [Response].
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
internal class ResponseConverter {
  /**
   * Creates an OkHttp's Response from the OkHttp-Cronet bridging callback.
   *
   *
   * As long as the callback's `UrlResponseInfo` is available this method is non-blocking.
   * However, this method doesn't fetch the entire body response. As a result, subsequent calls to
   * the result's [Response.body] methods might block further.
   */
  @Throws(IOException::class)
  fun toResponse(
    request: Request,
    callback: OkHttpBridgeRequestCallback,
  ): Response {
    val cronetResponseInfo: UrlResponseInfo =
      getFutureValue<UrlResponseInfo>(callback.urlResponseInfo)
    val responseBuilder: Response.Builder =
      createResponse(request, cronetResponseInfo, getFutureValue<Source>(callback.bodySource))

    val redirectResponseInfos = callback.getUrlResponseInfoChain()
    val urlChain = cronetResponseInfo.urlChain

    if (!redirectResponseInfos.isEmpty()) {
      check(
        urlChain.size == redirectResponseInfos.size + 1,
      ) {
        "The number of redirects should be consistent across URLs and headers!"
      }

      var priorResponse: Response? = null
      for (i in redirectResponseInfos.indices) {
        val redirectedRequest = request.newBuilder().url(urlChain[i]).build()
        priorResponse =
          createResponse(redirectedRequest, redirectResponseInfos[i], null)
            .priorResponse(priorResponse)
            .build()
      }

      responseBuilder
        .request(request.newBuilder().url(Iterables.getLast(urlChain)).build())
        .priorResponse(priorResponse)
    }

    return responseBuilder.build()
  }

  fun toResponseAsync(
    request: Request,
    callback: OkHttpBridgeRequestCallback,
  ): ListenableFuture<Response> =
    Futures
      .whenAllComplete(callback.urlResponseInfo, callback.bodySource)
      .call({ toResponse(request, callback) }, MoreExecutors.directExecutor())

  companion object {
    private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
    private const val CONTENT_TYPE_HEADER_NAME = "Content-Type"
    private const val CONTENT_ENCODING_HEADER_NAME = "Content-Encoding"

    // https://source.chromium.org/search?q=symbol:FilterSourceStream::ParseEncodingType%20f:cc
    private val ENCODINGS_HANDLED_BY_CRONET: ImmutableSet<String> =
      ImmutableSet.of("br", "deflate", "gzip", "x-gzip")

    private val COMMA_SPLITTER: Splitter = Splitter.on(',').trimResults().omitEmptyStrings()

    @Throws(IOException::class)
    private fun createResponse(
      request: Request,
      cronetResponseInfo: UrlResponseInfo,
      bodySource: Source?,
    ): Response.Builder {
      val responseBuilder = Response.Builder()

      val contentType: String? = getLastHeaderValue(CONTENT_TYPE_HEADER_NAME, cronetResponseInfo)

      // If all content encodings are those known to Cronet natively, Cronet decodes the body stream.
      // Otherwise, it's sent to the callbacks verbatim. For consistency with OkHttp, we only leave
      // the Content-Encoding headers if Cronet didn't decode the request. Similarly, for consistency,
      // we strip the Content-Length header of decoded responses.
      var contentLengthString: String? = null

      // Theoretically, the content encodings can be scattered across multiple comma separated
      // Content-Encoding headers. This list contains individual encodings.
      val contentEncodingItems: List<String> =
        buildList {
          val headerMap = cronetResponseInfo.headers.asMap
          headerMap[CONTENT_ENCODING_HEADER_NAME]?.forEach {
            addAll(COMMA_SPLITTER.split(it))
          }
        }

      val keepEncodingAffectedHeaders =
        contentEncodingItems.isEmpty() ||
          !ENCODINGS_HANDLED_BY_CRONET.containsAll(contentEncodingItems)

      if (keepEncodingAffectedHeaders) {
        contentLengthString = getLastHeaderValue(CONTENT_LENGTH_HEADER_NAME, cronetResponseInfo)
      }

      var responseBody: ResponseBody? = null
      if (bodySource != null) {
        responseBody =
          createResponseBody(
            request,
            cronetResponseInfo.httpStatusCode,
            contentType,
            contentLengthString,
            bodySource,
          )
      }

      responseBuilder
        .request(request)
        .code(cronetResponseInfo.httpStatusCode)
        .message(cronetResponseInfo.httpStatusText)
        .protocol(convertProtocol(cronetResponseInfo.negotiatedProtocol))
        // TODO don't fake this
        .handshake(
          Handshake(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_CCM_8_SHA256, listOf(), { listOf() }),
        ).apply {
          if (responseBody != null) {
            body(responseBody)
          }
        }

      for (header in cronetResponseInfo.headers.asList) {
        var copyHeader = true
        if (!keepEncodingAffectedHeaders) {
          if (Ascii.equalsIgnoreCase(header.key, CONTENT_LENGTH_HEADER_NAME) ||
            Ascii.equalsIgnoreCase(header.key, CONTENT_ENCODING_HEADER_NAME)
          ) {
            copyHeader = false
          }
        }
        if (copyHeader) {
          responseBuilder.addHeader(header.key, header.value)
        }
      }

      return responseBuilder
    }

    /**
     * Creates an OkHttp's ResponseBody from the OkHttp-Cronet bridging callback.
     *
     *
     * As long as the callback's `UrlResponseInfo` is available this method is non-blocking.
     * However, this method doesn't fetch the entire body response. As a result, subsequent calls to
     * [ResponseBody] methods might block further to fetch parts of the body.
     */
    @Throws(IOException::class)
    private fun createResponseBody(
      request: Request,
      httpStatusCode: Int,
      contentType: String?,
      contentLengthString: String?,
      bodySource: Source,
    ): ResponseBody {
      // Ignore content-length header for HEAD requests (consistency with OkHttp)
      val contentLength: Long =
        if (request.method == "HEAD") {
          0
        } else {
          try {
            contentLengthString?.toLong() ?: -1
          } catch (e: NumberFormatException) {
            // TODO(danstahr): add logging
            -1
          }
        }

      // Check for absence of body in No Content / Reset Content responses (OkHttp consistency)
      if ((httpStatusCode == 204 || httpStatusCode == 205) && contentLength > 0) {
        throw ProtocolException(
          "HTTP $httpStatusCode had non-zero Content-Length: $contentLengthString",
        )
      }

      return bodySource
        .buffer()
        .asResponseBody(
          contentType?.toMediaTypeOrNull(),
          contentLength,
        )
    }

    /** Converts Cronet's negotiated protocol string to OkHttp's [Protocol].  */
    private fun convertProtocol(negotiatedProtocol: String): Protocol {
      // See
      // https://www.iana.org/assignments/tls-extensiontype-values/tls-extensiontype-values.xhtml#alpn-protocol-ids
      if (negotiatedProtocol.contains("quic")) {
        return Protocol.QUIC
      } else if (negotiatedProtocol.contains("h3")) {
        // TODO(danstahr): Should be h3 for newer OkHttp
        return Protocol.QUIC
      } else if (negotiatedProtocol.contains("spdy")) {
        return Protocol.HTTP_2
      } else if (negotiatedProtocol.contains("h2")) {
        return Protocol.HTTP_2
      } else if (negotiatedProtocol.contains("http/1.1")) {
        return Protocol.HTTP_1_1
      }

      return Protocol.HTTP_1_0
    }

    /** Returns the last header value for the given name, or null if the header isn't present.  */
    private fun getLastHeaderValue(
      name: String?,
      responseInfo: UrlResponseInfo,
    ): String? {
      val headers = responseInfo.headers.asMap[name]
      if (headers == null || headers.isEmpty()) {
        return null
      }
      return Iterables.getLast(headers)
    }

    @Throws(IOException::class)
    private fun <T> getFutureValue(future: Future<T>): T {
      try {
        return Uninterruptibles.getUninterruptibly(future)
      } catch (e: ExecutionException) {
        throw IOException(e)
      }
    }
  }
}
