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
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.ByteString;
import com.squareup.okhttp.internal.bytes.Deadline;
import com.squareup.okhttp.internal.bytes.OkBuffer;
import com.squareup.okhttp.internal.bytes.OkBuffers;
import com.squareup.okhttp.internal.bytes.Source;
import com.squareup.okhttp.internal.spdy.ErrorCode;
import com.squareup.okhttp.internal.spdy.Header;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import com.squareup.okhttp.internal.spdy.SpdyStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.squareup.okhttp.internal.spdy.Header.RESPONSE_STATUS;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_AUTHORITY;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_HOST;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_METHOD;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_PATH;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_SCHEME;
import static com.squareup.okhttp.internal.spdy.Header.VERSION;

public final class SpdyTransport implements Transport {
  private final HttpEngine httpEngine;
  private final SpdyConnection spdyConnection;
  private SpdyStream stream;

  public SpdyTransport(HttpEngine httpEngine, SpdyConnection spdyConnection) {
    this.httpEngine = httpEngine;
    this.spdyConnection = spdyConnection;
  }

  @Override public OutputStream createRequestBody(Request request) throws IOException {
    // TODO: if bufferRequestBody is set, we must buffer the whole request
    writeRequestHeaders(request);
    return stream.getOutputStream();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    httpEngine.writingRequestHeaders();
    boolean hasRequestBody = httpEngine.hasRequestBody();
    boolean hasResponseBody = true;
    String version = RequestLine.version(httpEngine.getConnection().getHttpMinorVersion());
    stream = spdyConnection.newStream(
        writeNameValueBlock(request, spdyConnection.getProtocol(), version), hasRequestBody,
        hasResponseBody);
    stream.setReadTimeout(httpEngine.client.getReadTimeout());
  }

  @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flushRequest() throws IOException {
    stream.getOutputStream().close();
  }

  @Override public Response.Builder readResponseHeaders() throws IOException {
    return readNameValueBlock(stream.getResponseHeaders(), spdyConnection.getProtocol());
  }

  /**
   * Returns a list of alternating names and values containing a SPDY request.
   * Names are all lowercase. No names are repeated. If any name has multiple
   * values, they are concatenated using "\0" as a delimiter.
   */
  public static List<Header> writeNameValueBlock(Request request, Protocol protocol,
      String version) {
    Headers headers = request.headers();
    // TODO: make the known header names constants.
    List<Header> result = new ArrayList<Header>(headers.size() + 10);
    result.add(new Header(TARGET_METHOD, request.method()));
    result.add(new Header(TARGET_PATH, RequestLine.requestPath(request.url())));
    String host = HttpEngine.hostHeader(request.url());
    if (Protocol.SPDY_3 == protocol) {
      result.add(new Header(VERSION, version));
      result.add(new Header(TARGET_HOST, host));
    } else if (Protocol.HTTP_2 == protocol) {
      result.add(new Header(TARGET_AUTHORITY, host));
    } else {
      throw new AssertionError();
    }
    result.add(new Header(TARGET_SCHEME, request.url().getProtocol()));

    Set<ByteString> names = new LinkedHashSet<ByteString>();
    for (int i = 0; i < headers.size(); i++) {
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
    headersBuilder.set(OkHeaders.SELECTED_TRANSPORT, protocol.name.utf8());
    headersBuilder.set(OkHeaders.SELECTED_PROTOCOL, protocol.name.utf8());
    for (int i = 0; i < headerBlock.size(); i++) {
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
    if (version == null) throw new ProtocolException("Expected ':version' header not present");

    return new Response.Builder()
        .statusLine(new StatusLine(version + " " + status))
        .headers(headersBuilder.build());
  }

  @Override public void emptyTransferStream() {
    // Do nothing.
  }

  @Override public Source getTransferStream(CacheRequest cacheRequest) throws IOException {
    return new SpdySource(stream, cacheRequest);
  }

  @Override public void releaseConnectionOnIdle() {
  }

  @Override public boolean canReuseConnection() {
    return true; // TODO: spdyConnection.isClosed() ?
  }

  /** When true, this header should not be emitted or consumed. */
  private static boolean isProhibitedHeader(Protocol protocol, ByteString name) {
    boolean prohibited = false;
    if (protocol == Protocol.SPDY_3) {
      // http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3-1#TOC-3.2.1-Request
      if (name.equalsAscii("connection")
          || name.equalsAscii("host")
          || name.equalsAscii("keep-alive")
          || name.equalsAscii("proxy-connection")
          || name.equalsAscii("transfer-encoding")) {
        prohibited = true;
      }
    } else if (protocol == Protocol.HTTP_2) {
      // http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3
      if (name.equalsAscii("connection")
          || name.equalsAscii("host")
          || name.equalsAscii("keep-alive")
          || name.equalsAscii("proxy-connection")
          || name.equalsAscii("te")
          || name.equalsAscii("transfer-encoding")
          || name.equalsAscii("encoding")
          || name.equalsAscii("upgrade")) {
        prohibited = true;
      }
    } else {
      throw new AssertionError(protocol);
    }
    return prohibited;
  }

  /** An HTTP message body terminated by the end of the underlying stream. */
  private static class SpdySource implements Source {
    private final SpdyStream stream;
    private final Source source;
    private final CacheRequest cacheRequest;
    private final OutputStream cacheBody;

    private boolean inputExhausted;
    private boolean closed;

    SpdySource(SpdyStream stream, CacheRequest cacheRequest) throws IOException {
      this.stream = stream;
      this.source = stream.getSource();

      // Some apps return a null body; for compatibility we treat that like a null cache request.
      OutputStream cacheBody = cacheRequest != null ? cacheRequest.getBody() : null;
      if (cacheBody == null) {
        cacheRequest = null;
      }

      this.cacheBody = cacheBody;
      this.cacheRequest = cacheRequest;
    }

    @Override public long read(OkBuffer sink, long byteCount, Deadline deadline)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (inputExhausted) return -1;

      long read = source.read(sink, byteCount, deadline);
      if (read == -1) {
        inputExhausted = true;
        if (cacheRequest != null) {
          cacheBody.close();
        }
        return -1;
      }

      if (cacheBody != null) {
        OkBuffers.copy(sink, sink.byteCount() - read, read, cacheBody);
      }

      return read;
    }

    @Override public void close(Deadline deadline) throws IOException {
      if (closed) return;

      if (!inputExhausted && cacheBody != null) {
        discardStream(); // Could make inputExhausted true!
      }

      closed = true;

      if (!inputExhausted) {
        stream.closeLater(ErrorCode.CANCEL);
        if (cacheRequest != null) {
          cacheRequest.abort();
        }
      }
    }

    private boolean discardStream() {
      try {
        long socketTimeout = stream.getReadTimeoutMillis();
        stream.setReadTimeout(socketTimeout);
        stream.setReadTimeout(DISCARD_STREAM_TIMEOUT_MILLIS);
        try {
          Util.skipAll(this, DISCARD_STREAM_TIMEOUT_MILLIS);
          return true;
        } finally {
          stream.setReadTimeout(socketTimeout);
        }
      } catch (IOException e) {
        return false;
      }
    }
  }
}
