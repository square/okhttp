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

import com.squareup.okhttp.Address;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.TunnelRequest;
import com.squareup.okhttp.internal.Dns;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import static com.squareup.okhttp.internal.Util.EMPTY_BYTE_ARRAY;
import static com.squareup.okhttp.internal.Util.getDefaultPort;
import static com.squareup.okhttp.internal.Util.getEffectivePort;

/**
 * Handles a single HTTP request/response pair. Each HTTP engine follows this
 * lifecycle:
 * <ol>
 * <li>It is created.
 * <li>The HTTP request message is sent with sendRequest(). Once the request
 * is sent it is an error to modify the request headers. After
 * sendRequest() has been called the request body can be written to if
 * it exists.
 * <li>The HTTP response message is read with readResponse(). After the
 * response has been read the response headers and body can be read.
 * All responses have a response body input stream, though in some
 * instances this stream is empty.
 * </ol>
 *
 * <p>The request and response may be served by the HTTP response cache, by the
 * network, or by both in the event of a conditional GET.
 *
 * <p>This class may hold a socket connection that needs to be released or
 * recycled. By default, this socket connection is held when the last byte of
 * the response is consumed. To release the connection when it is no longer
 * required, use {@link #automaticallyReleaseConnectionToPool()}.
 */
public class HttpEngine {
  private static final CacheResponse GATEWAY_TIMEOUT_RESPONSE = new CacheResponse() {
    @Override public Map<String, List<String>> getHeaders() throws IOException {
      Map<String, List<String>> result = new HashMap<String, List<String>>();
      result.put(null, Collections.singletonList("HTTP/1.1 504 Gateway Timeout"));
      return result;
    }
    @Override public InputStream getBody() throws IOException {
      return new ByteArrayInputStream(EMPTY_BYTE_ARRAY);
    }
  };
  public static final int HTTP_CONTINUE = 100;

  protected final Policy policy;
  protected final OkHttpClient client;

  protected final String method;

  private ResponseSource responseSource;

  protected Connection connection;
  protected RouteSelector routeSelector;
  private OutputStream requestBodyOut;

  private Transport transport;

  private InputStream responseTransferIn;
  private InputStream responseBodyIn;

  private CacheResponse cacheResponse;
  private CacheRequest cacheRequest;

  /** The time when the request headers were written, or -1 if they haven't been written yet. */
  long sentRequestMillis = -1;

  /** Whether the connection has been established. */
  boolean connected;

  /**
   * True if this client added an "Accept-Encoding: gzip" header field and is
   * therefore responsible for also decompressing the transfer stream.
   */
  private boolean transparentGzip;

  final URI uri;

  final RequestHeaders requestHeaders;

  /** Null until a response is received from the network or the cache. */
  ResponseHeaders responseHeaders;

  // The cache response currently being validated on a conditional get. Null
  // if the cached response doesn't exist or doesn't need validation. If the
  // conditional get succeeds, these will be used for the response headers and
  // body. If it fails, these be closed and set to null.
  private ResponseHeaders cachedResponseHeaders;
  private InputStream cachedResponseBody;

  /**
   * True if the socket connection should be released to the connection pool
   * when the response has been fully read.
   */
  private boolean automaticallyReleaseConnectionToPool;

  /** True if the socket connection is no longer needed by this engine. */
  private boolean connectionReleased;

  /**
   * @param requestHeaders the client's supplied request headers. This class
   *     creates a private copy that it can mutate.
   * @param connection the connection used for an intermediate response
   *     immediately prior to this request/response pair, such as a same-host
   *     redirect. This engine assumes ownership of the connection and must
   *     release it when it is unneeded.
   */
  public HttpEngine(OkHttpClient client, Policy policy, String method, RawHeaders requestHeaders,
      Connection connection, RetryableOutputStream requestBodyOut) throws IOException {
    this.client = client;
    this.policy = policy;
    this.method = method;
    this.connection = connection;
    this.requestBodyOut = requestBodyOut;

    try {
      uri = Platform.get().toUriLenient(policy.getURL());
    } catch (URISyntaxException e) {
      throw new IOException(e.getMessage());
    }

    this.requestHeaders = new RequestHeaders(uri, new RawHeaders(requestHeaders));
  }

  public URI getUri() {
    return uri;
  }

  /**
   * Figures out what the response source will be, and opens a socket to that
   * source if necessary. Prepares the request headers and gets ready to start
   * writing the request body if it exists.
   */
  public final void sendRequest() throws IOException {
    if (responseSource != null) {
      return;
    }

    prepareRawRequestHeaders();
    initResponseSource();
    OkResponseCache responseCache = client.getOkResponseCache();
    if (responseCache != null) {
      responseCache.trackResponse(responseSource);
    }

    // The raw response source may require the network, but the request
    // headers may forbid network use. In that case, dispose of the network
    // response and use a GATEWAY_TIMEOUT response instead, as specified
    // by http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.4.
    if (requestHeaders.isOnlyIfCached() && responseSource.requiresConnection()) {
      if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
        Util.closeQuietly(cachedResponseBody);
      }
      this.responseSource = ResponseSource.CACHE;
      this.cacheResponse = GATEWAY_TIMEOUT_RESPONSE;
      RawHeaders rawResponseHeaders = RawHeaders.fromMultimap(cacheResponse.getHeaders(), true);
      setResponse(new ResponseHeaders(uri, rawResponseHeaders), cacheResponse.getBody());
    }

    if (responseSource.requiresConnection()) {
      sendSocketRequest();
    } else if (connection != null) {
      client.getConnectionPool().recycle(connection);
      connection = null;
    }
  }

  /**
   * Initialize the source for this response. It may be corrected later if the
   * request headers forbids network use.
   */
  private void initResponseSource() throws IOException {
    responseSource = ResponseSource.NETWORK;
    if (!policy.getUseCaches()) return;

    OkResponseCache responseCache = client.getOkResponseCache();
    if (responseCache == null) return;

    CacheResponse candidate = responseCache.get(
        uri, method, requestHeaders.getHeaders().toMultimap(false));
    if (candidate == null) return;

    Map<String, List<String>> responseHeadersMap = candidate.getHeaders();
    cachedResponseBody = candidate.getBody();
    if (!acceptCacheResponseType(candidate)
        || responseHeadersMap == null
        || cachedResponseBody == null) {
      Util.closeQuietly(cachedResponseBody);
      return;
    }

    RawHeaders rawResponseHeaders = RawHeaders.fromMultimap(responseHeadersMap, true);
    cachedResponseHeaders = new ResponseHeaders(uri, rawResponseHeaders);
    long now = System.currentTimeMillis();
    this.responseSource = cachedResponseHeaders.chooseResponseSource(now, requestHeaders);
    if (responseSource == ResponseSource.CACHE) {
      this.cacheResponse = candidate;
      setResponse(cachedResponseHeaders, cachedResponseBody);
    } else if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
      this.cacheResponse = candidate;
    } else if (responseSource == ResponseSource.NETWORK) {
      Util.closeQuietly(cachedResponseBody);
    } else {
      throw new AssertionError();
    }
  }

  private void sendSocketRequest() throws IOException {
    if (connection == null) {
      connect();
    }

    if (transport != null) {
      throw new IllegalStateException();
    }

    transport = (Transport) connection.newTransport(this);

    if (hasRequestBody() && requestBodyOut == null) {
      // Create a request body if we don't have one already. We'll already
      // have one if we're retrying a failed POST.
      requestBodyOut = transport.createRequestBody();
    }
  }

  /** Connect to the origin server either directly or via a proxy. */
  protected final void connect() throws IOException {
    if (connection != null) {
      return;
    }
    if (routeSelector == null) {
      String uriHost = uri.getHost();
      if (uriHost == null) {
        throw new UnknownHostException(uri.toString());
      }
      SSLSocketFactory sslSocketFactory = null;
      HostnameVerifier hostnameVerifier = null;
      if (uri.getScheme().equalsIgnoreCase("https")) {
        sslSocketFactory = client.getSslSocketFactory();
        hostnameVerifier = client.getHostnameVerifier();
      }
      Address address = new Address(uriHost, getEffectivePort(uri), sslSocketFactory,
          hostnameVerifier, client.getAuthenticator(), client.getProxy(), client.getTransports());
      routeSelector = new RouteSelector(address, uri, client.getProxySelector(),
          client.getConnectionPool(), Dns.DEFAULT, client.getRoutesDatabase());
    }
    connection = routeSelector.next(method);
    if (!connection.isConnected()) {
      connection.connect(client.getConnectTimeout(), client.getReadTimeout(), getTunnelConfig());
      client.getConnectionPool().maybeShare(connection);
      client.getRoutesDatabase().connected(connection.getRoute());
    } else if (!connection.isSpdy()) {
        connection.updateReadTimeout(client.getReadTimeout());
    }
    connected(connection);
    if (connection.getRoute().getProxy() != client.getProxy()) {
      // Update the request line if the proxy changed; it may need a host name.
      requestHeaders.getHeaders().setRequestLine(getRequestLine());
    }
  }

  /**
   * Called after a socket connection has been created or retrieved from the
   * pool. Subclasses use this hook to get a reference to the TLS data.
   */
  protected void connected(Connection connection) {
    policy.setSelectedProxy(connection.getRoute().getProxy());
    connected = true;
  }

  /**
   * Called immediately before the transport transmits HTTP request headers.
   * This is used to observe the sent time should the request be cached.
   */
  public void writingRequestHeaders() {
    if (sentRequestMillis != -1) {
      throw new IllegalStateException();
    }
    sentRequestMillis = System.currentTimeMillis();
  }

  /**
   * @param body the response body, or null if it doesn't exist or isn't
   * available.
   */
  private void setResponse(ResponseHeaders headers, InputStream body) throws IOException {
    if (this.responseBodyIn != null) {
      throw new IllegalStateException();
    }
    this.responseHeaders = headers;
    if (body != null) {
      initContentStream(body);
    }
  }

  boolean hasRequestBody() {
    return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
  }

  /** Returns the request body or null if this request doesn't have a body. */
  public final OutputStream getRequestBody() {
    if (responseSource == null) {
      throw new IllegalStateException();
    }
    return requestBodyOut;
  }

  public final boolean hasResponse() {
    return responseHeaders != null;
  }

  public final RequestHeaders getRequestHeaders() {
    return requestHeaders;
  }

  public final ResponseHeaders getResponseHeaders() {
    if (responseHeaders == null) {
      throw new IllegalStateException();
    }
    return responseHeaders;
  }

  public final int getResponseCode() {
    if (responseHeaders == null) {
      throw new IllegalStateException();
    }
    return responseHeaders.getHeaders().getResponseCode();
  }

  public final InputStream getResponseBody() {
    if (responseHeaders == null) {
      throw new IllegalStateException();
    }
    return responseBodyIn;
  }

  public final CacheResponse getCacheResponse() {
    return cacheResponse;
  }

  public final Connection getConnection() {
    return connection;
  }

  /**
   * Returns true if {@code cacheResponse} is of the right type. This
   * condition is necessary but not sufficient for the cached response to
   * be used.
   */
  protected boolean acceptCacheResponseType(CacheResponse cacheResponse) {
    return true;
  }

  private void maybeCache() throws IOException {
    // Are we caching at all?
    if (!policy.getUseCaches()) return;
    OkResponseCache responseCache = client.getOkResponseCache();
    if (responseCache == null) return;

    HttpURLConnection connectionToCache = policy.getHttpConnectionToCache();

    // Should we cache this response for this request?
    if (!responseHeaders.isCacheable(requestHeaders)) {
      responseCache.maybeRemove(connectionToCache.getRequestMethod(), uri);
      return;
    }

    // Offer this request to the cache.
    cacheRequest = responseCache.put(uri, connectionToCache);
  }

  /**
   * Cause the socket connection to be released to the connection pool when
   * it is no longer needed. If it is already unneeded, it will be pooled
   * immediately. Otherwise the connection is held so that redirects can be
   * handled by the same connection.
   */
  public final void automaticallyReleaseConnectionToPool() {
    automaticallyReleaseConnectionToPool = true;
    if (connection != null && connectionReleased) {
      client.getConnectionPool().recycle(connection);
      connection = null;
    }
  }

  /**
   * Releases this engine so that its resources may be either reused or
   * closed. Also call {@link #automaticallyReleaseConnectionToPool} unless
   * the connection will be used to follow a redirect.
   */
  public final void release(boolean streamCanceled) {
    // If the response body comes from the cache, close it.
    if (responseBodyIn == cachedResponseBody) {
      Util.closeQuietly(responseBodyIn);
    }

    if (!connectionReleased && connection != null) {
      connectionReleased = true;

      if (transport == null
          || !transport.makeReusable(streamCanceled, requestBodyOut, responseTransferIn)) {
        Util.closeQuietly(connection);
        connection = null;
      } else if (automaticallyReleaseConnectionToPool) {
        client.getConnectionPool().recycle(connection);
        connection = null;
      }
    }
  }

  private void initContentStream(InputStream transferStream) throws IOException {
    responseTransferIn = transferStream;
    if (transparentGzip && responseHeaders.isContentEncodingGzip()) {
      // If the response was transparently gzipped, remove the gzip header field
      // so clients don't double decompress. http://b/3009828
      //
      // Also remove the Content-Length in this case because it contains the
      // length 528 of the gzipped response. This isn't terribly useful and is
      // dangerous because 529 clients can query the content length, but not
      // the content encoding.
      responseHeaders.stripContentEncoding();
      responseHeaders.stripContentLength();
      responseBodyIn = new GZIPInputStream(transferStream);
    } else {
      responseBodyIn = transferStream;
    }
  }

  /**
   * Returns true if the response must have a (possibly 0-length) body.
   * See RFC 2616 section 4.3.
   */
  public final boolean hasResponseBody() {
    int responseCode = responseHeaders.getHeaders().getResponseCode();

    // HEAD requests never yield a body regardless of the response headers.
    if (method.equals("HEAD")) {
      return false;
    }

    if ((responseCode < HTTP_CONTINUE || responseCode >= 200)
        && responseCode != HttpURLConnectionImpl.HTTP_NO_CONTENT
        && responseCode != HttpURLConnectionImpl.HTTP_NOT_MODIFIED) {
      return true;
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the
    // response code, the response is malformed. For best compatibility, we
    // honor the headers.
    if (responseHeaders.getContentLength() != -1 || responseHeaders.isChunked()) {
      return true;
    }

    return false;
  }

  /**
   * Populates requestHeaders with defaults and cookies.
   *
   * <p>This client doesn't specify a default {@code Accept} header because it
   * doesn't know what content types the application is interested in.
   */
  private void prepareRawRequestHeaders() throws IOException {
    requestHeaders.getHeaders().setRequestLine(getRequestLine());

    if (requestHeaders.getUserAgent() == null) {
      requestHeaders.setUserAgent(getDefaultUserAgent());
    }

    if (requestHeaders.getHost() == null) {
      requestHeaders.setHost(getOriginAddress(policy.getURL()));
    }

    if ((connection == null || connection.getHttpMinorVersion() != 0)
        && requestHeaders.getConnection() == null) {
      requestHeaders.setConnection("Keep-Alive");
    }

    if (requestHeaders.getAcceptEncoding() == null) {
      transparentGzip = true;
      requestHeaders.setAcceptEncoding("gzip");
    }

    if (hasRequestBody() && requestHeaders.getContentType() == null) {
      requestHeaders.setContentType("application/x-www-form-urlencoded");
    }

    long ifModifiedSince = policy.getIfModifiedSince();
    if (ifModifiedSince != 0) {
      requestHeaders.setIfModifiedSince(new Date(ifModifiedSince));
    }

    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      requestHeaders.addCookies(
          cookieHandler.get(uri, requestHeaders.getHeaders().toMultimap(false)));
    }
  }

  /**
   * Returns the request status line, like "GET / HTTP/1.1". This is exposed
   * to the application by {@link HttpURLConnectionImpl#getHeaderFields}, so
   * it needs to be set even if the transport is SPDY.
   */
  String getRequestLine() {
    String protocol =
        (connection == null || connection.getHttpMinorVersion() != 0) ? "HTTP/1.1" : "HTTP/1.0";
    return method + " " + requestString() + " " + protocol;
  }

  private String requestString() {
    URL url = policy.getURL();
    if (includeAuthorityInRequestLine()) {
      return url.toString();
    } else {
      return requestPath(url);
    }
  }

  /**
   * Returns the path to request, like the '/' in 'GET / HTTP/1.1'. Never
   * empty, even if the request URL is. Includes the query component if it
   * exists.
   */
  public static String requestPath(URL url) {
    String fileOnly = url.getFile();
    if (fileOnly == null) {
      return "/";
    } else if (!fileOnly.startsWith("/")) {
      return "/" + fileOnly;
    } else {
      return fileOnly;
    }
  }

  /**
   * Returns true if the request line should contain the full URL with host
   * and port (like "GET http://android.com/foo HTTP/1.1") or only the path
   * (like "GET /foo HTTP/1.1").
   *
   * <p>This is non-final because for HTTPS it's never necessary to supply the
   * full URL, even if a proxy is in use.
   */
  protected boolean includeAuthorityInRequestLine() {
    return connection == null
        ? policy.usingProxy() // A proxy was requested.
        : connection.getRoute().getProxy().type() == Proxy.Type.HTTP; // A proxy was selected.
  }

  public static String getDefaultUserAgent() {
    String agent = System.getProperty("http.agent");
    return agent != null ? agent : ("Java" + System.getProperty("java.version"));
  }

  public static String getOriginAddress(URL url) {
    int port = url.getPort();
    String result = url.getHost();
    if (port > 0 && port != getDefaultPort(url.getProtocol())) {
      result = result + ":" + port;
    }
    return result;
  }

  /**
   * Flushes the remaining request header and body, parses the HTTP response
   * headers and starts reading the HTTP response body if it exists.
   */
  public final void readResponse() throws IOException {
    if (hasResponse()) {
      responseHeaders.setResponseSource(responseSource);
      return;
    }

    if (responseSource == null) {
      throw new IllegalStateException("readResponse() without sendRequest()");
    }

    if (!responseSource.requiresConnection()) {
      return;
    }

    if (sentRequestMillis == -1) {
      if (requestBodyOut instanceof RetryableOutputStream) {
        int contentLength = ((RetryableOutputStream) requestBodyOut).contentLength();
        requestHeaders.setContentLength(contentLength);
      }
      transport.writeRequestHeaders();
    }

    if (requestBodyOut != null) {
      requestBodyOut.close();
      if (requestBodyOut instanceof RetryableOutputStream) {
        transport.writeRequestBody((RetryableOutputStream) requestBodyOut);
      }
    }

    transport.flushRequest();

    responseHeaders = transport.readResponseHeaders();
    responseHeaders.setLocalTimestamps(sentRequestMillis, System.currentTimeMillis());
    responseHeaders.setResponseSource(responseSource);

    if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
      if (cachedResponseHeaders.validate(responseHeaders)) {
        release(false);
        ResponseHeaders combinedHeaders = cachedResponseHeaders.combine(responseHeaders);
        this.responseHeaders = combinedHeaders;

        // Update the cache after applying the combined headers but before initializing the content
        // stream, otherwise the Content-Encoding header (if present) will be stripped from the
        // combined headers and not end up in the cache file if transparent gzip compression is
        // turned on.
        OkResponseCache responseCache = client.getOkResponseCache();
        responseCache.trackConditionalCacheHit();
        responseCache.update(cacheResponse, policy.getHttpConnectionToCache());

        initContentStream(cachedResponseBody);
        return;
      } else {
        Util.closeQuietly(cachedResponseBody);
      }
    }

    if (hasResponseBody()) {
      maybeCache(); // reentrant. this calls into user code which may call back into this!
    }

    initContentStream(transport.getTransferStream(cacheRequest));
  }

  protected TunnelRequest getTunnelConfig() {
    return null;
  }

  public void receiveHeaders(RawHeaders headers) throws IOException {
    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      cookieHandler.put(uri, headers.toMultimap(true));
    }
  }
}
