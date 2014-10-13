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
import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionConfiguration;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.Network;
import com.squareup.okhttp.internal.RouteDatabase;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
import org.junit.Before;
import org.junit.Test;

import static java.net.Proxy.NO_PROXY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RouteSelectorTest {
  public final List<ConnectionConfiguration> connectionConfigurations = Util.immutableList(
      ConnectionConfiguration.MODERN_TLS,
      ConnectionConfiguration.COMPATIBLE_TLS,
      ConnectionConfiguration.CLEARTEXT);

  private static final int proxyAPort = 1001;
  private static final String proxyAHost = "proxyA";
  private static final Proxy proxyA =
      new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyAHost, proxyAPort));
  private static final int proxyBPort = 1002;
  private static final String proxyBHost = "proxyB";
  private static final Proxy proxyB =
      new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyBHost, proxyBPort));
  private String uriHost = "hostA";
  private int uriPort = 1003;

  private SocketFactory socketFactory;
  private final SSLContext sslContext = SslContextBuilder.localhost();
  private final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
  private HostnameVerifier hostnameVerifier;

  private final Authenticator authenticator = AuthenticatorAdapter.INSTANCE;
  private final List<Protocol> protocols = Arrays.asList(Protocol.HTTP_1_1);
  private final FakeDns dns = new FakeDns();
  private final RecordingProxySelector proxySelector = new RecordingProxySelector();
  private OkHttpClient client;
  private RouteDatabase routeDatabase;
  private Request httpRequest;
  private Request httpsRequest;

  @Before public void setUp() throws Exception {
    socketFactory = SocketFactory.getDefault();
    hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

    client = new OkHttpClient()
        .setAuthenticator(authenticator)
        .setProxySelector(proxySelector)
        .setSocketFactory(socketFactory)
        .setSslSocketFactory(sslSocketFactory)
        .setHostnameVerifier(hostnameVerifier)
        .setProtocols(protocols)
        .setConnectionConfigurations(connectionConfigurations)
        .setConnectionPool(ConnectionPool.getDefault());
    Internal.instance.setNetwork(client, dns);

    routeDatabase = Internal.instance.routeDatabase(client);

    httpRequest = new Request.Builder()
        .url("http://" + uriHost + ":" + uriPort + "/path")
        .build();
    httpsRequest = new Request.Builder()
        .url("https://" + uriHost + ":" + uriPort + "/path")
        .build();
  }

  @Test public void singleRoute() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
    try {
      routeSelector.nextUnconnected();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void singleRouteReturnsFailedRoute() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    Connection connection = routeSelector.nextUnconnected();
    routeDatabase.failed(connection.getRoute());
    routeSelector = RouteSelector.get(httpRequest, client);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.CLEARTEXT);
    assertFalse(routeSelector.hasNext());
    try {
      routeSelector.nextUnconnected();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void explicitProxyTriesThatProxysAddressesOnly() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, null, authenticator,
        proxyA, protocols, connectionConfigurations);
    client.setProxy(proxyA);
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[0],
        proxyAPort, ConnectionConfiguration.CLEARTEXT);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[1],
        proxyAPort, ConnectionConfiguration.CLEARTEXT);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(proxyAHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void explicitDirectProxy() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, null, null, null, authenticator,
        NO_PROXY, protocols, connectionConfigurations);
    client.setProxy(NO_PROXY);
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.CLEARTEXT);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[1],
        uriPort, ConnectionConfiguration.CLEARTEXT);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(uriHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void proxySelectorReturnsNull() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies = null;
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);
    proxySelector.assertRequests(httpRequest.uri());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxySelectorReturnsNoProxies() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.CLEARTEXT);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[1],
        uriPort, ConnectionConfiguration.CLEARTEXT);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(uriHost);
    proxySelector.assertRequests(httpRequest.uri());
  }

  @Test public void proxySelectorReturnsMultipleProxies() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);
    proxySelector.assertRequests(httpRequest.uri());

    // First try the IP addresses of the first proxy, in sequence.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[0], proxyAPort,
        ConnectionConfiguration.CLEARTEXT);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[1], proxyAPort,
        ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(proxyAHost);

    // Next try the IP address of the second proxy.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(254, 1);
    assertConnection(routeSelector.nextUnconnected(), address, proxyB, dns.inetAddresses[0], proxyBPort,
        ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(proxyBHost);

    // Finally try the only IP address of the origin server.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(253, 1);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxySelectorDirectConnectionsAreSkipped() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(NO_PROXY);
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);
    proxySelector.assertRequests(httpRequest.uri());

    // Only the origin server will be attempted.
    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0], uriPort,
        ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxyDnsFailureContinuesToNextProxy() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    proxySelector.proxies.add(proxyA);
    RouteSelector routeSelector = RouteSelector.get(httpRequest, client);
    proxySelector.assertRequests(httpRequest.uri());

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[0],
        proxyAPort, ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(proxyAHost);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = null;
    try {
      routeSelector.nextUnconnected();
      fail();
    } catch (UnknownHostException expected) {
    }
    dns.assertRequests(proxyBHost);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(255, 1);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[0],
        proxyAPort, ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(proxyAHost);

    assertTrue(routeSelector.hasNext());
    dns.inetAddresses = makeFakeAddresses(254, 1);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.CLEARTEXT);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  // https://github.com/square/okhttp/issues/442
  @Test public void nonSslErrorAddsAllTlsModesToFailedRoute() throws Exception {
    client.setProxy(Proxy.NO_PROXY);
    RouteSelector routeSelector = RouteSelector.get(httpsRequest, client);

    dns.inetAddresses = makeFakeAddresses(255, 1);
    Connection connection = routeSelector.nextUnconnected();
    routeSelector.connectFailed(connection, new IOException("Non SSL exception"));
    assertEquals(2, routeDatabase.failedRoutesCount());
    assertFalse(routeSelector.hasNext());
  }

  @Test public void sslErrorAddsOnlyFailedConfigurationToFailedRoute() throws Exception {
    client.setProxy(Proxy.NO_PROXY);
    RouteSelector routeSelector = RouteSelector.get(httpsRequest, client);

    dns.inetAddresses = makeFakeAddresses(255, 1);
    Connection connection = routeSelector.nextUnconnected();
    routeSelector.connectFailed(connection, new SSLHandshakeException("SSL exception"));
    assertTrue(routeDatabase.failedRoutesCount() == 1);
    assertTrue(routeSelector.hasNext());
  }

  @Test public void multipleProxiesMultipleInetAddressesMultipleConfigurations() throws Exception {
    Address address = new Address(uriHost, uriPort, socketFactory, sslSocketFactory,
        hostnameVerifier, null, authenticator, null, protocols, connectionConfigurations);
    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = RouteSelector.get(httpsRequest, client);

    // Proxy A
    dns.inetAddresses = makeFakeAddresses(255, 2);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[0],
        proxyAPort, ConnectionConfiguration.MODERN_TLS);
    dns.assertRequests(proxyAHost);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[0],
        proxyAPort, ConnectionConfiguration.COMPATIBLE_TLS);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[1],
        proxyAPort, ConnectionConfiguration.MODERN_TLS);
    assertConnection(routeSelector.nextUnconnected(), address, proxyA, dns.inetAddresses[1],
        proxyAPort, ConnectionConfiguration.COMPATIBLE_TLS);

    // Proxy B
    dns.inetAddresses = makeFakeAddresses(254, 2);
    assertConnection(routeSelector.nextUnconnected(), address, proxyB, dns.inetAddresses[0],
        proxyBPort, ConnectionConfiguration.MODERN_TLS);
    dns.assertRequests(proxyBHost);
    assertConnection(routeSelector.nextUnconnected(), address, proxyB, dns.inetAddresses[0],
        proxyBPort, ConnectionConfiguration.COMPATIBLE_TLS);
    assertConnection(routeSelector.nextUnconnected(), address, proxyB, dns.inetAddresses[1],
        proxyBPort, ConnectionConfiguration.MODERN_TLS);
    assertConnection(routeSelector.nextUnconnected(), address, proxyB, dns.inetAddresses[1],
        proxyBPort, ConnectionConfiguration.COMPATIBLE_TLS);

    // Origin
    dns.inetAddresses = makeFakeAddresses(253, 2);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.MODERN_TLS);
    dns.assertRequests(uriHost);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[0],
        uriPort, ConnectionConfiguration.COMPATIBLE_TLS);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[1],
        uriPort, ConnectionConfiguration.MODERN_TLS);
    assertConnection(routeSelector.nextUnconnected(), address, NO_PROXY, dns.inetAddresses[1],
        uriPort, ConnectionConfiguration.COMPATIBLE_TLS);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void failedRoutesAreLast() throws Exception {
    client.setProxy(Proxy.NO_PROXY);
    RouteSelector routeSelector = RouteSelector.get(httpsRequest, client);

    dns.inetAddresses = makeFakeAddresses(255, 1);

    // Extract the regular sequence of routes from selector.
    List<Connection> regularRoutes = new ArrayList<>();
    while (routeSelector.hasNext()) {
      regularRoutes.add(routeSelector.nextUnconnected());
    }

    // Check that we do indeed have more than one route.
    assertTrue(regularRoutes.size() > 1);
    // Add first regular route as failed.
    routeDatabase.failed(regularRoutes.get(0).getRoute());
    // Reset selector
    routeSelector = RouteSelector.get(httpsRequest, client);

    List<Connection> routesWithFailedRoute = new ArrayList<>();
    while (routeSelector.hasNext()) {
      routesWithFailedRoute.add(routeSelector.nextUnconnected());
    }

    assertEquals(regularRoutes.get(0).getRoute(),
        routesWithFailedRoute.get(routesWithFailedRoute.size() - 1).getRoute());
    assertEquals(regularRoutes.size(), routesWithFailedRoute.size());
  }

  private void assertConnection(Connection connection, Address address, Proxy proxy,
      InetAddress socketAddress, int socketPort, ConnectionConfiguration connectionConfiguration) {
    assertEquals(address, connection.getRoute().getAddress());
    assertEquals(proxy, connection.getRoute().getProxy());
    assertEquals(socketAddress, connection.getRoute().getSocketAddress().getAddress());
    assertEquals(socketPort, connection.getRoute().getSocketAddress().getPort());
    assertEquals(connectionConfiguration, connection.getRoute().getConnectionConfiguration());
  }

  /** Returns an address that's without an SSL socket factory or hostname verifier. */
  private Address httpAddress() {
    return new Address(uriHost, uriPort, socketFactory, null, null, null, authenticator, null,
        protocols, connectionConfigurations);
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

  private static class FakeDns implements Network {
    List<String> requestedHosts = new ArrayList<>();
    InetAddress[] inetAddresses;

    @Override public InetAddress[] resolveInetAddresses(String host) throws UnknownHostException {
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
