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
import com.squareup.okhttp.CertificatePinner;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.InternalCache;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.Version;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.ProtocolException;
import java.net.Proxy;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static com.squareup.okhttp.internal.Util.closeQuietly;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_CONTINUE;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

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

  /**
   * The original application-provided request. Never modified by OkHttp. When
   * follow-up requests are necessary, they are derived from this request.
   */
  private final Request userRequest;

  /**
   * The request to send on the network, or null for no network request. This is
   * derived from the user request, and customized to support OkHttp features
   * like compression and caching.
   */
  private Request networkRequest;

  /**
   * The cached response, or null if the cache doesn't exist or cannot be used
   * for this request. Conditional caching means this may be non-null even when
   * the network request is non-null. Never modified by OkHttp.
   */
  private Response cacheResponse;

  /**
   * The user-visible response. This is derived from either the network
   * response, cache response, or both. It is customized to support OkHttp
   * features like compression and caching.
   */
  private Response userResponse;

  private Sink requestBodyOut;
  private BufferedSink bufferedRequestBody;
  private final boolean callerWritesRequestBody;
  private final boolean forWebSocket;

  /** The cache request currently being populated from a network response. */
  private CacheRequest storeRequest;
  private CacheStrategy cacheStrategy;

  /**
   * @param request the HTTP request without a body. The body must be written via the engine's
   *     request body stream.
   * @param callerWritesRequestBody true for the {@code HttpURLConnection}-style interaction
   *     model where control flow is returned to the calling application to write the request body
   *     before the response body is readable.
   */
  public HttpEngine(OkHttpClient client, Request request, boolean bufferRequestBody,
      boolean callerWritesRequestBody, boolean forWebSocket, StreamAllocation streamAllocation,
      RetryableSink requestBodyOut, Response priorResponse) {
    this.client = client;
    this.userRequest = request;
    this.bufferRequestBody = bufferRequestBody;
    this.callerWritesRequestBody = callerWritesRequestBody;
    this.forWebSocket = forWebSocket;
    this.streamAllocation = streamAllocation != null
        ? streamAllocation
        : new StreamAllocation(client.getConnectionPool(), createAddress(client, request));
    this.requestBodyOut = requestBodyOut;
    this.priorResponse = priorResponse;
  }

  /**
   * Figures out what the response source will be, and opens a socket to that
   * source if necessary. Prepares the request headers and gets ready to start
   * writing the request body if it exists.
   *
   * @throws RequestException if there was a problem with request setup. Unrecoverable.
   * @throws RouteException if the was a problem during connection via a specific route. Sometimes
   *     recoverable. See {@link #recover(RouteException)}.
   * @throws IOException if there was a problem while making a request. Sometimes recoverable. See
   *     {@link #recover(IOException)}.
   *
   */
  public void sendRequest() throws RequestException, RouteException, IOException {
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

    if (networkRequest != null) {
      httpStream = connect();
      httpStream.setHttpEngine(this);

      // If the caller's control flow writes the request body, we need to create that stream
      // immediately. And that means we need to immediately write the request headers, so we can
      // start streaming the request body. (We may already have a request body if we're retrying a
      // failed POST.)
      if (callerWritesRequestBody && permitsRequestBody(networkRequest) && requestBodyOut == null) {
        long contentLength = OkHeaders.contentLength(request);
        if (bufferRequestBody) {
          if (contentLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("Use setFixedLengthStreamingMode() or "
                + "setChunkedStreamingMode() for requests larger than 2 GiB.");
          }

          if (contentLength != -1) {
            // Buffer a request body of a known length.
            httpStream.writeRequestHeaders(networkRequest);
            requestBodyOut = new RetryableSink((int) contentLength);
          } else {
            // Buffer a request body of an unknown length. Don't write request
            // headers until the entire body is ready; otherwise we can't set the
            // Content-Length header correctly.
            requestBodyOut = new RetryableSink();
          }
        } else {
          httpStream.writeRequestHeaders(networkRequest);
          requestBodyOut = httpStream.createRequestBody(networkRequest, contentLength);
        }
      }

    } else {
      streamAllocation.release();

      if (cacheResponse != null) {
        // We have a valid cached response. Promote it to the user response immediately.
        this.userResponse = cacheResponse.newBuilder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .cacheResponse(stripBody(cacheResponse))
            .build();
      } else {
        // We're forbidden from using the network, and the cache is insufficient.
        this.userResponse = new Response.Builder()
            .request(userRequest)
            .priorResponse(stripBody(priorResponse))
            .protocol(Protocol.HTTP_1_1)
            .code(504)
            .message("Unsatisfiable Request (only-if-cached)")
            .body(EMPTY_BODY)
            .build();
      }

      userResponse = unzip(userResponse);
    }
  }

  private HttpStream connect() throws RouteException, RequestException, IOException {
    boolean doExtensiveHealthChecks = !networkRequest.method().equals("GET");
    return streamAllocation.newStream(client.getConnectTimeout(),
        client.getReadTimeout(), client.getWriteTimeout(),
        client.getRetryOnConnectionFailure(), doExtensiveHealthChecks);
  }

  private static Response stripBody(Response response) {
    return response != null && response.body() != null
        ? response.newBuilder().body(null).build()
        : response;
  }


  /**
   * Called immediately before the transport transmits HTTP request headers.
   * This is used to observe the sent time should the request be cached.
   */
  public void writingRequestHeaders() {
    if (sentRequestMillis != -1) throw new IllegalStateException();
    sentRequestMillis = System.currentTimeMillis();
  }

  boolean permitsRequestBody(Request request) {
    return HttpMethod.permitsRequestBody(request.method());
  }

  /** Returns the request body or null if this request doesn't have a body. */
  public Sink getRequestBody() {
    if (cacheStrategy == null) throw new IllegalStateException();
    return requestBodyOut;
  }

  public BufferedSink getBufferedRequestBody() {
    BufferedSink result = bufferedRequestBody;
    if (result != null) return result;
    Sink requestBody = getRequestBody();
    return requestBody != null
        ? (bufferedRequestBody = Okio.buffer(requestBody))
        : null;
  }

  public boolean hasResponse() {
    return userResponse != null;
  }

  public Request getRequest() {
    return userRequest;
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
   * Attempt to recover from failure to connect via a route. Returns a new HTTP engine
   * that should be used for the retry if there are other routes to try, or null if
   * there are no more routes to try.
   */
  public HttpEngine recover(RouteException e) {
    if (!streamAllocation.recover(e)) {
      return null;
    }

    if (!client.getRetryOnConnectionFailure()) {
      return null;
    }

    StreamAllocation streamAllocation = close();

    // For failure recovery, use the same route selector with a new connection.
    return new HttpEngine(client, userRequest, bufferRequestBody, callerWritesRequestBody,
        forWebSocket, streamAllocation, (RetryableSink) requestBodyOut, priorResponse);
  }

  /**
   * Report and attempt to recover from a failure to communicate with a server. Returns a new
   * HTTP engine that should be used for the retry if {@code e} is recoverable, or null if
   * the failure is permanent. Requests with a body can only be recovered if the
   * body is buffered.
   */
  public HttpEngine recover(IOException e, Sink requestBodyOut) {
    if (!streamAllocation.recover(e, requestBodyOut)) {
      return null;
    }

    if (!client.getRetryOnConnectionFailure()) {
      return null;
    }

    StreamAllocation streamAllocation = close();

    // For failure recovery, use the same route selector with a new connection.
    return new HttpEngine(client, userRequest, bufferRequestBody, callerWritesRequestBody,
        forWebSocket, streamAllocation, (RetryableSink) requestBodyOut, priorResponse);
  }

  public HttpEngine recover(IOException e) {
    return recover(e, requestBodyOut);
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
    storeRequest = responseCache.put(stripBody(userResponse));
  }

  /**
   * Configure the socket connection to be either pooled or closed when it is
   * either exhausted or closed. If it is unneeded when this is called, it will
   * be released immediately.
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
    if (bufferedRequestBody != null) {
      // This also closes the wrapped requestBodyOut.
      closeQuietly(bufferedRequestBody);
    } else if (requestBodyOut != null) {
      closeQuietly(requestBodyOut);
    }

    if (userResponse != null) {
      closeQuietly(userResponse.body());
    } else {
      // If this engine never achieved a response body, its stream allocation is dead.
      streamAllocation.connectionFailed();
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
   * Returns true if the response must have a (possibly 0-length) body.
   * See RFC 2616 section 4.3.
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
   * <p>This client doesn't specify a default {@code Accept} header because it
   * doesn't know what content types the application is interested in.
   */
  private Request networkRequest(Request request) throws IOException {
    Request.Builder result = request.newBuilder();

    if (request.header("Host") == null) {
      result.header("Host", Util.hostHeader(request.httpUrl()));
    }

    if (request.header("Connection") == null) {
      result.header("Connection", "Keep-Alive");
    }

    if (request.header("Accept-Encoding") == null) {
      transparentGzip = true;
      result.header("Accept-Encoding", "gzip");
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

    if (request.header("User-Agent") == null) {
      result.header("User-Agent", Version.userAgent());
    }

    return result.build();
  }

  /**
   * Flushes the remaining request header and body, parses the HTTP response
   * headers and starts reading the HTTP response body if it exists.
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

    } else if (!callerWritesRequestBody) {
      networkResponse = new NetworkInterceptorChain(0, networkRequest).proceed(networkRequest);

    } else {
      // Emit the request body's buffer so that everything is in requestBodyOut.
      if (bufferedRequestBody != null && bufferedRequestBody.buffer().size() > 0) {
        bufferedRequestBody.emit();
      }

      // Emit the request headers if we haven't yet. We might have just learned the Content-Length.
      if (sentRequestMillis == -1) {
        if (OkHeaders.contentLength(networkRequest) == -1
            && requestBodyOut instanceof RetryableSink) {
          long contentLength = ((RetryableSink) requestBodyOut).contentLength();
          networkRequest = networkRequest.newBuilder()
              .header("Content-Length", Long.toString(contentLength))
              .build();
        }
        httpStream.writeRequestHeaders(networkRequest);
      }

      // Write the request body to the socket.
      if (requestBodyOut != null) {
        if (bufferedRequestBody != null) {
          // This also closes the wrapped requestBodyOut.
          bufferedRequestBody.close();
        } else {
          requestBodyOut.close();
        }
        if (requestBodyOut instanceof RetryableSink) {
          httpStream.writeRequestBody((RetryableSink) requestBodyOut);
        }
      }

      networkResponse = readNetworkResponse();
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
        responseCache.update(cacheResponse, stripBody(userResponse));
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
    private int calls;

    NetworkInterceptorChain(int index, Request request) {
      this.index = index;
      this.request = request;
    }

    @Override public Connection connection() {
      return streamAllocation.connection();
    }

    @Override public Request request() {
      return request;
    }

    @Override public Response proceed(Request request) throws IOException {
      calls++;

      if (index > 0) {
        Interceptor caller = client.networkInterceptors().get(index - 1);
        Address address = connection().getRoute().getAddress();

        // Confirm that the interceptor uses the connection we've already prepared.
        if (!request.httpUrl().host().equals(address.getUriHost())
            || request.httpUrl().port() != address.getUriPort()) {
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
        NetworkInterceptorChain chain = new NetworkInterceptorChain(index + 1, request);
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
        .handshake(streamAllocation.connection().getHandshake())
        .header(OkHeaders.SENT_MILLIS, Long.toString(sentRequestMillis))
        .header(OkHeaders.RECEIVED_MILLIS, Long.toString(System.currentTimeMillis()))
        .build();

    if (!forWebSocket) {
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
            && !Util.discard(this, HttpStream.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
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
   * Returns true if {@code cached} should be used; false if {@code network}
   * response should be used.
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
   * Combines cached headers with a network headers as defined by RFC 2616,
   * 13.5.3.
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
        result.add(fieldName, value);
      }
    }

    for (int i = 0, size = networkHeaders.size(); i < size; i++) {
      String fieldName = networkHeaders.name(i);
      if ("Content-Length".equalsIgnoreCase(fieldName)) {
        continue; // Ignore content-length headers of validating responses.
      }
      if (OkHeaders.isEndToEnd(fieldName)) {
        result.add(fieldName, networkHeaders.value(i));
      }
    }

    return result.build();
  }

  public void receiveHeaders(Headers headers) throws IOException {
    CookieHandler cookieHandler = client.getCookieHandler();
    if (cookieHandler != null) {
      cookieHandler.put(userRequest.uri(), OkHeaders.toMultimap(headers, null));
    }
  }

  /**
   * Figures out the HTTP request to make in response to receiving this engine's
   * response. This will either add authentication headers or follow redirects.
   * If a follow-up is either unnecessary or not applicable, this returns null.
   */
  public Request followUpRequest() throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    Connection connection = streamAllocation.connection();
    Route route = connection != null
        ? connection.getRoute()
        : null;
    Proxy selectedProxy = route != null
        ? route.getProxy()
        : client.getProxy();
    int responseCode = userResponse.code();

    final String method = userRequest.method();
    switch (responseCode) {
      case HTTP_PROXY_AUTH:
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        // fall-through
      case HTTP_UNAUTHORIZED:
        return OkHeaders.processAuthHeader(client.getAuthenticator(), userResponse, selectedProxy);

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
        if (!client.getFollowRedirects()) return null;

        String location = userResponse.header("Location");
        if (location == null) return null;
        HttpUrl url = userRequest.httpUrl().resolve(location);

        // Don't follow redirects to unsupported protocols.
        if (url == null) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        boolean sameScheme = url.scheme().equals(userRequest.httpUrl().scheme());
        if (!sameScheme && !client.getFollowSslRedirects()) return null;

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

      default:
        return null;
    }
  }

  /**
   * Returns true if an HTTP request for {@code followUp} can reuse the
   * connection used by this engine.
   */
  public boolean sameConnection(HttpUrl followUp) {
    HttpUrl url = userRequest.httpUrl();
    return url.host().equals(followUp.host())
        && url.port() == followUp.port()
        && url.scheme().equals(followUp.scheme());
  }

  private static Address createAddress(OkHttpClient client, Request request) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (request.isHttps()) {
      sslSocketFactory = client.getSslSocketFactory();
      hostnameVerifier = client.getHostnameVerifier();
      certificatePinner = client.getCertificatePinner();
    }

    return new Address(request.httpUrl().host(), request.httpUrl().port(), client.getDns(),
        client.getSocketFactory(), sslSocketFactory, hostnameVerifier, certificatePinner,
        client.getAuthenticator(), client.getProxy(), client.getProtocols(),
        client.getConnectionSpecs(), client.getProxySelector());
  }
}
