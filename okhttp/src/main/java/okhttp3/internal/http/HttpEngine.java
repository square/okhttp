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
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.InternalCache;
import okhttp3.internal.Version;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.discard;
import static okhttp3.internal.Util.hostHeader;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * Handles a single HTTP request/response pair. Each HTTP engine follows this lifecycle:
 *
 * <ol>
 *     <li>It is created.
 *     <li>The HTTP request message is sent with sendRequest(). Once the request is sent it is an
 *         error to modify the request headers. After sendRequest() has been called the request body
 *         can be written to if it exists.
 *     <li>The HTTP response message is read with readResponse(). After the response has been read
 *         the response headers and body can be read. All responses have a response body input
 *         stream, though in some instances this stream is empty.
 * </ol>
 *
 * <p>The request and response may be served by the HTTP response cache, by the network, or by both
 * in the event of a conditional GET.
 */
public final class HttpEngine {
  /**
   * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
   * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  public static final int MAX_FOLLOW_UPS = 20;

  private static final ResponseBody EMPTY_BODY = new ResponseBody() {
    @Override public MediaType contentType() {
      return null;
    }

    @Override public long contentLength() {
      return 0;
    }

    @Override public BufferedSource source() {
      return new Buffer();
    }
  };

  final OkHttpClient client;

  public final StreamAllocation streamAllocation;
  private final Response priorResponse;
  private HttpStream httpStream;

  /** The time when the request headers were written, or -1 if they haven't been written yet. */
  long sentRequestMillis = -1;

  /**
   * True if this client added an "Accept-Encoding: gzip" header field and is therefore responsible
   * for also decompressing the transfer stream.
   */
  private boolean transparentGzip;

  /**
   * True if the request body must be completely buffered before transmission; false if it can be
   * streamed. Buffering has two advantages: we don't need the content-length in advance and we can
   * retransmit if necessary. The upside of streaming is that we can save memory.
   */
  public final boolean bufferRequestBody;

  /**
   * The original application-provided request. Never modified by OkHttp. When follow-up requests
   * are necessary, they are derived from this request.
   */
  private final Request userRequest;

  /**
   * The request to send on the network, or null for no network request. This is derived from the
   * user request, and customized to support OkHttp features like compression and caching.
   */
  private Request networkRequest;

  /**
   * The cached response, or null if the cache doesn't exist or cannot be used for this request.
   * Conditional caching means this may be non-null even when the network request is non-null. Never
   * modified by OkHttp.
   */
  private Response cacheResponse;

  /**
   * The user-visible response. This is derived from either the network response, cache response, or
   * both. It is customized to support OkHttp features like compression and caching.
   */
  private Response userResponse;

  private final boolean forWebSocket;

  /** The cache request currently being populated from a network response. */
  private CacheRequest storeRequest;
  private CacheStrategy cacheStrategy;

  /**
   * @param request the HTTP request without a body. The body must be written via the engine's
   * request body stream.
   */
  public HttpEngine(OkHttpClient client, Request request, boolean bufferRequestBody,
      boolean forWebSocket, StreamAllocation streamAllocation, Response priorResponse) {
    this.client = client;
    this.userRequest = request;
    this.bufferRequestBody = bufferRequestBody;
    this.forWebSocket = forWebSocket;
    this.streamAllocation = streamAllocation != null
        ? streamAllocation
        : new StreamAllocation(client.connectionPool(), createAddress(client, request));
    this.priorResponse = priorResponse;
  }

  /**
   * Figures out what the response source will be, and opens a socket to that source if necessary.
   * Prepares the request headers and gets ready to start writing the request body if it exists.
   *
   * @throws RouteException if the was a problem during connection via a specific route. Sometimes
   * recoverable. See {@link #recover}.
   * @throws IOException if there was a problem while making a request. Sometimes recoverable. See
   * {@link #recover(IOException, boolean)}.
   */
  public void sendRequest() throws RouteException, IOException {
    if (cacheStrategy != null) return; // Already sent.
    if (httpStream != null) throw new IllegalStateException();

    Request request = networkRequest(userRequest);

    InternalCache responseCache = Internal.instance.internalCache(client);
    Response cacheCandidate = responseCache != null
        ? responseCache.get(request)
        : null;

    long now = System.currentTimeMillis();
    cacheStrategy = new CacheStrategy.Factory(now, request, cacheCandidate).get();
    networkRequest = cacheStrategy.networkRequest;
    cacheResponse = cacheStrategy.cacheResponse;

    if (responseCache != null) {
      responseCache.trackResponse(cacheStrategy);
    }

    if (cacheCandidate != null && cacheResponse == null) {
      closeQuietly(cacheCandidate.body()); // The cache candidate wasn't applicable. Close it.
    }

    // If we're forbidden from using the network and the cache is insufficient, fail.
    if (networkRequest == null && cacheResponse == null) {
      userResponse = new Response.Builder()
          .request(userRequest)
          .priorResponse(stripBody(priorResponse))
          .protocol(Protocol.HTTP_1_1)
          .code(504)
          .message("Unsatisfiable Request (only-if-cached)")
          .body(EMPTY_BODY)
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build();
      return;
    }

    // If we don't need the network, we're done.
    if (networkRequest == null) {
      userResponse = cacheResponse.newBuilder()
          .request(userRequest)
          .priorResponse(stripBody(priorResponse))
          .cacheResponse(stripBody(cacheResponse))
          .build();
      userResponse = unzip(userResponse);
      return;
    }

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    boolean success = false;
    try {
      httpStream = connect();
      httpStream.setHttpEngine(this);
      success = true;
    } finally {
      // If we're crashing on I/O or otherwise, don't leak the cache body.
      if (!success && cacheCandidate != null) {
        closeQuietly(cacheCandidate.body());
      }
    }
  }

  private HttpStream connect() throws RouteException, IOException {
    boolean doExtensiveHealthChecks = !networkRequest.method().equals("GET");
    return streamAllocation.newStream(client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis(),
        client.retryOnConnectionFailure(), doExtensiveHealthChecks);
  }

  private static Response stripBody(Response response) {
    return response != null && response.body() != null
        ? response.newBuilder().body(null).build()
        : response;
  }

  /**
   * Called immediately before the transport transmits HTTP request headers. This is used to observe
   * the sent time should the request be cached.
   */
  public void writingRequestHeaders() {
    if (sentRequestMillis != -1) throw new IllegalStateException();
    sentRequestMillis = System.currentTimeMillis();
  }

  boolean permitsRequestBody(Request request) {
    return HttpMethod.permitsRequestBody(request.method());
  }

  /** Returns the engine's response. */
  // TODO: the returned body will always be null.
  public Response getResponse() {
    if (userResponse == null) throw new IllegalStateException();
    return userResponse;
  }

  public Connection getConnection() {
    return streamAllocation.connection();
  }

  /**
   * Report and attempt to recover from a failure to communicate with a server. Returns a new HTTP
   * engine that should be used for the retry if {@code e} is recoverable, or null if the failure is
   * permanent. Requests with a body can only be recovered if the body is buffered.
   */
  public HttpEngine recover(IOException e, boolean routeException) {
    streamAllocation.streamFailed(e);

    if (!client.retryOnConnectionFailure()) {
      return null; // The application layer has forbidden retries.
    }

    if (!isRecoverable(e, routeException)) {
      return null; // This exception is fatal.
    }

    if (!streamAllocation.hasMoreRoutes()) {
      return null; // No more routes to attempt.
    }

    StreamAllocation streamAllocation = close();

    // For failure recovery, use the same route selector with a new connection.
    return new HttpEngine(client, userRequest, bufferRequestBody,
        forWebSocket, streamAllocation, priorResponse);
  }

  private boolean isRecoverable(IOException e, boolean routeException) {
    // If there was a protocol problem, don't recover.
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException && routeException;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (e instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  private void maybeCache() throws IOException {
    InternalCache responseCache = Internal.instance.internalCache(client);
    if (responseCache == null) return;

    // Should we cache this response for this request?
    if (!CacheStrategy.isCacheable(userResponse, networkRequest)) {
      if (HttpMethod.invalidatesCache(networkRequest.method())) {
        try {
          responseCache.remove(networkRequest);
        } catch (IOException ignored) {
          // The cache cannot be written.
        }
      }
      return;
    }

    // Offer this request to the cache.
    storeRequest = responseCache.put(userResponse);
  }

  /**
   * Configure the socket connection to be either pooled or closed when it is either exhausted or
   * closed. If it is unneeded when this is called, it will be released immediately.
   */
  public void releaseStreamAllocation() throws IOException {
    streamAllocation.release();
  }

  /**
   * Immediately closes the socket connection if it's currently held by this engine. Use this to
   * interrupt an in-flight request from any thread. It's the caller's responsibility to close the
   * request body and response body streams; otherwise resources may be leaked.
   *
   * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
   * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
   * Otherwise if a socket connection is being established, that is terminated.
   */
  public void cancel() {
    streamAllocation.cancel();
  }

  /**
   * Release any resources held by this engine. Returns the stream allocation held by this engine,
   * which itself must be used or released.
   */
  public StreamAllocation close() {
    if (userResponse != null) {
      closeQuietly(userResponse.body());
    } else {
      // If this engine never achieved a response body, its stream allocation is dead.
      streamAllocation.streamFailed(null);
    }

    return streamAllocation;
  }

  /**
   * Returns a new response that does gzip decompression on {@code response}, if transparent gzip
   * was both offered by OkHttp and used by the origin server.
   *
   * <p>In addition to decompression, this will also strip the corresponding headers. We strip the
   * Content-Encoding header to prevent the application from attempting to double decompress. We
   * strip the Content-Length header because it is the length of the compressed content, but the
   * application is only interested in the length of the uncompressed content.
   *
   * <p>This method should only be used for non-empty response bodies. Response codes like "304 Not
   * Modified" can include "Content-Encoding: gzip" without a response body and we will crash if we
   * attempt to decompress the zero-byte source.
   */
  private Response unzip(final Response response) throws IOException {
    if (!transparentGzip || !"gzip".equalsIgnoreCase(userResponse.header("Content-Encoding"))) {
      return response;
    }

    if (response.body() == null) {
      return response;
    }

    GzipSource responseBody = new GzipSource(response.body().source());
    Headers strippedHeaders = response.headers().newBuilder()
        .removeAll("Content-Encoding")
        .removeAll("Content-Length")
        .build();
    return response.newBuilder()
        .headers(strippedHeaders)
        .body(new RealResponseBody(strippedHeaders, Okio.buffer(responseBody)))
        .build();
  }

  /**
   * Returns true if the response must have a (possibly 0-length) body. See RFC 2616 section 4.3.
   */
  public static boolean hasBody(Response response) {
    // HEAD requests never yield a body regardless of the response headers.
    if (response.request().method().equals("HEAD")) {
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
   * <p>This client doesn't specify a default {@code Accept} header because it doesn't know what
   * content types the application is interested in.
   */
  private Request networkRequest(Request request) throws IOException {
    Request.Builder result = request.newBuilder();

    if (request.header("Host") == null) {
      result.header("Host", hostHeader(request.url(), false));
    }

    if (request.header("Connection") == null) {
      result.header("Connection", "Keep-Alive");
    }

    if (request.header("Accept-Encoding") == null) {
      transparentGzip = true;
      result.header("Accept-Encoding", "gzip");
    }

    List<Cookie> cookies = client.cookieJar().loadForRequest(request.url());
    if (!cookies.isEmpty()) {
      result.header("Cookie", cookieHeader(cookies));
    }

    if (request.header("User-Agent") == null) {
      result.header("User-Agent", Version.userAgent());
    }

    return result.build();
  }

  /** Returns a 'Cookie' HTTP request header with all cookies, like {@code a=b; c=d}. */
  private String cookieHeader(List<Cookie> cookies) {
    StringBuilder cookieHeader = new StringBuilder();
    for (int i = 0, size = cookies.size(); i < size; i++) {
      if (i > 0) {
        cookieHeader.append("; ");
      }
      Cookie cookie = cookies.get(i);
      cookieHeader.append(cookie.name()).append('=').append(cookie.value());
    }
    return cookieHeader.toString();
  }

  /**
   * Flushes the remaining request header and body, parses the HTTP response headers and starts
   * reading the HTTP response body if it exists.
   */
  public void readResponse() throws IOException {
    if (userResponse != null) {
      return; // Already ready.
    }
    if (networkRequest == null && cacheResponse == null) {
      throw new IllegalStateException("call sendRequest() first!");
    }
    if (networkRequest == null) {
      return; // No network response to read.
    }

    Response networkResponse;

    if (forWebSocket) {
      httpStream.writeRequestHeaders(networkRequest);
      networkResponse = readNetworkResponse();
    } else {
      networkResponse = new NetworkInterceptorChain(0, networkRequest,
          streamAllocation.connection()).proceed(networkRequest);
    }

    receiveHeaders(networkResponse.headers());

    // If we have a cache response too, then we're doing a conditional get.
    if (cacheResponse != null) {
      if (validate(cacheResponse, networkResponse)) {
        userResponse = cacheResponse.newBuilder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .headers(combine(cacheResponse.headers(), networkResponse.headers()))
            .cacheResponse(stripBody(cacheResponse))
            .networkResponse(stripBody(networkResponse))
            .build();
        networkResponse.body().close();
        releaseStreamAllocation();

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        InternalCache responseCache = Internal.instance.internalCache(client);
        responseCache.trackConditionalCacheHit();
        responseCache.update(cacheResponse, userResponse);
        userResponse = unzip(userResponse);
        return;
      } else {
        closeQuietly(cacheResponse.body());
      }
    }

    userResponse = networkResponse.newBuilder()
        .request(userRequest)
        .priorResponse(stripBody(priorResponse))
        .cacheResponse(stripBody(cacheResponse))
        .networkResponse(stripBody(networkResponse))
        .build();

    if (hasBody(userResponse)) {
      maybeCache();
      userResponse = unzip(cacheWritingResponse(storeRequest, userResponse));
    }
  }

  class NetworkInterceptorChain implements Interceptor.Chain {
    private final int index;
    private final Request request;
    private final Connection connection;
    private int calls;

    NetworkInterceptorChain(int index, Request request, Connection connection) {
      this.index = index;
      this.request = request;
      this.connection = connection;
    }

    @Override public Connection connection() {
      return connection;
    }

    @Override public Request request() {
      return request;
    }

    @Override public Response proceed(Request request) throws IOException {
      calls++;

      if (index > 0) {
        Interceptor caller = client.networkInterceptors().get(index - 1);
        Address address = connection().route().address();

        // Confirm that the interceptor uses the connection we've already prepared.
        if (!request.url().host().equals(address.url().host())
            || request.url().port() != address.url().port()) {
          throw new IllegalStateException("network interceptor " + caller
              + " must retain the same host and port");
        }

        // Confirm that this is the interceptor's first call to chain.proceed().
        if (calls > 1) {
          throw new IllegalStateException("network interceptor " + caller
              + " must call proceed() exactly once");
        }
      }

      if (index < client.networkInterceptors().size()) {
        // There's another interceptor in the chain. Call that.
        NetworkInterceptorChain chain = new NetworkInterceptorChain(index + 1, request, connection);
        Interceptor interceptor = client.networkInterceptors().get(index);
        Response interceptedResponse = interceptor.intercept(chain);

        // Confirm that the interceptor made the required call to chain.proceed().
        if (chain.calls != 1) {
          throw new IllegalStateException("network interceptor " + interceptor
              + " must call proceed() exactly once");
        }
        if (interceptedResponse == null) {
          throw new NullPointerException("network interceptor " + interceptor
              + " returned null");
        }

        return interceptedResponse;
      }

      httpStream.writeRequestHeaders(request);

      //Update the networkRequest with the possibly updated interceptor request.
      networkRequest = request;

      if (permitsRequestBody(request) && request.body() != null) {
        Sink requestBodyOut = httpStream.createRequestBody(request, request.body().contentLength());
        BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);
        request.body().writeTo(bufferedRequestBody);
        bufferedRequestBody.close();
      }

      Response response = readNetworkResponse();

      int code = response.code();
      if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
        throw new ProtocolException(
            "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
      }

      return response;
    }
  }

  private Response readNetworkResponse() throws IOException {
    httpStream.finishRequest();

    Response networkResponse = httpStream.readResponseHeaders()
        .request(networkRequest)
        .handshake(streamAllocation.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build();

    if (!forWebSocket || networkResponse.code() != 101) {
      networkResponse = networkResponse.newBuilder()
          .body(httpStream.openResponseBody(networkResponse))
          .build();
    }

    if ("close".equalsIgnoreCase(networkResponse.request().header("Connection"))
        || "close".equalsIgnoreCase(networkResponse.header("Connection"))) {
      streamAllocation.noNewStreams();
    }

    return networkResponse;
  }

  /**
   * Returns a new source that writes bytes to {@code cacheRequest} as they are read by the source
   * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
   * may never exhaust the source stream and therefore not complete the cached response.
   */
  private Response cacheWritingResponse(final CacheRequest cacheRequest, Response response)
      throws IOException {
    // Some apps return a null body; for compatibility we treat that like a null cache request.
    if (cacheRequest == null) return response;
    Sink cacheBodyUnbuffered = cacheRequest.body();
    if (cacheBodyUnbuffered == null) return response;

    final BufferedSource source = response.body().source();
    final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);

    Source cacheWritingSource = new Source() {
      boolean cacheRequestClosed;

      @Override public long read(Buffer sink, long byteCount) throws IOException {
        long bytesRead;
        try {
          bytesRead = source.read(sink, byteCount);
        } catch (IOException e) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true;
            cacheRequest.abort(); // Failed to write a complete cache response.
          }
          throw e;
        }

        if (bytesRead == -1) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true;
            cacheBody.close(); // The cache response is complete!
          }
          return -1;
        }

        sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
        cacheBody.emitCompleteSegments();
        return bytesRead;
      }

      @Override public Timeout timeout() {
        return source.timeout();
      }

      @Override public void close() throws IOException {
        if (!cacheRequestClosed
            && !discard(this, HttpStream.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
          cacheRequestClosed = true;
          cacheRequest.abort();
        }
        source.close();
      }
    };

    return response.newBuilder()
        .body(new RealResponseBody(response.headers(), Okio.buffer(cacheWritingSource)))
        .build();
  }

  /**
   * Returns true if {@code cached} should be used; false if {@code network} response should be
   * used.
   */
  private static boolean validate(Response cached, Response network) {
    if (network.code() == HTTP_NOT_MODIFIED) {
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
   * Combines cached headers with a network headers as defined by RFC 2616, 13.5.3.
   */
  private static Headers combine(Headers cachedHeaders, Headers networkHeaders) throws IOException {
    Headers.Builder result = new Headers.Builder();

    for (int i = 0, size = cachedHeaders.size(); i < size; i++) {
      String fieldName = cachedHeaders.name(i);
      String value = cachedHeaders.value(i);
      if ("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) {
        continue; // Drop 100-level freshness warnings.
      }
      if (!OkHeaders.isEndToEnd(fieldName) || networkHeaders.get(fieldName) == null) {
        Internal.instance.addLenient(result, fieldName, value);
      }
    }

    for (int i = 0, size = networkHeaders.size(); i < size; i++) {
      String fieldName = networkHeaders.name(i);
      if ("Content-Length".equalsIgnoreCase(fieldName)) {
        continue; // Ignore content-length headers of validating responses.
      }
      if (OkHeaders.isEndToEnd(fieldName)) {
        Internal.instance.addLenient(result, fieldName, networkHeaders.value(i));
      }
    }

    return result.build();
  }

  public void receiveHeaders(Headers headers) throws IOException {
    if (client.cookieJar() == CookieJar.NO_COOKIES) return;

    List<Cookie> cookies = Cookie.parseAll(userRequest.url(), headers);
    if (cookies.isEmpty()) return;

    client.cookieJar().saveFromResponse(userRequest.url(), cookies);
  }

  /**
   * Figures out the HTTP request to make in response to receiving this engine's response. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   */
  public Request followUpRequest() throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    Connection connection = streamAllocation.connection();
    Route route = connection != null
        ? connection.route()
        : null;
    int responseCode = userResponse.code();

    final String method = userRequest.method();
    switch (responseCode) {
      case HTTP_PROXY_AUTH:
        Proxy selectedProxy = route != null
            ? route.proxy()
            : client.proxy();
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        return client.proxyAuthenticator().authenticate(route, userResponse);

      case HTTP_UNAUTHORIZED:
        return client.authenticator().authenticate(route, userResponse);

      case HTTP_PERM_REDIRECT:
      case HTTP_TEMP_REDIRECT:
        // "If the 307 or 308 status code is received in response to a request other than GET
        // or HEAD, the user agent MUST NOT automatically redirect the request"
        if (!method.equals("GET") && !method.equals("HEAD")) {
          return null;
        }
        // fall-through
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        // Does the client allow redirects?
        if (!client.followRedirects()) return null;

        String location = userResponse.header("Location");
        if (location == null) return null;
        HttpUrl url = userRequest.url().resolve(location);

        // Don't follow redirects to unsupported protocols.
        if (url == null) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        boolean sameScheme = url.scheme().equals(userRequest.url().scheme());
        if (!sameScheme && !client.followSslRedirects()) return null;

        // Redirects don't include a request body.
        Request.Builder requestBuilder = userRequest.newBuilder();
        if (HttpMethod.permitsRequestBody(method)) {
          if (HttpMethod.redirectsToGet(method)) {
            requestBuilder.method("GET", null);
          } else {
            requestBuilder.method(method, null);
          }
          requestBuilder.removeHeader("Transfer-Encoding");
          requestBuilder.removeHeader("Content-Length");
          requestBuilder.removeHeader("Content-Type");
        }

        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.
        if (!sameConnection(url)) {
          requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();

      case HTTP_CLIENT_TIMEOUT:
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        if (userRequest.body() instanceof UnrepeatableRequestBody) {
          return null;
        }

        return userRequest;

      default:
        return null;
    }
  }

  /**
   * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
   * engine.
   */
  public boolean sameConnection(HttpUrl followUp) {
    HttpUrl url = userRequest.url();
    return url.host().equals(followUp.host())
        && url.port() == followUp.port()
        && url.scheme().equals(followUp.scheme());
  }

  private static Address createAddress(OkHttpClient client, Request request) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (request.isHttps()) {
      sslSocketFactory = client.sslSocketFactory();
      hostnameVerifier = client.hostnameVerifier();
      certificatePinner = client.certificatePinner();
    }

    return new Address(request.url().host(), request.url().port(), client.dns(),
        client.socketFactory(), sslSocketFactory, hostnameVerifier, certificatePinner,
        client.proxyAuthenticator(), client.proxy(), client.protocols(),
        client.connectionSpecs(), client.proxySelector());
  }
}
