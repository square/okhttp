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
import okhttp3.ResponseBody;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.RealResponseBody;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.Buffer;
import okio.ByteString;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;
import static okhttp3.internal.http2.Header.RESPONSE_STATUS;
import static okhttp3.internal.http2.Header.TARGET_AUTHORITY;
import static okhttp3.internal.http2.Header.TARGET_METHOD;
import static okhttp3.internal.http2.Header.TARGET_PATH;
import static okhttp3.internal.http2.Header.TARGET_SCHEME;

/** Encode requests and responses using HTTP/2 frames. */
public final class Http2Codec implements HttpCodec {
  private static final ByteString CONNECTION = ByteString.encodeUtf8("connection");
  private static final ByteString HOST = ByteString.encodeUtf8("host");
  private static final ByteString KEEP_ALIVE = ByteString.encodeUtf8("keep-alive");
  private static final ByteString PROXY_CONNECTION = ByteString.encodeUtf8("proxy-connection");
  private static final ByteString TRANSFER_ENCODING = ByteString.encodeUtf8("transfer-encoding");
  private static final ByteString TE = ByteString.encodeUtf8("te");
  private static final ByteString ENCODING = ByteString.encodeUtf8("encoding");
  private static final ByteString UPGRADE = ByteString.encodeUtf8("upgrade");

  /** See http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3. */
  private static final List<ByteString> HTTP_2_SKIPPED_REQUEST_HEADERS = Util.immutableList(
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
  private static final List<ByteString> HTTP_2_SKIPPED_RESPONSE_HEADERS = Util.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TE,
      TRANSFER_ENCODING,
      ENCODING,
      UPGRADE);

  private final OkHttpClient client;
  private final Interceptor.Chain chain;
  final StreamAllocation streamAllocation;
  private final Http2Connection connection;
  private Http2Stream stream;

  public Http2Codec(OkHttpClient client, Interceptor.Chain chain, StreamAllocation streamAllocation,
      Http2Connection connection) {
    this.client = client;
    this.chain = chain;
    this.streamAllocation = streamAllocation;
    this.connection = connection;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    boolean hasRequestBody = request.body() != null;
    List<Header> requestHeaders = http2HeadersList(request);
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
    List<Header> headers = stream.takeResponseHeaders();
    Response.Builder responseBuilder = readHttp2HeadersList(headers);
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
      ByteString name = ByteString.encodeUtf8(headers.name(i).toLowerCase(Locale.US));
      if (!HTTP_2_SKIPPED_REQUEST_HEADERS.contains(name)) {
        result.add(new Header(name, headers.value(i)));
      }
    }
    return result;
  }

  /** Returns headers for a name value block containing an HTTP/2 response. */
  public static Response.Builder readHttp2HeadersList(List<Header> headerBlock) throws IOException {
    StatusLine statusLine = null;
    Headers.Builder headersBuilder = new Headers.Builder();
    for (int i = 0, size = headerBlock.size(); i < size; i++) {
      Header header = headerBlock.get(i);

      // If there were multiple header blocks they will be delimited by nulls. Discard existing
      // header blocks if the existing header block is a '100 Continue' intermediate response.
      if (header == null) {
        if (statusLine != null && statusLine.code == HTTP_CONTINUE) {
          statusLine = null;
          headersBuilder = new Headers.Builder();
        }
        continue;
      }

      ByteString name = header.name;
      String value = header.value.utf8();
      if (name.equals(RESPONSE_STATUS)) {
        statusLine = StatusLine.parse("HTTP/1.1 " + value);
      } else if (!HTTP_2_SKIPPED_RESPONSE_HEADERS.contains(name)) {
        Internal.instance.addLenient(headersBuilder, name.utf8(), value);
      }
    }
    if (statusLine == null) throw new ProtocolException("Expected ':status' header not present");

    return new Response.Builder()
        .protocol(Protocol.HTTP_2)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
  }

  @Override public ResponseBody openResponseBody(Response response) throws IOException {
    streamAllocation.eventListener.responseBodyStart(streamAllocation.call);
    Source source = new StreamFinishingSource(stream.getSource());
    return new RealResponseBody(response.headers(), Okio.buffer(source));
  }

  @Override public void cancel() {
    if (stream != null) stream.closeLater(ErrorCode.CANCEL);
  }

  class StreamFinishingSource extends ForwardingSource {
    boolean completed = false;

    StreamFinishingSource(Source delegate) {
      super(delegate);
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      try {
        return delegate().read(sink, byteCount);
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
      streamAllocation.eventListener.responseBodyEnd(streamAllocation.call, e);
      streamAllocation.streamFinished(false, Http2Codec.this);
    }
  }
}
