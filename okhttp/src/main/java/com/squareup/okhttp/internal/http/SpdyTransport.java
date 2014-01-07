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
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.ByteString;
import com.squareup.okhttp.internal.spdy.ErrorCode;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import com.squareup.okhttp.internal.spdy.SpdyStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    String version = RequestLine.version(httpEngine.connection.getHttpMinorVersion());
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
   * Names are all lower case. No names are repeated. If any name has multiple
   * values, they are concatenated using "\0" as a delimiter.
   */
  public static List<ByteString> writeNameValueBlock(Request request, String protocol,
      String version) {
    Headers headers = request.headers();
    // TODO: make the known header names constants.
    List<ByteString> result = new ArrayList<ByteString>(headers.size() + 10);
    result.add(ByteString.encodeUtf8(":method"));
    result.add(ByteString.encodeUtf8(request.method()));
    result.add(ByteString.encodeUtf8(":path"));
    result.add(ByteString.encodeUtf8(RequestLine.requestPath(request.url())));
    result.add(ByteString.encodeUtf8(":version"));
    result.add(ByteString.encodeUtf8(version));
    if (protocol.equals("spdy/3")) {
      result.add(ByteString.encodeUtf8(":host"));
    } else if (protocol.equals("HTTP-draft-09/2.0")) {
      result.add(ByteString.encodeUtf8(":authority"));
    } else {
      throw new AssertionError();
    }
    result.add(ByteString.encodeUtf8(HttpEngine.hostHeader(request.url())));
    result.add(ByteString.encodeUtf8(":scheme"));
    result.add(ByteString.encodeUtf8(request.url().getProtocol()));

    Set<ByteString> names = new LinkedHashSet<ByteString>();
    for (int i = 0; i < headers.size(); i++) {
      String name = headers.name(i).toLowerCase(Locale.US);
      String value = headers.value(i);

      // Drop headers that are forbidden when layering HTTP over SPDY.
      if (isProhibitedHeader(protocol, name)) continue;

      // They shouldn't be set, but if they are, drop them. We've already written them!
      if (name.equals(":method")
          || name.equals(":path")
          || name.equals(":version")
          || name.equals(":host")
          || name.equals(":authority")
          || name.equals(":scheme")) {
        continue;
      }

      // If we haven't seen this name before, add the pair to the end of the list...
      if (names.add(ByteString.encodeUtf8(name))) {
        result.add(ByteString.encodeUtf8(name));
        result.add(ByteString.encodeUtf8(value));
        continue;
      }

      // ...otherwise concatenate the existing values and this value.
      for (int j = 0; j < result.size(); j += 2) {
        if (result.get(j).utf8Equals(name)) {
          result.set(j + 1, ByteString.encodeUtf8(result.get(j + 1).utf8() + "\0" + value));
          break;
        }
      }
    }
    return result;
  }

  /** Returns headers for a name value block containing a SPDY response. */
  public static Response.Builder readNameValueBlock(List<ByteString> nameValueBlock,
      String protocol) throws IOException {
    if (nameValueBlock.size() % 2 != 0) {
      throw new IllegalArgumentException("Unexpected name value block: " + nameValueBlock);
    }
    String status = null;
    String version = null;

    Headers.Builder headersBuilder = new Headers.Builder();
    headersBuilder.set(OkHeaders.SELECTED_TRANSPORT, protocol);
    for (int i = 0; i < nameValueBlock.size(); i += 2) {
      String name = nameValueBlock.get(i).utf8();
      String values = nameValueBlock.get(i + 1).utf8();
      for (int start = 0; start < values.length(); ) {
        int end = values.indexOf('\0', start);
        if (end == -1) {
          end = values.length();
        }
        String value = values.substring(start, end);
        if (":status".equals(name)) {
          status = value;
        } else if (":version".equals(name)) {
          version = value;
        } else if (!isProhibitedHeader(protocol, name)) { // Don't write forbidden headers!
          headersBuilder.add(name, value);
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

  @Override public InputStream getTransferStream(CacheRequest cacheRequest) throws IOException {
    return new UnknownLengthHttpInputStream(stream.getInputStream(), cacheRequest, httpEngine);
  }

  @Override public boolean makeReusable(boolean streamCanceled, OutputStream requestBodyOut,
      InputStream responseBodyIn) {
    if (streamCanceled) {
      if (stream != null) {
        stream.closeLater(ErrorCode.CANCEL);
        return true;
      } else {
        // If stream is null, it either means that writeRequestHeaders wasn't called
        // or that SpdyConnection#newStream threw an IOException. In both cases there's
        // nothing to do here and this stream can't be reused.
        return false;
      }
    }
    return true;
  }

  /** When true, this header should not be emitted or consumed. */
  private static boolean isProhibitedHeader(String protocol, String name) {
    boolean prohibited = false;
    if (protocol.equals("spdy/3")) {
      // http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3#TOC-3.2.1-Request
      if (name.equals("connection")
          || name.equals("host")
          || name.equals("keep-alive")
          || name.equals("proxy-connection")
          || name.equals("transfer-encoding")) {
        prohibited = true;
      }
    } else if (protocol.equals("HTTP-draft-09/2.0")) {
      // http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3
      if (name.equals("connection")
          || name.equals("host")
          || name.equals("keep-alive")
          || name.equals("proxy-connection")
          || name.equals("te")
          || name.equals("transfer-encoding")
          || name.equals("encoding")
          || name.equals("upgrade")) {
        prohibited = true;
      }
    } else {
      throw new AssertionError();
    }
    return prohibited;
  }
}
