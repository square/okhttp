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
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.RealResponseBody;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.ByteString;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

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
  private final StreamAllocation streamAllocation;
  private final Http2Connection connection;
  private Http2Stream stream;

  public Http2Codec(
      OkHttpClient client, StreamAllocation streamAllocation, Http2Connection connection) {
    this.client = client;
    this.streamAllocation = streamAllocation;
    this.connection = connection;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    boolean permitsRequestBody = HttpMethod.permitsRequestBody(request.method());
    List<Header> requestHeaders = http2HeadersList(request);
    stream = connection.newStream(requestHeaders, permitsRequestBody);
    stream.readTimeout().timeout(client.readTimeoutMillis(), TimeUnit.MILLISECONDS);
    stream.writeTimeout().timeout(client.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
  }

  @Override public void finishRequest() throws IOException {
    stream.getSink().close();
  }

  @Override public Response.Builder readResponseHeaders() throws IOException {
    return readHttp2HeadersList(stream.getResponseHeaders());
  }

  public static List<Header> http2HeadersList(Request request) {
    Headers headers = request.headers();
    List<Header> result = new ArrayList<>(headers.size() + 4);
    result.add(new Header(TARGET_METHOD, request.method()));
    result.add(new Header(TARGET_PATH, RequestLine.requestPath(request.url())));
    result.add(new Header(TARGET_AUTHORITY, Util.hostHeader(request.url(), false))); // Optional.
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
    String status = null;

    Headers.Builder headersBuilder = new Headers.Builder();
    for (int i = 0, size = headerBlock.size(); i < size; i++) {
      ByteString name = headerBlock.get(i).name;

      String value = headerBlock.get(i).value.utf8();
      if (name.equals(RESPONSE_STATUS)) {
        status = value;
      } else if (!HTTP_2_SKIPPED_RESPONSE_HEADERS.contains(name)) {
        Internal.instance.addLenient(headersBuilder, name.utf8(), value);
      }
    }
    if (status == null) throw new ProtocolException("Expected ':status' header not present");

    StatusLine statusLine = StatusLine.parse("HTTP/1.1 " + status);
    return new Response.Builder()
        .protocol(Protocol.HTTP_2)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
  }

  @Override public ResponseBody openResponseBody(Response response) throws IOException {
    Source source = new StreamFinishingSource(stream.getSource());
    return new RealResponseBody(response.headers(), Okio.buffer(source));
  }

  @Override public void cancel() {
    if (stream != null) stream.closeLater(ErrorCode.CANCEL);
  }

  class StreamFinishingSource extends ForwardingSource {
    public StreamFinishingSource(Source delegate) {
      super(delegate);
    }

    @Override public void close() throws IOException {
      streamAllocation.streamFinished(false, Http2Codec.this);
      super.close();
    }
  }
}
