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
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Handshake;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.ResponseSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.ResponseCache;
import java.net.SecureCacheResponse;
import java.net.URI;
import java.net.URL;
import java.security.Permission;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

/**
 * An adapter from {@link ResponseCache} to {@link com.squareup.okhttp.OkResponseCache}. This class
 * enables OkHttp to continue supporting Java standard response cache implementations.
 */
public class ResponseCacheAdapter implements OkResponseCache {

  private final ResponseCache delegate;

  public ResponseCacheAdapter(ResponseCache delegate) {
    this.delegate = delegate;
  }

  public ResponseCache getDelegate() {
    return delegate;
  }

  @Override
  public Response get(Request request) throws IOException {
    CacheResponse javaResponse = getJavaCachedResponse(request);
    if (javaResponse == null) {
      return null;
    }

    Response.Builder okResponseBuilder = new Response.Builder();

    // Request: Use the one provided.
    okResponseBuilder.request(request);

    // Status Line: Java has this as one of the headers.
    okResponseBuilder.statusLine(extractStatusLine(javaResponse));

    // Response headers
    Headers okHeaders = extractOkHeaders(javaResponse);
    okResponseBuilder.headers(okHeaders);

    // Meta data: Defaulted
    okResponseBuilder.setResponseSource(ResponseSource.CACHE);

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

  @Override
  public CacheRequest put(Response response) throws IOException {
    URI uri = response.request().uri();
    HttpURLConnection connection = createJavaUrlConnection(response);
    return delegate.put(uri, connection);
  }

  @Override
  public boolean maybeRemove(Request request) throws IOException {
    // This method is treated as optional and there is no obvious way of implementing it with
    // ResponseCache. Removing items from the cache due to modifications made from this client is
    // not essential given that modifications could be made from any other client. We have to assume
    // that it's ok to keep using the cached data. Otherwise the server shouldn't declare it as
    // cacheable or the client should be careful about caching it.
    return false;
  }

  @Override
  public void update(Response cached, Response network) throws IOException {
    // This method is treated as optional and there is no obvious way of implementing it with
    // ResponseCache. Updating headers is useful if the server changes the metadata for a resource
    // (e.g. max age) to extend or truncate the life of that resource in the cache. If the metadata
    // is not updated the caching behavior may not be optimal, but will obey the metadata sent
    // with the original cached response.
  }

  @Override
  public void trackConditionalCacheHit() {
    // This method is treated as optional.
  }

  @Override
  public void trackResponse(ResponseSource source) {
    // This method is treated as optional.
  }

  /**
   * Returns the {@link CacheResponse} from the delegate by converting the
   * OkHttp {@link Request} into the arguments required by the {@link ResponseCache}.
   */
  private CacheResponse getJavaCachedResponse(Request request) throws IOException {
    Map<String, List<String>> headers = extractJavaHeaders(request);
    return delegate.get(request.uri(), request.method(), headers);
  }

  /**
   * Creates an {@link HttpURLConnection} of the correct subclass from the supplied OkHttp response.
   */
  private static HttpURLConnection createJavaUrlConnection(Response okResponse) {
    Request request = okResponse.request();
    // Create an object of the correct class in case the ResponseCache uses instanceof.
    if (request.isHttps()) {
      return new CacheHttpsURLConnection(okResponse);
    } else {
      return new CacheHttpURLConnection(okResponse);
    }
  }

  /**
   * Extracts OkHttp headers from the supplied {@link CacheResponse}. Only real headers are
   * extracted. The status line entry (which has a null key) is discarded.
   * See {@link #extractStatusLine(java.net.CacheResponse)}.
   */
  private static Headers extractOkHeaders(CacheResponse javaResponse) throws IOException {
    Map<String, List<String>> cachedHeaders = javaResponse.getHeaders();
    Headers.Builder okHeadersBuilder = new Headers.Builder();
    for (Map.Entry<String, List<String>> cachedHeader : cachedHeaders.entrySet()) {
      String name = cachedHeader.getKey();
      if (name == null) {
        // The Java API uses the null key to store the status line.
        continue;
      }
      for (String value : cachedHeader.getValue()) {
        okHeadersBuilder.add(name, value);
      }
    }
    return okHeadersBuilder.build();
  }

  /**
   * Extracts the status line {@link CacheResponse} from the supplied Java API response. As per the
   * spec, the status line is held as the header with the null key. Returns {@code null} if there is
   * no status line.
   */
  private static String extractStatusLine(CacheResponse javaResponse) throws IOException {
    List<String> values = javaResponse.getHeaders().get(null);
    if (values == null || values.size() == 0) {
      return null;
    }
    return values.get(0);
  }

  /**
   * Extracts an immutable header map from the supplied {@link Headers}.
   */
  private static Map<String, List<String>> extractJavaHeaders(Request request) {
    return OkHeaders.toMultimap(request.headers(), null);
  }

  /**
   * Creates an OkHttp Response.Body containing the supplied information.
   */
  private static Response.Body createOkBody(final Headers okHeaders, final InputStream body) {
    return new Response.Body() {

      @Override
      public boolean ready() throws IOException {
        return true;
      }

      @Override
      public MediaType contentType() {
        String contentTypeHeader = okHeaders.get("Content-Type");
        return contentTypeHeader == null ? null : MediaType.parse(contentTypeHeader);
      }

      @Override
      public long contentLength() {
        return OkHeaders.contentLength(okHeaders);
      }

      @Override
      public InputStream byteStream() {
        return body;
      }
    };
  }

  /**
   * An {@link HttpURLConnection} that represents an HTTP request at the point where
   * the request has been made, and the response headers have been received, but the body content,
   * if present, has not been read yet. This intended to provide enough information for
   * {@link ResponseCache} subclasses and no more.
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
        return response.statusLine();
      }
      return response.headers().value(position - 1);
    }

    @Override
    public String getHeaderField(String fieldName) {
      return fieldName == null ? response.statusLine() : response.headers().get(fieldName);
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      return OkHeaders.toMultimap(response.headers(), response.statusLine());
    }

    @Override
    public int getResponseCode() throws IOException {
      return response.code();
    }

    @Override
    public String getResponseMessage() throws IOException {
      return response.statusMessage();
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

  /**
   * An HttpsURLConnection to offer to the cache. HttpsURLConnection is concrete; rather than
   * completely duplicate CacheHttpURLConnection all methods that can be are delegated to a
   * CacheHttpURLConnection instead. The intent is that all real logic (besides HTTPS-specific
   * calls) exists in CacheHttpURLConnection.
   */
  private static final class CacheHttpsURLConnection extends HttpsURLConnection {

    private final CacheHttpURLConnection delegate;
    private final Response response;

    public CacheHttpsURLConnection(Response response) {
      super(response.request().url());
      this.response = response;
      this.delegate = new CacheHttpURLConnection(response);
    }

    // HttpsURLConnection methods.

    @Override
    public String getCipherSuite() {
      if (response == null || response.handshake() == null) {
        return null;
      }
      return response.handshake().cipherSuite();
    }

    @Override
    public Certificate[] getLocalCertificates() {
      if (response == null || response.handshake() == null) {
        return null;
      }
      List<Certificate> localCertificates = response.handshake().localCertificates();
      if (localCertificates == null || localCertificates.size() == 0) {
        return null;
      }
      return localCertificates.toArray(new Certificate[localCertificates.size()]);
    }

    @Override
    public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
      if (response == null || response.handshake() == null) {
        return null;
      }
      List<Certificate> peerCertificates = response.handshake().peerCertificates();
      if (peerCertificates == null || peerCertificates.size() == 0) {
        return null;
      }
      return peerCertificates.toArray(new Certificate[peerCertificates.size()]);
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
      if (response == null || response.handshake() == null) {
        return null;
      }
      return response.handshake().peerPrincipal();
    }

    @Override
    public Principal getLocalPrincipal() {
      if (response == null || response.handshake() == null) {
        return null;
      }
      return response.handshake().localPrincipal();
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
      throw throwRequestModificationException();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
      throw throwRequestSslAccessException();
    }

    @Override
    public void setSSLSocketFactory(SSLSocketFactory socketFactory) {
      throw throwRequestModificationException();
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory() {
      throw throwRequestSslAccessException();
    }

    // Delegated methods.

    @Override
    public void connect() throws IOException {
      delegate.connect();
    }

    @Override
    public void disconnect() {
      delegate.disconnect();
    }

    @Override
    public void setRequestProperty(String key, String value) {
      delegate.setRequestProperty(key, value);
    }

    @Override
    public void addRequestProperty(String key, String value) {
      delegate.addRequestProperty(key, value);
    }

    @Override
    public String getRequestProperty(String key) {
      return delegate.getRequestProperty(key);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
      return delegate.getRequestProperties();
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
      delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override
    public void setFixedLengthStreamingMode(long contentLength) {
      delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override
    public void setChunkedStreamingMode(int chunkLength) {
      delegate.setChunkedStreamingMode(chunkLength);
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
      delegate.setInstanceFollowRedirects(followRedirects);
    }

    @Override
    public boolean getInstanceFollowRedirects() {
      return delegate.getInstanceFollowRedirects();
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
      delegate.setRequestMethod(method);
    }

    @Override
    public String getRequestMethod() {
      return delegate.getRequestMethod();
    }

    @Override
    public String getHeaderFieldKey(int position) {
      return delegate.getHeaderFieldKey(position);
    }

    @Override
    public String getHeaderField(int position) {
      return delegate.getHeaderField(position);
    }

    @Override
    public String getHeaderField(String fieldName) {
      return delegate.getHeaderField(fieldName);
    }

    @Override
    public int getResponseCode() throws IOException {
      return delegate.getResponseCode();
    }

    @Override
    public String getResponseMessage() throws IOException {
      return delegate.getResponseMessage();
    }

    @Override
    public InputStream getErrorStream() {
      return delegate.getErrorStream();
    }

    @Override
    public boolean usingProxy() {
      return delegate.usingProxy();
    }

    @Override
    public void setConnectTimeout(int timeout) {
      delegate.setConnectTimeout(timeout);
    }

    @Override
    public int getConnectTimeout() {
      return delegate.getConnectTimeout();
    }

    @Override
    public void setReadTimeout(int timeout) {
      delegate.setReadTimeout(timeout);
    }

    @Override
    public int getReadTimeout() {
      return delegate.getReadTimeout();
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      return delegate.getHeaderFields();
    }

    @Override
    public Object getContent() throws IOException {
      return delegate.getContent();
    }

    @Override
    public Object getContent(Class[] classes) throws IOException {
      return delegate.getContent(classes);
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return delegate.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      return delegate.getOutputStream();
    }

    @Override
    public void setDoInput(boolean doInput) {
      delegate.setDoInput(doInput);
    }

    @Override
    public boolean getDoInput() {
      return delegate.getDoInput();
    }

    @Override
    public void setDoOutput(boolean doOutput) {
      delegate.setDoOutput(doOutput);
    }

    @Override
    public boolean getDoOutput() {
      return delegate.getDoOutput();
    }

    @Override
    public void setAllowUserInteraction(boolean allowUserInteraction) {
      delegate.setAllowUserInteraction(allowUserInteraction);
    }

    @Override
    public boolean getAllowUserInteraction() {
      return delegate.getAllowUserInteraction();
    }

    @Override
    public void setUseCaches(boolean useCaches) {
      delegate.setUseCaches(useCaches);
    }

    @Override
    public boolean getUseCaches() {
      return delegate.getUseCaches();
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
      delegate.setIfModifiedSince(ifModifiedSince);
    }

    @Override
    public long getIfModifiedSince() {
      return delegate.getIfModifiedSince();
    }

    @Override
    public boolean getDefaultUseCaches() {
      return delegate.getDefaultUseCaches();
    }

    @Override
    public void setDefaultUseCaches(boolean defaultUseCaches) {
      delegate.setDefaultUseCaches(defaultUseCaches);
    }

    @Override
    public long getHeaderFieldDate(String name, long defaultValue) {
      return delegate.getHeaderFieldDate(name, defaultValue);
    }

    @Override
    public Permission getPermission() throws IOException {
      return delegate.getPermission();
    }

    @Override
    public URL getURL() {
      return delegate.getURL();
    }

    @Override
    public int getContentLength() {
      return delegate.getContentLength();
    }

    @Override
    public long getContentLengthLong() {
      return delegate.getContentLengthLong();
    }

    @Override
    public String getContentType() {
      return delegate.getContentType();
    }

    @Override
    public String getContentEncoding() {
      return delegate.getContentEncoding();
    }

    @Override
    public long getExpiration() {
      return delegate.getExpiration();
    }

    @Override
    public long getDate() {
      return delegate.getDate();
    }

    @Override
    public long getLastModified() {
      return delegate.getLastModified();
    }

    @Override
    public int getHeaderFieldInt(String name, int defaultValue) {
      return delegate.getHeaderFieldInt(name, defaultValue);
    }

    @Override
    public long getHeaderFieldLong(String name, long defaultValue) {
      return delegate.getHeaderFieldLong(name, defaultValue);
    }

    @Override
    public String toString() {
      return delegate.toString();
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
}
