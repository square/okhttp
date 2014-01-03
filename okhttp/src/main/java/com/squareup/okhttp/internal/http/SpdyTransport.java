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
        writeNameValueBlock(request, version), hasRequestBody, hasResponseBody);
    stream.setReadTimeout(httpEngine.client.getReadTimeout());
  }

  @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flushRequest() throws IOException {
    stream.getOutputStream().close();
  }

  @Override public Response.Builder readResponseHeaders() throws IOException {
    return readNameValueBlock(stream.getResponseHeaders());
  }

  /**
   * Returns a list of alternating names and values containing a SPDY request.
   * Names are all lower case. No names are repeated. If any name has multiple
   * values, they are concatenated using "\0" as a delimiter.
   */
  public static List<String> writeNameValueBlock(Request request, String version) {
    Headers headers = request.headers();
    List<String> result = new ArrayList<String>(headers.size() + 10);
    result.add(":method");
    result.add(request.method());
    result.add(":path");
    result.add(RequestLine.requestPath(request.url()));
    result.add(":version");
    result.add(version);
    result.add(":host");
    result.add(HttpEngine.hostHeader(request.url()));
    result.add(":scheme");
    result.add(request.url().getProtocol());

    Set<String> names = new LinkedHashSet<String>();
    for (int i = 0; i < headers.size(); i++) {
      String name = headers.name(i).toLowerCase(Locale.US);
      String value = headers.value(i);

      // Drop headers that are forbidden when layering HTTP over SPDY.
      if (name.equals("connection")
          || name.equals("host")
          || name.equals("keep-alive")
          || name.equals("proxy-connection")
          || name.equals("transfer-encoding")) {
        continue;
      }

      // They shouldn't be set, but if they are, drop them. We've already written them!
      if (name.equals(":method")
          || name.equals(":path")
          || name.equals(":version")
          || name.equals(":host")
          || name.equals(":scheme")) {
        continue;
      }

      // If we haven't seen this name before, add the pair to the end of the list...
      if (names.add(name)) {
        result.add(name);
        result.add(value);
        continue;
      }

      // ...otherwise concatenate the existing values and this value.
      for (int j = 0; j < result.size(); j += 2) {
        if (name.equals(result.get(j))) {
          result.set(j + 1, result.get(j + 1) + "\0" + value);
          break;
        }
      }
    }
    return result;
  }

  /** Returns headers for a name value block containing a SPDY response. */
  public static Response.Builder readNameValueBlock(List<String> nameValueBlock)
      throws IOException {
    if (nameValueBlock.size() % 2 != 0) {
      throw new IllegalArgumentException("Unexpected name value block: " + nameValueBlock);
    }
    String status = null;
    String version = null;

    Headers.Builder headersBuilder = new Headers.Builder();
    headersBuilder.set(OkHeaders.SELECTED_TRANSPORT, "spdy/3");
    for (int i = 0; i < nameValueBlock.size(); i += 2) {
      String name = nameValueBlock.get(i);
      String values = nameValueBlock.get(i + 1);
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
        } else {
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
}
