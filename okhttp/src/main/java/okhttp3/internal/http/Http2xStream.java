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
package okhttp3.internal.http;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Util;
import okhttp3.internal.framed.ErrorCode;
import okhttp3.internal.framed.FramedConnection;
import okhttp3.internal.framed.FramedStream;
import okhttp3.internal.framed.Header;
import okio.ByteString;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.framed.Header.RESPONSE_STATUS;
import static okhttp3.internal.framed.Header.TARGET_AUTHORITY;
import static okhttp3.internal.framed.Header.TARGET_HOST;
import static okhttp3.internal.framed.Header.TARGET_METHOD;
import static okhttp3.internal.framed.Header.TARGET_PATH;
import static okhttp3.internal.framed.Header.TARGET_SCHEME;
import static okhttp3.internal.framed.Header.VERSION;

/** An HTTP stream for HTTP/2 and SPDY. */
public final class Http2xStream implements HttpStream {
  private static final ByteString CONNECTION = ByteString.encodeUtf8("connection");
  private static final ByteString HOST = ByteString.encodeUtf8("host");
  private static final ByteString KEEP_ALIVE = ByteString.encodeUtf8("keep-alive");
  private static final ByteString PROXY_CONNECTION = ByteString.encodeUtf8("proxy-connection");
  private static final ByteString TRANSFER_ENCODING = ByteString.encodeUtf8("transfer-encoding");
  private static final ByteString TE = ByteString.encodeUtf8("te");
  private static final ByteString ENCODING = ByteString.encodeUtf8("encoding");
  private static final ByteString UPGRADE = ByteString.encodeUtf8("upgrade");

  /** See http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3-1#TOC-3.2.1-Request. */
  private static final List<ByteString> SPDY_3_SKIPPED_REQUEST_HEADERS = Util.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TRANSFER_ENCODING,
      TARGET_METHOD,
      TARGET_PATH,
      TARGET_SCHEME,
      TARGET_AUTHORITY,
      TARGET_HOST,
      VERSION);
  private static final List<ByteString> SPDY_3_SKIPPED_RESPONSE_HEADERS = Util.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TRANSFER_ENCODING);

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
      TARGET_AUTHORITY,
      TARGET_HOST,
      VERSION);
  private static final List<ByteString> HTTP_2_SKIPPED_RESPONSE_HEADERS = Util.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TE,
      TRANSFER_ENCODING,
      ENCODING,
      UPGRADE);

  private final StreamAllocation streamAllocation;
  private final FramedConnection framedConnection;
  private HttpEngine httpEngine;
  private FramedStream stream;

  public Http2xStream(StreamAllocation streamAllocation, FramedConnection framedConnection) {
    this.streamAllocation = streamAllocation;
    this.framedConnection = framedConnection;
  }

  @Override public void setHttpEngine(HttpEngine httpEngine) {
    this.httpEngine = httpEngine;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) throws IOException {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    httpEngine.writingRequestHeaders();
    boolean permitsRequestBody = httpEngine.permitsRequestBody(request);
    List<Header> requestHeaders = framedConnection.getProtocol() == Protocol.HTTP_2
        ? http2HeadersList(request)
        : spdy3HeadersList(request);
    boolean hasResponseBody = true;
    stream = framedConnection.newStream(requestHeaders, permitsRequestBody, hasResponseBody);
    stream.readTimeout().timeout(httpEngine.client.readTimeoutMillis(), TimeUnit.MILLISECONDS);
    stream.writeTimeout().timeout(httpEngine.client.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
  }

  @Override public void writeRequestBody(RetryableSink requestBody) throws IOException {
    requestBody.writeToSocket(stream.getSink());
  }

  @Override public void finishRequest() throws IOException {
    stream.getSink().close();
  }

  @Override public Response.Builder readResponseHeaders() throws IOException {
    return framedConnection.getProtocol() == Protocol.HTTP_2
        ? readHttp2HeadersList(stream.getResponseHeaders())
        : readSpdy3HeadersList(stream.getResponseHeaders());
  }

  /**
   * Returns a list of alternating names and values containing a SPDY request. Names are all
   * lowercase. No names are repeated. If any name has multiple values, they are concatenated using
   * "\0" as a delimiter.
   */
  public static List<Header> spdy3HeadersList(Request request) {
    Headers headers = request.headers();
    List<Header> result = new ArrayList<>(headers.size() + 5);
    result.add(new Header(TARGET_METHOD, request.method()));
    result.add(new Header(TARGET_PATH, RequestLine.requestPath(request.url())));
    result.add(new Header(VERSION, "HTTP/1.1"));
    result.add(new Header(TARGET_HOST, Util.hostHeader(request.url(), false)));
    result.add(new Header(TARGET_SCHEME, request.url().scheme()));

    Set<ByteString> names = new LinkedHashSet<>();
    for (int i = 0, size = headers.size(); i < size; i++) {
      // header names must be lowercase.
      ByteString name = ByteString.encodeUtf8(headers.name(i).toLowerCase(Locale.US));

      // Drop headers that are forbidden when layering HTTP over SPDY.
      if (SPDY_3_SKIPPED_REQUEST_HEADERS.contains(name)) continue;

      // If we haven't seen this name before, add the pair to the end of the list...
      String value = headers.value(i);
      if (names.add(name)) {
        result.add(new Header(name, value));
        continue;
      }

      // ...otherwise concatenate the existing values and this value.
      for (int j = 0; j < result.size(); j++) {
        if (result.get(j).name.equals(name)) {
          String concatenated = joinOnNull(result.get(j).value.utf8(), value);
          result.set(j, new Header(name, concatenated));
          break;
        }
      }
    }
    return result;
  }

  private static String joinOnNull(String first, String second) {
    return new StringBuilder(first).append('\0').append(second).toString();
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

  /** Returns headers for a name value block containing a SPDY response. */
  public static Response.Builder readSpdy3HeadersList(List<Header> headerBlock) throws IOException {
    String status = null;
    String version = "HTTP/1.1";
    Headers.Builder headersBuilder = new Headers.Builder();
    for (int i = 0, size = headerBlock.size(); i < size; i++) {
      ByteString name = headerBlock.get(i).name;

      String values = headerBlock.get(i).value.utf8();
      for (int start = 0; start < values.length(); ) {
        int end = values.indexOf('\0', start);
        if (end == -1) {
          end = values.length();
        }
        String value = values.substring(start, end);
        if (name.equals(RESPONSE_STATUS)) {
          status = value;
        } else if (name.equals(VERSION)) {
          version = value;
        } else if (!SPDY_3_SKIPPED_RESPONSE_HEADERS.contains(name)) {
          headersBuilder.add(name.utf8(), value);
        }
        start = end + 1;
      }
    }
    if (status == null) throw new ProtocolException("Expected ':status' header not present");

    StatusLine statusLine = StatusLine.parse(version + " " + status);
    return new Response.Builder()
        .protocol(Protocol.SPDY_3)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
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
        headersBuilder.add(name.utf8(), value);
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
      streamAllocation.streamFinished(false, Http2xStream.this);
      super.close();
    }
  }
}
