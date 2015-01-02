/*
 * Copyright (C) 2011 Google Inc.
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

package com.squareup.okhttp.mockwebserver;

import com.squareup.okhttp.TlsVersion;
import com.squareup.okhttp.internal.Internal;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import okio.Buffer;

/** An HTTP request that came into the mock web server. */
public final class RecordedRequest {
  private final String requestLine;
  private final String method;
  private final String path;
  private final List<String> headers;
  private final List<Integer> chunkSizes;
  private final long bodySize;
  private final Buffer body;
  private final int sequenceNumber;
  private final TlsVersion tlsVersion;

  public RecordedRequest(String requestLine, List<String> headers, List<Integer> chunkSizes,
      long bodySize, Buffer body, int sequenceNumber, Socket socket) {
    this.requestLine = requestLine;
    this.headers = headers;
    this.chunkSizes = chunkSizes;
    this.bodySize = bodySize;
    this.body = body;
    this.sequenceNumber = sequenceNumber;
    this.tlsVersion = socket instanceof SSLSocket
        ? TlsVersion.forJavaName(((SSLSocket) socket).getSession().getProtocol())
        : null;

    if (requestLine != null) {
      int methodEnd = requestLine.indexOf(' ');
      int pathEnd = requestLine.indexOf(' ', methodEnd + 1);
      this.method = requestLine.substring(0, methodEnd);
      this.path = requestLine.substring(methodEnd + 1, pathEnd);
    } else {
      this.method = null;
      this.path = null;
    }
  }

  public String getRequestLine() {
    return requestLine;
  }

  public String getMethod() {
    return method;
  }

  public String getPath() {
    return path;
  }

  /** Returns all headers. */
  public List<String> getHeaders() {
    return headers;
  }

  /**
   * Returns the first header named {@code name}, or null if no such header
   * exists.
   */
  public String getHeader(String name) {
    name += ":";
    for (int i = 0, size = headers.size(); i < size; i++) {
      String header = headers.get(i);
      if (name.regionMatches(true, 0, header, 0, name.length())) {
        return header.substring(name.length()).trim();
      }
    }
    return null;
  }

  /** Returns the headers named {@code name}. */
  public List<String> getHeaders(String name) {
    List<String> result = new ArrayList<>();
    name += ":";
    for (int i = 0, size = headers.size(); i < size; i++) {
      String header = headers.get(i);
      if (name.regionMatches(true, 0, header, 0, name.length())) {
        result.add(header.substring(name.length()).trim());
      }
    }
    return result;
  }

  /**
   * Returns the sizes of the chunks of this request's body, or an empty list
   * if the request's body was empty or unchunked.
   */
  public List<Integer> getChunkSizes() {
    return chunkSizes;
  }

  /**
   * Returns the total size of the body of this POST request (before
   * truncation).
   */
  public long getBodySize() {
    return bodySize;
  }

  /** Returns the body of this POST request. This may be truncated. */
  public Buffer getBody() {
    return body;
  }

  /** @deprecated Use {@link #getBody() getBody().readUtf8()}. */
  public String getUtf8Body() {
    return getBody().readUtf8();
  }

  /**
   * Returns the index of this request on its HTTP connection. Since a single
   * HTTP connection may serve multiple requests, each request is assigned its
   * own sequence number.
   */
  public int getSequenceNumber() {
    return sequenceNumber;
  }

  /** @deprecated Use {@link #getTlsVersion()}. */
  public String getSslProtocol() {
    return tlsVersion != null ? Internal.instance.tlsVersionJavaName(tlsVersion) : null;
  }

  /** Returns the connection's TLS version or null if the connection doesn't use SSL. */
  public TlsVersion getTlsVersion() {
    return tlsVersion;
  }

  @Override public String toString() {
    return requestLine;
  }
}
