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

import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.InternalCache;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.Transport;
import com.squareup.okhttp.internal.huc.AuthenticatorAdapter;
import com.squareup.okhttp.internal.huc.CacheAdapter;
import com.squareup.okhttp.internal.huc.HttpURLConnectionImpl;
import com.squareup.okhttp.internal.huc.HttpsURLConnectionImpl;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Configures and creates HTTP connections. Most applications can use a single
 * OkHttpClient for all of their HTTP requests - benefiting from a shared
 * response cache, thread pool, connection re-use, etc.
 *
 * <p>Instances of OkHttpClient are intended to be fully configured before they're
 * shared - once shared they should be treated as immutable and can safely be used
 * to concurrently open new connections. If required, threads can call
 * {@link #clone()} to make a shallow copy of the OkHttpClient that can be
 * safely modified with further configuration changes.
 */
public final class OkHttpClient implements URLStreamHandlerFactory, Cloneable {
  static {
    Internal.instance = new Internal() {
      @Override public Transport newTransport(
          Connection connection, HttpEngine httpEngine) throws IOException {
        return connection.newTransport(httpEngine);
      }

      @Override public boolean clearOwner(Connection connection) {
        return connection.clearOwner();
      }

      @Override public void closeIfOwnedBy(Connection connection, Object owner) throws IOException {
        connection.closeIfOwnedBy(owner);
      }

      @Override public int recycleCount(Connection connection) {
        return connection.recycleCount();
      }

      @Override public Object getOwner(Connection connection) {
        return connection.getOwner();
      }

      @Override public void setProtocol(Connection connection, Protocol protocol) {
        connection.setProtocol(protocol);
      }

      @Override public void setOwner(Connection connection, HttpEngine httpEngine) {
        connection.setOwner(httpEngine);
      }

      @Override public void connect(Connection connection, int connectTimeout, int readTimeout,
          int writeTimeout, Request request) throws IOException {
        connection.connect(connectTimeout, readTimeout, writeTimeout, request);
      }

      @Override public boolean isConnected(Connection connection) {
        return connection.isConnected();
      }

      @Override public boolean isSpdy(Connection connection) {
        return connection.isSpdy();
      }

      @Override public void setTimeouts(Connection connection, int readTimeout, int writeTimeout)
          throws IOException {
        connection.setTimeouts(readTimeout, writeTimeout);
      }

      @Override public boolean isReadable(Connection pooled) {
        return pooled.isReadable();
      }

      @Override public void addLine(Headers.Builder builder, String line) {
        builder.addLine(line);
      }

      @Override public void setResponseCache(OkHttpClient client, ResponseCache responseCache) {
        client.setResponseCache(responseCache);
      }

      @Override public InternalCache internalCache(OkHttpClient client) {
        return client.internalCache();
      }

      @Override public void recycle(ConnectionPool pool, Connection connection) {
        pool.recycle(connection);
      }

      @Override public void share(ConnectionPool connectionPool, Connection connection) {
        connectionPool.share(connection);
      }
    };
  }

  private final RouteDatabase routeDatabase;
  private Dispatcher dispatcher;
  private Proxy proxy;
  private List<Protocol> protocols;
  private ProxySelector proxySelector;
  private CookieHandler cookieHandler;

  // At least one of the two cache fields will be null.
  private Cache cache;
  private CacheAdapter cacheAdapter;

  private SocketFactory socketFactory;
  private SSLSocketFactory sslSocketFactory;
  private HostnameVerifier hostnameVerifier;
  private OkAuthenticator authenticator;
  private ConnectionPool connectionPool;
  private boolean followSslRedirects = true;
  private int connectTimeout;
  private int readTimeout;
  private int writeTimeout;

  public OkHttpClient() {
    routeDatabase = new RouteDatabase();
    dispatcher = new Dispatcher();
  }

  /**
   * Sets the default connect timeout for new connections. A value of 0 means no timeout.
   *
   * @see URLConnection#setConnectTimeout(int)
   */
  public OkHttpClient setConnectTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    connectTimeout = (int) millis;
    return this;
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
  public OkHttpClient setReadTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    readTimeout = (int) millis;
    return this;
  }

  /** Default read timeout (in milliseconds). */
  public int getReadTimeout() {
    return readTimeout;
  }

  /**
   * Sets the default write timeout for new connections. A value of 0 means no timeout.
   */
  public OkHttpClient setWriteTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    writeTimeout = (int) millis;
    return this;
  }

  /** Default write timeout (in milliseconds). */
  public int getWriteTimeout() {
    return writeTimeout;
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

  /** Sets the response cache to be used to read and write cached responses. */
  OkHttpClient setResponseCache(ResponseCache responseCache) {
    this.cacheAdapter = responseCache != null ? new CacheAdapter(responseCache) : null;
    this.cache = null;
    return this;
  }

  ResponseCache getResponseCache() {
    return cacheAdapter != null ? cacheAdapter.getDelegate() : null;
  }

  public OkHttpClient setCache(Cache cache) {
    this.cache = cache;
    this.cacheAdapter = null;
    return this;
  }

  public Cache getCache() {
    return cache;
  }

  InternalCache internalCache() {
    return cache != null ? cache.internalCache : cacheAdapter;
  }

  /**
   * Sets the socket factory used to create connections.
   *
   * <p>If unset, the {@link SocketFactory#getDefault() system-wide default}
   * socket factory will be used.
   */
  public OkHttpClient setSocketFactory(SocketFactory socketFactory) {
    this.socketFactory = socketFactory;
    return this;
  }

  public SocketFactory getSocketFactory() {
    return socketFactory;
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
  public OkHttpClient setFollowSslRedirects(boolean followProtocolRedirects) {
    this.followSslRedirects = followProtocolRedirects;
    return this;
  }

  public boolean getFollowSslRedirects() {
    return followSslRedirects;
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
   *   <li><a href="http://tools.ietf.org/html/draft-ietf-httpbis-http2-10">h2-10</a>
   * </ul>
   *
   * <p><strong>This is an evolving set.</strong> Future releases may drop
   * support for transitional protocols (like h2-10), in favor of their
   * successors (h2). The http/1.1 transport will never be dropped.
   *
   * <p>If multiple protocols are specified, <a
   * href="https://technotes.googlecode.com/git/nextprotoneg.html">NPN</a> or
   * <a href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a>
   * will be used to negotiate a transport.
   *
   * @param protocols the protocols to use, in order of preference. The list
   *     must contain {@link Protocol#HTTP_1_1}. It must not contain null.
   */
  public OkHttpClient setProtocols(List<Protocol> protocols) {
    protocols = Util.immutableList(protocols);
    if (!protocols.contains(Protocol.HTTP_1_1)) {
      throw new IllegalArgumentException("protocols doesn't contain http/1.1: " + protocols);
    }
    if (protocols.contains(null)) {
      throw new IllegalArgumentException("protocols must not contain null");
    }
    this.protocols = Util.immutableList(protocols);
    return this;
  }

  public List<Protocol> getProtocols() {
    return protocols;
  }

  /**
   * Prepares the {@code request} to be executed at some point in the future.
   */
  public Call newCall(Request request) {
    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    OkHttpClient client = copyWithDefaults();
    return new Call(client, dispatcher, request);
  }

  /**
   * Cancels all scheduled tasks tagged with {@code tag}. Requests that are already
   * complete cannot be canceled.
   */
  public OkHttpClient cancel(Object tag) {
    dispatcher.cancel(tag);
    return this;
  }

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
    if (result.cache == null && result.cacheAdapter == null) {
      // TODO: drop support for the default response cache.
      ResponseCache defaultCache = ResponseCache.getDefault();
      result.cacheAdapter = defaultCache != null ? new CacheAdapter(defaultCache) : null;
    }
    if (result.socketFactory == null) {
      result.socketFactory = SocketFactory.getDefault();
    }
    if (result.sslSocketFactory == null) {
      result.sslSocketFactory = getDefaultSSLSocketFactory();
    }
    if (result.hostnameVerifier == null) {
      result.hostnameVerifier = OkHostnameVerifier.INSTANCE;
    }
    if (result.authenticator == null) {
      result.authenticator = AuthenticatorAdapter.INSTANCE;
    }
    if (result.connectionPool == null) {
      result.connectionPool = ConnectionPool.getDefault();
    }
    if (result.protocols == null) {
      result.protocols = Util.immutableList(Protocol.HTTP_2, Protocol.SPDY_3, Protocol.HTTP_1_1);
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
