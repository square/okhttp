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
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.TunnelRequest;
import com.squareup.okhttp.internal.Dns;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CookieHandler;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import static com.squareup.okhttp.internal.Util.EMPTY_INPUT_STREAM;
import static com.squareup.okhttp.internal.Util.getDefaultPort;
import static com.squareup.okhttp.internal.Util.getEffectivePort;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_CONTINUE;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;

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
  private static final Response.Body EMPTY_BODY = new Response.Body() {
    @Override public boolean ready() throws IOException {
      return true;
    }
    @Override public MediaType contentType() {
      return null;
    }
    @Override public long contentLength() {
      return 0;
    }
    @Override public InputStream byteStream() {
      return EMPTY_INPUT_STREAM;
    }
  };

  final Policy policy;
  final OkHttpClient client;

  Connection connection;
  RouteSelector routeSelector;

  private Transport transport;

  /** The time when the request headers were written, or -1 if they haven't been written yet. */
  long sentRequestMillis = -1;

  /**
   * True if this client added an "Accept-Encoding: gzip" header field and is
   * therefore responsible for also decompressing the transfer stream.
   */
  private boolean transparentGzip;

  private Request request;
  private OutputStream requestBodyOut;

  private ResponseSource responseSource;

  /** Null until a response is received from the network or the cache. */
  private Response response;
  private InputStream responseTransferIn;
  private InputStream responseBodyIn;

  /**
   * The cache response currently being validated on a conditional get. Null
   * if the cached response doesn't exist or doesn't need validation. If the
   * conditional get succeeds, these will be used for the response. If it fails,
   * it will be set to null.
   */
  private Response validatingResponse;

  /** The cache request currently being populated from a network response. */
  private CacheRequest cacheRequest;

  /**
   * True if the socket connection should be released to the connection pool
   * when the response has been fully read.
   */
  private boolean automaticallyReleaseConnectionToPool;

  /** True if the socket connection is no longer needed by this engine. */
  private boolean connectionReleased;

  /**
   * @param request the HTTP request without a body. The body must be
   *     written via the engine's request body stream.
   * @param connection the connection used for an intermediate response
   *     immediately prior to this request/response pair, such as a same-host
   *     redirect. This engine assumes ownership of the connection and must
   *     release it when it is unneeded.
   */
  public HttpEngine(OkHttpClient client, Policy policy, Request request,
      Connection connection, RetryableOutputStream requestBodyOut) throws IOException {
    this.client = client;
    this.policy = policy;
    this.request = request;
    this.connection = connection;
    this.requestBodyOut = requestBodyOut;
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
    // response and use a gateway timeout response instead, as specified
    // by http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.4.
    if (request.isOnlyIfCached() && responseSource.requiresConnection()) {
      if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
        Util.closeQuietly(validatingResponse.body());
      }
      this.responseSource = ResponseSource.CACHE;

      this.validatingResponse = new Response.Builder(request)
          .statusLine(new StatusLine("HTTP/1.1 504 Gateway Timeout"))
          .body(EMPTY_BODY)
          .build();
      promoteValidatingResponse();
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

    OkResponseCache responseCache = client.getOkResponseCache();
    if (responseCache == null) return;

    Response candidate = responseCache.get(request);
    if (candidate == null) return;

    // Drop the cached response if it's missing a required handshake.
    if (request.isHttps() && candidate.handshake() == null) {
      Util.closeQuietly(candidate.body());
      return;
    }

    long now = System.currentTimeMillis();
    ResponseStrategy responseStrategy = ResponseStrategy.get(now, candidate, request);
    this.responseSource = responseStrategy.source;
    this.request = responseStrategy.request;

    if (responseSource == ResponseSource.CACHE) {
      this.validatingResponse = responseStrategy.response;
      promoteValidatingResponse();
    } else if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
      this.validatingResponse = responseStrategy.response;
    } else if (responseSource == ResponseSource.NETWORK) {
      Util.closeQuietly(candidate.body());
    }
  }

  private Response cacheableResponse() {
    // Use an unreadable response body when offering the response to the cache.
    // The cache isn't allowed to consume the response body bytes!
    return response.newBuilder()
        .body(new UnreadableResponseBody(response.getContentType(),
            response.getContentLength()))
        .build();
  }

  private void sendSocketRequest() throws IOException {
    if (connection == null) {
      connect();
    }

    if (transport != null) {
      throw new IllegalStateException();
    }

    transport = (Transport) connection.newTransport(this);
    request = transport.prepareRequest(request);

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
      String uriHost = request.url().getHost();
      if (uriHost == null || uriHost.length() == 0) {
        throw new UnknownHostException(request.url().toString());
      }
      SSLSocketFactory sslSocketFactory = null;
      HostnameVerifier hostnameVerifier = null;
      if (request.isHttps()) {
        sslSocketFactory = client.getSslSocketFactory();
        hostnameVerifier = client.getHostnameVerifier();
      }
      Address address = new Address(uriHost, getEffectivePort(request.url()), sslSocketFactory,
          hostnameVerifier, client.getAuthenticator(), client.getProxy(), client.getTransports());
      routeSelector = new RouteSelector(address, request.uri(), client.getProxySelector(),
          client.getConnectionPool(), Dns.DEFAULT, client.getRoutesDatabase());
    }
    connection = routeSelector.next(request.method());
    if (!connection.isConnected()) {
      connection.connect(client.getConnectTimeout(), client.getReadTimeout(), getTunnelConfig());
      client.getConnectionPool().maybeShare(connection);
      client.getRoutesDatabase().connected(connection.getRoute());
    } else {
      connection.updateReadTimeout(client.getReadTimeout());
    }

    // Update the policy to tell 'em which proxy we ended up going with.
    policy.setSelectedProxy(connection.getRoute().getProxy());
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

  private void promoteValidatingResponse() throws IOException {
    if (this.responseBodyIn != null) throw new IllegalStateException();

    this.response = validatingResponse;
    if (validatingResponse.body() != null) {
      initContentStream(validatingResponse.body().byteStream());
    }
  }

  boolean hasRequestBody() {
    String method = request.method();
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
    return response != null;
  }

  public final Request getRequest() {
    return request;
  }

  /** Returns the engine's response. */
  // TODO: the returned body will always be null.
  public final Response getResponse() {
    if (response == null) throw new IllegalStateException();
    return response;
  }

  public final InputStream getResponseBody() {
    if (response == null) throw new IllegalStateException();
    return responseBodyIn;
  }

  public final Connection getConnection() {
    return connection;
  }

  private void maybeCache() throws IOException {
    OkResponseCache responseCache = client.getOkResponseCache();
    if (responseCache == null) return;

    // Should we cache this response for this request?
    if (!ResponseStrategy.isCacheable(response, request)) {
      responseCache.maybeRemove(request);
      return;
    }

    // Offer this request to the cache.
    cacheRequest = responseCache.put(cacheableResponse());
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
    if (validatingResponse != null
        && validatingResponse.body() != null
        && responseBodyIn == validatingResponse.body().byteStream()) {
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
    if (transparentGzip && response.isContentEncodingGzip()) {
      // If the response was transparently gzipped, remove the gzip header field
      // so clients don't double decompress. http://b/3009828
      //
      // Also remove the Content-Length in this case because it contains the
      // length of the gzipped response. This isn't terribly useful and is
      // dangerous because clients can query the content length, but not the
      // content encoding.
      response = response.newBuilder()
          .stripContentEncoding()
          .stripContentLength()
          .build();
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
    // HEAD requests never yield a body regardless of the response headers.
    if (request.method().equals("HEAD")) {
      return false;
    }

    int responseCode = response.code();
    if ((responseCode < HTTP_CONTINUE || responseCode >= 200)
        && responseCode != HTTP_NO_CONTENT
        && responseCode != HTTP_NOT_MODIFIED) {
      return true;
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the
    // response code, the response is malformed. For best compatibility, we
    // honor the headers.
    if (response.getContentLength() != -1 || response.isChunked()) {
      return true;
    }

    return false;
  }

  /**
   * Populates request with defaults and cookies.
   *
   * <p>This client doesn't specify a default {@code Accept} header because it
   * doesn't know what content types the application is interested in.
   */
  private void prepareRawRequestHeaders() throws IOException {
    Request.Builder result = request.newBuilder();

    if (request.getUserAgent() == null) {
      result.setUserAgent(getDefaultUserAgent());
    }

    if (request.getHost() == null) {
      result.setHost(getHostHeader(request.url()));
    }

    if ((connection == null || connection.getHttpMinorVersion() != 0)
        && request.getConnection() == null) {
      result.setConnection("Keep-Alive");
    }

    if (request.getAcceptEncoding() == null) {
      transparentGzip = true;
      result.setAcceptEncoding("gzip");
    }

    if (hasRequestBody() && request.getContentType() == null) {
      result.setContentType("application/x-www-form-urlencoded");
    }

    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      result.addCookies(cookieHandler.get(request.uri(), request.getHeaders().toMultimap(null)));
    }

    request = result.build();
  }

  public static String getDefaultUserAgent() {
    String agent = System.getProperty("http.agent");
    return agent != null ? agent : ("Java" + System.getProperty("java.version"));
  }

  public static String getHostHeader(URL url) {
    return getEffectivePort(url) != getDefaultPort(url.getProtocol())
        ? url.getHost() + ":" + url.getPort()
        : url.getHost();
  }

  /**
   * Flushes the remaining request header and body, parses the HTTP response
   * headers and starts reading the HTTP response body if it exists.
   */
  public final void readResponse() throws IOException {
    if (hasResponse()) {
      // TODO: this doesn't make much sense.
      response = response.newBuilder().setResponseSource(responseSource).build();
      return;
    }

    if (responseSource == null) {
      throw new IllegalStateException("readResponse() without sendRequest()");
    }

    if (!responseSource.requiresConnection()) {
      return;
    }

    if (sentRequestMillis == -1) {
      if (request.getContentLength() == -1
          && requestBodyOut instanceof RetryableOutputStream) {
        // We might not learn the Content-Length until the request body has been buffered.
        int contentLength = ((RetryableOutputStream) requestBodyOut).contentLength();
        request = request.newBuilder().setContentLength(contentLength).build();
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

    response = transport.readResponseHeaders()
        .newBuilder()
        .setLocalTimestamps(sentRequestMillis, System.currentTimeMillis())
        .setResponseSource(responseSource)
        .build();

    if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
      if (validatingResponse.validate(response)) {
        release(false);
        response = validatingResponse.combine(response);

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        OkResponseCache responseCache = client.getOkResponseCache();
        responseCache.trackConditionalCacheHit();
        responseCache.update(validatingResponse, cacheableResponse());

        if (validatingResponse.body() != null) {
          initContentStream(validatingResponse.body().byteStream());
        }
        return;
      } else {
        Util.closeQuietly(validatingResponse.body());
      }
    }

    if (hasResponseBody()) {
      maybeCache();
    }

    initContentStream(transport.getTransferStream(cacheRequest));
  }

  private TunnelRequest getTunnelConfig() {
    if (!request.isHttps()) return null;

    String userAgent = request.getUserAgent();
    if (userAgent == null) userAgent = getDefaultUserAgent();

    URL url = request.url();
    return new TunnelRequest(url.getHost(), getEffectivePort(url), userAgent,
        request.getProxyAuthorization());
  }

  public void receiveHeaders(Headers headers) throws IOException {
    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      cookieHandler.put(request.uri(), headers.toMultimap(null));
    }
  }

  static class UnreadableResponseBody extends Response.Body {
    private final String contentType;
    private final long contentLength;

    public UnreadableResponseBody(String contentType, long contentLength) {
      this.contentType = contentType;
      this.contentLength = contentLength;
    }

    @Override public boolean ready() throws IOException {
      throw new IllegalStateException("It is an error to read this response body at this time.");
    }

    @Override public MediaType contentType() {
      return contentType != null ? MediaType.parse(contentType) : null;
    }

    @Override public long contentLength() {
      return contentLength;
    }

    @Override public InputStream byteStream() {
      throw new IllegalStateException("It is an error to read this response body at this time.");
    }
  }
}
