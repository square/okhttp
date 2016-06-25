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
import java.util.Date;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.CertificatePinner;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
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

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.discard;
import static okhttp3.internal.Util.hostHeader;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/**
 * Handles a single HTTP request/response pair. The request and response may be served by the HTTP
 * response cache, by the network, or by both in the event of a conditional GET.
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
  final StreamAllocation streamAllocation;
  final Response priorResponse;

  /**
   * True if this client added an "Accept-Encoding: gzip" header field and is therefore responsible
   * for also decompressing the transfer stream.
   */
  private boolean transparentGzip;

  /** The original application-provided request URL. Never modified by OkHttp. */
  final HttpUrl userRequestUrl;

  public HttpEngine(OkHttpClient client, HttpUrl userRequestUrl, StreamAllocation streamAllocation,
      Response priorResponse) {
    this.client = client;
    this.userRequestUrl = userRequestUrl;
    this.streamAllocation = streamAllocation != null
        ? streamAllocation
        : new StreamAllocation(client.connectionPool(), createAddress(client, userRequestUrl));
    this.priorResponse = priorResponse;
  }

  /**
   * Figures out what the response source will be, and opens a socket to that source if necessary.
   * Prepares the request headers and gets ready to start writing the request body if it exists.
   *
   * @throws RouteException if the was a problem during connection via a specific route. Sometimes
   *     recoverable.
   * @throws IOException if there was a problem while making a request. Sometimes recoverable.
   */
  public Response proceed(Request userRequest, RealInterceptorChain chain) throws IOException {
    Request request = networkRequest(userRequest);

    InternalCache responseCache = Internal.instance.internalCache(client);
    Response cacheCandidate = responseCache != null
        ? responseCache.get(request)
        : null;

    long now = System.currentTimeMillis();

    CacheStrategy cacheStrategy = new CacheStrategy.Factory(now, request, cacheCandidate).get();
    Request networkRequest = cacheStrategy.networkRequest;
    Response cacheResponse = cacheStrategy.cacheResponse;

    if (responseCache != null) {
      responseCache.trackResponse(cacheStrategy);
    }

    if (cacheCandidate != null && cacheResponse == null) {
      closeQuietly(cacheCandidate.body()); // The cache candidate wasn't applicable. Close it.
    }

    // If we're forbidden from using the network and the cache is insufficient, fail.
    if (networkRequest == null && cacheResponse == null) {
      return new Response.Builder()
          .request(userRequest)
          .priorResponse(stripBody(priorResponse))
          .protocol(Protocol.HTTP_1_1)
          .code(504)
          .message("Unsatisfiable Request (only-if-cached)")
          .body(EMPTY_BODY)
          .sentRequestAtMillis(-1L)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build();
    }

    // If we don't need the network, we're done.
    if (networkRequest == null) {
      Response userResponse = cacheResponse.newBuilder()
          .request(userRequest)
          .priorResponse(stripBody(priorResponse))
          .cacheResponse(stripBody(cacheResponse))
          .build();
      return unzip(userResponse);
    }

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    HttpStream httpStream = null;
    try {
      httpStream = connect(networkRequest);
      httpStream.setHttpEngine(this);
    } finally {
      // If we're crashing on I/O or otherwise, don't leak the cache body.
      if (httpStream == null && cacheCandidate != null) {
        closeQuietly(cacheCandidate.body());
      }
    }

    Response networkResponse = chain.proceed(networkRequest, streamAllocation.connection(),
        streamAllocation, httpStream);

    receiveHeaders(networkResponse.headers());

    // If we have a cache response too, then we're doing a conditional get.
    if (cacheResponse != null) {
      if (validate(cacheResponse, networkResponse)) {
        Response userResponse = cacheResponse.newBuilder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .headers(combine(cacheResponse.headers(), networkResponse.headers()))
            .cacheResponse(stripBody(cacheResponse))
            .networkResponse(stripBody(networkResponse))
            .build();
        networkResponse.body().close();
        streamAllocation.release();

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        responseCache.trackConditionalCacheHit();
        responseCache.update(cacheResponse, userResponse);
        return unzip(userResponse);
      } else {
        closeQuietly(cacheResponse.body());
      }
    }

    Response userResponse = networkResponse.newBuilder()
        .request(userRequest)
        .priorResponse(stripBody(priorResponse))
        .cacheResponse(stripBody(cacheResponse))
        .networkResponse(stripBody(networkResponse))
        .build();

    if (hasBody(userResponse)) {
      CacheRequest cacheRequest = maybeCache(
          userResponse, networkResponse.request(), responseCache);
      userResponse = unzip(cacheWritingResponse(cacheRequest, userResponse));
    }

    return userResponse;
  }

  private HttpStream connect(Request networkRequest) throws IOException {
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

  private CacheRequest maybeCache(Response userResponse, Request networkRequest,
      InternalCache responseCache) throws IOException {
    if (responseCache == null) return null;

    // Should we cache this response for this request?
    if (!CacheStrategy.isCacheable(userResponse, networkRequest)) {
      if (HttpMethod.invalidatesCache(networkRequest.method())) {
        try {
          responseCache.remove(networkRequest);
        } catch (IOException ignored) {
          // The cache cannot be written.
        }
      }
      return null;
    }

    // Offer this request to the cache.
    return responseCache.put(userResponse);
  }

  /**
   * Release any resources held by this engine. Returns the stream allocation held by this engine,
   * which itself must be used or released.
   */
  public StreamAllocation close(Response userResponse) {
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
  private Response unzip(Response response) throws IOException {
    if (!transparentGzip || !"gzip".equalsIgnoreCase(response.header("Content-Encoding"))) {
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

    List<Cookie> cookies = Cookie.parseAll(userRequestUrl, headers);
    if (cookies.isEmpty()) return;

    client.cookieJar().saveFromResponse(userRequestUrl, cookies);
  }

  private static Address createAddress(OkHttpClient client, HttpUrl url) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (url.isHttps()) {
      sslSocketFactory = client.sslSocketFactory();
      hostnameVerifier = client.hostnameVerifier();
      certificatePinner = client.certificatePinner();
    }

    return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
        sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
        client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
  }
}
