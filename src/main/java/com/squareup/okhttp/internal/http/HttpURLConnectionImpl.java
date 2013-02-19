/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.internal.Util;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.SocketPermission;
import java.net.URL;
import java.security.Permission;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

import static com.squareup.okhttp.internal.Util.getEffectivePort;

/**
 * This implementation uses HttpEngine to send requests and receive responses.
 * This class may use multiple HttpEngines to follow redirects, authentication
 * retries, etc. to retrieve the final response body.
 *
 * <h3>What does 'connected' mean?</h3>
 * This class inherits a {@code connected} field from the superclass. That field
 * is <strong>not</strong> used to indicate not whether this URLConnection is
 * currently connected. Instead, it indicates whether a connection has ever been
 * attempted. Once a connection has been attempted, certain properties (request
 * header fields, request method, etc.) are immutable. Test the {@code
 * connection} field on this class for null/non-null to determine of an instance
 * is currently connected to a server.
 */
public class HttpURLConnectionImpl extends HttpURLConnection {
  /**
   * How many redirects should we follow? Chrome follows 21; Firefox, curl,
   * and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  private static final int MAX_REDIRECTS = 20;

  private final boolean followProtocolRedirects;

  /** The proxy requested by the client, or null for a proxy to be selected automatically. */
  final Proxy requestedProxy;

  final ProxySelector proxySelector;
  final CookieHandler cookieHandler;
  final ResponseCache responseCache;
  final ConnectionPool connectionPool;
  /* SSL configuration; necessary for HTTP requests that get redirected to HTTPS. */
  SSLSocketFactory sslSocketFactory;
  HostnameVerifier hostnameVerifier;

  private final RawHeaders rawRequestHeaders = new RawHeaders();

  private int redirectionCount;

  protected IOException httpEngineFailure;
  protected HttpEngine httpEngine;

  public HttpURLConnectionImpl(URL url, OkHttpClient client) {
    super(url);
    this.followProtocolRedirects = client.getFollowProtocolRedirects();
    this.requestedProxy = client.getProxy();
    this.proxySelector = client.getProxySelector();
    this.cookieHandler = client.getCookieHandler();
    this.responseCache = client.getResponseCache();
    this.connectionPool = client.getConnectionPool();
    this.sslSocketFactory = client.getSslSocketFactory();
    this.hostnameVerifier = client.getHostnameVerifier();
  }

  @Override public final void connect() throws IOException {
    initHttpEngine();
    boolean success;
    do {
      success = execute(false);
    } while (!success);
  }

  @Override public final void disconnect() {
    // Calling disconnect() before a connection exists should have no effect.
    if (httpEngine != null) {
      // We close the response body here instead of in
      // HttpEngine.release because that is called when input
      // has been completely read from the underlying socket.
      // However the response body can be a GZIPInputStream that
      // still has unread data.
      if (httpEngine.hasResponse()) {
        Util.closeQuietly(httpEngine.getResponseBody());
      }
      httpEngine.release(true);
    }
  }

  /**
   * Returns an input stream from the server in the case of error such as the
   * requested file (txt, htm, html) is not found on the remote server.
   */
  @Override public final InputStream getErrorStream() {
    try {
      HttpEngine response = getResponse();
      if (response.hasResponseBody() && response.getResponseCode() >= HTTP_BAD_REQUEST) {
        return response.getResponseBody();
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Returns the value of the field at {@code position}. Returns null if there
   * are fewer than {@code position} headers.
   */
  @Override public final String getHeaderField(int position) {
    try {
      return getResponse().getResponseHeaders().getHeaders().getValue(position);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Returns the value of the field corresponding to the {@code fieldName}, or
   * null if there is no such field. If the field has multiple values, the
   * last value is returned.
   */
  @Override public final String getHeaderField(String fieldName) {
    try {
      RawHeaders rawHeaders = getResponse().getResponseHeaders().getHeaders();
      return fieldName == null ? rawHeaders.getStatusLine() : rawHeaders.get(fieldName);
    } catch (IOException e) {
      return null;
    }
  }

  @Override public final String getHeaderFieldKey(int position) {
    try {
      return getResponse().getResponseHeaders().getHeaders().getFieldName(position);
    } catch (IOException e) {
      return null;
    }
  }

  @Override public final Map<String, List<String>> getHeaderFields() {
    try {
      return getResponse().getResponseHeaders().getHeaders().toMultimap(true);
    } catch (IOException e) {
      return null;
    }
  }

  @Override public final Map<String, List<String>> getRequestProperties() {
    if (connected) {
      throw new IllegalStateException(
          "Cannot access request header fields after connection is set");
    }
    return rawRequestHeaders.toMultimap(false);
  }

  @Override public final InputStream getInputStream() throws IOException {
    if (!doInput) {
      throw new ProtocolException("This protocol does not support input");
    }

    HttpEngine response = getResponse();

    // if the requested file does not exist, throw an exception formerly the
    // Error page from the server was returned if the requested file was
    // text/html this has changed to return FileNotFoundException for all
    // file types
    if (getResponseCode() >= HTTP_BAD_REQUEST) {
      throw new FileNotFoundException(url.toString());
    }

    InputStream result = response.getResponseBody();
    if (result == null) {
      throw new ProtocolException("No response body exists; responseCode=" + getResponseCode());
    }
    return result;
  }

  @Override public final OutputStream getOutputStream() throws IOException {
    connect();

    OutputStream result = httpEngine.getRequestBody();
    if (result == null) {
      throw new ProtocolException("method does not support a request body: " + method);
    } else if (httpEngine.hasResponse()) {
      throw new ProtocolException("cannot write request body after response has been read");
    }

    return result;
  }

  @Override public final Permission getPermission() throws IOException {
    String hostName = getURL().getHost();
    int hostPort = Util.getEffectivePort(getURL());
    if (usingProxy()) {
      InetSocketAddress proxyAddress = (InetSocketAddress) requestedProxy.address();
      hostName = proxyAddress.getHostName();
      hostPort = proxyAddress.getPort();
    }
    return new SocketPermission(hostName + ":" + hostPort, "connect, resolve");
  }

  @Override public final String getRequestProperty(String field) {
    if (field == null) {
      return null;
    }
    return rawRequestHeaders.get(field);
  }

  private void initHttpEngine() throws IOException {
    if (httpEngineFailure != null) {
      throw httpEngineFailure;
    } else if (httpEngine != null) {
      return;
    }

    connected = true;
    try {
      if (doOutput) {
        if (method.equals("GET")) {
          // they are requesting a stream to write to. This implies a POST method
          method = "POST";
        } else if (!method.equals("POST") && !method.equals("PUT")) {
          // If the request method is neither POST nor PUT, then you're not writing
          throw new ProtocolException(method + " does not support writing");
        }
      }
      httpEngine = newHttpEngine(method, rawRequestHeaders, null, null);
    } catch (IOException e) {
      httpEngineFailure = e;
      throw e;
    }
  }

  protected HttpURLConnection getHttpConnectionToCache() {
    return this;
  }

  private HttpEngine newHttpEngine(String method, RawHeaders requestHeaders,
      Connection connection, RetryableOutputStream requestBody) throws IOException {
    if (url.getProtocol().equals("http")) {
      return new HttpEngine(this, method, requestHeaders, connection, requestBody);
    } else if (url.getProtocol().equals("https")) {
      return new HttpsURLConnectionImpl.HttpsEngine(
          this, method, requestHeaders, connection, requestBody);
    } else {
      throw new AssertionError();
    }
  }

  /**
   * Aggressively tries to get the final HTTP response, potentially making
   * many HTTP requests in the process in order to cope with redirects and
   * authentication.
   */
  private HttpEngine getResponse() throws IOException {
    initHttpEngine();

    if (httpEngine.hasResponse()) {
      return httpEngine;
    }

    while (true) {
      if (!execute(true)) {
        continue;
      }

      Retry retry = processResponseHeaders();
      if (retry == Retry.NONE) {
        httpEngine.automaticallyReleaseConnectionToPool();
        return httpEngine;
      }

      // The first request was insufficient. Prepare for another...
      String retryMethod = method;
      OutputStream requestBody = httpEngine.getRequestBody();

      // Although RFC 2616 10.3.2 specifies that a HTTP_MOVED_PERM
      // redirect should keep the same method, Chrome, Firefox and the
      // RI all issue GETs when following any redirect.
      int responseCode = getResponseCode();
      if (responseCode == HTTP_MULT_CHOICE
          || responseCode == HTTP_MOVED_PERM
          || responseCode == HTTP_MOVED_TEMP
          || responseCode == HTTP_SEE_OTHER) {
        retryMethod = "GET";
        requestBody = null;
      }

      if (requestBody != null && !(requestBody instanceof RetryableOutputStream)) {
        throw new HttpRetryException("Cannot retry streamed HTTP body",
            httpEngine.getResponseCode());
      }

      if (retry == Retry.DIFFERENT_CONNECTION) {
        httpEngine.automaticallyReleaseConnectionToPool();
      }

      httpEngine.release(false);

      httpEngine = newHttpEngine(retryMethod, rawRequestHeaders, httpEngine.getConnection(),
          (RetryableOutputStream) requestBody);
    }
  }

  /**
   * Sends a request and optionally reads a response. Returns true if the
   * request was successfully executed, and false if the request can be
   * retried. Throws an exception if the request failed permanently.
   */
  private boolean execute(boolean readResponse) throws IOException {
    try {
      httpEngine.sendRequest();
      if (readResponse) {
        httpEngine.readResponse();
      }
      return true;
    } catch (IOException e) {
      RouteSelector routeSelector = httpEngine.routeSelector;
      if (routeSelector != null && httpEngine.connection != null) {
        routeSelector.connectFailed(httpEngine.connection, e);
      }
      if (routeSelector == null && httpEngine.connection == null) {
        throw e; // If we failed before finding a route or a connection, give up.
      }

      // The connection failure isn't fatal if there's another route to attempt.
      OutputStream requestBody = httpEngine.getRequestBody();
      if ((routeSelector == null || routeSelector.hasNext()) && isRecoverable(e) && (requestBody
          == null || requestBody instanceof RetryableOutputStream)) {
        httpEngine.release(true);
        httpEngine =
            newHttpEngine(method, rawRequestHeaders, null, (RetryableOutputStream) requestBody);
        httpEngine.routeSelector = routeSelector; // Keep the same routeSelector.
        return false;
      }
      httpEngineFailure = e;
      throw e;
    }
  }

  private boolean isRecoverable(IOException e) {
    // If the problem was a CertificateException from the X509TrustManager,
    // do not retry, we didn't have an abrupt server initiated exception.
    boolean sslFailure =
        e instanceof SSLHandshakeException && e.getCause() instanceof CertificateException;
    boolean protocolFailure = e instanceof ProtocolException;
    return !sslFailure && !protocolFailure;
  }

  HttpEngine getHttpEngine() {
    return httpEngine;
  }

  enum Retry {
    NONE,
    SAME_CONNECTION,
    DIFFERENT_CONNECTION
  }

  /**
   * Returns the retry action to take for the current response headers. The
   * headers, proxy and target URL or this connection may be adjusted to
   * prepare for a follow up request.
   */
  private Retry processResponseHeaders() throws IOException {
    Proxy selectedProxy = httpEngine.connection != null
        ? httpEngine.connection.getProxy()
        : requestedProxy;
    switch (getResponseCode()) {
      case HTTP_PROXY_AUTH:
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        // fall-through
      case HTTP_UNAUTHORIZED:
        boolean credentialsFound = HttpAuthenticator.processAuthHeader(getResponseCode(),
            httpEngine.getResponseHeaders().getHeaders(), rawRequestHeaders, selectedProxy, url);
        return credentialsFound ? Retry.SAME_CONNECTION : Retry.NONE;

      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        if (!getInstanceFollowRedirects()) {
          return Retry.NONE;
        }
        if (++redirectionCount > MAX_REDIRECTS) {
          throw new ProtocolException("Too many redirects: " + redirectionCount);
        }
        String location = getHeaderField("Location");
        if (location == null) {
          return Retry.NONE;
        }
        URL previousUrl = url;
        url = new URL(previousUrl, location);
        if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) {
          return Retry.NONE; // Don't follow redirects to unsupported protocols.
        }
        boolean sameProtocol = previousUrl.getProtocol().equals(url.getProtocol());
        if (!sameProtocol && !followProtocolRedirects) {
          return Retry.NONE; // This client doesn't follow redirects across protocols.
        }
        boolean sameHost = previousUrl.getHost().equals(url.getHost());
        boolean samePort = getEffectivePort(previousUrl) == getEffectivePort(url);
        if (sameHost && samePort && sameProtocol) {
          return Retry.SAME_CONNECTION;
        } else {
          return Retry.DIFFERENT_CONNECTION;
        }

      default:
        return Retry.NONE;
    }
  }

  /** @see java.net.HttpURLConnection#setFixedLengthStreamingMode(int) */
  final int getFixedContentLength() {
    return fixedContentLength;
  }

  /** @see java.net.HttpURLConnection#setChunkedStreamingMode(int) */
  final int getChunkLength() {
    return chunkLength;
  }

  @Override public final boolean usingProxy() {
    return (requestedProxy != null && requestedProxy.type() != Proxy.Type.DIRECT);
  }

  @Override public String getResponseMessage() throws IOException {
    return getResponse().getResponseHeaders().getHeaders().getResponseMessage();
  }

  @Override public final int getResponseCode() throws IOException {
    return getResponse().getResponseCode();
  }

  @Override public final void setRequestProperty(String field, String newValue) {
    if (connected) {
      throw new IllegalStateException("Cannot set request property after connection is made");
    }
    if (field == null) {
      throw new NullPointerException("field == null");
    }
    rawRequestHeaders.set(field, newValue);
  }

  @Override public final void addRequestProperty(String field, String value) {
    if (connected) {
      throw new IllegalStateException("Cannot add request property after connection is made");
    }
    if (field == null) {
      throw new NullPointerException("field == null");
    }
    rawRequestHeaders.add(field, value);
  }
}
