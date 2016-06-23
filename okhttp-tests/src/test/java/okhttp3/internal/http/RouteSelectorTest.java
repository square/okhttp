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
package okhttp3.internal.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.Authenticator;
import okhttp3.ConnectionSpec;
import okhttp3.FakeDns;
import okhttp3.Protocol;
import okhttp3.Route;
import okhttp3.internal.RouteDatabase;
import okhttp3.internal.Util;
import okhttp3.internal.tls.SslClient;
import org.junit.Before;
import org.junit.Test;

import static java.net.Proxy.NO_PROXY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RouteSelectorTest {
  public final List<ConnectionSpec> connectionSpecs = Util.immutableList(
      ConnectionSpec.MODERN_TLS,
      ConnectionSpec.COMPATIBLE_TLS,
      ConnectionSpec.CLEARTEXT);

  private static final int proxyAPort = 1001;
  private static final String proxyAHost = "proxya";
  private static final Proxy proxyA =
      new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyAHost, proxyAPort));
  private static final int proxyBPort = 1002;
  private static final String proxyBHost = "proxyb";
  private static final Proxy proxyB =
      new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyBHost, proxyBPort));
  private String uriHost = "hosta";
  private int uriPort = 1003;

  private SocketFactory socketFactory;
  private final SslClient sslClient = SslClient.localhost();
  private final SSLSocketFactory sslSocketFactory = sslClient.socketFactory;
  private HostnameVerifier hostnameVerifier;

  private final Authenticator authenticator = Authenticator.NONE;
  private final List<Protocol> protocols = Arrays.asList(Protocol.HTTP_1_1);
  private final FakeDns dns = new FakeDns();
  private final RecordingProxySelector proxySelector = new RecordingProxySelector();
  private RouteDatabase routeDatabase = new RouteDatabase();

  @Before public void setUp() throws Exception {
    socketFactory = SocketFactory.getDefault();
    hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
  }

  @Test public void singleRoute() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 1));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
    try {
      routeSelector.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void singleRouteReturnsFailedRoute() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 1));
    Route route = routeSelector.next();
    routeDatabase.failed(route);
    routeSelector = new RouteSelector(address, routeDatabase);
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    assertFalse(routeSelector.hasNext());
    try {
      routeSelector.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void explicitProxyTriesThatProxysAddressesOnly() throws Exception {
    Address address = new Address(uriHost, uriPort, dns, socketFactory, null, null, null,
        authenticator, proxyA, protocols, connectionSpecs, proxySelector);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 2));
    assertRoute(routeSelector.next(), address, proxyA, dns.address(0), proxyAPort);
    assertRoute(routeSelector.next(), address, proxyA, dns.address(1), proxyAPort);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(proxyAHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void explicitDirectProxy() throws Exception {
    Address address = new Address(uriHost, uriPort, dns, socketFactory, null, null, null,
        authenticator, NO_PROXY, protocols, connectionSpecs, proxySelector);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 2));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(1), uriPort);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(uriHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void proxySelectorReturnsNull() throws Exception {
    ProxySelector nullProxySelector = new ProxySelector() {
      @Override public List<Proxy> select(URI uri) {
        assertEquals(uriHost, uri.getHost());
        return null;
      }

      @Override public void connectFailed(
          URI uri, SocketAddress socketAddress, IOException e) {
        throw new AssertionError();
      }
    };

    Address address = new Address(uriHost, uriPort, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, nullProxySelector);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);
    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 1));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxySelectorReturnsNoProxies() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 2));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(1), uriPort);

    assertFalse(routeSelector.hasNext());
    dns.assertRequests(uriHost);
    proxySelector.assertRequests(address.url().uri());
  }

  @Test public void proxySelectorReturnsMultipleProxies() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);
    proxySelector.assertRequests(address.url().uri());

    // First try the IP addresses of the first proxy, in sequence.
    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 2));
    assertRoute(routeSelector.next(), address, proxyA, dns.address(0), proxyAPort);
    assertRoute(routeSelector.next(), address, proxyA, dns.address(1), proxyAPort);
    dns.assertRequests(proxyAHost);

    // Next try the IP address of the second proxy.
    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(254, 1));
    assertRoute(routeSelector.next(), address, proxyB, dns.address(0), proxyBPort);
    dns.assertRequests(proxyBHost);

    // Finally try the only IP address of the origin server.
    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(253, 1));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxySelectorDirectConnectionsAreSkipped() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(NO_PROXY);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);
    proxySelector.assertRequests(address.url().uri());

    // Only the origin server will be attempted.
    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 1));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void proxyDnsFailureContinuesToNextProxy() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    proxySelector.proxies.add(proxyA);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);
    proxySelector.assertRequests(address.url().uri());

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 1));
    assertRoute(routeSelector.next(), address, proxyA, dns.address(0), proxyAPort);
    dns.assertRequests(proxyAHost);

    assertTrue(routeSelector.hasNext());
    dns.unknownHost();
    try {
      routeSelector.next();
      fail();
    } catch (UnknownHostException expected) {
    }
    dns.assertRequests(proxyBHost);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(255, 1));
    assertRoute(routeSelector.next(), address, proxyA, dns.address(0), proxyAPort);
    dns.assertRequests(proxyAHost);

    assertTrue(routeSelector.hasNext());
    dns.addresses(makeFakeAddresses(254, 1));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    dns.assertRequests(uriHost);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void multipleProxiesMultipleInetAddressesMultipleConfigurations() throws Exception {
    Address address = httpsAddress();
    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    // Proxy A
    dns.addresses(makeFakeAddresses(255, 2));
    assertRoute(routeSelector.next(), address, proxyA, dns.address(0), proxyAPort);
    dns.assertRequests(proxyAHost);
    assertRoute(routeSelector.next(), address, proxyA, dns.address(1), proxyAPort);

    // Proxy B
    dns.addresses(makeFakeAddresses(254, 2));
    assertRoute(routeSelector.next(), address, proxyB, dns.address(0), proxyBPort);
    dns.assertRequests(proxyBHost);
    assertRoute(routeSelector.next(), address, proxyB, dns.address(1), proxyBPort);

    // Origin
    dns.addresses(makeFakeAddresses(253, 2));
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(0), uriPort);
    dns.assertRequests(uriHost);
    assertRoute(routeSelector.next(), address, NO_PROXY, dns.address(1), uriPort);

    assertFalse(routeSelector.hasNext());
  }

  @Test public void failedRoutesAreLast() throws Exception {
    Address address = httpsAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);

    final int numberOfAddresses = 2;
    dns.addresses(makeFakeAddresses(255, numberOfAddresses));

    // Extract the regular sequence of routes from selector.
    List<Route> regularRoutes = new ArrayList<>();
    while (routeSelector.hasNext()) {
      regularRoutes.add(routeSelector.next());
    }

    // Check that we do indeed have more than one route.
    assertEquals(numberOfAddresses, regularRoutes.size());
    // Add first regular route as failed.
    routeDatabase.failed(regularRoutes.get(0));
    // Reset selector
    routeSelector = new RouteSelector(address, routeDatabase);

    List<Route> routesWithFailedRoute = new ArrayList<>();
    while (routeSelector.hasNext()) {
      routesWithFailedRoute.add(routeSelector.next());
    }

    assertEquals(regularRoutes.get(0),
        routesWithFailedRoute.get(routesWithFailedRoute.size() - 1));
    assertEquals(regularRoutes.size(), routesWithFailedRoute.size());
  }

  @Test public void getHostString() throws Exception {
    // Name proxy specification.
    InetSocketAddress socketAddress = InetSocketAddress.createUnresolved("host", 1234);
    assertEquals("host", RouteSelector.getHostString(socketAddress));
    socketAddress = InetSocketAddress.createUnresolved("127.0.0.1", 1234);
    assertEquals("127.0.0.1", RouteSelector.getHostString(socketAddress));

    // InetAddress proxy specification.
    socketAddress = new InetSocketAddress(InetAddress.getByName("localhost"), 1234);
    assertEquals("127.0.0.1", RouteSelector.getHostString(socketAddress));
    socketAddress = new InetSocketAddress(
        InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 1234);
    assertEquals("127.0.0.1", RouteSelector.getHostString(socketAddress));
    socketAddress = new InetSocketAddress(
        InetAddress.getByAddress("foobar", new byte[] {127, 0, 0, 1}), 1234);
    assertEquals("127.0.0.1", RouteSelector.getHostString(socketAddress));
  }

  private void assertRoute(Route route, Address address, Proxy proxy, InetAddress socketAddress,
      int socketPort) {
    assertEquals(address, route.address());
    assertEquals(proxy, route.proxy());
    assertEquals(socketAddress, route.socketAddress().getAddress());
    assertEquals(socketPort, route.socketAddress().getPort());
  }

  /** Returns an address that's without an SSL socket factory or hostname verifier. */
  private Address httpAddress() {
    return new Address(uriHost, uriPort, dns, socketFactory, null, null, null, authenticator, null,
        protocols, connectionSpecs, proxySelector);
  }

  private Address httpsAddress() {
    return new Address(uriHost, uriPort, dns, socketFactory, sslSocketFactory,
        hostnameVerifier, null, authenticator, null, protocols, connectionSpecs, proxySelector);
  }

  private static List<InetAddress> makeFakeAddresses(int prefix, int count) {
    try {
      List<InetAddress> result = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        result.add(InetAddress.getByAddress(
            new byte[] {(byte) prefix, (byte) 0, (byte) 0, (byte) i}));
      }
      return result;
    } catch (UnknownHostException e) {
      throw new AssertionError();
    }
  }
}
