/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal.huc;

import com.squareup.okhttp.Handshake;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.internal.http.StatusLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SecureCacheResponse;
import java.net.URI;
import java.net.URLConnection;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import okio.BufferedSource;
import okio.Okio;

/**
 * Helper methods that convert between Java and OkHttp representations.
 */
public final class JavaApiConverter {

  private JavaApiConverter() {
  }

  /**
   * Creates an OkHttp {@link Response} using the supplied {@link URI} and {@link URLConnection}
   * to supply the data. The URLConnection is assumed to already be connected.
   */
  public static Response createOkResponse(URI uri, URLConnection urlConnection) throws IOException {
    HttpURLConnection httpUrlConnection = (HttpURLConnection) urlConnection;

    Response.Builder okResponseBuilder = new Response.Builder();

    // Request: Create one from the URL connection.
    // A connected HttpURLConnection does not permit access to request headers.
    Map<String, List<String>> requestHeaders = null;
    Request okRequest = createOkRequest(uri, httpUrlConnection.getRequestMethod(), requestHeaders);
    okResponseBuilder.request(okRequest);

    // Status line
    StatusLine statusLine = StatusLine.parse(extractStatusLine(httpUrlConnection));
    okResponseBuilder.protocol(statusLine.protocol);
    okResponseBuilder.code(statusLine.code);
    okResponseBuilder.message(statusLine.message);

    // Response headers
    Headers okHeaders = extractOkResponseHeaders(httpUrlConnection);
    okResponseBuilder.headers(okHeaders);

    // Meta data: Defaulted
    okResponseBuilder.responseSource(ResponseSource.NETWORK);

    // Response body
    Response.Body okBody = createOkBody(okHeaders, urlConnection.getInputStream());
    okResponseBuilder.body(okBody);

    // Handle SSL handshake information as needed.
    if (httpUrlConnection instanceof HttpsURLConnection) {
      HttpsURLConnection httpsUrlConnection = (HttpsURLConnection) httpUrlConnection;

      Certificate[] peerCertificates;
      try {
        peerCertificates = httpsUrlConnection.getServerCertificates();
      } catch (SSLPeerUnverifiedException e) {
        peerCertificates = null;
      }

      Certificate[] localCertificates = httpsUrlConnection.getLocalCertificates();

      Handshake handshake = Handshake.get(
          httpsUrlConnection.getCipherSuite(), nullSafeImmutableList(peerCertificates),
          nullSafeImmutableList(localCertificates));
      okResponseBuilder.handshake(handshake);
    }

    return okResponseBuilder.build();
  }

  /**
   * Creates an OkHttp {@link Response} using the supplied {@link Request} and {@link CacheResponse}
   * to supply the data.
   */
  static Response createOkResponse(Request request, CacheResponse javaResponse)
      throws IOException {
    Response.Builder okResponseBuilder = new Response.Builder();

    // Request: Use the one provided.
    okResponseBuilder.request(request);

    // Status line: Java has this as one of the headers.
    StatusLine statusLine = StatusLine.parse(extractStatusLine(javaResponse));
    okResponseBuilder.protocol(statusLine.protocol);
    okResponseBuilder.code(statusLine.code);
    okResponseBuilder.message(statusLine.message);

    // Response headers
    Headers okHeaders = extractOkHeaders(javaResponse);
    okResponseBuilder.headers(okHeaders);

    // Meta data: Defaulted
    okResponseBuilder.responseSource(ResponseSource.CACHE);

    // Response body
    Response.Body okBody = createOkBody(okHeaders, javaResponse.getBody());
    okResponseBuilder.body(okBody);

    // Handle SSL handshake information as needed.
    if (javaResponse instanceof SecureCacheResponse) {
      SecureCacheResponse javaSecureCacheResponse = (SecureCacheResponse) javaResponse;

      // Handshake doesn't support null lists.
      List<Certificate> peerCertificates;
      try {
        peerCertificates = javaSecureCacheResponse.getServerCertificateChain();
      } catch (SSLPeerUnverifiedException e) {
        peerCertificates = Collections.emptyList();
      }
      List<Certificate> localCertificates = javaSecureCacheResponse.getLocalCertificateChain();
      if (localCertificates == null) {
        localCertificates = Collections.emptyList();
      }
      Handshake handshake = Handshake.get(
          javaSecureCacheResponse.getCipherSuite(), peerCertificates, localCertificates);
      okResponseBuilder.handshake(handshake);
    }

    return okResponseBuilder.build();
  }

  /**
   * Creates an OkHttp {@link Request} from the supplied information.
   *
   * <p>This method allows a {@code null} value for {@code requestHeaders} for situations
   * where a connection is already connected and access to the headers has been lost.
   * See {@link java.net.HttpURLConnection#getRequestProperties()} for details.
   */
  public static Request createOkRequest(
      URI uri, String requestMethod, Map<String, List<String>> requestHeaders) {

    Request.Builder builder = new Request.Builder()
        .url(uri.toString())
        .method(requestMethod, null);

    if (requestHeaders != null) {
      Headers headers = extractOkHeaders(requestHeaders);
      builder.headers(headers);
    }
    return builder.build();
  }

  /**
   * Creates a {@link java.net.CacheResponse} of the correct (sub)type using information
   * gathered from the supplied {@link Response}.
   */
  public static CacheResponse createJavaCacheResponse(final Response response) {
    final Headers headers = response.headers();
    final Response.Body body = response.body();
    if (response.request().isHttps()) {
      final Handshake handshake = response.handshake();
      return new SecureCacheResponse() {
        @Override
        public String getCipherSuite() {
          return handshake != null ? handshake.cipherSuite() : null;
        }

        @Override
        public List<Certificate> getLocalCertificateChain() {
          if (handshake == null) return null;
          // Java requires null, not an empty list here.
          List<Certificate> certificates = handshake.localCertificates();
          return certificates.size() > 0 ? certificates : null;
        }

        @Override
        public List<Certificate> getServerCertificateChain() throws SSLPeerUnverifiedException {
          if (handshake == null) return null;
          // Java requires null, not an empty list here.
          List<Certificate> certificates = handshake.peerCertificates();
          return certificates.size() > 0 ? certificates : null;
        }

        @Override
        public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
          if (handshake == null) return null;
          return handshake.peerPrincipal();
        }

        @Override
        public Principal getLocalPrincipal() {
          if (handshake == null) return null;
          return handshake.localPrincipal();
        }

        @Override
        public Map<String, List<String>> getHeaders() throws IOException {
          // Java requires that the entry with a null key be the status line.
          return OkHeaders.toMultimap(headers, StatusLine.get(response).toString());
        }

        @Override
        public InputStream getBody() throws IOException {
          if (body == null) return null;
          return body.byteStream();
        }
      };
    } else {
      return new CacheResponse() {
        @Override
        public Map<String, List<String>> getHeaders() throws IOException {
          // Java requires that the entry with a null key be the status line.
          return OkHeaders.toMultimap(headers, StatusLine.get(response).toString());
        }

        @Override
        public InputStream getBody() throws IOException {
          if (body == null) return null;
          return body.byteStream();
        }
      };
    }
  }

  /**
   * Creates an {@link java.net.HttpURLConnection} of the correct subclass from the supplied OkHttp
   * {@link Response}.
   */
  static HttpURLConnection createJavaUrlConnection(Response okResponse) {
    Request request = okResponse.request();
    // Create an object of the correct class in case the ResponseCache uses instanceof.
    if (request.isHttps()) {
      return new CacheHttpsURLConnection(new CacheHttpURLConnection(okResponse));
    } else {
      return new CacheHttpURLConnection(okResponse);
    }
  }

  /**
   * Extracts an immutable request header map from the supplied {@link com.squareup.okhttp.Headers}.
   */
  static Map<String, List<String>> extractJavaHeaders(Request request) {
    return OkHeaders.toMultimap(request.headers(), null);
  }

  /**
   * Extracts OkHttp headers from the supplied {@link java.net.CacheResponse}. Only real headers are
   * extracted. See {@link #extractStatusLine(java.net.CacheResponse)}.
   */
  private static Headers extractOkHeaders(CacheResponse javaResponse) throws IOException {
    Map<String, List<String>> javaResponseHeaders = javaResponse.getHeaders();
    return extractOkHeaders(javaResponseHeaders);
  }

  /**
   * Extracts OkHttp headers from the supplied {@link java.net.HttpURLConnection}. Only real headers
   * are extracted. See {@link #extractStatusLine(java.net.HttpURLConnection)}.
   */
  private static Headers extractOkResponseHeaders(HttpURLConnection httpUrlConnection) {
    Map<String, List<String>> javaResponseHeaders = httpUrlConnection.getHeaderFields();
    return extractOkHeaders(javaResponseHeaders);
  }

  /**
   * Extracts OkHttp headers from the supplied {@link Map}. Only real headers are
   * extracted. Any entry (one with a {@code null} key) is discarded.
   */
  // @VisibleForTesting
  static Headers extractOkHeaders(Map<String, List<String>> javaHeaders) {
    Headers.Builder okHeadersBuilder = new Headers.Builder();
    for (Map.Entry<String, List<String>> javaHeader : javaHeaders.entrySet()) {
      String name = javaHeader.getKey();
      if (name == null) {
        // The Java API uses the null key to store the status line in responses.
        // Earlier versions of OkHttp would use the null key to store the "request line" in
        // requests. e.g. "GET / HTTP 1.1". Although this is no longer the case it must be
        // explicitly ignored because Headers.Builder does not support null keys.
        continue;
      }
      for (String value : javaHeader.getValue()) {
        okHeadersBuilder.add(name, value);
      }
    }
    return okHeadersBuilder.build();
  }

  /**
   * Extracts the status line from the supplied Java API {@link java.net.HttpURLConnection}.
   * As per the spec, the status line is held as the header with the null key. Returns {@code null}
   * if there is no status line.
   */
  private static String extractStatusLine(HttpURLConnection httpUrlConnection) {
    // Java specifies that this will be be response header with a null key.
    return httpUrlConnection.getHeaderField(null);
  }

  /**
   * Extracts the status line from the supplied Java API {@link java.net.CacheResponse}.
   * As per the spec, the status line is held as the header with the null key. Returns {@code null}
   * if there is no status line.
   */
  private static String extractStatusLine(CacheResponse javaResponse) throws IOException {
    Map<String, List<String>> javaResponseHeaders = javaResponse.getHeaders();
    return extractStatusLine(javaResponseHeaders);
  }

  // VisibleForTesting
  static String extractStatusLine(Map<String, List<String>> javaResponseHeaders) {
    List<String> values = javaResponseHeaders.get(null);
    if (values == null || values.size() == 0) {
      return null;
    }
    return values.get(0);
  }

  /**
   * Creates an OkHttp Response.Body containing the supplied information.
   */
  private static Response.Body createOkBody(final Headers okHeaders, InputStream body) {
    final BufferedSource source = Okio.buffer(Okio.source(body));
    return new Response.Body() {
      @Override public MediaType contentType() {
        String contentTypeHeader = okHeaders.get("Content-Type");
        return contentTypeHeader == null ? null : MediaType.parse(contentTypeHeader);
      }
      @Override public long contentLength() {
        return OkHeaders.contentLength(okHeaders);
      }
      @Override public BufferedSource source() {
        return source;
      }
    };
  }

  /**
   * An {@link java.net.HttpURLConnection} that represents an HTTP request at the point where
   * the request has been made, and the response headers have been received, but the body content,
   * if present, has not been read yet. This intended to provide enough information for
   * {@link java.net.ResponseCache} subclasses and no more.
   *
   * <p>Much of the method implementations are overrides to delegate to the OkHttp request and
   * response, or to deny access to information as a real HttpURLConnection would after connection.
   */
  private static final class CacheHttpURLConnection extends HttpURLConnection {

    private final Request request;
    private final Response response;

    public CacheHttpURLConnection(Response response) {
      super(response.request().url());
      this.request = response.request();
      this.response = response;

      // Configure URLConnection inherited fields.
      this.connected = true;
      this.doOutput = response.body() == null;

      // Configure HttpUrlConnection inherited fields.
      this.method = request.method();
    }

    // HTTP connection lifecycle methods

    @Override
    public void connect() throws IOException {
      throw throwRequestModificationException();
    }

    @Override
    public void disconnect() {
      throw throwRequestModificationException();
    }

    // HTTP Request methods

    @Override
    public void setRequestProperty(String key, String value) {
      throw throwRequestModificationException();
    }

    @Override
    public void addRequestProperty(String key, String value) {
      throw throwRequestModificationException();
    }

    @Override
    public String getRequestProperty(String key) {
      return request.header(key);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
      // This is to preserve RI and compatibility with OkHttp's HttpURLConnectionImpl. There seems
      // no good reason why this should fail while getRequestProperty() is ok.
      throw throwRequestHeaderAccessException();
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
      throw throwRequestModificationException();
    }

    @Override
    public void setFixedLengthStreamingMode(long contentLength) {
      throw throwRequestModificationException();
    }

    @Override
    public void setChunkedStreamingMode(int chunklen) {
      throw throwRequestModificationException();
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
      throw throwRequestModificationException();
    }

    @Override
    public boolean getInstanceFollowRedirects() {
      // Return the platform default.
      return super.getInstanceFollowRedirects();
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
      throw throwRequestModificationException();
    }

    @Override
    public String getRequestMethod() {
      return request.method();
    }

    // HTTP Response methods

    @Override
    public String getHeaderFieldKey(int position) {
      // Deal with index 0 meaning "status line"
      if (position < 0) {
        throw new IllegalArgumentException("Invalid header index: " + position);
      }
      if (position == 0) {
        return null;
      }
      return response.headers().name(position - 1);
    }

    @Override
    public String getHeaderField(int position) {
      // Deal with index 0 meaning "status line"
      if (position < 0) {
        throw new IllegalArgumentException("Invalid header index: " + position);
      }
      if (position == 0) {
        return StatusLine.get(response).toString();
      }
      return response.headers().value(position - 1);
    }

    @Override
    public String getHeaderField(String fieldName) {
      return fieldName == null
          ? StatusLine.get(response).toString()
          : response.headers().get(fieldName);
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      return OkHeaders.toMultimap(response.headers(), StatusLine.get(response).toString());
    }

    @Override
    public int getResponseCode() throws IOException {
      return response.code();
    }

    @Override
    public String getResponseMessage() throws IOException {
      return response.message();
    }

    @Override
    public InputStream getErrorStream() {
      return null;
    }

    // HTTP miscellaneous methods

    @Override
    public boolean usingProxy() {
      // It's safe to return false here, even if a proxy is in use. The problem is we don't
      // necessarily know if we're going to use a proxy by the time we ask the cache for a response.
      return false;
    }

    // URLConnection methods

    @Override
    public void setConnectTimeout(int timeout) {
      throw throwRequestModificationException();
    }

    @Override
    public int getConnectTimeout() {
      // Impossible to say.
      return 0;
    }

    @Override
    public void setReadTimeout(int timeout) {
      throw throwRequestModificationException();
    }

    @Override
    public int getReadTimeout() {
      // Impossible to say.
      return 0;
    }

    @Override
    public Object getContent() throws IOException {
      throw throwResponseBodyAccessException();
    }

    @Override
    public Object getContent(Class[] classes) throws IOException {
      throw throwResponseBodyAccessException();
    }

    @Override
    public InputStream getInputStream() throws IOException {
      throw throwResponseBodyAccessException();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      throw throwRequestModificationException();
    }

    @Override
    public void setDoInput(boolean doInput) {
      throw throwRequestModificationException();
    }

    @Override
    public boolean getDoInput() {
      return true;
    }

    @Override
    public void setDoOutput(boolean doOutput) {
      throw throwRequestModificationException();
    }

    @Override
    public boolean getDoOutput() {
      return request.body() != null;
    }

    @Override
    public void setAllowUserInteraction(boolean allowUserInteraction) {
      throw throwRequestModificationException();
    }

    @Override
    public boolean getAllowUserInteraction() {
      return false;
    }

    @Override
    public void setUseCaches(boolean useCaches) {
      throw throwRequestModificationException();
    }

    @Override
    public boolean getUseCaches() {
      return super.getUseCaches();
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
      throw throwRequestModificationException();
    }

    @Override
    public long getIfModifiedSince() {
      return 0;
    }

    @Override
    public boolean getDefaultUseCaches() {
      return super.getDefaultUseCaches();
    }

    @Override
    public void setDefaultUseCaches(boolean defaultUseCaches) {
      super.setDefaultUseCaches(defaultUseCaches);
    }
  }

  /** An HttpsURLConnection to offer to the cache. */
  private static final class CacheHttpsURLConnection extends DelegatingHttpsURLConnection {
    private final CacheHttpURLConnection delegate;

    public CacheHttpsURLConnection(CacheHttpURLConnection delegate) {
      super(delegate);
      this.delegate = delegate;
    }

    @Override protected Handshake handshake() {
      return delegate.response.handshake();
    }

    @Override public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
      throw throwRequestModificationException();
    }

    @Override public HostnameVerifier getHostnameVerifier() {
      throw throwRequestSslAccessException();
    }

    @Override public void setSSLSocketFactory(SSLSocketFactory socketFactory) {
      throw throwRequestModificationException();
    }

    @Override public SSLSocketFactory getSSLSocketFactory() {
      throw throwRequestSslAccessException();
    }

    @Override public long getContentLengthLong() {
      return delegate.getContentLengthLong();
    }

    @Override public void setFixedLengthStreamingMode(long contentLength) {
      delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override public long getHeaderFieldLong(String field, long defaultValue) {
      return delegate.getHeaderFieldLong(field, defaultValue);
    }
  }

  private static RuntimeException throwRequestModificationException() {
    throw new UnsupportedOperationException("ResponseCache cannot modify the request.");
  }

  private static RuntimeException throwRequestHeaderAccessException() {
    throw new UnsupportedOperationException("ResponseCache cannot access request headers");
  }

  private static RuntimeException throwRequestSslAccessException() {
    throw new UnsupportedOperationException("ResponseCache cannot access SSL internals");
  }

  private static RuntimeException throwResponseBodyAccessException() {
    throw new UnsupportedOperationException("ResponseCache cannot access the response body.");
  }

  private static <T> List<T> nullSafeImmutableList(T[] elements) {
    return elements == null ? Collections.<T>emptyList() : Util.immutableList(elements);
  }

}
