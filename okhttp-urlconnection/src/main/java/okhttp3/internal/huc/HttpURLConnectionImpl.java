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

package okhttp3.internal.huc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketPermission;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.JavaNetHeaders;
import okhttp3.internal.Platform;
import okhttp3.internal.URLFilter;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.http.HttpDate;
import okhttp3.internal.http.HttpEngine;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.OkHeaders;
import okhttp3.internal.http.RequestException;
import okhttp3.internal.http.RetryableSink;
import okhttp3.internal.http.RouteException;
import okhttp3.internal.http.StatusLine;
import okhttp3.internal.http.StreamAllocation;
import okio.BufferedSink;
import okio.Sink;

/**
 * This implementation uses HttpEngine to send requests and receive responses. This class may use
 * multiple HttpEngines to follow redirects, authentication retries, etc. to retrieve the final
 * response body.
 *
 * <h3>What does 'connected' mean?</h3> This class inherits a {@code connected} field from the
 * superclass. That field is <strong>not</strong> used to indicate whether this URLConnection is
 * currently connected. Instead, it indicates whether a connection has ever been attempted. Once a
 * connection has been attempted, certain properties (request header fields, request method, etc.)
 * are immutable.
 */
public class HttpURLConnectionImpl extends HttpURLConnection {
  private static final Set<String> METHODS = new LinkedHashSet<>(
      Arrays.asList("OPTIONS", "GET", "HEAD", "POST", "PUT", "DELETE", "TRACE", "PATCH"));
  private static final RequestBody EMPTY_REQUEST_BODY = RequestBody.create(null, new byte[0]);

  OkHttpClient client;

  private Headers.Builder requestHeaders = new Headers.Builder();

  /** Like the superclass field of the same name, but a long and available on all platforms. */
  private long fixedContentLength = -1;
  private int followUpCount;
  protected IOException httpEngineFailure;
  protected HttpEngine httpEngine;
  /** Lazily created (with synthetic headers) on first call to getHeaders(). */
  private Headers responseHeaders;

  /**
   * The most recently attempted route. This will be null if we haven't sent a request yet, or if
   * the response comes from a cache.
   */
  private Route route;

  /**
   * The most recently received TLS handshake. This will be null if we haven't connected yet, or if
   * the most recent connection was HTTP (and not HTTPS).
   */
  Handshake handshake;

  private URLFilter urlFilter;

  public HttpURLConnectionImpl(URL url, OkHttpClient client) {
    super(url);
    this.client = client;
  }

  public HttpURLConnectionImpl(URL url, OkHttpClient client, URLFilter urlFilter) {
    this(url, client);
    this.urlFilter = urlFilter;
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
    if (httpEngine == null) return;

    httpEngine.cancel();

    // This doesn't close the stream because doing so would require all stream
    // access to be synchronized. It's expected that the thread using the
    // connection will close its streams directly. If it doesn't, the worst
    // case is that the GzipSource's Inflater won't be released until it's
    // finalized. (This logs a warning on Android.)
  }

  /**
   * Returns an input stream from the server in the case of error such as the requested file (txt,
   * htm, html) is not found on the remote server.
   */
  @Override public final InputStream getErrorStream() {
    try {
      HttpEngine response = getResponse();
      if (HttpEngine.hasBody(response.getResponse())
          && response.getResponse().code() >= HTTP_BAD_REQUEST) {
        return response.getResponse().body().byteStream();
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  private Headers getHeaders() throws IOException {
    if (responseHeaders == null) {
      Response response = getResponse().getResponse();
      Headers headers = response.headers();
      responseHeaders = headers.newBuilder()
          .add(OkHeaders.SELECTED_PROTOCOL, response.protocol().toString())
          .add(OkHeaders.RESPONSE_SOURCE, responseSourceHeader(response))
          .build();
    }
    return responseHeaders;
  }

  private static String responseSourceHeader(Response response) {
    if (response.networkResponse() == null) {
      if (response.cacheResponse() == null) {
        return "NONE";
      }
      return "CACHE " + response.code();
    }
    if (response.cacheResponse() == null) {
      return "NETWORK " + response.code();
    }
    return "CONDITIONAL_CACHE " + response.networkResponse().code();
  }

  /**
   * Returns the value of the field at {@code position}. Returns null if there are fewer than {@code
   * position} headers.
   */
  @Override public final String getHeaderField(int position) {
    try {
      Headers headers = getHeaders();
      if (position < 0 || position >= headers.size()) return null;
      return headers.value(position);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Returns the value of the field corresponding to the {@code fieldName}, or null if there is no
   * such field. If the field has multiple values, the last value is returned.
   */
  @Override public final String getHeaderField(String fieldName) {
    try {
      return fieldName == null
          ? StatusLine.get(getResponse().getResponse()).toString()
          : getHeaders().get(fieldName);
    } catch (IOException e) {
      return null;
    }
  }

  @Override public final String getHeaderFieldKey(int position) {
    try {
      Headers headers = getHeaders();
      if (position < 0 || position >= headers.size()) return null;
      return headers.name(position);
    } catch (IOException e) {
      return null;
    }
  }

  @Override public final Map<String, List<String>> getHeaderFields() {
    try {
      return JavaNetHeaders.toMultimap(getHeaders(),
          StatusLine.get(getResponse().getResponse()).toString());
    } catch (IOException e) {
      return Collections.emptyMap();
    }
  }

  @Override public final Map<String, List<String>> getRequestProperties() {
    if (connected) {
      throw new IllegalStateException(
          "Cannot access request header fields after connection is set");
    }

    return JavaNetHeaders.toMultimap(requestHeaders.build(), null);
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

    return response.getResponse().body().byteStream();
  }

  @Override public final OutputStream getOutputStream() throws IOException {
    connect();

    BufferedSink sink = httpEngine.getBufferedRequestBody();
    if (sink == null) {
      throw new ProtocolException("method does not support a request body: " + method);
    } else if (httpEngine.hasResponse()) {
      throw new ProtocolException("cannot write request body after response has been read");
    }

    return sink.outputStream();
  }

  @Override public final Permission getPermission() throws IOException {
    URL url = getURL();
    String hostname = url.getHost();
    int hostPort = url.getPort() != -1
        ? url.getPort()
        : HttpUrl.defaultPort(url.getProtocol());
    if (usingProxy()) {
      InetSocketAddress proxyAddress = (InetSocketAddress) client.proxy().address();
      hostname = proxyAddress.getHostName();
      hostPort = proxyAddress.getPort();
    }
    return new SocketPermission(hostname + ":" + hostPort, "connect, resolve");
  }

  @Override public final String getRequestProperty(String field) {
    if (field == null) return null;
    return requestHeaders.get(field);
  }

  @Override public void setConnectTimeout(int timeoutMillis) {
    client = client.newBuilder()
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build();
  }

  @Override
  public void setInstanceFollowRedirects(boolean followRedirects) {
    client = client.newBuilder()
        .followRedirects(followRedirects)
        .build();
  }

  @Override public boolean getInstanceFollowRedirects() {
    return client.followRedirects();
  }

  @Override public int getConnectTimeout() {
    return client.connectTimeoutMillis();
  }

  @Override public void setReadTimeout(int timeoutMillis) {
    client = client.newBuilder()
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build();
  }

  @Override public int getReadTimeout() {
    return client.readTimeoutMillis();
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
        } else if (!HttpMethod.permitsRequestBody(method)) {
          throw new ProtocolException(method + " does not support writing");
        }
      }
      // If the user set content length to zero, we know there will not be a request body.
      httpEngine = newHttpEngine(method, null, null, null);
    } catch (IOException e) {
      httpEngineFailure = e;
      throw e;
    }
  }

  private HttpEngine newHttpEngine(String method, StreamAllocation streamAllocation,
      RetryableSink requestBody, Response priorResponse)
      throws MalformedURLException, UnknownHostException {
    // OkHttp's Call API requires a placeholder body; the real body will be streamed separately.
    RequestBody placeholderBody = HttpMethod.requiresRequestBody(method)
        ? EMPTY_REQUEST_BODY
        : null;
    URL url = getURL();
    HttpUrl httpUrl = Internal.instance.getHttpUrlChecked(url.toString());
    Request.Builder builder = new Request.Builder()
        .url(httpUrl)
        .method(method, placeholderBody);
    Headers headers = requestHeaders.build();
    for (int i = 0, size = headers.size(); i < size; i++) {
      builder.addHeader(headers.name(i), headers.value(i));
    }

    boolean bufferRequestBody = false;
    if (HttpMethod.permitsRequestBody(method)) {
      // Specify how the request body is terminated.
      if (fixedContentLength != -1) {
        builder.header("Content-Length", Long.toString(fixedContentLength));
      } else if (chunkLength > 0) {
        builder.header("Transfer-Encoding", "chunked");
      } else {
        bufferRequestBody = true;
      }

      // Add a content type for the request body, if one isn't already present.
      if (headers.get("Content-Type") == null) {
        builder.header("Content-Type", "application/x-www-form-urlencoded");
      }
    }

    if (headers.get("User-Agent") == null) {
      builder.header("User-Agent", defaultUserAgent());
    }

    Request request = builder.build();

    // If we're currently not using caches, make sure the engine's client doesn't have one.
    OkHttpClient engineClient = client;
    if (Internal.instance.internalCache(engineClient) != null && !getUseCaches()) {
      engineClient = client.newBuilder()
          .cache(null)
          .build();
    }

    return new HttpEngine(engineClient, request, bufferRequestBody, true, false, streamAllocation,
        requestBody, priorResponse);
  }

  private String defaultUserAgent() {
    String agent = System.getProperty("http.agent");
    return agent != null ? Util.toHumanReadableAscii(agent) : Version.userAgent();
  }

  /**
   * Aggressively tries to get the final HTTP response, potentially making many HTTP requests in the
   * process in order to cope with redirects and authentication.
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

      Response response = httpEngine.getResponse();
      Request followUp = httpEngine.followUpRequest();

      if (followUp == null) {
        httpEngine.releaseStreamAllocation();
        return httpEngine;
      }

      if (++followUpCount > HttpEngine.MAX_FOLLOW_UPS) {
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      // The first request was insufficient. Prepare for another...
      url = followUp.url().url();
      requestHeaders = followUp.headers().newBuilder();

      // Although RFC 2616 10.3.2 specifies that a HTTP_MOVED_PERM redirect
      // should keep the same method, Chrome, Firefox and the RI all issue GETs
      // when following any redirect.
      Sink requestBody = httpEngine.getRequestBody();
      if (!followUp.method().equals(method)) {
        requestBody = null;
      }

      if (requestBody != null && !(requestBody instanceof RetryableSink)) {
        throw new HttpRetryException("Cannot retry streamed HTTP body", responseCode);
      }

      StreamAllocation streamAllocation = httpEngine.close();
      if (!httpEngine.sameConnection(followUp.url())) {
        streamAllocation.release();
        streamAllocation = null;
      }

      httpEngine = newHttpEngine(followUp.method(), streamAllocation, (RetryableSink) requestBody,
          response);
    }
  }

  /**
   * Sends a request and optionally reads a response. Returns true if the request was successfully
   * executed, and false if the request can be retried. Throws an exception if the request failed
   * permanently.
   */
  private boolean execute(boolean readResponse) throws IOException {
    boolean releaseConnection = true;
    if (urlFilter != null) {
      urlFilter.checkURLPermitted(httpEngine.getRequest().url().url());
    }
    try {
      httpEngine.sendRequest();
      Connection connection = httpEngine.getConnection();
      if (connection != null) {
        route = connection.route();
        handshake = connection.handshake();
      } else {
        route = null;
        handshake = null;
      }
      if (readResponse) {
        httpEngine.readResponse();
      }
      releaseConnection = false;

      return true;
    } catch (RequestException e) {
      // An attempt to interpret a request failed.
      IOException toThrow = e.getCause();
      httpEngineFailure = toThrow;
      throw toThrow;
    } catch (RouteException e) {
      // The attempt to connect via a route failed. The request will not have been sent.
      HttpEngine retryEngine = httpEngine.recover(e.getLastConnectException(), true);
      if (retryEngine != null) {
        releaseConnection = false;
        httpEngine = retryEngine;
        return false;
      }

      // Give up; recovery is not possible.
      IOException toThrow = e.getLastConnectException();
      httpEngineFailure = toThrow;
      throw toThrow;
    } catch (IOException e) {
      // An attempt to communicate with a server failed. The request may have been sent.
      HttpEngine retryEngine = httpEngine.recover(e, false);
      if (retryEngine != null) {
        releaseConnection = false;
        httpEngine = retryEngine;
        return false;
      }

      // Give up; recovery is not possible.
      httpEngineFailure = e;
      throw e;
    } finally {
      // We're throwing an unchecked exception. Release any resources.
      if (releaseConnection) {
        StreamAllocation streamAllocation = httpEngine.close();
        streamAllocation.release();
      }
    }
  }

  /**
   * Returns true if either:
   *
   * <ul>
   *   <li>A specific proxy was explicitly configured for this connection.
   *   <li>The response has already been retrieved, and a proxy was {@link
   *       java.net.ProxySelector selected} in order to get it.
   * </ul>
   *
   * <p><strong>Warning:</strong> This method may return false before attempting to connect and true
   * afterwards.
   */
  @Override public final boolean usingProxy() {
    Proxy proxy = route != null
        ? route.proxy()
        : client.proxy();
    return proxy != null && proxy.type() != Proxy.Type.DIRECT;
  }

  @Override public String getResponseMessage() throws IOException {
    return getResponse().getResponse().message();
  }

  @Override public final int getResponseCode() throws IOException {
    return getResponse().getResponse().code();
  }

  @Override public final void setRequestProperty(String field, String newValue) {
    if (connected) {
      throw new IllegalStateException("Cannot set request property after connection is made");
    }
    if (field == null) {
      throw new NullPointerException("field == null");
    }
    if (newValue == null) {
      // Silently ignore null header values for backwards compatibility with older
      // android versions as well as with other URLConnection implementations.
      //
      // Some implementations send a malformed HTTP header when faced with
      // such requests, we respect the spec and ignore the header.
      Platform.get().logW("Ignoring header " + field + " because its value was null.");
      return;
    }

    // TODO: Deprecate use of X-Android-Transports header?
    if ("X-Android-Transports".equals(field) || "X-Android-Protocols".equals(field)) {
      setProtocols(newValue, false /* append */);
    } else {
      requestHeaders.set(field, newValue);
    }
  }

  @Override public void setIfModifiedSince(long newValue) {
    super.setIfModifiedSince(newValue);
    if (ifModifiedSince != 0) {
      requestHeaders.set("If-Modified-Since", HttpDate.format(new Date(ifModifiedSince)));
    } else {
      requestHeaders.removeAll("If-Modified-Since");
    }
  }

  @Override public final void addRequestProperty(String field, String value) {
    if (connected) {
      throw new IllegalStateException("Cannot add request property after connection is made");
    }
    if (field == null) {
      throw new NullPointerException("field == null");
    }
    if (value == null) {
      // Silently ignore null header values for backwards compatibility with older
      // android versions as well as with other URLConnection implementations.
      //
      // Some implementations send a malformed HTTP header when faced with
      // such requests, we respect the spec and ignore the header.
      Platform.get().logW("Ignoring header " + field + " because its value was null.");
      return;
    }

    // TODO: Deprecate use of X-Android-Transports header?
    if ("X-Android-Transports".equals(field) || "X-Android-Protocols".equals(field)) {
      setProtocols(value, true /* append */);
    } else {
      requestHeaders.add(field, value);
    }
  }

  /*
   * Splits and validates a comma-separated string of protocols.
   * When append == false, we require that the transport list contains "http/1.1".
   * Throws {@link IllegalStateException} when one of the protocols isn't
   * defined in {@link Protocol OkHttp's protocol enumeration}.
   */
  private void setProtocols(String protocolsString, boolean append) {
    List<Protocol> protocolsList = new ArrayList<>();
    if (append) {
      protocolsList.addAll(client.protocols());
    }
    for (String protocol : protocolsString.split(",", -1)) {
      try {
        protocolsList.add(Protocol.get(protocol));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    client = client.newBuilder()
        .protocols(protocolsList)
        .build();
  }

  @Override public void setRequestMethod(String method) throws ProtocolException {
    if (!METHODS.contains(method)) {
      throw new ProtocolException("Expected one of " + METHODS + " but was " + method);
    }
    this.method = method;
  }

  @Override public void setFixedLengthStreamingMode(int contentLength) {
    setFixedLengthStreamingMode((long) contentLength);
  }

  @Override public void setFixedLengthStreamingMode(long contentLength) {
    if (super.connected) throw new IllegalStateException("Already connected");
    if (chunkLength > 0) throw new IllegalStateException("Already in chunked mode");
    if (contentLength < 0) throw new IllegalArgumentException("contentLength < 0");
    this.fixedContentLength = contentLength;
    super.fixedContentLength = (int) Math.min(contentLength, Integer.MAX_VALUE);
  }
}
