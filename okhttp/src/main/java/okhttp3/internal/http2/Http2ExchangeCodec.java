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
package okhttp3.internal.http2;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.http.ExchangeCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;
import static okhttp3.internal.http2.Header.RESPONSE_STATUS_UTF8;
import static okhttp3.internal.http2.Header.TARGET_AUTHORITY;
import static okhttp3.internal.http2.Header.TARGET_AUTHORITY_UTF8;
import static okhttp3.internal.http2.Header.TARGET_METHOD;
import static okhttp3.internal.http2.Header.TARGET_METHOD_UTF8;
import static okhttp3.internal.http2.Header.TARGET_PATH;
import static okhttp3.internal.http2.Header.TARGET_PATH_UTF8;
import static okhttp3.internal.http2.Header.TARGET_SCHEME;
import static okhttp3.internal.http2.Header.TARGET_SCHEME_UTF8;

/** Encode requests and responses using HTTP/2 frames. */
public final class Http2ExchangeCodec implements ExchangeCodec {
  private static final String CONNECTION = "connection";
  private static final String HOST = "host";
  private static final String KEEP_ALIVE = "keep-alive";
  private static final String PROXY_CONNECTION = "proxy-connection";
  private static final String TRANSFER_ENCODING = "transfer-encoding";
  private static final String TE = "te";
  private static final String ENCODING = "encoding";
  private static final String UPGRADE = "upgrade";

  /** See http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3. */
  private static final List<String> HTTP_2_SKIPPED_REQUEST_HEADERS = Util.immutableList(
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
      TARGET_AUTHORITY_UTF8);
  private static final List<String> HTTP_2_SKIPPED_RESPONSE_HEADERS = Util.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TE,
      TRANSFER_ENCODING,
      ENCODING,
      UPGRADE);

  private final Interceptor.Chain chain;
  private final RealConnection realConnection;
  private final Http2Connection connection;
  private volatile Http2Stream stream;
  private final Protocol protocol;
  private volatile boolean canceled;

  public Http2ExchangeCodec(OkHttpClient client, RealConnection realConnection,
      Interceptor.Chain chain, Http2Connection connection) {
    this.realConnection = realConnection;
    this.chain = chain;
    this.connection = connection;
    this.protocol = client.protocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)
        ? Protocol.H2_PRIOR_KNOWLEDGE
        : Protocol.HTTP_2;
  }

  @Override public RealConnection connection() {
    return realConnection;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    boolean hasRequestBody = request.body() != null;
    List<Header> requestHeaders = http2HeadersList(request);
    stream = connection.newStream(requestHeaders, hasRequestBody);
    // We may have been asked to cancel while creating the new stream and sending the request
    // headers, but there was still no stream to close.
    if (canceled) {
      stream.closeLater(ErrorCode.CANCEL);
      throw new IOException("Canceled");
    }
    stream.readTimeout().timeout(chain.readTimeoutMillis(), TimeUnit.MILLISECONDS);
    stream.writeTimeout().timeout(chain.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
  }

  @Override public void flushRequest() throws IOException {
    connection.flush();
  }

  @Override public void finishRequest() throws IOException {
    stream.getSink().close();
  }

  @Override public Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
    Headers headers = stream.takeHeaders();
    Response.Builder responseBuilder = readHttp2HeadersList(headers, protocol);
    if (expectContinue && Internal.instance.code(responseBuilder) == HTTP_CONTINUE) {
      return null;
    }
    return responseBuilder;
  }

  public static List<Header> http2HeadersList(Request request) {
    Headers headers = request.headers();
    List<Header> result = new ArrayList<>(headers.size() + 4);
    result.add(new Header(TARGET_METHOD, request.method()));
    result.add(new Header(TARGET_PATH, RequestLine.requestPath(request.url())));
    String host = request.header("Host");
    if (host != null) {
      result.add(new Header(TARGET_AUTHORITY, host)); // Optional.
    }
    result.add(new Header(TARGET_SCHEME, request.url().scheme()));

    for (int i = 0, size = headers.size(); i < size; i++) {
      // header names must be lowercase.
      String name = headers.name(i).toLowerCase(Locale.US);
      if (!HTTP_2_SKIPPED_REQUEST_HEADERS.contains(name)
          || name.equals(TE) && headers.value(i).equals("trailers")) {
        result.add(new Header(name, headers.value(i)));
      }
    }
    return result;
  }

  /** Returns headers for a name value block containing an HTTP/2 response. */
  public static Response.Builder readHttp2HeadersList(Headers headerBlock,
      Protocol protocol) throws IOException {
    StatusLine statusLine = null;
    Headers.Builder headersBuilder = new Headers.Builder();
    for (int i = 0, size = headerBlock.size(); i < size; i++) {
      String name = headerBlock.name(i);
      String value = headerBlock.value(i);
      if (name.equals(RESPONSE_STATUS_UTF8)) {
        statusLine = StatusLine.parse("HTTP/1.1 " + value);
      } else if (!HTTP_2_SKIPPED_RESPONSE_HEADERS.contains(name)) {
        Internal.instance.addLenient(headersBuilder, name, value);
      }
    }
    if (statusLine == null) throw new ProtocolException("Expected ':status' header not present");

    return new Response.Builder()
        .protocol(protocol)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
  }

  @Override public long reportedContentLength(Response response) {
    return HttpHeaders.contentLength(response);
  }

  @Override public Source openResponseBodySource(Response response) {
    return stream.getSource();
  }

  @Override public Headers trailers() throws IOException {
    return stream.trailers();
  }

  @Override public void cancel() {
    canceled = true;
    if (stream != null) stream.closeLater(ErrorCode.CANCEL);
  }
}
