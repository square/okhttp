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
package okhttp3;

import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.internal.Internal;
import okhttp3.internal.InternalCache;
import okhttp3.internal.RouteDatabase;
import okhttp3.internal.Util;
import okhttp3.internal.http.AuthenticatorAdapter;
import okhttp3.internal.http.StreamAllocation;
import okhttp3.internal.io.RealConnection;
import okhttp3.internal.tls.OkHostnameVerifier;

/**
 * Configures and creates HTTP connections. Most applications can use a single OkHttpClient for all
 * of their HTTP requests - benefiting from a shared response cache, thread pool, connection re-use,
 * etc.
 *
 * <p>Instances of OkHttpClient are intended to be fully configured before they're shared - once
 * shared they should be treated as immutable and can safely be used to concurrently open new
 * connections. If required, threads can call {@link #clone()} to make a shallow copy of the
 * OkHttpClient that can be safely modified with further configuration changes.
 */
public class OkHttpClient implements Cloneable, Call.Factory {
  private static final List<Protocol> DEFAULT_PROTOCOLS = Util.immutableList(
      Protocol.HTTP_2, Protocol.SPDY_3, Protocol.HTTP_1_1);

  private static final List<ConnectionSpec> DEFAULT_CONNECTION_SPECS = Util.immutableList(
      ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT);

  static {
    Internal.instance = new Internal() {
      @Override public void addLenient(Headers.Builder builder, String line) {
        builder.addLenient(line);
      }

      @Override public void addLenient(Headers.Builder builder, String name, String value) {
        builder.addLenient(name, value);
      }

      @Override public void setCache(OkHttpClient client, InternalCache internalCache) {
        client.setInternalCache(internalCache);
      }

      @Override public InternalCache internalCache(OkHttpClient client) {
        return client.internalCache();
      }

      @Override public boolean connectionBecameIdle(
          ConnectionPool pool, RealConnection connection) {
        return pool.connectionBecameIdle(connection);
      }

      @Override public RealConnection get(
          ConnectionPool pool, Address address, StreamAllocation streamAllocation) {
        return pool.get(address, streamAllocation);
      }

      @Override public void put(ConnectionPool pool, RealConnection connection) {
        pool.put(connection);
      }

      @Override public RouteDatabase routeDatabase(ConnectionPool connectionPool) {
        return connectionPool.routeDatabase;
      }

      @Override
      public void callEnqueue(Call call, Callback responseCallback, boolean forWebSocket) {
        ((RealCall) call).enqueue(responseCallback, forWebSocket);
      }

      @Override public StreamAllocation callEngineGetStreamAllocation(Call call) {
        return ((RealCall) call).engine.streamAllocation;
      }

      @Override
      public void apply(ConnectionSpec tlsConfiguration, SSLSocket sslSocket, boolean isFallback) {
        tlsConfiguration.apply(sslSocket, isFallback);
      }

      @Override public HttpUrl getHttpUrlChecked(String url)
          throws MalformedURLException, UnknownHostException {
        return HttpUrl.getChecked(url);
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
  private CookieJar cookieJar;

  /** Non-null if this client is caching; possibly by {@code cache}. */
  private InternalCache internalCache;
  private Cache cache;

  private SocketFactory socketFactory;
  private SSLSocketFactory sslSocketFactory;
  private HostnameVerifier hostnameVerifier;
  private CertificatePinner certificatePinner;
  private Authenticator proxyAuthenticator;
  private Authenticator authenticator;
  private ConnectionPool connectionPool;
  private Dns dns;
  private boolean followSslRedirects = true;
  private boolean followRedirects = true;
  private boolean retryOnConnectionFailure = true;
  private int connectTimeout = 10_000;
  private int readTimeout = 10_000;
  private int writeTimeout = 10_000;

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
    this.cookieJar = okHttpClient.cookieJar;
    this.cache = okHttpClient.cache;
    this.internalCache = cache != null ? cache.internalCache : okHttpClient.internalCache;
    this.socketFactory = okHttpClient.socketFactory;
    this.sslSocketFactory = okHttpClient.sslSocketFactory;
    this.hostnameVerifier = okHttpClient.hostnameVerifier;
    this.certificatePinner = okHttpClient.certificatePinner;
    this.proxyAuthenticator = okHttpClient.proxyAuthenticator;
    this.authenticator = okHttpClient.authenticator;
    this.connectionPool = okHttpClient.connectionPool;
    this.dns = okHttpClient.dns;
    this.followSslRedirects = okHttpClient.followSslRedirects;
    this.followRedirects = okHttpClient.followRedirects;
    this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure;
    this.connectTimeout = okHttpClient.connectTimeout;
    this.readTimeout = okHttpClient.readTimeout;
    this.writeTimeout = okHttpClient.writeTimeout;
  }

  /**
   * Sets the default connect timeout for new connections. A value of 0 means no timeout, otherwise
   * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
   *
   * @see URLConnection#setConnectTimeout(int)
   */
  public OkHttpClient setConnectTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    if (millis == 0 && timeout > 0) throw new IllegalArgumentException("Timeout too small.");
    connectTimeout = (int) millis;
    return this;
  }

  /** Default connect timeout (in milliseconds). */
  public int getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
   * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
   *
   * @see URLConnection#setReadTimeout(int)
   */
  public OkHttpClient setReadTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    if (millis == 0 && timeout > 0) throw new IllegalArgumentException("Timeout too small.");
    readTimeout = (int) millis;
    return this;
  }

  /** Default read timeout (in milliseconds). */
  public int getReadTimeout() {
    return readTimeout;
  }

  /**
   * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
   * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
   */
  public OkHttpClient setWriteTimeout(long timeout, TimeUnit unit) {
    if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
    if (unit == null) throw new IllegalArgumentException("unit == null");
    long millis = unit.toMillis(timeout);
    if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Timeout too large.");
    if (millis == 0 && timeout > 0) throw new IllegalArgumentException("Timeout too small.");
    writeTimeout = (int) millis;
    return this;
  }

  /** Default write timeout (in milliseconds). */
  public int getWriteTimeout() {
    return writeTimeout;
  }

  /**
   * Sets the HTTP proxy that will be used by connections created by this client.
   * To disable proxy use completely, call {@code setProxy(Proxy.NO_PROXY)}.
   */
  public OkHttpClient setProxy(Proxy proxy) {
    this.proxy = proxy;
    return this;
  }

  public Proxy getProxy() {
    return proxy;
  }

  /**
   * Sets the handler that can accept cookies from incoming HTTP responses and provides cookies to
   * outgoing HTTP requests.
   *
   * <p>If unset, {@linkplain CookieJar#NO_COOKIES no cookies} will be accepted nor provided.
   */
  public OkHttpClient setCookieJar(CookieJar cookieJar) {
    this.cookieJar = cookieJar;
    return this;
  }

  public CookieJar getCookieJar() {
    return cookieJar;
  }

  /** Sets the response cache to be used to read and write cached responses. */
  void setInternalCache(InternalCache internalCache) {
    this.internalCache = internalCache;
    this.cache = null;
  }

  InternalCache internalCache() {
    return internalCache;
  }

  public OkHttpClient setCache(Cache cache) {
    this.cache = cache;
    this.internalCache = null;
    return this;
  }

  public Cache getCache() {
    return cache;
  }

  /**
   * Sets the DNS service used to lookup IP addresses for hostnames.
   *
   * <p>If unset, the {@link Dns#SYSTEM system-wide default} DNS will be used.
   */
  public OkHttpClient setDns(Dns dns) {
    this.dns = dns;
    return this;
  }

  public Dns getDns() {
    return dns;
  }

  /**
   * Sets the socket factory used to create connections. OkHttp only uses the parameterless {@link
   * SocketFactory#createSocket() createSocket()} method to create unconnected sockets. Overriding
   * this method, e. g., allows the socket to be bound to a specific local address.
   *
   * <p>If unset, the {@link SocketFactory#getDefault() system-wide default} socket factory will be
   * used.
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
   * Sets the verifier used to confirm that response certificates apply to requested hostnames for
   * HTTPS connections.
   *
   * <p>If unset, a default hostname verifier will be used.
   */
  public OkHttpClient setHostnameVerifier(HostnameVerifier hostnameVerifier) {
    this.hostnameVerifier = hostnameVerifier;
    return this;
  }

  public HostnameVerifier getHostnameVerifier() {
    return hostnameVerifier;
  }

  /**
   * Sets the certificate pinner that constrains which certificates are trusted. By default HTTPS
   * connections rely on only the {@link #setSslSocketFactory SSL socket factory} to establish
   * trust. Pinning certificates avoids the need to trust certificate authorities.
   */
  public OkHttpClient setCertificatePinner(CertificatePinner certificatePinner) {
    this.certificatePinner = certificatePinner;
    return this;
  }

  public CertificatePinner getCertificatePinner() {
    return certificatePinner;
  }

  /**
   * Sets the authenticator used to respond to challenges from origin servers. Use {@link
   * #setProxyAuthenticator} to set the authenticator for proxy servers.
   *
   * <p>If unset, the {@linkplain java.net.Authenticator#setDefault system-wide default}
   * authenticator will be used.
   */
  public OkHttpClient setAuthenticator(Authenticator authenticator) {
    this.authenticator = authenticator;
    return this;
  }

  public Authenticator getAuthenticator() {
    return authenticator;
  }

  /**
   * Sets the authenticator used to respond to challenges from proxy servers. Use {@link
   * #setAuthenticator} to set the authenticator for origin servers.
   *
   * <p>If unset, the {@linkplain java.net.Authenticator#setDefault system-wide default}
   * authenticator will be used.
   */
  public OkHttpClient setProxyAuthenticator(Authenticator proxyAuthenticator) {
    this.proxyAuthenticator = proxyAuthenticator;
    return this;
  }

  public Authenticator getProxyAuthenticator() {
    return proxyAuthenticator;
  }

  /**
   * Sets the connection pool used to recycle HTTP and HTTPS connections.
   *
   * <p>If unset, the {@link ConnectionPool#getDefault() system-wide default} connection pool will
   * be used.
   */
  public OkHttpClient setConnectionPool(ConnectionPool connectionPool) {
    this.connectionPool = connectionPool;
    return this;
  }

  public ConnectionPool getConnectionPool() {
    return connectionPool;
  }

  /**
   * Configure this client to follow redirects from HTTPS to HTTP and from HTTP to HTTPS.
   *
   * <p>If unset, protocol redirects will be followed. This is different than the built-in {@code
   * HttpURLConnection}'s default.
   */
  public OkHttpClient setFollowSslRedirects(boolean followProtocolRedirects) {
    this.followSslRedirects = followProtocolRedirects;
    return this;
  }

  public boolean getFollowSslRedirects() {
    return followSslRedirects;
  }

  /** Configure this client to follow redirects. If unset, redirects be followed. */
  public OkHttpClient setFollowRedirects(boolean followRedirects) {
    this.followRedirects = followRedirects;
    return this;
  }

  public boolean getFollowRedirects() {
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
   *   <li><strong>Unreachable proxy servers.</strong> falling back to a direct connection.
   * </ul>
   *
   * Set this to false to avoid retrying requests when doing so is destructive. In this case the
   * calling application should do its own recovery of connectivity failures.
   */
  public OkHttpClient setRetryOnConnectionFailure(boolean retryOnConnectionFailure) {
    this.retryOnConnectionFailure = retryOnConnectionFailure;
    return this;
  }

  public boolean getRetryOnConnectionFailure() {
    return retryOnConnectionFailure;
  }

  RouteDatabase routeDatabase() {
    return routeDatabase;
  }

  /**
   * Sets the dispatcher used to set policy and execute asynchronous requests. Must not be null.
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
   * Configure the protocols used by this client to communicate with remote servers. By default this
   * client will prefer the most efficient transport available, falling back to more ubiquitous
   * protocols. Applications should only call this method to avoid specific compatibility problems,
   * such as web servers that behave incorrectly when SPDY is enabled.
   *
   * <p>The following protocols are currently supported:
   *
   * <ul>
   *   <li><a href="http://www.w3.org/Protocols/rfc2616/rfc2616.html">http/1.1</a>
   *   <li><a href="http://www.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3-1">spdy/3.1</a>
   *   <li><a href="http://tools.ietf.org/html/draft-ietf-httpbis-http2-17">h2</a>
   * </ul>
   *
   * <p><strong>This is an evolving set.</strong> Future releases include support for transitional
   * protocols. The http/1.1 transport will never be dropped.
   *
   * <p>If multiple protocols are specified, <a
   * href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a> will be used to
   * negotiate a transport.
   *
   * <p>{@link Protocol#HTTP_1_0} is not supported in this set. Requests are initiated with {@code
   * HTTP/1.1} only. If the server responds with {@code HTTP/1.0}, that will be exposed by {@link
   * Response#protocol()}.
   *
   * @param protocols the protocols to use, in order of preference. The list must contain {@link
   * Protocol#HTTP_1_1}. It must not contain null or {@link Protocol#HTTP_1_0}.
   */
  public OkHttpClient setProtocols(List<Protocol> protocols) {
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

  public List<Protocol> getProtocols() {
    return protocols;
  }

  public OkHttpClient setConnectionSpecs(List<ConnectionSpec> connectionSpecs) {
    this.connectionSpecs = Util.immutableList(connectionSpecs);
    return this;
  }

  public List<ConnectionSpec> getConnectionSpecs() {
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
  @Override public Call newCall(Request request) {
    return new RealCall(this, request);
  }

  /**
   * Cancels all scheduled or in-flight calls tagged with {@code tag}. Requests that are already
   * complete cannot be canceled.
   */
  public OkHttpClient cancel(Object tag) {
    getDispatcher().cancel(tag);
    return this;
  }

  /**
   * Returns a shallow copy of this OkHttpClient that uses the system-wide default for each field
   * that hasn't been explicitly configured.
   */
  OkHttpClient copyWithDefaults() {
    OkHttpClient result = new OkHttpClient(this);
    if (result.cookieJar == null) {
      result.cookieJar = CookieJar.NO_COOKIES;
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
    if (result.proxyAuthenticator == null) {
      result.proxyAuthenticator = AuthenticatorAdapter.INSTANCE;
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
    if (result.dns == null) {
      result.dns = Dns.SYSTEM;
    }
    return result;
  }

  /**
   * Java and Android programs default to using a single global SSL context, accessible to HTTP
   * clients as {@link SSLSocketFactory#getDefault()}. If we used the shared SSL context, when
   * OkHttp enables ALPN for its SPDY-related stuff, it would also enable ALPN for other usages,
   * which might crash them because ALPN is enabled when it isn't expected to be.
   *
   * <p>This code avoids that by defaulting to an OkHttp-created SSL context. The drawback of this
   * approach is that apps that customize the global SSL context will lose these customizations.
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
  @Override public OkHttpClient clone() {
    return new OkHttpClient(this);
  }
}
