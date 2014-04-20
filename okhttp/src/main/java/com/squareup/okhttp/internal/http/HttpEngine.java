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
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.internal.Dns;
import java.io.IOException;
import java.io.InputStream;
import java.net.CacheRequest;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static com.squareup.okhttp.internal.Util.closeQuietly;
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
 */
public class HttpEngine {
  final OkHttpClient client;

  private Connection connection;
  private RouteSelector routeSelector;
  private Route route;

  private Transport transport;

  /** The time when the request headers were written, or -1 if they haven't been written yet. */
  long sentRequestMillis = -1;

  /**
   * True if this client added an "Accept-Encoding: gzip" header field and is
   * therefore responsible for also decompressing the transfer stream.
   */
  private boolean transparentGzip;

  /**
   * True if the request body must be completely buffered before transmission;
   * false if it can be streamed. Buffering has two advantages: we don't need
   * the content-length in advance and we can retransmit if necessary. The
   * upside of streaming is that we can save memory.
   */
  public final boolean bufferRequestBody;

  private Request originalRequest;
  private Request request;
  private Sink requestBodyOut;
  private BufferedSink bufferedRequestBody;

  private ResponseSource responseSource;

  /** Null until a response is received from the network or the cache. */
  private Response response;
  private Source responseTransferSource;
  private BufferedSource responseBody;
  private InputStream responseBodyBytes;

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
   * @param request the HTTP request without a body. The body must be
   *     written via the engine's request body stream.
   * @param connection the connection used for an intermediate response
   *     immediately prior to this request/response pair, such as a same-host
   *     redirect. This engine assumes ownership of the connection and must
   *     release it when it is unneeded.
   * @param routeSelector the route selector used for a failed attempt
   *     immediately preceding this attempt, or null if this request doesn't
   *     recover from a failure.
   */
  public HttpEngine(OkHttpClient client, Request request, boolean bufferRequestBody,
      Connection connection, RouteSelector routeSelector, RetryableSink requestBodyOut) {
    this.client = client;
    this.originalRequest = request;
    this.request = request;
    this.bufferRequestBody = bufferRequestBody;
    this.connection = connection;
    this.routeSelector = routeSelector;
    this.requestBodyOut = requestBodyOut;

    if (connection != null) {
      connection.setOwner(this);
      this.route = connection.getRoute();
    } else {
      this.route = null;
    }
  }

  /**
   * Figures out what the response source will be, and opens a socket to that
   * source if necessary. Prepares the request headers and gets ready to start
   * writing the request body if it exists.
   */
  public final void sendRequest() throws IOException {
    if (responseSource != null) return; // Already sent.
    if (transport != null) throw new IllegalStateException();

    prepareRawRequestHeaders();
    OkResponseCache responseCache = client.getOkResponseCache();

    Response cacheResponse = responseCache != null
        ? responseCache.get(request)
        : null;
    long now = System.currentTimeMillis();
    CacheStrategy cacheStrategy = new CacheStrategy.Factory(now, request, cacheResponse).get();
    responseSource = cacheStrategy.source;
    request = cacheStrategy.request;

    if (responseCache != null) {
      responseCache.trackResponse(responseSource);
    }

    if (responseSource != ResponseSource.NETWORK) {
      validatingResponse = cacheStrategy.response;
    }

    if (cacheResponse != null && !responseSource.usesCache()) {
      closeQuietly(cacheResponse.body()); // We don't need this cached response. Close it.
    }

    if (responseSource.requiresConnection()) {
      // Open a connection unless we inherited one from a redirect.
      if (connection == null) {
        connect();
      }

      // Blow up if we aren't the current owner of the connection.
      if (connection.getOwner() != this && !connection.isSpdy()) throw new AssertionError();

      transport = (Transport) connection.newTransport(this);

      // Create a request body if we don't have one already. We'll already have
      // one if we're retrying a failed POST.
      if (hasRequestBody() && requestBodyOut == null) {
        requestBodyOut = transport.createRequestBody(request);
      }

    } else {
      // We're using a cached response. Recycle a connection we may have inherited from a redirect.
      if (connection != null) {
        client.getConnectionPool().recycle(connection);
        connection = null;
      }

      // No need for the network! Promote the cached response immediately.
      this.response = validatingResponse;
      if (validatingResponse.body() != null) {
        initContentStream(validatingResponse.body().source());
      }
    }
  }

  private Response cacheableResponse() {
    // Use an unreadable response body when offering the response to the cache.
    // The cache isn't allowed to consume the response body bytes!
    return response.newBuilder().body(null).build();
  }

  /** Connect to the origin server either directly or via a proxy. */
  private void connect() throws IOException {
    if (connection != null) throw new IllegalStateException();

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
      Address address = new Address(uriHost, getEffectivePort(request.url()),
          client.getSocketFactory(), sslSocketFactory, hostnameVerifier, client.getAuthenticator(),
          client.getProxy(), client.getProtocols());
      routeSelector = new RouteSelector(address, request.uri(), client.getProxySelector(),
          client.getConnectionPool(), Dns.DEFAULT, client.getRoutesDatabase());
    }

    connection = routeSelector.next(request.method());
    connection.setOwner(this);

    if (!connection.isConnected()) {
      connection.connect(client.getConnectTimeout(), client.getReadTimeout(),
          client.getWriteTimeout(), tunnelRequest(connection, request));
      if (connection.isSpdy()) client.getConnectionPool().share(connection);
      client.getRoutesDatabase().connected(connection.getRoute());
    }
    connection.setTimeouts(client.getReadTimeout(), client.getWriteTimeout());
    route = connection.getRoute();
  }

  /**
   * Called immediately before the transport transmits HTTP request headers.
   * This is used to observe the sent time should the request be cached.
   */
  public void writingRequestHeaders() {
    if (sentRequestMillis != -1) throw new IllegalStateException();
    sentRequestMillis = System.currentTimeMillis();
  }

  boolean hasRequestBody() {
    return HttpMethod.hasRequestBody(request.method());
  }

  /** Returns the request body or null if this request doesn't have a body. */
  public final Sink getRequestBody() {
    if (responseSource == null) throw new IllegalStateException();
    return requestBodyOut;
  }

  public final BufferedSink getBufferedRequestBody() {
    BufferedSink result = bufferedRequestBody;
    if (result != null) return result;
    Sink requestBody = getRequestBody();
    return requestBody != null
        ? (bufferedRequestBody = Okio.buffer(requestBody))
        : null;
  }

  public final boolean hasResponse() {
    return response != null;
  }

  public final ResponseSource responseSource() {
    return responseSource;
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

  public final BufferedSource getResponseBody() {
    if (response == null) throw new IllegalStateException();
    return responseBody;
  }

  public final InputStream getResponseBodyBytes() {
    InputStream result = responseBodyBytes;
    return result != null
        ? result
        : (responseBodyBytes = Okio.buffer(getResponseBody()).inputStream());
  }

  public final Connection getConnection() {
    return connection;
  }

  /**
   * Report and attempt to recover from {@code e}. Returns a new HTTP engine
   * that should be used for the retry if {@code e} is recoverable, or null if
   * the failure is permanent.
   */
  public HttpEngine recover(IOException e) {
    if (routeSelector != null && connection != null) {
      routeSelector.connectFailed(connection, e);
    }

    boolean canRetryRequestBody = requestBodyOut == null || requestBodyOut instanceof RetryableSink;
    if (routeSelector == null && connection == null // No connection.
        || routeSelector != null && !routeSelector.hasNext() // No more routes to attempt.
        || !isRecoverable(e)
        || !canRetryRequestBody) {
      return null;
    }

    Connection connection = close();

    // For failure recovery, use the same route selector with a new connection.
    return new HttpEngine(client, originalRequest, bufferRequestBody, connection, routeSelector,
        (RetryableSink) requestBodyOut);
  }

  private boolean isRecoverable(IOException e) {
    // If the problem was a CertificateException from the X509TrustManager,
    // do not retry, we didn't have an abrupt server-initiated exception.
    boolean sslFailure =
        e instanceof SSLHandshakeException && e.getCause() instanceof CertificateException;
    boolean protocolFailure = e instanceof ProtocolException;
    return !sslFailure && !protocolFailure;
  }

  /**
   * Returns the route used to retrieve the response. Null if we haven't
   * connected yet, or if no connection was necessary.
   */
  public Route getRoute() {
    return route;
  }

  private void maybeCache() throws IOException {
    OkResponseCache responseCache = client.getOkResponseCache();
    if (responseCache == null) return;

    // Should we cache this response for this request?
    if (!CacheStrategy.isCacheable(response, request)) {
      if (HttpMethod.invalidatesCache(request.method())) {
        try {
          responseCache.remove(request);
        } catch (IOException ignored) {
          // The cache cannot be written.
        }
      }
      return;
    }

    // Offer this request to the cache.
    cacheRequest = responseCache.put(cacheableResponse());
  }

  /**
   * Configure the socket connection to be either pooled or closed when it is
   * either exhausted or closed. If it is unneeded when this is called, it will
   * be released immediately.
   */
  public final void releaseConnection() throws IOException {
    if (transport != null && connection != null) {
      transport.releaseConnectionOnIdle();
    }
    connection = null;
  }

  /**
   * Immediately closes the socket connection if it's currently held by this
   * engine. Use this to interrupt an in-flight request from any thread. It's
   * the caller's responsibility to close the request body and response body
   * streams; otherwise resources may be leaked.
   */
  public final void disconnect() throws IOException {
    if (transport != null) {
      transport.disconnect(this);
    }
  }

  /**
   * Release any resources held by this engine. If a connection is still held by
   * this engine, it is returned.
   */
  public final Connection close() {
    if (bufferedRequestBody != null) {
      // This also closes the wrapped requestBodyOut.
      closeQuietly(bufferedRequestBody);
    } else if (requestBodyOut != null) {
      closeQuietly(requestBodyOut);
    }

    // If this engine never achieved a response body, its connection cannot be reused.
    if (responseBody == null) {
      closeQuietly(connection);
      connection = null;
      return null;
    }

    // Close the response body. This will recycle the connection if it is eligible.
    closeQuietly(responseBody);

    // Clear the buffer held by the response body input stream adapter.
    closeQuietly(responseBodyBytes);

    // Close the connection if it cannot be reused.
    if (transport != null && !transport.canReuseConnection()) {
      closeQuietly(connection);
      connection = null;
      return null;
    }

    // Prevent this engine from disconnecting a connection it no longer owns.
    if (connection != null && !connection.clearOwner()) {
      connection = null;
    }

    Connection result = connection;
    connection = null;
    return result;
  }

  /**
   * Initialize the response content stream from the response transfer source.
   * These two sources are the same unless we're doing transparent gzip, in
   * which case the content source is decompressed.
   *
   * <p>Whenever we do transparent gzip we also strip the corresponding headers.
   * We strip the Content-Encoding header to prevent the application from
   * attempting to double decompress. We strip the Content-Length header because
   * it is the length of the compressed content, but the application is only
   * interested in the length of the uncompressed content.
   *
   * <p>This method should only be used for non-empty response bodies. Response
   * codes like "304 Not Modified" can include "Content-Encoding: gzip" without
   * a response body and we will crash if we attempt to decompress the zero-byte
   * source.
   */
  private void initContentStream(Source transferSource) throws IOException {
    responseTransferSource = transferSource;
    if (transparentGzip && "gzip".equalsIgnoreCase(response.header("Content-Encoding"))) {
      response = response.newBuilder()
          .removeHeader("Content-Encoding")
          .removeHeader("Content-Length")
          .build();
      responseBody = Okio.buffer(new GzipSource(transferSource));
    } else {
      responseBody = Okio.buffer(transferSource);
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
    if (OkHeaders.contentLength(response) != -1
        || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
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

    if (request.header("User-Agent") == null) {
      result.header("User-Agent", getDefaultUserAgent());
    }

    if (request.header("Host") == null) {
      result.header("Host", hostHeader(request.url()));
    }

    if ((connection == null || connection.getProtocol() != Protocol.HTTP_1_0)
        && request.header("Connection") == null) {
      result.header("Connection", "Keep-Alive");
    }

    if (request.header("Accept-Encoding") == null) {
      transparentGzip = true;
      result.header("Accept-Encoding", "gzip");
    }

    if (hasRequestBody() && request.header("Content-Type") == null) {
      result.header("Content-Type", "application/x-www-form-urlencoded");
    }

    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      // Capture the request headers added so far so that they can be offered to the CookieHandler.
      // This is mostly to stay close to the RI; it is unlikely any of the headers above would
      // affect cookie choice besides "Host".
      Map<String, List<String>> headers = OkHeaders.toMultimap(result.build().headers(), null);

      Map<String, List<String>> cookies = cookieHandler.get(request.uri(), headers);

      // Add any new cookies to the request.
      OkHeaders.addCookies(result, cookies);
    }

    request = result.build();
  }

  public static String getDefaultUserAgent() {
    String agent = System.getProperty("http.agent");
    return agent != null ? agent : ("Java" + System.getProperty("java.version"));
  }

  public static String hostHeader(URL url) {
    return getEffectivePort(url) != getDefaultPort(url.getProtocol())
        ? url.getHost() + ":" + url.getPort()
        : url.getHost();
  }

  /**
   * Flushes the remaining request header and body, parses the HTTP response
   * headers and starts reading the HTTP response body if it exists.
   */
  public final void readResponse() throws IOException {
    if (response != null) return;
    if (responseSource == null) throw new IllegalStateException("call sendRequest() first!");
    if (!responseSource.requiresConnection()) return;

    // Flush the request body if there's data outstanding.
    if (bufferedRequestBody != null && bufferedRequestBody.buffer().size() > 0) {
      bufferedRequestBody.flush();
    }

    if (sentRequestMillis == -1) {
      if (OkHeaders.contentLength(request) == -1 && requestBodyOut instanceof RetryableSink) {
        // We might not learn the Content-Length until the request body has been buffered.
        long contentLength = ((RetryableSink) requestBodyOut).contentLength();
        request = request.newBuilder()
            .header("Content-Length", Long.toString(contentLength))
            .build();
      }
      transport.writeRequestHeaders(request);
    }

    if (requestBodyOut != null) {
      if (bufferedRequestBody != null) {
        // This also closes the wrapped requestBodyOut.
        bufferedRequestBody.close();
      } else {
        requestBodyOut.close();
      }
      if (requestBodyOut instanceof RetryableSink) {
        transport.writeRequestBody((RetryableSink) requestBodyOut);
      }
    }

    transport.flushRequest();

    response = transport.readResponseHeaders()
        .request(request)
        .handshake(connection.getHandshake())
        .header(OkHeaders.SENT_MILLIS, Long.toString(sentRequestMillis))
        .header(OkHeaders.RECEIVED_MILLIS, Long.toString(System.currentTimeMillis()))
        .setResponseSource(responseSource)
        .build();
    connection.setProtocol(response.protocol());
    receiveHeaders(response.headers());

    if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
      if (validate(validatingResponse, response)) {
        transport.emptyTransferStream();
        releaseConnection();
        response = combine(validatingResponse, response);

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        OkResponseCache responseCache = client.getOkResponseCache();
        responseCache.trackConditionalCacheHit();
        responseCache.update(validatingResponse, cacheableResponse());

        if (validatingResponse.body() != null) {
          initContentStream(validatingResponse.body().source());
        }
        return;
      } else {
        closeQuietly(validatingResponse.body());
      }
    }

    if (!hasResponseBody()) {
      // Don't call initContentStream() when the response doesn't have any content.
      responseTransferSource = transport.getTransferStream(cacheRequest);
      responseBody = Okio.buffer(responseTransferSource);
      return;
    }

    maybeCache();
    initContentStream(transport.getTransferStream(cacheRequest));
  }

  /**
   * Returns true if {@code cached} should be used; false if {@code network}
   * response should be used.
   */
  private static boolean validate(Response cached, Response network) {
    if (network.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
      return true;
    }

    // The HTTP spec says that if the network's response is older than our
    // cached response, we may return the cache's response. Like Chrome (but
    // unlike Firefox), this client prefers to return the newer response.
    Date lastModified = cached.headers().getDate("Last-Modified");
    if (lastModified != null) {
      Date networkLastModified = network.headers().getDate("Last-Modified");
      if (networkLastModified != null
          && networkLastModified.getTime() < lastModified.getTime()) {
        return true;
      }
    }

    return false;
  }

  /**
   * Combines cached headers with a network headers as defined by RFC 2616,
   * 13.5.3.
   */
  private static Response combine(Response cached, Response network) throws IOException {
    Headers.Builder result = new Headers.Builder();

    Headers cachedHeaders = cached.headers();
    for (int i = 0; i < cachedHeaders.size(); i++) {
      String fieldName = cachedHeaders.name(i);
      String value = cachedHeaders.value(i);
      if ("Warning".equals(fieldName) && value.startsWith("1")) {
        continue; // drop 100-level freshness warnings
      }
      if (!OkHeaders.isEndToEnd(fieldName) || network.header(fieldName) == null) {
        result.add(fieldName, value);
      }
    }

    Headers networkHeaders = network.headers();
    for (int i = 0; i < networkHeaders.size(); i++) {
      String fieldName = networkHeaders.name(i);
      if (OkHeaders.isEndToEnd(fieldName)) {
        result.add(fieldName, networkHeaders.value(i));
      }
    }

    return cached.newBuilder().headers(result.build()).build();
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy, or null if
   * no tunnel is necessary. Everything in the tunnel request is sent
   * unencrypted to the proxy server, so tunnels include only the minimum set of
   * headers. This avoids sending potentially sensitive data like HTTP cookies
   * to the proxy unencrypted.
   */
  private Request tunnelRequest(Connection connection, Request request) throws IOException {
    if (!connection.getRoute().requiresTunnel()) return null;

    String userAgent = request.header("User-Agent");
    if (userAgent == null) userAgent = getDefaultUserAgent();

    String host = request.url().getHost();
    int port = getEffectivePort(request.url());
    String authority = (port == getDefaultPort("https")) ? host : (host + ":" + port);
    Request.Builder result = new Request.Builder()
        .url(new URL("https", host, port, "/"))
        .header("Host", authority)
        .header("User-Agent", userAgent)
        .header("Proxy-Connection", "Keep-Alive"); // For HTTP/1.0 proxies like Squid.

    // Copy over the Proxy-Authorization header if it exists.
    String proxyAuthorization = request.header("Proxy-Authorization");
    if (proxyAuthorization != null) {
      result.header("Proxy-Authorization", proxyAuthorization);
    }

    return result.build();
  }

  public void receiveHeaders(Headers headers) throws IOException {
    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      cookieHandler.put(request.uri(), OkHeaders.toMultimap(headers, null));
    }
  }
}
