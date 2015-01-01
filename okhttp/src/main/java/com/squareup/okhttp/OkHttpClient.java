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
import com.squareup.okhttp.internal.Network;
import com.squareup.okhttp.internal.RouteDatabase;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.AuthenticatorAdapter;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.Transport;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
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
public class OkHttpClient implements Cloneable {
  private static final List<Protocol> DEFAULT_PROTOCOLS = Util.immutableList(
      Protocol.HTTP_2, Protocol.SPDY_3, Protocol.HTTP_1_1);

  private static final List<ConnectionSpec> DEFAULT_CONNECTION_SPECS = Util.immutableList(
      ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT);

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

      @Override public void setProtocol(Connection connection, Protocol protocol) {
        connection.setProtocol(protocol);
      }

      @Override public void setOwner(Connection connection, HttpEngine httpEngine) {
        connection.setOwner(httpEngine);
      }

      @Override public boolean isReadable(Connection pooled) {
        return pooled.isReadable();
      }

      @Override public void setCache(OkHttpClient client, InternalCache internalCache) {
        client.setInternalCache(internalCache);
      }

      @Override public InternalCache internalCache(OkHttpClient client) {
        return client.internalCache();
      }

      @Override public void recycle(ConnectionPool pool, Connection connection) {
        pool.recycle(connection);
      }

      @Override public RouteDatabase routeDatabase(OkHttpClient client) {
        return client.routeDatabase();
      }

      @Override public Network network(OkHttpClient client) {
        return client.network;
      }

      @Override public void setNetwork(OkHttpClient client, Network network) {
        client.network = network;
      }

      @Override public void connectAndSetOwner(OkHttpClient client, Connection connection,
          HttpEngine owner, Request request) throws IOException {
        connection.connectAndSetOwner(client, owner, request);
      }

      @Override
      public void callEnqueue(Call call, Callback responseCallback, boolean forWebSocket) {
        call.enqueue(responseCallback, forWebSocket);
      }

      @Override public void callEngineReleaseConnection(Call call) throws IOException {
        call.engine.releaseConnection();
      }

      @Override public Connection callEngineGetConnection(Call call) {
        return call.engine.getConnection();
      }

      @Override public void connectionSetOwner(Connection connection, Object owner) {
        connection.setOwner(owner);
      }
    };
  }

  /** Lazily-initialized. */
  private static SSLSocketFactory defaultSslSocketFactory;

  private final RouteDatabase routeDatabase;
  private Dispatcher dispatcher;
  private Proxy proxy;
  private List<Protocol> protocols;
  private List<ConnectionSpec> connectionSpecs;
  private final List<Interceptor> interceptors = new ArrayList<>();
  private final List<Interceptor> networkInterceptors = new ArrayList<>();
  private ProxySelector proxySelector;
  private CookieHandler cookieHandler;

  /** Non-null if this client is caching; possibly by {@code cache}. */
  private InternalCache internalCache;
  private Cache cache;

  private SocketFactory socketFactory;
  private SSLSocketFactory sslSocketFactory;
  private HostnameVerifier hostnameVerifier;
  private CertificatePinner certificatePinner;
  private Authenticator authenticator;
  private ConnectionPool connectionPool;
  private Network network;
  private boolean followSslRedirects = true;
  private boolean followRedirects = true;
  private boolean retryOnConnectionFailure = true;
  private int connectTimeout;
  private int readTimeout;
  private int writeTimeout;

  public OkHttpClient() {
    routeDatabase = new RouteDatabase();
    dispatcher = new Dispatcher();
  }

  private OkHttpClient(OkHttpClient okHttpClient) {
    this.routeDatabase = okHttpClient.routeDatabase;
    this.dispatcher = okHttpClient.dispatcher;
    this.proxy = okHttpClient.proxy;
    this.protocols = okHttpClient.protocols;
    this.connectionSpecs = okHttpClient.connectionSpecs;
    this.interceptors.addAll(okHttpClient.interceptors);
    this.networkInterceptors.addAll(okHttpClient.networkInterceptors);
    this.proxySelector = okHttpClient.proxySelector;
    this.cookieHandler = okHttpClient.cookieHandler;
    this.cache = okHttpClient.cache;
    this.internalCache = cache != null ? cache.internalCache : okHttpClient.internalCache;
    this.socketFactory = okHttpClient.socketFactory;
    this.sslSocketFactory = okHttpClient.sslSocketFactory;
    this.hostnameVerifier = okHttpClient.hostnameVerifier;
    this.certificatePinner = okHttpClient.certificatePinner;
    this.authenticator = okHttpClient.authenticator;
    this.connectionPool = okHttpClient.connectionPool;
    this.network = okHttpClient.network;
    this.followSslRedirects = okHttpClient.followSslRedirects;
    this.followRedirects = okHttpClient.followRedirects;
    this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure;
    this.connectTimeout = okHttpClient.connectTimeout;
    this.readTimeout = okHttpClient.readTimeout;
    this.writeTimeout = okHttpClient.writeTimeout;
  }

  /**
   * Sets the default connect timeout for new connections. A value of 0 means no timeout.
   *
   * @see URLConnection#setConnectTimeout(int)
   */
  public final void setConnectTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    connectTimeout = (int) millis;
  }

  /** Default connect timeout (in milliseconds). */
  public final int getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * Sets the default read timeout for new connections. A value of 0 means no timeout.
   *
   * @see URLConnection#setReadTimeout(int)
   */
  public final void setReadTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    readTimeout = (int) millis;
  }

  /** Default read timeout (in milliseconds). */
  public final int getReadTimeout() {
    return readTimeout;
  }

  /**
   * Sets the default write timeout for new connections. A value of 0 means no timeout.
   */
  public final void setWriteTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    writeTimeout = (int) millis;
  }

  /** Default write timeout (in milliseconds). */
  public final int getWriteTimeout() {
    return writeTimeout;
  }

  /**
   * Sets the HTTP proxy that will be used by connections created by this
   * client. This takes precedence over {@link #setProxySelector}, which is
   * only honored when this proxy is null (which it is by default). To disable
   * proxy use completely, call {@code setProxy(Proxy.NO_PROXY)}.
   */
  public final OkHttpClient setProxy(Proxy proxy) {
    this.proxy = proxy;
    return this;
  }

  public final Proxy getProxy() {
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
  public final OkHttpClient setProxySelector(ProxySelector proxySelector) {
    this.proxySelector = proxySelector;
    return this;
  }

  public final ProxySelector getProxySelector() {
    return proxySelector;
  }

  /**
   * Sets the cookie handler to be used to read outgoing cookies and write
   * incoming cookies.
   *
   * <p>If unset, the {@link CookieHandler#getDefault() system-wide default}
   * cookie handler will be used.
   */
  public final OkHttpClient setCookieHandler(CookieHandler cookieHandler) {
    this.cookieHandler = cookieHandler;
    return this;
  }

  public final CookieHandler getCookieHandler() {
    return cookieHandler;
  }

  /** Sets the response cache to be used to read and write cached responses. */
  final void setInternalCache(InternalCache internalCache) {
    this.internalCache = internalCache;
    this.cache = null;
  }

  final InternalCache internalCache() {
    return internalCache;
  }

  public final OkHttpClient setCache(Cache cache) {
    this.cache = cache;
    this.internalCache = null;
    return this;
  }

  public final Cache getCache() {
    return cache;
  }

  /**
   * Sets the socket factory used to create connections.
   *
   * <p>If unset, the {@link SocketFactory#getDefault() system-wide default}
   * socket factory will be used.
   */
  public final OkHttpClient setSocketFactory(SocketFactory socketFactory) {
    this.socketFactory = socketFactory;
    return this;
  }

  public final SocketFactory getSocketFactory() {
    return socketFactory;
  }

  /**
   * Sets the socket factory used to secure HTTPS connections.
   *
   * <p>If unset, a lazily created SSL socket factory will be used.
   */
  public final OkHttpClient setSslSocketFactory(SSLSocketFactory sslSocketFactory) {
    this.sslSocketFactory = sslSocketFactory;
    return this;
  }

  public final SSLSocketFactory getSslSocketFactory() {
    return sslSocketFactory;
  }

  /**
   * Sets the verifier used to confirm that response certificates apply to
   * requested hostnames for HTTPS connections.
   *
   * <p>If unset, a default hostname verifier will be used.
   */
  public final OkHttpClient setHostnameVerifier(HostnameVerifier hostnameVerifier) {
    this.hostnameVerifier = hostnameVerifier;
    return this;
  }

  public final HostnameVerifier getHostnameVerifier() {
    return hostnameVerifier;
  }

  /**
   * Sets the certificate pinner that constrains which certificates are trusted.
   * By default HTTPS connections rely on only the {@link #setSslSocketFactory
   * SSL socket factory} to establish trust. Pinning certificates avoids the
   * need to trust certificate authorities.
   */
  public final OkHttpClient setCertificatePinner(CertificatePinner certificatePinner) {
    this.certificatePinner = certificatePinner;
    return this;
  }

  public final CertificatePinner getCertificatePinner() {
    return certificatePinner;
  }

  /**
   * Sets the authenticator used to respond to challenges from the remote web
   * server or proxy server.
   *
   * <p>If unset, the {@link java.net.Authenticator#setDefault system-wide default}
   * authenticator will be used.
   */
  public final OkHttpClient setAuthenticator(Authenticator authenticator) {
    this.authenticator = authenticator;
    return this;
  }

  public final Authenticator getAuthenticator() {
    return authenticator;
  }

  /**
   * Sets the connection pool used to recycle HTTP and HTTPS connections.
   *
   * <p>If unset, the {@link ConnectionPool#getDefault() system-wide
   * default} connection pool will be used.
   */
  public final OkHttpClient setConnectionPool(ConnectionPool connectionPool) {
    this.connectionPool = connectionPool;
    return this;
  }

  public final ConnectionPool getConnectionPool() {
    return connectionPool;
  }

  /**
   * Configure this client to follow redirects from HTTPS to HTTP and from HTTP
   * to HTTPS.
   *
   * <p>If unset, protocol redirects will be followed. This is different than
   * the built-in {@code HttpURLConnection}'s default.
   */
  public final OkHttpClient setFollowSslRedirects(boolean followProtocolRedirects) {
    this.followSslRedirects = followProtocolRedirects;
    return this;
  }

  public final boolean getFollowSslRedirects() {
    return followSslRedirects;
  }

  /** Configure this client to follow redirects. If unset, redirects be followed. */
  public final void setFollowRedirects(boolean followRedirects) {
    this.followRedirects = followRedirects;
  }

  public final boolean getFollowRedirects() {
    return followRedirects;
  }

  /**
   * Configure this client to retry or not when a connectivity problem is encountered. By default,
   * this client silently recovers from the following problems:
   *
   * <ul>
   *   <li><strong>Unreachable IP addresses.</strong> If the URL's host has multiple IP addresses,
   *       failure to reach any individual IP address doesn't fail the overall request. This can
   *       increase availability of multi-homed services.
   *   <li><strong>Stale pooled connections.</strong> The {@link ConnectionPool} reuses sockets
   *       to decrease request latency, but these connections will occasionally time out.
   *   <li><strong>Unreachable proxy servers.</strong> A {@link ProxySelector} can be used to
   *       attempt multiple proxy servers in sequence, eventually falling back to a direct
   *       connection.
   * </ul>
   *
   * Set this to false to avoid retrying requests when doing so is destructive. In this case the
   * calling application should do its own recovery of connectivity failures.
   */
  public final void setRetryOnConnectionFailure(boolean retryOnConnectionFailure) {
    this.retryOnConnectionFailure = retryOnConnectionFailure;
  }

  public final boolean getRetryOnConnectionFailure() {
    return retryOnConnectionFailure;
  }

  final RouteDatabase routeDatabase() {
    return routeDatabase;
  }

  /**
   * Sets the dispatcher used to set policy and execute asynchronous requests.
   * Must not be null.
   */
  public final OkHttpClient setDispatcher(Dispatcher dispatcher) {
    if (dispatcher == null) throw new IllegalArgumentException("dispatcher == null");
    this.dispatcher = dispatcher;
    return this;
  }

  public final Dispatcher getDispatcher() {
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
   *   <li><a href="http://tools.ietf.org/html/draft-ietf-httpbis-http2-16">h2-16</a>
   * </ul>
   *
   * <p><strong>This is an evolving set.</strong> Future releases may drop
   * support for transitional protocols (like h2-16), in favor of their
   * successors (h2). The http/1.1 transport will never be dropped.
   *
   * <p>If multiple protocols are specified, <a
   * href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a>
   * will be used to negotiate a transport.
   *
   * <p>{@link Protocol#HTTP_1_0} is not supported in this set. Requests are
   * initiated with {@code HTTP/1.1} only. If the server responds with {@code
   * HTTP/1.0}, that will be exposed by {@link Response#protocol()}.
   *
   * @param protocols the protocols to use, in order of preference. The list
   *     must contain {@link Protocol#HTTP_1_1}. It must not contain null or
   *     {@link Protocol#HTTP_1_0}.
   */
  public final OkHttpClient setProtocols(List<Protocol> protocols) {
    protocols = Util.immutableList(protocols);
    if (!protocols.contains(Protocol.HTTP_1_1)) {
      throw new IllegalArgumentException("protocols doesn't contain http/1.1: " + protocols);
    }
    if (protocols.contains(Protocol.HTTP_1_0)) {
      throw new IllegalArgumentException("protocols must not contain http/1.0: " + protocols);
    }
    if (protocols.contains(null)) {
      throw new IllegalArgumentException("protocols must not contain null");
    }
    this.protocols = Util.immutableList(protocols);
    return this;
  }

  public final List<Protocol> getProtocols() {
    return protocols;
  }

  public final OkHttpClient setConnectionSpecs(List<ConnectionSpec> connectionSpecs) {
    this.connectionSpecs = Util.immutableList(connectionSpecs);
    return this;
  }

  public final List<ConnectionSpec> getConnectionSpecs() {
    return connectionSpecs;
  }

  /**
   * Returns a modifiable list of interceptors that observe the full span of each call: from before
   * the connection is established (if any) until after the response source is selected (either the
   * origin server, cache, or both).
   */
  public List<Interceptor> interceptors() {
    return interceptors;
  }

  /**
   * Returns a modifiable list of interceptors that observe a single network request and response.
   * These interceptors must call {@link Interceptor.Chain#proceed} exactly once: it is an error for
   * a network interceptor to short-circuit or repeat a network request.
   */
  public List<Interceptor> networkInterceptors() {
    return networkInterceptors;
  }

  /**
   * Prepares the {@code request} to be executed at some point in the future.
   */
  public Call newCall(Request request) {
    return new Call(this, request);
  }

  /**
   * Cancels all scheduled or in-flight calls tagged with {@code tag}. Requests
   * that are already complete cannot be canceled.
   */
  public OkHttpClient cancel(Object tag) {
    getDispatcher().cancel(tag);
    return this;
  }

  /**
   * Returns a shallow copy of this OkHttpClient that uses the system-wide
   * default for each field that hasn't been explicitly configured.
   */
  final OkHttpClient copyWithDefaults() {
    OkHttpClient result = new OkHttpClient(this);
    if (result.proxySelector == null) {
      result.proxySelector = ProxySelector.getDefault();
    }
    if (result.cookieHandler == null) {
      result.cookieHandler = CookieHandler.getDefault();
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
    if (result.certificatePinner == null) {
      result.certificatePinner = CertificatePinner.DEFAULT;
    }
    if (result.authenticator == null) {
      result.authenticator = AuthenticatorAdapter.INSTANCE;
    }
    if (result.connectionPool == null) {
      result.connectionPool = ConnectionPool.getDefault();
    }
    if (result.protocols == null) {
      result.protocols = DEFAULT_PROTOCOLS;
    }
    if (result.connectionSpecs == null) {
      result.connectionSpecs = DEFAULT_CONNECTION_SPECS;
    }
    if (result.network == null) {
      result.network = Network.DEFAULT;
    }
    return result;
  }

  /**
   * Java and Android programs default to using a single global SSL context,
   * accessible to HTTP clients as {@link SSLSocketFactory#getDefault()}. If we
   * used the shared SSL context, when OkHttp enables ALPN for its SPDY-related
   * stuff, it would also enable ALPN for other usages, which might crash them
   * because ALPN is enabled when it isn't expected to be.
   *
   * <p>This code avoids that by defaulting to an OkHttp-created SSL context.
   * The drawback of this approach is that apps that customize the global SSL
   * context will lose these customizations.
   */
  private synchronized SSLSocketFactory getDefaultSSLSocketFactory() {
    if (defaultSslSocketFactory == null) {
      try {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        defaultSslSocketFactory = sslContext.getSocketFactory();
      } catch (GeneralSecurityException e) {
        throw new AssertionError(); // The system has no TLS. Just give up.
      }
    }
    return defaultSslSocketFactory;
  }

  /** Returns a shallow copy of this OkHttpClient. */
  @Override public final OkHttpClient clone() {
    try {
      return (OkHttpClient) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError();
    }
  }
}
