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
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.ByteString;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static com.squareup.okhttp.internal.spdy.Header.RESPONSE_STATUS;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_AUTHORITY;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_HOST;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_METHOD;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_PATH;
import static com.squareup.okhttp.internal.spdy.Header.TARGET_SCHEME;
import static com.squareup.okhttp.internal.spdy.Header.VERSION;

public final class SpdyTransport implements Transport {
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
  private final SpdyConnection spdyConnection;
  private SpdyStream stream;

  public SpdyTransport(HttpEngine httpEngine, SpdyConnection spdyConnection) {
    this.httpEngine = httpEngine;
    this.spdyConnection = spdyConnection;
  }

  @Override public Sink createRequestBody(Request request) throws IOException {
    // TODO: if bufferRequestBody is set, we must buffer the whole request
    writeRequestHeaders(request);
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(Request request) throws IOException {
    if (stream != null) return;

    httpEngine.writingRequestHeaders();
    boolean hasRequestBody = httpEngine.hasRequestBody();
    boolean hasResponseBody = true;
    String version = RequestLine.version(httpEngine.getConnection().getProtocol());
    stream = spdyConnection.newStream(
        writeNameValueBlock(request, spdyConnection.getProtocol(), version), hasRequestBody,
        hasResponseBody);
    stream.readTimeout().timeout(httpEngine.client.getReadTimeout(), TimeUnit.MILLISECONDS);
  }

  @Override public void writeRequestBody(RetryableSink requestBody) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flushRequest() throws IOException {
    stream.getSink().close();
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
    List<Header> result = new ArrayList<Header>(headers.size() + 10);
    result.add(new Header(TARGET_METHOD, request.method()));
    result.add(new Header(TARGET_PATH, RequestLine.requestPath(request.url())));
    String host = HttpEngine.hostHeader(request.url());
    if (Protocol.SPDY_3 == protocol) {
      result.add(new Header(VERSION, version));
      result.add(new Header(TARGET_HOST, host));
    } else if (Protocol.HTTP_2 == protocol) {
      result.add(new Header(TARGET_AUTHORITY, host)); // Optional in HTTP/2
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
    headersBuilder.set(OkHeaders.SELECTED_PROTOCOL, protocol.toString());
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

    StatusLine statusLine = StatusLine.parse(version + " " + status);
    return new Response.Builder()
        .protocol(statusLine.protocol)
        .code(statusLine.code)
        .message(statusLine.message)
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

  @Override public void disconnect(HttpEngine engine) throws IOException {
    stream.close(ErrorCode.CANCEL);
  }

  @Override public boolean canReuseConnection() {
    return true; // TODO: spdyConnection.isClosed() ?
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

    @Override public long read(Buffer sink, long byteCount)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (inputExhausted) return -1;

      long read = source.read(sink, byteCount);
      if (read == -1) {
        inputExhausted = true;
        if (cacheRequest != null) {
          cacheBody.close();
        }
        return -1;
      }

      if (cacheBody != null) {
        sink.copyTo(cacheBody, sink.size() - read, read);
      }

      return read;
    }

    @Override public Timeout timeout() {
      return source.timeout();
    }

    @Override public void close() throws IOException {
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
      long oldTimeoutNanos = stream.readTimeout().timeoutNanos();
      stream.readTimeout().timeout(DISCARD_STREAM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      try {
        Util.skipAll(this, DISCARD_STREAM_TIMEOUT_MILLIS);
        return true;
      } catch (IOException e) {
        return false;
      } finally {
        stream.readTimeout().timeout(oldTimeoutNanos, TimeUnit.NANOSECONDS);
      }
    }
  }
}
