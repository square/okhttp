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

  @Override public Request prepareRequest(Request request) {
    Request.Builder builder = request.newBuilder()
        .header(":method", request.method())
        .header(":scheme", httpEngine.getRequest().url().getProtocol())
        .header(":path", RequestLine.requestPath(request.url()))
        .header(":version", RequestLine.version(httpEngine.connection.getHttpMinorVersion()))
        .header(":host", HttpEngine.hostHeader(request.url()));

    if (httpEngine.hasRequestBody()) {
      long fixedContentLength = httpEngine.policy.getFixedContentLength();
      if (fixedContentLength != -1) {
        builder.setContentLength(fixedContentLength);
      }
    }

    return builder.build();
  }

  @Override public OutputStream createRequestBody() throws IOException {
    // TODO: if we aren't streaming up to the server, we should buffer the whole request
    writeRequestHeaders();
    return stream.getOutputStream();
  }

  @Override public void writeRequestHeaders() throws IOException {
    if (stream != null) return;

    httpEngine.writingRequestHeaders();
    boolean hasRequestBody = httpEngine.hasRequestBody();
    boolean hasResponseBody = true;
    stream = spdyConnection.newStream(writeNameValueBlock(httpEngine.getRequest().getHeaders()),
        hasRequestBody, hasResponseBody);
    stream.setReadTimeout(httpEngine.client.getReadTimeout());
  }

  @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flushRequest() throws IOException {
    stream.getOutputStream().close();
  }

  @Override public Response readResponseHeaders() throws IOException {
    List<String> nameValueBlock = stream.getResponseHeaders();
    Response response = readNameValueBlock(httpEngine.getRequest(), nameValueBlock)
        .handshake(httpEngine.connection.getHandshake())
        .build();
    httpEngine.connection.setHttpMinorVersion(response.httpMinorVersion());
    httpEngine.receiveHeaders(response.headers());
    return response;
  }

  /**
   * Returns a list of alternating names and values containing a SPDY request.
   * Names are all lower case. No names are repeated. If any name has multiple
   * values, they are concatenated using "\0" as a delimiter.
   */
  public static List<String> writeNameValueBlock(Headers headers) {
    Set<String> names = new LinkedHashSet<String>();
    List<String> result = new ArrayList<String>(headers.length() * 2);
    for (int i = 0; i < headers.length(); i++) {
      String name = headers.getFieldName(i).toLowerCase(Locale.US);
      String value = headers.getValue(i);

      // Drop headers that are forbidden when layering HTTP over SPDY.
      if (name.equals("connection")
          || name.equals("host")
          || name.equals("keep-alive")
          || name.equals("proxy-connection")
          || name.equals("transfer-encoding")) {
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
  public static Response.Builder readNameValueBlock(Request request, List<String> nameValueBlock)
      throws IOException {
    if (nameValueBlock.size() % 2 != 0) {
      throw new IllegalArgumentException("Unexpected name value block: " + nameValueBlock);
    }
    String status = null;
    String version = null;

    Headers.Builder headersBuilder = new Headers.Builder();
    headersBuilder.set(SyntheticHeaders.SELECTED_TRANSPORT, "spdy/3");
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

    return new Response.Builder(request)
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
