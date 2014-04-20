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
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Address;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.RouteDatabase;
import com.squareup.okhttp.internal.Dns;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;

import static com.squareup.okhttp.internal.Util.getEffectivePort;

/**
 * Selects routes to connect to an origin server. Each connection requires a
 * choice of proxy server, IP address, and TLS mode. Connections may also be
 * recycled.
 */
public final class RouteSelector {
  public static final String TLS_V1 = "TLSv1";
  public static final String SSL_V3 = "SSLv3";

  private final Address address;
  private final URI uri;
  private final ProxySelector proxySelector;
  private final ConnectionPool pool;
  private final Dns dns;
  private final RouteDatabase routeDatabase;

  /* The most recently attempted route. */
  private Proxy lastProxy;
  private InetSocketAddress lastInetSocketAddress;

  /* State for negotiating the next proxy to use. */
  private boolean hasNextProxy;
  private Proxy userSpecifiedProxy;
  private Iterator<Proxy> proxySelectorProxies;

  /* State for negotiating the next InetSocketAddress to use. */
  private InetAddress[] socketAddresses;
  private int nextSocketAddressIndex;
  private int socketPort;

  /* TLS version to attempt with the connection. */
  private String nextTlsVersion;

  /* State for negotiating failed routes */
  private final List<Route> postponedRoutes;

  public RouteSelector(Address address, URI uri, ProxySelector proxySelector, ConnectionPool pool,
      Dns dns, RouteDatabase routeDatabase) {
    this.address = address;
    this.uri = uri;
    this.proxySelector = proxySelector;
    this.pool = pool;
    this.dns = dns;
    this.routeDatabase = routeDatabase;
    this.postponedRoutes = new LinkedList<Route>();

    resetNextProxy(uri, address.getProxy());
  }

  /**
   * Returns true if there's another route to attempt. Every address has at
   * least one route.
   */
  public boolean hasNext() {
    return hasNextTlsVersion()
        || hasNextInetSocketAddress()
        || hasNextProxy()
        || hasNextPostponed();
  }

  /**
   * Returns the next route address to attempt.
   *
   * @throws NoSuchElementException if there are no more routes to attempt.
   */
  public Connection next(String method) throws IOException {
    // Always prefer pooled connections over new connections.
    for (Connection pooled; (pooled = pool.get(address)) != null; ) {
      if (method.equals("GET") || pooled.isReadable()) return pooled;
      pooled.close();
    }

    // Compute the next route to attempt.
    if (!hasNextTlsVersion()) {
      if (!hasNextInetSocketAddress()) {
        if (!hasNextProxy()) {
          if (!hasNextPostponed()) {
            throw new NoSuchElementException();
          }
          return new Connection(pool, nextPostponed());
        }
        lastProxy = nextProxy();
        resetNextInetSocketAddress(lastProxy);
      }
      lastInetSocketAddress = nextInetSocketAddress();
      resetNextTlsVersion();
    }

    String tlsVersion = nextTlsVersion();
    Route route = new Route(address, lastProxy, lastInetSocketAddress, tlsVersion);
    if (routeDatabase.shouldPostpone(route)) {
      postponedRoutes.add(route);
      // We will only recurse in order to skip previously failed routes. They will be
      // tried last.
      return next(method);
    }

    return new Connection(pool, route);
  }

  /**
   * Clients should invoke this method when they encounter a connectivity
   * failure on a connection returned by this route selector.
   */
  public void connectFailed(Connection connection, IOException failure) {
    // If this is a recycled connection, don't count its failure against the route.
    if (connection.recycleCount() > 0) return;

    Route failedRoute = connection.getRoute();
    if (failedRoute.getProxy().type() != Proxy.Type.DIRECT && proxySelector != null) {
      // Tell the proxy selector when we fail to connect on a fresh connection.
      proxySelector.connectFailed(uri, failedRoute.getProxy().address(), failure);
    }

    routeDatabase.failed(failedRoute);

    // If the previously returned route's problem was not related to TLS, and
    // the next route only changes the TLS mode, we shouldn't even attempt it.
    // This suppresses it in both this selector and also in the route database.
    if (!(failure instanceof SSLHandshakeException) && !(failure instanceof SSLProtocolException)) {
      while (hasNextTlsVersion()) {
        Route toSuppress = new Route(address, lastProxy, lastInetSocketAddress, nextTlsVersion());
        routeDatabase.failed(toSuppress);
      }
    }
  }

  /** Resets {@link #nextProxy} to the first option. */
  private void resetNextProxy(URI uri, Proxy proxy) {
    this.hasNextProxy = true; // This includes NO_PROXY!
    if (proxy != null) {
      this.userSpecifiedProxy = proxy;
    } else {
      List<Proxy> proxyList = proxySelector.select(uri);
      if (proxyList != null) {
        this.proxySelectorProxies = proxyList.iterator();
      }
    }
  }

  /** Returns true if there's another proxy to try. */
  private boolean hasNextProxy() {
    return hasNextProxy;
  }

  /** Returns the next proxy to try. May be PROXY.NO_PROXY but never null. */
  private Proxy nextProxy() {
    // If the user specifies a proxy, try that and only that.
    if (userSpecifiedProxy != null) {
      hasNextProxy = false;
      return userSpecifiedProxy;
    }

    // Try each of the ProxySelector choices until one connection succeeds. If none succeed
    // then we'll try a direct connection below.
    if (proxySelectorProxies != null) {
      while (proxySelectorProxies.hasNext()) {
        Proxy candidate = proxySelectorProxies.next();
        if (candidate.type() != Proxy.Type.DIRECT) {
          return candidate;
        }
      }
    }

    // Finally try a direct connection.
    hasNextProxy = false;
    return Proxy.NO_PROXY;
  }

  /** Resets {@link #nextInetSocketAddress} to the first option. */
  private void resetNextInetSocketAddress(Proxy proxy) throws UnknownHostException {
    socketAddresses = null; // Clear the addresses. Necessary if getAllByName() below throws!

    String socketHost;
    if (proxy.type() == Proxy.Type.DIRECT) {
      socketHost = uri.getHost();
      socketPort = getEffectivePort(uri);
    } else {
      SocketAddress proxyAddress = proxy.address();
      if (!(proxyAddress instanceof InetSocketAddress)) {
        throw new IllegalArgumentException(
            "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
      }
      InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
      socketHost = proxySocketAddress.getHostName();
      socketPort = proxySocketAddress.getPort();
    }

    // Try each address for best behavior in mixed IPv4/IPv6 environments.
    socketAddresses = dns.getAllByName(socketHost);
    nextSocketAddressIndex = 0;
  }

  /** Returns true if there's another socket address to try. */
  private boolean hasNextInetSocketAddress() {
    return socketAddresses != null;
  }

  /** Returns the next socket address to try. */
  private InetSocketAddress nextInetSocketAddress() throws UnknownHostException {
    InetSocketAddress result =
        new InetSocketAddress(socketAddresses[nextSocketAddressIndex++], socketPort);
    if (nextSocketAddressIndex == socketAddresses.length) {
      socketAddresses = null; // So that hasNextInetSocketAddress() returns false.
      nextSocketAddressIndex = 0;
    }

    return result;
  }

  /**
   * Resets {@link #nextTlsVersion} to the first option. For routes that don't
   * use SSL, this returns {@link #SSL_V3} so that there is no SSL fallback.
   */
  private void resetNextTlsVersion() {
    nextTlsVersion = (address.getSslSocketFactory() != null) ? TLS_V1 : SSL_V3;
  }

  /** Returns true if there's another TLS version to try. */
  private boolean hasNextTlsVersion() {
    return nextTlsVersion != null;
  }

  /** Returns the next TLS mode to try. */
  private String nextTlsVersion() {
    if (nextTlsVersion == null) {
      throw new IllegalStateException("No next TLS version");
    } else if (nextTlsVersion.equals(TLS_V1)) {
      nextTlsVersion = SSL_V3;
      return TLS_V1;
    } else if (nextTlsVersion.equals(SSL_V3)) {
      nextTlsVersion = null;  // So that hasNextTlsVersion() returns false.
      return SSL_V3;
    } else {
      throw new AssertionError();
    }
  }

  /** Returns true if there is another postponed route to try. */
  private boolean hasNextPostponed() {
    return !postponedRoutes.isEmpty();
  }

  /** Returns the next postponed route to try. */
  private Route nextPostponed() {
    return postponedRoutes.remove(0);
  }
}
