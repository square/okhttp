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
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.TunnelRequest;
import com.squareup.okhttp.internal.Dns;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CookieHandler;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

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
 *
 * <p>This class may hold a socket connection that needs to be released or
 * recycled. By default, this socket connection is held when the last byte of
 * the response is consumed. To release the connection when it is no longer
 * required, use {@link #automaticallyReleaseConnectionToPool()}.
 */
public class HttpEngine {
  final OkHttpClient client;

  Connection connection;
  RouteSelector routeSelector;
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
  public HttpEngine(OkHttpClient client, Request request, boolean bufferRequestBody,
      Connection connection, RetryableOutputStream requestBodyOut) throws IOException {
    this.client = client;
    this.request = request;
    this.bufferRequestBody = bufferRequestBody;
    this.connection = connection;
    this.route = connection != null ? connection.getRoute() : null;
    this.requestBodyOut = requestBodyOut;
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
    CacheStrategy cacheStrategy = CacheStrategy.get(now, cacheResponse, request);
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

      transport = (Transport) connection.newTransport(this);

      // Create a request body if we don't have one already. We'll already have
      // one if we're retrying a failed POST.
      if (hasRequestBody() && requestBodyOut == null) {
        requestBodyOut = transport.createRequestBody(request);
      }

    } else {
      // We're using a cached response. Close the connection we may have inherited from a redirect.
      if (connection != null) {
        disconnect();
      }

      // No need for the network! Promote the cached response immediately.
      this.response = validatingResponse;
      if (validatingResponse.body() != null) {
        initContentStream(validatingResponse.body().byteStream());
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
      Address address = new Address(uriHost, getEffectivePort(request.url()), sslSocketFactory,
          hostnameVerifier, client.getAuthenticator(), client.getProxy(), client.getTransports());
      routeSelector = new RouteSelector(address, request.uri(), client.getProxySelector(),
          client.getConnectionPool(), Dns.DEFAULT, client.getRoutesDatabase());
    }

    connection = routeSelector.next(request.method());

    if (!connection.isConnected()) {
      connection.connect(client.getConnectTimeout(), client.getReadTimeout(), getTunnelConfig(),
              client.forceSpdyAddresses);
      client.getConnectionPool().maybeShare(connection);
      client.getRoutesDatabase().connected(connection.getRoute());
    } else {
      connection.updateReadTimeout(client.getReadTimeout());
    }

    route = connection.getRoute();
  }

  /**
   * Recycle the connection to the origin server. It is an error to call this
   * with a request in flight.
   */
  private void disconnect() {
    client.getConnectionPool().recycle(connection);
    connection = null;
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
    String method = request.method();
    return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
  }

  /** Returns the request body or null if this request doesn't have a body. */
  public final OutputStream getRequestBody() {
    if (responseSource == null) throw new IllegalStateException();
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
      disconnect();
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
      closeQuietly(responseBodyIn);
    }

    if (connection != null && !connectionReleased) {
      connectionReleased = true;

      if (transport == null
          || !transport.makeReusable(streamCanceled, requestBodyOut, responseTransferIn)) {
        closeQuietly(connection);
        connection = null;
      } else if (automaticallyReleaseConnectionToPool) {
        disconnect();
      }
    }
  }

  private void initContentStream(InputStream transferStream) throws IOException {
    responseTransferIn = transferStream;
    if (transparentGzip && "gzip".equalsIgnoreCase(response.header("Content-Encoding"))) {
      // If the response was transparently gzipped, remove the gzip header field
      // so clients don't double decompress. http://b/3009828
      //
      // Also remove the Content-Length in this case because it contains the
      // length of the gzipped response. This isn't terribly useful and is
      // dangerous because clients can query the content length, but not the
      // content encoding.
      response = response.newBuilder()
          .removeHeader("Content-Encoding")
          .removeHeader("Content-Length")
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

    if (request.getUserAgent() == null) {
      result.setUserAgent(getDefaultUserAgent());
    }

    if (request.header("Host") == null) {
      result.header("Host", hostHeader(request.url()));
    }

    if ((connection == null || connection.getHttpMinorVersion() != 0)
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
      Map<String, List<String>> cookies = cookieHandler.get(
          request.uri(), OkHeaders.toMultimap(request.getHeaders(), null));
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

    if (sentRequestMillis == -1) {
      if (OkHeaders.contentLength(request) == -1
          && requestBodyOut instanceof RetryableOutputStream) {
        // We might not learn the Content-Length until the request body has been buffered.
        long contentLength = ((RetryableOutputStream) requestBodyOut).contentLength();
        request = request.newBuilder()
            .header("Content-Length", Long.toString(contentLength))
            .build();
      }
      transport.writeRequestHeaders(request);
    }

    if (requestBodyOut != null) {
      requestBodyOut.close();
      if (requestBodyOut instanceof RetryableOutputStream) {
        transport.writeRequestBody((RetryableOutputStream) requestBodyOut);
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
    connection.setHttpMinorVersion(response.httpMinorVersion());
    receiveHeaders(response.headers());

    if (responseSource == ResponseSource.CONDITIONAL_CACHE) {
      if (validatingResponse.validate(response)) {
        release(false);
        response = combine(validatingResponse, response);

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
        closeQuietly(validatingResponse.body());
      }
    }

    if (hasResponseBody()) {
      maybeCache();
    }

    initContentStream(transport.getTransferStream(cacheRequest));
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
      if (!isEndToEnd(fieldName) || network.header(fieldName) == null) {
        result.add(fieldName, value);
      }
    }

    Headers networkHeaders = network.headers();
    for (int i = 0; i < networkHeaders.size(); i++) {
      String fieldName = networkHeaders.name(i);
      if (isEndToEnd(fieldName)) {
        result.add(fieldName, networkHeaders.value(i));
      }
    }

    return cached.newBuilder().headers(result.build()).build();
  }

  /**
   * Returns true if {@code fieldName} is an end-to-end HTTP header, as
   * defined by RFC 2616, 13.5.1.
   */
  private static boolean isEndToEnd(String fieldName) {
    return !"Connection".equalsIgnoreCase(fieldName)
        && !"Keep-Alive".equalsIgnoreCase(fieldName)
        && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
        && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
        && !"TE".equalsIgnoreCase(fieldName)
        && !"Trailers".equalsIgnoreCase(fieldName)
        && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
        && !"Upgrade".equalsIgnoreCase(fieldName);
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
      cookieHandler.put(request.uri(), OkHeaders.toMultimap(headers, null));
    }
  }
}
