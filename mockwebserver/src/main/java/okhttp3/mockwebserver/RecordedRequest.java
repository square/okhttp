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

package okhttp3.mockwebserver;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import javax.net.ssl.SSLSocket;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.TlsVersion;
import okio.Buffer;

/** An HTTP request that came into the mock web server. */
public final class RecordedRequest {
  private final String requestLine;
  private final String method;
  private final String path;
  private final Headers headers;
  private final Handshake handshake;
  private final List<Integer> chunkSizes;
  private final long bodySize;
  private final Buffer body;
  private final int sequenceNumber;
  private final HttpUrl requestUrl;

  public RecordedRequest(String requestLine, Headers headers, List<Integer> chunkSizes,
      long bodySize, Buffer body, int sequenceNumber, Socket socket) {
    this.requestLine = requestLine;
    this.headers = headers;
    this.chunkSizes = chunkSizes;
    this.bodySize = bodySize;
    this.body = body;
    this.sequenceNumber = sequenceNumber;
    if (socket instanceof SSLSocket) {
      try {
        this.handshake = Handshake.get(((SSLSocket) socket).getSession());
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    } else {
      this.handshake = null;
    }

    if (requestLine != null) {
      int methodEnd = requestLine.indexOf(' ');
      int pathEnd = requestLine.indexOf(' ', methodEnd + 1);
      this.method = requestLine.substring(0, methodEnd);
      String path = requestLine.substring(methodEnd + 1, pathEnd);
      if (!path.startsWith("/")) {
        path = "/";
      }
      this.path = path;

      String scheme = socket instanceof SSLSocket ? "https" : "http";
      InetAddress inetAddress = socket.getLocalAddress();

      String hostname = inetAddress.getHostName();
      if (inetAddress instanceof Inet6Address) {
        hostname = "[" + hostname + "]";
      }

      int localPort = socket.getLocalPort();
      // Allow null in failure case to allow for testing bad requests
      this.requestUrl =
          HttpUrl.parse(String.format("%s://%s:%s%s", scheme, hostname, localPort, path));
    } else {
      this.requestUrl = null;
      this.method = null;
      this.path = null;
    }
  }

  public HttpUrl getRequestUrl() {
    return requestUrl;
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
  public Headers getHeaders() {
    return headers;
  }

  /** Returns the first header named {@code name}, or null if no such header exists. */
  public String getHeader(String name) {
    List<String> values = headers.values(name);
    return values.isEmpty() ? null : values.get(0);
  }

  /**
   * Returns the sizes of the chunks of this request's body, or an empty list if the request's body
   * was empty or unchunked.
   */
  public List<Integer> getChunkSizes() {
    return chunkSizes;
  }

  /**
   * Returns the total size of the body of this POST request (before truncation).
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
   * Returns the index of this request on its HTTP connection. Since a single HTTP connection may
   * serve multiple requests, each request is assigned its own sequence number.
   */
  public int getSequenceNumber() {
    return sequenceNumber;
  }

  /** Returns the connection's TLS version or null if the connection doesn't use SSL. */
  public TlsVersion getTlsVersion() {
    return handshake != null ? handshake.tlsVersion() : null;
  }

  /**
   * Returns the TLS handshake of the connection that carried this request, or null if the request
   * was received without TLS.
   */
  public Handshake getHandshake() {
    return handshake;
  }

  @Override public String toString() {
    return requestLine;
  }
}
