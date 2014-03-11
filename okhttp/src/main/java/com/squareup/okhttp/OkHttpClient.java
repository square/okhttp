/*
 * Copyright (C) 2012 Square, Inc.
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HttpAuthenticator;
import com.squareup.okhttp.internal.http.HttpURLConnectionImpl;
import com.squareup.okhttp.internal.http.HttpsURLConnectionImpl;
import com.squareup.okhttp.internal.http.ResponseCacheAdapter;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import okio.ByteString;

/**
 * Configures and creates HTTP connections. Designed to be treated as a singleton - by
 * using a single instance you are afforded a shared response cache, thread pool, connection
 * re-use, etc.
 */
public final class OkHttpClient implements URLStreamHandlerFactory, Cloneable {

  private final RouteDatabase routeDatabase;
  private Dispatcher dispatcher;
  private Proxy proxy;
  private List<Protocol> protocols;
  private ProxySelector proxySelector;
  private CookieHandler cookieHandler;
  private OkResponseCache responseCache;
  private SSLSocketFactory sslSocketFactory;
  private HostnameVerifier hostnameVerifier;
  private OkAuthenticator authenticator;
  private ConnectionPool connectionPool;
  private boolean followProtocolRedirects = true;
  private int connectTimeout;
  private int readTimeout;

  public OkHttpClient() {
    routeDatabase = new RouteDatabase();
    dispatcher = new Dispatcher();
  }

  /**
   * Sets the default connect timeout for new connections. A value of 0 means no timeout.
   *
   * @see URLConnection#setConnectTimeout(int)
   */
  public void setConnectTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) {
      throw new IllegalArgumentException("timeout < 0");
    }
    if (unit == null) {
      throw new IllegalArgumentException("unit == null");
    }
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Timeout too large.");
    }
    connectTimeout = (int) millis;
  }

  /** Default connect timeout (in milliseconds). */
  public int getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * Sets the default read timeout for new connections. A value of 0 means no timeout.
   *
   * @see URLConnection#setReadTimeout(int)
   */
  public void setReadTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) {
      throw new IllegalArgumentException("timeout < 0");
    }
    if (unit == null) {
      throw new IllegalArgumentException("unit == null");
    }
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Timeout too large.");
    }
    readTimeout = (int) millis;
  }

  /** Default read timeout (in milliseconds). */
  public int getReadTimeout() {
    return readTimeout;
  }

  /**
   * Sets the HTTP proxy that will be used by connections created by this
   * client. This takes precedence over {@link #setProxySelector}, which is
   * only honored when this proxy is null (which it is by default). To disable
   * proxy use completely, call {@code setProxy(Proxy.NO_PROXY)}.
   */
  public OkHttpClient setProxy(Proxy proxy) {
    this.proxy = proxy;
    return this;
  }

  public Proxy getProxy() {
    return proxy;
  }

  /**
   * Sets the proxy selection policy to be used if no {@link #setProxy proxy}
   * is specified explicitly. The proxy selector may return multiple proxies;
   * in that case they will be tried in sequence until a successful connection
   * is established.
   *
   * <p>If unset, the {@link ProxySelector#getDefault() system-wide default}
   * proxy selector will be used.
   */
  public OkHttpClient setProxySelector(ProxySelector proxySelector) {
    this.proxySelector = proxySelector;
    return this;
  }

  public ProxySelector getProxySelector() {
    return proxySelector;
  }

  /**
   * Sets the cookie handler to be used to read outgoing cookies and write
   * incoming cookies.
   *
   * <p>If unset, the {@link CookieHandler#getDefault() system-wide default}
   * cookie handler will be used.
   */
  public OkHttpClient setCookieHandler(CookieHandler cookieHandler) {
    this.cookieHandler = cookieHandler;
    return this;
  }

  public CookieHandler getCookieHandler() {
    return cookieHandler;
  }

  /**
   * Sets the response cache to be used to read and write cached responses.
   */
  public OkHttpClient setResponseCache(ResponseCache responseCache) {
    return setOkResponseCache(toOkResponseCache(responseCache));
  }

  public ResponseCache getResponseCache() {
    return responseCache instanceof ResponseCacheAdapter
        ? ((ResponseCacheAdapter) responseCache).getDelegate()
        : null;
  }

  public OkHttpClient setOkResponseCache(OkResponseCache responseCache) {
    this.responseCache = responseCache;
    return this;
  }

  public OkResponseCache getOkResponseCache() {
    return responseCache;
  }

  /**
   * Sets the socket factory used to secure HTTPS connections.
   *
   * <p>If unset, a lazily created SSL socket factory will be used.
   */
  public OkHttpClient setSslSocketFactory(SSLSocketFactory sslSocketFactory) {
    this.sslSocketFactory = sslSocketFactory;
    return this;
  }

  public SSLSocketFactory getSslSocketFactory() {
    return sslSocketFactory;
  }

  /**
   * Sets the verifier used to confirm that response certificates apply to
   * requested hostnames for HTTPS connections.
   *
   * <p>If unset, the
   * {@link javax.net.ssl.HttpsURLConnection#getDefaultHostnameVerifier()
   * system-wide default} hostname verifier will be used.
   */
  public OkHttpClient setHostnameVerifier(HostnameVerifier hostnameVerifier) {
    this.hostnameVerifier = hostnameVerifier;
    return this;
  }

  public HostnameVerifier getHostnameVerifier() {
    return hostnameVerifier;
  }

  /**
   * Sets the authenticator used to respond to challenges from the remote web
   * server or proxy server.
   *
   * <p>If unset, the {@link java.net.Authenticator#setDefault system-wide default}
   * authenticator will be used.
   */
  public OkHttpClient setAuthenticator(OkAuthenticator authenticator) {
    this.authenticator = authenticator;
    return this;
  }

  public OkAuthenticator getAuthenticator() {
    return authenticator;
  }

  /**
   * Sets the connection pool used to recycle HTTP and HTTPS connections.
   *
   * <p>If unset, the {@link ConnectionPool#getDefault() system-wide
   * default} connection pool will be used.
   */
  public OkHttpClient setConnectionPool(ConnectionPool connectionPool) {
    this.connectionPool = connectionPool;
    return this;
  }

  public ConnectionPool getConnectionPool() {
    return connectionPool;
  }

  /**
   * Configure this client to follow redirects from HTTPS to HTTP and from HTTP
   * to HTTPS.
   *
   * <p>If unset, protocol redirects will be followed. This is different than
   * the built-in {@code HttpURLConnection}'s default.
   */
  public OkHttpClient setFollowProtocolRedirects(boolean followProtocolRedirects) {
    this.followProtocolRedirects = followProtocolRedirects;
    return this;
  }

  public boolean getFollowProtocolRedirects() {
    return followProtocolRedirects;
  }

  public RouteDatabase getRoutesDatabase() {
    return routeDatabase;
  }

  /**
   * Sets the dispatcher used to set policy and execute asynchronous requests.
   * Must not be null.
   */
  public OkHttpClient setDispatcher(Dispatcher dispatcher) {
    if (dispatcher == null) throw new IllegalArgumentException("dispatcher == null");
    this.dispatcher = dispatcher;
    return this;
  }

  public Dispatcher getDispatcher() {
    return dispatcher;
  }

  /**
   * @deprecated OkHttp 1.5 enforces an enumeration of {@link Protocol
   *     protocols} that can be selected. Please switch to {@link
   *     #setProtocols(java.util.List)}.
   */
  @Deprecated
  public OkHttpClient setTransports(List<String> transports) {
    List<Protocol> protocols = new ArrayList<Protocol>(transports.size());
    for (int i = 0, size = transports.size(); i < size; i++) {
      try {
        Protocol protocol = Protocol.find(ByteString.encodeUtf8(transports.get(i)));
        protocols.add(protocol);
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    }
    return setProtocols(protocols);
  }

  /**
   * Configure the protocols used by this client to communicate with remote
   * servers. By default this client will prefer the most efficient transport
   * available, falling back to more ubiquitous protocols. Applications should
   * only call this method to avoid specific compatibility problems, such as web
   * servers that behave incorrectly when SPDY is enabled.
   *
   * <p>The following protocols are currently supported:
   * <ul>
   *   <li><a href="http://www.w3.org/Protocols/rfc2616/rfc2616.html">http/1.1</a>
   *   <li><a href="http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3-1">spdy/3.1</a>
   *   <li><a href="http://tools.ietf.org/html/draft-ietf-httpbis-http2-09">HTTP-draft-09/2.0</a>
   * </ul>
   *
   * <p><strong>This is an evolving set.</strong> Future releases may drop
   * support for transitional protocols (like spdy/3.1), in favor of their
   * successors (spdy/4 or http/2.0). The http/1.1 transport will never be
   * dropped.
   *
   * <p>If multiple protocols are specified, <a
   * href="https://technotes.googlecode.com/git/nextprotoneg.html">NPN</a> will
   * be used to negotiate a transport. Future releases may use another mechanism
   * (such as <a href="http://tools.ietf.org/html/draft-friedl-tls-applayerprotoneg-02">ALPN</a>)
   * to negotiate a transport.
   *
   * @param protocols the protocols to use, in order of preference. The list
   *     must contain "http/1.1". It must not contain null.
   */
  public OkHttpClient setProtocols(List<Protocol> protocols) {
    protocols = Util.immutableList(protocols);
    if (!protocols.contains(Protocol.HTTP_11)) {
      throw new IllegalArgumentException("protocols doesn't contain http/1.1: " + protocols);
    }
    if (protocols.contains(null)) {
      throw new IllegalArgumentException("protocols must not contain null");
    }
    this.protocols = Util.immutableList(protocols);
    return this;
  }

  /**
   * @deprecated OkHttp 1.5 enforces an enumeration of {@link Protocol
   *     protocols} that can be selected. Please switch to {@link
   *     #getProtocols()}.
   */
  @Deprecated
  public List<String> getTransports() {
    List<String> transports = new ArrayList<String>(protocols.size());
    for (int i = 0, size = protocols.size(); i < size; i++) {
      transports.add(protocols.get(i).name.utf8());
    }
    return transports;
  }

  public List<Protocol> getProtocols() {
    return protocols;
  }

  /**
   * Invokes {@code request} immediately, and blocks until the response can be
   * processed or is in error.
   *
   * <p>The caller may read the response body with the response's
   * {@link Response#body} method.  To facilitate connection recycling, callers
   * should always {@link Response.Body#close() close the response body}.
   *
   * <p>Note that transport-layer success (receiving a HTTP response code,
   * headers and body) does not necessarily indicate application-layer
   * success: {@code response} may still indicate an unhappy HTTP response
   * code like 404 or 500.
   *
   * <h3>Non-blocking responses</h3>
   *
   * <p>Receivers do not need to block while waiting for the response body to
   * download. Instead, they can get called back as data arrives. Use {@link
   * Response.Body#ready} to check if bytes should be read immediately. While
   * there is data ready, read it.
   *
   * <p>The current implementation of {@link Response.Body#ready} always
   * returns true when the underlying transport is HTTP/1. This results in
   * blocking on that transport. For effective non-blocking your server must
   * support {@link Protocol#SPDY_3} or {@link Protocol#HTTP_2}.
   *
   * @throws IOException when the request could not be executed due to a
   * connectivity problem or timeout. Because networks can fail during an
   * exchange, it is possible that the remote server accepted the request
   * before the failure.
   */
  public Response execute(Request request) throws IOException {
    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    OkHttpClient client = copyWithDefaults();
    Job job = new Job(dispatcher, client, request, null);
    Response result = job.getResponse(); // Since we don't cancel, this won't be null.
    job.engine.releaseConnection(); // Transfer ownership of the body to the caller.
    return result;
  }

  /**
   * Schedules {@code request} to be executed at some point in the future. The
   * {@link #getDispatcher dispatcher} defines when the request will run:
   * usually immediately unless there are several other requests currently being
   * executed.
   *
   * <p>This client will later call back {@code responseReceiver} with either an
   * HTTP response or a failure exception. If you {@link #cancel} a request
   * before it completes the receiver will not be called back.
   */
  public void enqueue(Request request, Response.Receiver responseReceiver) {
    dispatcher.enqueue(this, request, responseReceiver);
  }

  /**
   * Cancels all scheduled tasks tagged with {@code tag}. Requests that are already
   * complete cannot be canceled.
   */
  public void cancel(Object tag) {
    dispatcher.cancel(tag);
  }

  /**
   * This method is thread-safe.
   */
  public HttpURLConnection open(URL url) {
    return open(url, proxy);
  }

  HttpURLConnection open(URL url, Proxy proxy) {
    String protocol = url.getProtocol();
    OkHttpClient copy = copyWithDefaults();
    copy.proxy = proxy;

    if (protocol.equals("http")) return new HttpURLConnectionImpl(url, copy);
    if (protocol.equals("https")) return new HttpsURLConnectionImpl(url, copy);
    throw new IllegalArgumentException("Unexpected protocol: " + protocol);
  }

  /**
   * Returns a shallow copy of this OkHttpClient that uses the system-wide
   * default for each field that hasn't been explicitly configured.
   */
  OkHttpClient copyWithDefaults() {
    OkHttpClient result = clone();
    if (result.proxySelector == null) {
      result.proxySelector = ProxySelector.getDefault();
    }
    if (result.cookieHandler == null) {
      result.cookieHandler = CookieHandler.getDefault();
    }
    if (result.responseCache == null) {
      result.responseCache = toOkResponseCache(ResponseCache.getDefault());
    }
    if (result.sslSocketFactory == null) {
      result.sslSocketFactory = getDefaultSSLSocketFactory();
    }
    if (result.hostnameVerifier == null) {
      result.hostnameVerifier = OkHostnameVerifier.INSTANCE;
    }
    if (result.authenticator == null) {
      result.authenticator = HttpAuthenticator.SYSTEM_DEFAULT;
    }
    if (result.connectionPool == null) {
      result.connectionPool = ConnectionPool.getDefault();
    }
    if (result.protocols == null) {
      result.protocols = Protocol.HTTP2_SPDY3_AND_HTTP;
    }
    return result;
  }

  /**
   * Java and Android programs default to using a single global SSL context,
   * accessible to HTTP clients as {@link SSLSocketFactory#getDefault()}. If we
   * used the shared SSL context, when OkHttp enables NPN for its SPDY-related
   * stuff, it would also enable NPN for other usages, which might crash them
   * because NPN is enabled when it isn't expected to be.
   * <p>
   * This code avoids that by defaulting to an OkHttp created SSL context. The
   * significant drawback of this approach is that apps that customize the
   * global SSL context will lose these customizations.
   */
  private synchronized SSLSocketFactory getDefaultSSLSocketFactory() {
    if (sslSocketFactory == null) {
      try {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        sslSocketFactory = sslContext.getSocketFactory();
      } catch (GeneralSecurityException e) {
        throw new AssertionError(); // The system has no TLS. Just give up.
      }
    }
    return sslSocketFactory;
  }

  /** Returns a shallow copy of this OkHttpClient. */
  @Override public OkHttpClient clone() {
    try {
      return (OkHttpClient) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError();
    }
  }

  private OkResponseCache toOkResponseCache(ResponseCache responseCache) {
    return responseCache == null || responseCache instanceof OkResponseCache
        ? (OkResponseCache) responseCache
        : new ResponseCacheAdapter(responseCache);
  }

  /**
   * Creates a URLStreamHandler as a {@link URL#setURLStreamHandlerFactory}.
   *
   * <p>This code configures OkHttp to handle all HTTP and HTTPS connections
   * created with {@link URL#openConnection()}: <pre>   {@code
   *
   *   OkHttpClient okHttpClient = new OkHttpClient();
   *   URL.setURLStreamHandlerFactory(okHttpClient);
   * }</pre>
   */
  public URLStreamHandler createURLStreamHandler(final String protocol) {
    if (!protocol.equals("http") && !protocol.equals("https")) return null;

    return new URLStreamHandler() {
      @Override protected URLConnection openConnection(URL url) {
        return open(url);
      }

      @Override protected URLConnection openConnection(URL url, Proxy proxy) {
        return open(url, proxy);
      }

      @Override protected int getDefaultPort() {
        if (protocol.equals("http")) return 80;
        if (protocol.equals("https")) return 443;
        throw new AssertionError();
      }
    };
  }
}
