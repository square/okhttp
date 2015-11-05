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

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.TlsVersion;

import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import okio.Buffer;

/** An HTTP request that came into the mock web server. */
public final class RecordedRequest {
  private final String requestLine;
  private final String method;
  private final String path;
  private final Headers headers;
  private final List<Integer> chunkSizes;
  private final long bodySize;
  private final Buffer body;
  private final int sequenceNumber;
  private final TlsVersion tlsVersion;
  private final Map<String, List<String>> queryParams;
  private final Map<String, List<String>> postParams;

  public RecordedRequest(String requestLine, Headers headers, List<Integer> chunkSizes,
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
      this.queryParams = parseGetParams(path);
      this.postParams = isUrlencodedPost()
        ? parseParams(getBody().readUtf8())
        : Collections.<String, List<String>>emptyMap();
    } else {
      this.method = null;
      this.path = null;
      this.queryParams = Collections.emptyMap();
      this.postParams = Collections.emptyMap();
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
  public Headers getHeaders() {
    return headers;
  }

  /** Returns the first header named {@code name}, or null if no such header exists. */
  public String getHeader(String name) {
    List<String> values = headers.values(name);
    return values.isEmpty() ? null : values.get(0);
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

  /** Returns the connection's TLS version or null if the connection doesn't use SSL. */
  public TlsVersion getTlsVersion() {
    return tlsVersion;
  }

  /**
   * Returns all values for query param
   */
  public List<String> getQueryParams(String param) {
    return queryParams.get(param);
  }

  /**
   * Returns all query params
   */
  public Map<String, List<String>> getQueryParams() {
    return queryParams;
  }

  /**
   * Returns single value for query param
   */
  public String getQueryParam(String param) {
    List<String> params = getQueryParams(param);
    if (params != null) {
      return params.get(0);
    }
    return null;
  }

  /**
   * Returns all values for form post param
   */
  public List<String> getPostParams(String param) {
    return postParams.get(param);
  }

  /**
   * Returns all form post params
   */
  public Map<String, List<String>> getPostParams() {
    return postParams;
  }

  /**
   * Returns single value for form post param
   */
  public String getPostParam(String param) {
    List<String> params = getPostParams(param);
    if (params != null) {
      return params.get(0);
    }
    return null;
  }

  @Override public String toString() {
    return requestLine;
  }

  /**
   * split & decodes query parameters into new map
   */
  private static Map<String, List<String>> splitQuery(String query) throws UnsupportedEncodingException {
    if (query == null) {
      return Collections.emptyMap();
    }
    Map<String, List<String>> query_pairs = new LinkedHashMap<>();
    String[] pairs = query.split("&");
    for (String pair : pairs) {
      int idx = pair.indexOf("=");
      String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
      if (!query_pairs.containsKey(key)) {
        query_pairs.put(key, new LinkedList<String>());
      }
      String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : null;
      query_pairs.get(key).add(value);
    }
    return query_pairs;
  }

  /**
   * split valid query string
   */
  private Map<String, List<String>> parseParams(String body) {
    Map<String, List<String>> queryParams;
    try {
      queryParams = Collections.unmodifiableMap(splitQuery(body));
    } catch (UnsupportedEncodingException ignored) {
      queryParams = Collections.emptyMap();
    }
    return queryParams;
  }

  /**
   * get query from path and split it
   */
  private Map<String, List<String>> parseGetParams(String path) {
    URI uri;
    try {
      uri = new URI(path);
    } catch (URISyntaxException ignored) {
      return Collections.emptyMap();
    }
    return parseParams(uri.getQuery());
  }

  /**
   * checks if request is post and is form-urlencoded (for valid query parsing)
   */
  private boolean isUrlencodedPost() {
    return method.toLowerCase().equals("post") && getHeader("Content-Type").equals("application/x-www-form-urlencoded");
  }
}
