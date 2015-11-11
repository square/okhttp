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

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.framed.ErrorCode;
import com.squareup.okhttp.internal.framed.FramedConnection;
import com.squareup.okhttp.internal.framed.FramedStream;
import com.squareup.okhttp.internal.framed.Header;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okio.ByteString;
import okio.Okio;
import okio.Sink;

import static com.squareup.okhttp.internal.framed.Header.RESPONSE_STATUS;
import static com.squareup.okhttp.internal.framed.Header.TARGET_AUTHORITY;
import static com.squareup.okhttp.internal.framed.Header.TARGET_HOST;
import static com.squareup.okhttp.internal.framed.Header.TARGET_METHOD;
import static com.squareup.okhttp.internal.framed.Header.TARGET_PATH;
import static com.squareup.okhttp.internal.framed.Header.TARGET_SCHEME;
import static com.squareup.okhttp.internal.framed.Header.VERSION;

public final class FramedTransport implements Transport {
  /** See http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3-1#TOC-3.2.1-Request. */
  private static final List<ByteString> SPDY_3_PROHIBITED_HEADERS = Util.immutableList(
      ByteString.encodeUtf8("connection"),
      ByteString.encodeUtf8("host"),
      ByteString.encodeUtf8("keep-alive"),
      ByteString.encodeUtf8("proxy-connection"),
      ByteString.encodeUtf8("transfer-encoding"));

  /** See http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3. */
  private static final List<ByteString> HTTP_2_PROHIBITED_HEADERS = Util.immutableList(
      ByteString.encodeUtf8("connection"),
      ByteString.encodeUtf8("host"),
      ByteString.encodeUtf8("keep-alive"),
      ByteString.encodeUtf8("proxy-connection"),
      ByteString.encodeUtf8("te"),
      ByteString.encodeUtf8("transfer-encoding"),
      ByteString.encodeUtf8("encoding"),
      ByteString.encodeUtf8("upgrade"));

  private final HttpEngine httpEngine;
  private final FramedConnection framedConnection;
  private FramedStream stream;

  public FramedTransport(HttpEngine httpEngine, FramedConnection framedConnection) {
    this.httpEngine = httpEngine;
    this.framedConnection = framedConnection;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) throws IOException {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    httpEngine.writingRequestHeaders();
    boolean permitsRequestBody = httpEngine.permitsRequestBody(request);
    boolean hasResponseBody = true;
    String version = RequestLine.version(httpEngine.getConnection().getProtocol());
    stream = framedConnection.newStream(
        writeNameValueBlock(request, framedConnection.getProtocol(), version), permitsRequestBody,
        hasResponseBody);
    stream.readTimeout().timeout(httpEngine.client.getReadTimeout(), TimeUnit.MILLISECONDS);
  }

  @Override public void writeRequestBody(RetryableSink requestBody) throws IOException {
    requestBody.writeToSocket(stream.getSink());
  }

  @Override public void finishRequest() throws IOException {
    stream.getSink().close();
  }

  @Override public Response.Builder readResponseHeaders() throws IOException {
    return readNameValueBlock(stream.getResponseHeaders(), framedConnection.getProtocol());
  }

  /**
   * Returns a list of alternating names and values containing a SPDY request.
   * Names are all lowercase. No names are repeated. If any name has multiple
   * values, they are concatenated using "\0" as a delimiter.
   */
  public static List<Header> writeNameValueBlock(Request request, Protocol protocol,
      String version) {
    Headers headers = request.headers();
    List<Header> result = new ArrayList<>(headers.size() + 10);
    result.add(new Header(TARGET_METHOD, request.method()));
    result.add(new Header(TARGET_PATH, RequestLine.requestPath(request.httpUrl())));
    String host = Util.hostHeader(request.httpUrl());
    if (Protocol.SPDY_3 == protocol) {
      result.add(new Header(VERSION, version));
      result.add(new Header(TARGET_HOST, host));
    } else if (Protocol.HTTP_2 == protocol) {
      result.add(new Header(TARGET_AUTHORITY, host)); // Optional in HTTP/2
    } else {
      throw new AssertionError("unknown protocol " + protocol == null ? "null" : protocol.name());
    }
    result.add(new Header(TARGET_SCHEME, request.httpUrl().scheme()));

    Set<ByteString> names = new LinkedHashSet<ByteString>();
    for (int i = 0, size = headers.size(); i < size; i++) {
      // header names must be lowercase.
      ByteString name = ByteString.encodeUtf8(headers.name(i).toLowerCase(Locale.US));
      String value = headers.value(i);

      // Drop headers that are forbidden when layering HTTP over SPDY.
      if (isProhibitedHeader(protocol, name)) continue;

      // They shouldn't be set, but if they are, drop them. We've already written them!
      if (name.equals(TARGET_METHOD)
          || name.equals(TARGET_PATH)
          || name.equals(TARGET_SCHEME)
          || name.equals(TARGET_AUTHORITY)
          || name.equals(TARGET_HOST)
          || name.equals(VERSION)) {
        continue;
      }

      // If we haven't seen this name before, add the pair to the end of the list...
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

  /** Returns headers for a name value block containing a SPDY response. */
  public static Response.Builder readNameValueBlock(List<Header> headerBlock,
      Protocol protocol) throws IOException {
    String status = null;
    String version = "HTTP/1.1"; // :version present only in spdy/3.

    Headers.Builder headersBuilder = new Headers.Builder();
    headersBuilder.set(OkHeaders.SELECTED_PROTOCOL, protocol.toString());
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
        } else if (!isProhibitedHeader(protocol, name)) { // Don't write forbidden headers!
          headersBuilder.add(name.utf8(), value);
        }
        start = end + 1;
      }
    }
    if (status == null) throw new ProtocolException("Expected ':status' header not present");

    StatusLine statusLine = StatusLine.parse(version + " " + status);
    return new Response.Builder()
        .protocol(protocol)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
  }

  @Override public ResponseBody openResponseBody(Response response) throws IOException {
    return new RealResponseBody(response.headers(), Okio.buffer(stream.getSource()));
  }

  @Override public void releaseConnectionOnIdle() {
  }

  @Override public void disconnect(HttpEngine engine) throws IOException {
    if (stream != null) stream.close(ErrorCode.CANCEL);
  }

  @Override public boolean canReuseConnection() {
    return true; // TODO: framedConnection.isClosed() ?
  }

  /** When true, this header should not be emitted or consumed. */
  private static boolean isProhibitedHeader(Protocol protocol, ByteString name) {
    if (protocol == Protocol.SPDY_3) {
      return SPDY_3_PROHIBITED_HEADERS.contains(name);
    } else if (protocol == Protocol.HTTP_2) {
      return HTTP_2_PROHIBITED_HEADERS.contains(name);
    } else {
      throw new AssertionError(protocol);
    }
  }
}
