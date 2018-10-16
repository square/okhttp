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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.RealResponseBody;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;
import static okhttp3.internal.http2.Http2.RESPONSE_STATUS;
import static okhttp3.internal.http2.Http2.TARGET_AUTHORITY;
import static okhttp3.internal.http2.Http2.TARGET_METHOD;
import static okhttp3.internal.http2.Http2.TARGET_PATH;
import static okhttp3.internal.http2.Http2.TARGET_SCHEME;

/** Encode requests and responses using HTTP/2 frames. */
public final class Http2Codec implements HttpCodec {
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
      TARGET_METHOD,
      TARGET_PATH,
      TARGET_SCHEME,
      TARGET_AUTHORITY);
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
  final StreamAllocation streamAllocation;
  private final Http2Connection connection;
  private Http2Stream stream;
  private final Protocol protocol;

  public Http2Codec(OkHttpClient client, Interceptor.Chain chain, StreamAllocation streamAllocation,
      Http2Connection connection) {
    this.chain = chain;
    this.streamAllocation = streamAllocation;
    this.connection = connection;
    this.protocol = client.protocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)
        ? Protocol.H2_PRIOR_KNOWLEDGE
        : Protocol.HTTP_2;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    boolean hasRequestBody = request.body() != null;
    Headers requestHeaders = http2HeadersList(request);
    stream = connection.newStream(requestHeaders, hasRequestBody);
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
    List<Headers> headers = stream.takeHeaders();
    Response.Builder responseBuilder = readHttp2HeadersList(headers, protocol);
    if (expectContinue && Internal.instance.code(responseBuilder) == HTTP_CONTINUE) {
      return null;
    }
    return responseBuilder;
  }

  public static Headers http2HeadersList(Request request) {
    Headers headers = request.headers();
    Headers.Builder result = new Headers.Builder();
    result.add(TARGET_METHOD, request.method());
    result.add(TARGET_PATH, RequestLine.requestPath(request.url()));
    String host = request.header("Host");
    if (host != null) {
      result.add(TARGET_AUTHORITY, host); // Optional.
    }
    result.add(TARGET_SCHEME, request.url().scheme());

    for (int i = 0, size = headers.size(); i < size; i++) {
      // header names must be lowercase.
      String name = headers.name(i).toLowerCase(Locale.US);
      if (!HTTP_2_SKIPPED_REQUEST_HEADERS.contains(name)) {
        result.add(name, headers.value(i));
      }
    }
    return result.build();
  }

  /** Returns headers for a name value block containing an HTTP/2 response. */
  public static Response.Builder readHttp2HeadersList(List<Headers> headersBlock,
      Protocol protocol) throws IOException {
    StatusLine statusLine = null;
    Headers.Builder headersBuilder = new Headers.Builder();
    for (int i = 0, size = headersBlock.size(); i < size; i++) {
      if (i > 0) {
        // There are multiple header blocks. Discard existing header blocks if
        // the existing header block is a '100 Continue' intermediate response.
        if (statusLine != null && statusLine.code == HTTP_CONTINUE) {
          statusLine = null;
          headersBuilder = new Headers.Builder();
        }
      }

      Headers headers = headersBlock.get(i);
      for (int j = 0, sizej = headers.size(); j < sizej; j++) {
        String name = headers.name(j);
        String value = headers.value(j);
        if (name.equals(RESPONSE_STATUS)) {
          statusLine = StatusLine.parse("HTTP/1.1 " + value);
        } else if (!HTTP_2_SKIPPED_RESPONSE_HEADERS.contains(name)) {
          Internal.instance.addLenient(headersBuilder, name, value);
        }
      }
    }
    if (statusLine == null) throw new ProtocolException("Expected ':status' header not present");

    return new Response.Builder()
        .protocol(protocol)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
  }

  @Override public ResponseBody openResponseBody(Response response) throws IOException {
    streamAllocation.eventListener.responseBodyStart(streamAllocation.call);
    String contentType = response.header("Content-Type");
    long contentLength = HttpHeaders.contentLength(response);
    Source source = new StreamFinishingSource(stream.getSource());
    return new RealResponseBody(contentType, contentLength, Okio.buffer(source));
  }

  @Override public void cancel() {
    if (stream != null) stream.closeLater(ErrorCode.CANCEL);
  }

  class StreamFinishingSource extends ForwardingSource {
    boolean completed = false;
    long bytesRead = 0;

    StreamFinishingSource(Source delegate) {
      super(delegate);
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      try {
        long read = delegate().read(sink, byteCount);
        if (read > 0) {
          bytesRead += read;
        }
        return read;
      } catch (IOException e) {
        endOfInput(e);
        throw e;
      }
    }

    @Override public void close() throws IOException {
      super.close();
      endOfInput(null);
    }

    private void endOfInput(IOException e) {
      if (completed) return;
      completed = true;
      streamAllocation.streamFinished(false, Http2Codec.this, bytesRead, e);
    }
  }
}
