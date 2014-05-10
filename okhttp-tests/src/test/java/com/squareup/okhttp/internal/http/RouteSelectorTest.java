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
import com.squareup.okhttp.OkAuthenticator;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.Dns;
import com.squareup.okhttp.internal.RouteDatabase;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.huc.AuthenticatorAdapter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

import static com.squareup.okhttp.internal.http.RouteSelector.SSL_V3;
import static com.squareup.okhttp.internal.http.RouteSelector.TLS_V1;
import static java.net.Proxy.NO_PROXY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RouteSelectorTest {
  private static final int proxyAPort = 1001;
  private static final String proxyAHost = "proxyA";
  private static final Proxy proxyA =
      new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyAHost, proxyAPort));
  private static final int proxyBPort = 1002;
  private static final String proxyBHost = "proxyB";
  private static final Proxy proxyB =
      new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyBHost, proxyBPort));
  private static final URI uri;
  private static final String uriHost = "hostA";
  private static final int uriPort = 80;

  private static final SocketFactory socketFactory;
  private static final SSLContext sslContext = SslContextBuilder.localhost();
  private static final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
  private static final HostnameVerifier hostnameVerifier;
  private static final ConnectionPool pool;

  static {
    try {
      uri = new URI("http://" + uriHost + ":" + uriPort + "/path");
      socketFactory = SocketFactory.getDefault();
      pool = ConnectionPool.getDefault();
      hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private final OkAuthenticator authenticator = AuthenticatorAdapter.INSTANCE;
  private final List<Protocol> protocols = Arrays.asList(Protocol.HTTP_1_1);
  private final FakeDns dns = new FakeDns();
  private final RecordingProxySelector proxySelector = new RecordingProxySelector();

  @Test public void singleRoute() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
    try {
      routeSelector.next("GET");
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void singleRouteReturnsFailedRoute() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    Connection connection = routeSelector.next("GET");
    RouteDatabase routeDatabase = new RouteDatabase();
    routeDatabase.failed(connection.getRoute());
    routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns, routeDatabase);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    assertFalse(routeSelector.hasNext());
    try {
      routeSelector.next("GET");
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void explicitProxyTriesThatProxiesAddressesOnly() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator,
        proxyA, protocols);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[0], proxyAPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[1], proxyAPort,
        SSL_V3);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(proxyAHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void explicitDirectProxy() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator,
        NO_PROXY, protocols);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[1], uriPort,
        SSL_V3);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(uri.getHost());
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void proxySelectorReturnsNull() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);

    proxySelector.proxies = null;
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());
    proxySelector.assertRequests(uri);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxySelectorReturnsNoProxies() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[1], uriPort,
        SSL_V3);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(uri.getHost());
    proxySelector.assertRequests(uri);
  }

  @Test public void proxySelectorReturnsMultipleProxies() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());
    proxySelector.assertRequests(uri);

    // First try the IP addresses of the first proxy, in sequence.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[0], proxyAPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[1], proxyAPort,
        SSL_V3);
    dns.assertRequests(proxyAHost);

    // Next try the IP address of the second proxy.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(254, 1);
    assertConnection(routeSelector.next("GET"), address, proxyB, dns.inetAddresses[0], proxyBPort,
        SSL_V3);
    dns.assertRequests(proxyBHost);

    // Finally try the only IP address of the origin server.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(253, 1);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxySelectorDirectConnectionsAreSkipped() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);

    proxySelector.proxies.add(NO_PROXY);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());
    proxySelector.assertRequests(uri);

    // Only the origin server will be attempted.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxyDnsFailureContinuesToNextProxy() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, authenticator, null,
        protocols);

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    proxySelector.proxies.add(proxyA);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());
    proxySelector.assertRequests(uri);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[0], proxyAPort,
        SSL_V3);
    dns.assertRequests(proxyAHost);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = null;
    try {
      routeSelector.next("GET");
      fail();
    } catch (UnknownHostException expected) {
    }
    dns.assertRequests(proxyBHost);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[0], proxyAPort,
        SSL_V3);
    dns.assertRequests(proxyAHost);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(254, 1);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  // https://github.com/square/okhttp/issues/442
  @Test public void nonSslErrorAddsAllTlsModesToFailedRoute() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, sslSocketFactory,
        hostnameVerifier, authenticator, Proxy.NO_PROXY, protocols);
    RouteDatabase routeDatabase = new RouteDatabase();
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        routeDatabase);

    dns.inetAddresses = makeFakeAddresses(255, 1);
    Connection connection = routeSelector.next("GET");
    routeSelector.connectFailed(connection, new IOException("Non SSL exception"));
    assertTrue(routeDatabase.failedRoutesCount() == 2);
    assertFalse(routeSelector.hasNext());
  }

  @Test public void sslErrorAddsOnlyFailedTlsModeToFailedRoute() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, sslSocketFactory,
        hostnameVerifier, authenticator, Proxy.NO_PROXY, protocols);
    RouteDatabase routeDatabase = new RouteDatabase();
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        routeDatabase);

    dns.inetAddresses = makeFakeAddresses(255, 1);
    Connection connection = routeSelector.next("GET");
    routeSelector.connectFailed(connection, new SSLHandshakeException("SSL exception"));
    assertTrue(routeDatabase.failedRoutesCount() == 1);
    assertTrue(routeSelector.hasNext());
  }

  @Test public void multipleProxiesMultipleInetAddressesMultipleTlsModes() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, sslSocketFactory,
        hostnameVerifier, authenticator, null, protocols);
    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        new RouteDatabase());

    // Proxy A
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[0], proxyAPort,
        TLS_V1);
    dns.assertRequests(proxyAHost);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[0], proxyAPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[1], proxyAPort,
        TLS_V1);
    assertConnection(routeSelector.next("GET"), address, proxyA, dns.inetAddresses[1], proxyAPort,
        SSL_V3);

    // Proxy B
    dns.inetAddresses = makeFakeAddresses(254, 2);
    assertConnection(routeSelector.next("GET"), address, proxyB, dns.inetAddresses[0], proxyBPort,
        TLS_V1);
    dns.assertRequests(proxyBHost);
    assertConnection(routeSelector.next("GET"), address, proxyB, dns.inetAddresses[0], proxyBPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, proxyB, dns.inetAddresses[1], proxyBPort,
        TLS_V1);
    assertConnection(routeSelector.next("GET"), address, proxyB, dns.inetAddresses[1], proxyBPort,
        SSL_V3);

    // Origin
    dns.inetAddresses = makeFakeAddresses(253, 2);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        TLS_V1);
    dns.assertRequests(uriHost);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        SSL_V3);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[1], uriPort,
        TLS_V1);
    assertConnection(routeSelector.next("GET"), address, NO_PROXY, dns.inetAddresses[1], uriPort,
        SSL_V3);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void failedRoutesAreLast() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, sslSocketFactory,
        hostnameVerifier, authenticator, Proxy.NO_PROXY, protocols);

    RouteDatabase routeDatabase = new RouteDatabase();
    RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns,
        routeDatabase);
    dns.inetAddresses = makeFakeAddresses(255, 1);

    // Extract the regular sequence of routes from selector.
    List<Connection> regularRoutes = new ArrayList<Connection>();
    while (routeSelector.hasNext()) {
      regularRoutes.add(routeSelector.next("GET"));
    }

    // Check that we do indeed have more than one route.
    assertTrue(regularRoutes.size() > 1);
    // Add first regular route as failed.
    routeDatabase.failed(regularRoutes.get(0).getRoute());
    // Reset selector
    routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns, routeDatabase);

    List<Connection> routesWithFailedRoute = new ArrayList<Connection>();
    while (routeSelector.hasNext()) {
      routesWithFailedRoute.add(routeSelector.next("GET"));
    }

    assertEquals(regularRoutes.get(0).getRoute(),
        routesWithFailedRoute.get(routesWithFailedRoute.size() - 1).getRoute());
    assertEquals(regularRoutes.size(), routesWithFailedRoute.size());
  }

  private void assertConnection(Connection connection, Address address, Proxy proxy,
      InetAddress socketAddress, int socketPort, String tlsVersion) {
    assertEquals(address, connection.getRoute().getAddress());
    assertEquals(proxy, connection.getRoute().getProxy());
    assertEquals(socketAddress, connection.getRoute().getSocketAddress().getAddress());
    assertEquals(socketPort, connection.getRoute().getSocketAddress().getPort());
    assertEquals(tlsVersion, connection.getRoute().getTlsVersion());
  }

  private static InetAddress[] makeFakeAddresses(int prefix, int count) {
    try {
      InetAddress[] result = new InetAddress[count];
      for (int i = 0; i < count; i++) {
        result[i] =
            InetAddress.getByAddress(new byte[] { (byte) prefix, (byte) 0, (byte) 0, (byte) i });
      }
      return result;
    } catch (UnknownHostException e) {
      throw new AssertionError();
    }
  }

  private static class FakeDns implements Dns {
    List<String> requestedHosts = new ArrayList<String>();
    InetAddress[] inetAddresses;

    @Override public InetAddress[] getAllByName(String host) throws UnknownHostException {
      requestedHosts.add(host);
      if (inetAddresses == null) throw new UnknownHostException();
      return inetAddresses;
    }

    public void assertRequests(String... expectedHosts) {
      assertEquals(Arrays.asList(expectedHosts), requestedHosts);
      requestedHosts.clear();
    }
  }
}
