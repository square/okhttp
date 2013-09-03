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

import com.squareup.okhttp.internal.spdy.ErrorCode;
import com.squareup.okhttp.internal.spdy.SpdyConnection;
import com.squareup.okhttp.internal.spdy.SpdyStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.URL;
import java.util.List;

public final class SpdyTransport implements Transport {
  private final HttpEngine httpEngine;
  private final SpdyConnection spdyConnection;
  private SpdyStream stream;

  public SpdyTransport(HttpEngine httpEngine, SpdyConnection spdyConnection) {
    this.httpEngine = httpEngine;
    this.spdyConnection = spdyConnection;
  }

  @Override public OutputStream createRequestBody() throws IOException {
    // TODO: if we aren't streaming up to the server, we should buffer the whole request
    writeRequestHeaders();
    return stream.getOutputStream();
  }

  @Override public void writeRequestHeaders() throws IOException {
    if (stream != null) {
      return;
    }
    httpEngine.writingRequestHeaders();
    RawHeaders requestHeaders = httpEngine.requestHeaders.getHeaders();
    String version = httpEngine.connection.getHttpMinorVersion() == 1 ? "HTTP/1.1" : "HTTP/1.0";
    URL url = httpEngine.policy.getURL();
    requestHeaders.addSpdyRequestHeaders(httpEngine.method, HttpEngine.requestPath(url), version,
        HttpEngine.getOriginAddress(url), httpEngine.uri.getScheme());
    boolean hasRequestBody = httpEngine.hasRequestBody();
    boolean hasResponseBody = true;
    stream = spdyConnection.newStream(requestHeaders.toNameValueBlock(), hasRequestBody,
        hasResponseBody);
    stream.setReadTimeout(httpEngine.client.getReadTimeout());
  }

  @Override public void writeRequestBody(RetryableOutputStream requestBody) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override public void flushRequest() throws IOException {
    stream.getOutputStream().close();
  }

  @Override public ResponseHeaders readResponseHeaders() throws IOException {
    List<String> nameValueBlock = stream.getResponseHeaders();
    RawHeaders rawHeaders = RawHeaders.fromNameValueBlock(nameValueBlock);
    httpEngine.receiveHeaders(rawHeaders);

    ResponseHeaders headers = new ResponseHeaders(httpEngine.uri, rawHeaders);
    headers.setTransport(spdyConnection.getVariant());
    return headers;
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
