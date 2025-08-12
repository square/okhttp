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

package okhttp3.android.httpengine;

import androidx.annotation.NonNull;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import okhttp3.*;
import okio.Okio;
import okio.Source;
import android.net.http.UrlResponseInfo;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Converts Cronet's responses (or, more precisely, its chunks as they come from Cronet's {@link
 * android.net.http.UrlRequest.Callback}), to OkHttp's {@link Response}.
 */
final class ResponseConverter {
  private static final String CONTENT_LENGTH_HEADER_NAME = "Content-Length";
  private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
  private static final String CONTENT_ENCODING_HEADER_NAME = "Content-Encoding";

  // https://source.chromium.org/search?q=symbol:FilterSourceStream::ParseEncodingType%20f:cc
  private static final ImmutableSet<String> ENCODINGS_HANDLED_BY_CRONET =
      ImmutableSet.of("br", "deflate", "gzip", "x-gzip");

  private static final Splitter COMMA_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

  /**
   * Creates an OkHttp's Response from the OkHttp-Cronet bridging callback.
   *
   * <p>As long as the callback's {@code UrlResponseInfo} is available this method is non-blocking.
   * However, this method doesn't fetch the entire body response. As a result, subsequent calls to
   * the result's {@link Response#body()} methods might block further.
   */
  Response toResponse(Request request, OkHttpBridgeRequestCallback callback) throws IOException {
    UrlResponseInfo cronetResponseInfo = getFutureValue(callback.getUrlResponseInfo());
    Response.Builder responseBuilder =
        createResponse(request, cronetResponseInfo, getFutureValue(callback.getBodySource()));

    List<UrlResponseInfo> redirectResponseInfos = callback.getUrlResponseInfoChain();
    List<String> urlChain = cronetResponseInfo.getUrlChain();

    if (!redirectResponseInfos.isEmpty()) {
      checkArgument(
          urlChain.size() == redirectResponseInfos.size() + 1,
          "The number of redirects should be consistent across URLs and headers!");

      Response priorResponse = null;
      for (int i = 0; i < redirectResponseInfos.size(); i++) {
        Request redirectedRequest = request.newBuilder().url(urlChain.get(i)).build();
        priorResponse =
            createResponse(redirectedRequest, redirectResponseInfos.get(i), null)
                .priorResponse(priorResponse)
                .build();
      }

      responseBuilder
          .request(request.newBuilder().url(Iterables.getLast(urlChain)).build())
          .priorResponse(priorResponse);
    }

    return responseBuilder.build();
  }

  ListenableFuture<Response> toResponseAsync(
      Request request, OkHttpBridgeRequestCallback callback) {
    return Futures.whenAllComplete(callback.getUrlResponseInfo(), callback.getBodySource())
        .call(() -> toResponse(request, callback), MoreExecutors.directExecutor());
  }

  private static Response.Builder createResponse(
      Request request, UrlResponseInfo cronetResponseInfo, @Nullable Source bodySource)
      throws IOException {

    Response.Builder responseBuilder = new Response.Builder();

    @Nullable String contentType = getLastHeaderValue(CONTENT_TYPE_HEADER_NAME, cronetResponseInfo);

    // If all content encodings are those known to Cronet natively, Cronet decodes the body stream.
    // Otherwise, it's sent to the callbacks verbatim. For consistency with OkHttp, we only leave
    // the Content-Encoding headers if Cronet didn't decode the request. Similarly, for consistency,
    // we strip the Content-Length header of decoded responses.

    @Nullable String contentLengthString = null;

    // Theoretically, the content encodings can be scattered across multiple comma separated
    // Content-Encoding headers. This list contains individual encodings.
    List<String> contentEncodingItems = new ArrayList<>();

    for (String contentEncodingHeaderValue :
        getOrDefault(
            cronetResponseInfo.getAllHeaders(),
            CONTENT_ENCODING_HEADER_NAME,
            Collections.emptyList())) {
      Iterables.addAll(contentEncodingItems, COMMA_SPLITTER.split(contentEncodingHeaderValue));
    }

    boolean keepEncodingAffectedHeaders =
        contentEncodingItems.isEmpty()
            || !ENCODINGS_HANDLED_BY_CRONET.containsAll(contentEncodingItems);

    if (keepEncodingAffectedHeaders) {
      contentLengthString = getLastHeaderValue(CONTENT_LENGTH_HEADER_NAME, cronetResponseInfo);
    }

    ResponseBody responseBody = null;
    if (bodySource != null) {
      responseBody =
          createResponseBody(
              request,
              cronetResponseInfo.getHttpStatusCode(),
              contentType,
              contentLengthString,
              bodySource);
    }

    responseBuilder
        .request(request)
        .code(cronetResponseInfo.getHttpStatusCode())
        .message(cronetResponseInfo.getHttpStatusText())
        .protocol(convertProtocol(cronetResponseInfo.getNegotiatedProtocol()))
        .body(responseBody);

    for (Map.Entry<String, String> header : cronetResponseInfo.getAllHeadersAsList()) {
      boolean copyHeader = true;
      if (!keepEncodingAffectedHeaders) {
        if (Ascii.equalsIgnoreCase(header.getKey(), CONTENT_LENGTH_HEADER_NAME)
            || Ascii.equalsIgnoreCase(header.getKey(), CONTENT_ENCODING_HEADER_NAME)) {
          copyHeader = false;
        }
      }
      if (copyHeader) {
        responseBuilder.addHeader(header.getKey(), header.getValue());
      }
    }

    return responseBuilder;
  }

  /**
   * Creates an OkHttp's ResponseBody from the OkHttp-Cronet bridging callback.
   *
   * <p>As long as the callback's {@code UrlResponseInfo} is available this method is non-blocking.
   * However, this method doesn't fetch the entire body response. As a result, subsequent calls to
   * {@link ResponseBody} methods might block further to fetch parts of the body.
   */
  private static ResponseBody createResponseBody(
      Request request,
      int httpStatusCode,
      @Nullable String contentType,
      @Nullable String contentLengthString,
      Source bodySource)
      throws IOException {

    long contentLength;

    // Ignore content-length header for HEAD requests (consistency with OkHttp)
    if (request.method().equals("HEAD")) {
      contentLength = 0;
    } else {
      try {
        contentLength = contentLengthString != null ? Long.parseLong(contentLengthString) : -1;
      } catch (NumberFormatException e) {
        // TODO(danstahr): add logging
        contentLength = -1;
      }
    }

    // Check for absence of body in No Content / Reset Content responses (OkHttp consistency)
    if ((httpStatusCode == 204 || httpStatusCode == 205) && contentLength > 0) {
      throw new ProtocolException(
          "HTTP " + httpStatusCode + " had non-zero Content-Length: " + contentLengthString);
    }

    return ResponseBody.create(
        contentType != null ? MediaType.parse(contentType) : null,
        contentLength,
        Okio.buffer(bodySource));
  }

  /** Converts Cronet's negotiated protocol string to OkHttp's {@link Protocol}. */
  private static Protocol convertProtocol(String negotiatedProtocol) {
    // See
    // https://www.iana.org/assignments/tls-extensiontype-values/tls-extensiontype-values.xhtml#alpn-protocol-ids
    if (negotiatedProtocol.contains("quic")) {
      return Protocol.QUIC;
    } else if (negotiatedProtocol.contains("h3")) {
      // TODO(danstahr): Should be h3 for newer OkHttp
      return Protocol.QUIC;
    } else if (negotiatedProtocol.contains("spdy")) {
      return Protocol.HTTP_2;
    } else if (negotiatedProtocol.contains("h2")) {
      return Protocol.HTTP_2;
    } else if (negotiatedProtocol.contains("http/1.1")) {
      return Protocol.HTTP_1_1;
    }

    return Protocol.HTTP_1_0;
  }

  /** Returns the last header value for the given name, or null if the header isn't present. */
  @Nullable
  private static String getLastHeaderValue(String name, UrlResponseInfo responseInfo) {
    List<String> headers = responseInfo.getAllHeaders().get(name);
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    return Iterables.getLast(headers);
  }

  private static <T> T getFutureValue(Future<T> future) throws IOException {
    try {
      return Uninterruptibles.getUninterruptibly(future);
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }

  private static <K, V> V getOrDefault(Map<K, V> map, K key, @NonNull V defaultValue) {
    V value = map.get(key);
    if (value == null) {
      return checkNotNull(defaultValue);
    } else {
      return value;
    }
  }
}
