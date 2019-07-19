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
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.EventListener;
import okhttp3.FakeDns;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Route;
import okhttp3.internal.http.RecordingProxySelector;
import okhttp3.tls.HandshakeCertificates;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.net.Proxy.NO_PROXY;
import static okhttp3.internal.Util.immutableListOf;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class RouteSelectorTest {
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  public final List<ConnectionSpec> connectionSpecs = immutableListOf(
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

  private Call call;
  private SocketFactory socketFactory;
  private final HandshakeCertificates handshakeCertificates = localhost();
  private final SSLSocketFactory sslSocketFactory = handshakeCertificates.sslSocketFactory();
  private HostnameVerifier hostnameVerifier;

  private final Authenticator authenticator = Authenticator.NONE;
  private final List<Protocol> protocols = Arrays.asList(Protocol.HTTP_1_1);
  private final FakeDns dns = new FakeDns();
  private final RecordingProxySelector proxySelector = new RecordingProxySelector();
  private RouteDatabase routeDatabase = new RouteDatabase();

  @Before public void setUp() throws Exception {
    call = clientTestRule.newClient().newCall(new Request.Builder()
        .url("https://" + uriHost + ":" + uriPort + "/")
        .build());
    socketFactory = SocketFactory.getDefault();
    hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
  }

  @Test public void singleRoute() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(uriHost, dns.allocate(1));
    RouteSelector.Selection selection = routeSelector.next();
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    dns.assertRequests(uriHost);
    assertThat(selection.hasNext()).isFalse();
    try {
      selection.next();
      fail();
    } catch (NoSuchElementException expected) {
    }

    assertThat(routeSelector.hasNext()).isFalse();
    try {
      routeSelector.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void singleRouteReturnsFailedRoute() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(uriHost, dns.allocate(1));
    RouteSelector.Selection selection = routeSelector.next();
    Route route = selection.next();
    routeDatabase.failed(route);
    routeSelector = new RouteSelector(address, routeDatabase, call, EventListener.NONE);
    selection = routeSelector.next();
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    assertThat(selection.hasNext()).isFalse();

    try {
      selection.next();
      fail();
    } catch (NoSuchElementException expected) {
    }

    assertThat(routeSelector.hasNext()).isFalse();
    try {
      routeSelector.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void explicitProxyTriesThatProxysAddressesOnly() throws Exception {
    Address address = new Address(uriHost, uriPort, dns, socketFactory, null, null, null,
        authenticator, proxyA, protocols, connectionSpecs, proxySelector);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(proxyAHost, dns.allocate(2));
    RouteSelector.Selection selection = routeSelector.next();
    assertRoute(selection.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    assertRoute(selection.next(), address, proxyA, dns.lookup(proxyAHost, 1), proxyAPort);

    assertThat(selection.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
    dns.assertRequests(proxyAHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void explicitDirectProxy() throws Exception {
    Address address = new Address(uriHost, uriPort, dns, socketFactory, null, null, null,
        authenticator, NO_PROXY, protocols, connectionSpecs, proxySelector);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(uriHost, dns.allocate(2));
    RouteSelector.Selection selection = routeSelector.next();
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 1), uriPort);

    assertThat(selection.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
    dns.assertRequests(uriHost);
    proxySelector.assertRequests(); // No proxy selector requests!
  }

  @Test public void proxySelectorReturnsNull() throws Exception {
    ProxySelector nullProxySelector = new ProxySelector() {
      @Override public List<Proxy> select(URI uri) {
        assertThat(uri.getHost()).isEqualTo(uriHost);
        return null;
      }

      @Override public void connectFailed(
          URI uri, SocketAddress socketAddress, IOException e) {
        throw new AssertionError();
      }
    };

    Address address = new Address(uriHost, uriPort, dns, socketFactory, null, null, null,
        authenticator, null, protocols, connectionSpecs, nullProxySelector);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);
    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(uriHost, dns.allocate(1));
    RouteSelector.Selection selection = routeSelector.next();
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    dns.assertRequests(uriHost);

    assertThat(selection.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void proxySelectorReturnsNoProxies() throws Exception {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(uriHost, dns.allocate(2));
    RouteSelector.Selection selection = routeSelector.next();
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 1), uriPort);

    assertThat(selection.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
    dns.assertRequests(uriHost);
    proxySelector.assertRequests(address.url().uri());
  }

  @Test public void proxySelectorReturnsMultipleProxies() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);
    proxySelector.assertRequests(address.url().uri());

    // First try the IP addresses of the first proxy, in sequence.
    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(proxyAHost, dns.allocate(2));
    RouteSelector.Selection selection1 = routeSelector.next();
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 1), proxyAPort);
    dns.assertRequests(proxyAHost);
    assertThat(selection1.hasNext()).isFalse();

    // Next try the IP address of the second proxy.
    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(proxyBHost, dns.allocate(1));
    RouteSelector.Selection selection2 = routeSelector.next();
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 0), proxyBPort);
    dns.assertRequests(proxyBHost);
    assertThat(selection2.hasNext()).isFalse();

    // No more proxies to try.
    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void proxySelectorDirectConnectionsAreSkipped() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(NO_PROXY);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);
    proxySelector.assertRequests(address.url().uri());

    // Only the origin server will be attempted.
    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(uriHost, dns.allocate(1));
    RouteSelector.Selection selection = routeSelector.next();
    assertRoute(selection.next(), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    dns.assertRequests(uriHost);

    assertThat(selection.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void proxyDnsFailureContinuesToNextProxy() throws Exception {
    Address address = httpAddress();

    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    proxySelector.proxies.add(proxyA);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);
    proxySelector.assertRequests(address.url().uri());

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(proxyAHost, dns.allocate(1));
    RouteSelector.Selection selection1 = routeSelector.next();
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    dns.assertRequests(proxyAHost);
    assertThat(selection1.hasNext()).isFalse();

    assertThat(routeSelector.hasNext()).isTrue();
    dns.clear(proxyBHost);
    try {
      routeSelector.next();
      fail();
    } catch (UnknownHostException expected) {
    }
    dns.assertRequests(proxyBHost);

    assertThat(routeSelector.hasNext()).isTrue();
    dns.set(proxyAHost, dns.allocate(1));
    RouteSelector.Selection selection2 = routeSelector.next();
    assertRoute(selection2.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    dns.assertRequests(proxyAHost);

    assertThat(selection2.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void multipleProxiesMultipleInetAddressesMultipleConfigurations() throws Exception {
    Address address = httpsAddress();
    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    // Proxy A
    dns.set(proxyAHost, dns.allocate(2));
    RouteSelector.Selection selection1 = routeSelector.next();
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    dns.assertRequests(proxyAHost);
    assertRoute(selection1.next(), address, proxyA, dns.lookup(proxyAHost, 1), proxyAPort);
    assertThat(selection1.hasNext()).isFalse();

    // Proxy B
    dns.set(proxyBHost, dns.allocate(2));
    RouteSelector.Selection selection2 = routeSelector.next();
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 0), proxyBPort);
    dns.assertRequests(proxyBHost);
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 1), proxyBPort);
    assertThat(selection2.hasNext()).isFalse();

    // No more proxies to attempt.
    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void failedRouteWithSingleProxy() throws Exception {
    Address address = httpsAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    final int numberOfAddresses = 2;
    dns.set(uriHost, dns.allocate(numberOfAddresses));

    // Extract the regular sequence of routes from selector.
    RouteSelector.Selection selection1 = routeSelector.next();
    List<Route> regularRoutes = selection1.getRoutes();

    // Check that we do indeed have more than one route.
    assertThat(regularRoutes.size()).isEqualTo(numberOfAddresses);
    // Add first regular route as failed.
    routeDatabase.failed(regularRoutes.get(0));
    // Reset selector
    routeSelector = new RouteSelector(address, routeDatabase, call, EventListener.NONE);

    // The first selection prioritizes the non-failed routes.
    RouteSelector.Selection selection2 = routeSelector.next();
    assertThat(selection2.next()).isEqualTo(regularRoutes.get(1));
    assertThat(selection2.hasNext()).isFalse();

    // The second selection will contain all failed routes.
    RouteSelector.Selection selection3 = routeSelector.next();
    assertThat(selection3.next()).isEqualTo(regularRoutes.get(0));
    assertThat(selection3.hasNext()).isFalse();

    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void failedRouteWithMultipleProxies() throws IOException {
    Address address = httpsAddress();
    proxySelector.proxies.add(proxyA);
    proxySelector.proxies.add(proxyB);
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    dns.set(proxyAHost, dns.allocate(1));
    dns.set(proxyBHost, dns.allocate(1));

    // Mark the ProxyA route as failed.
    RouteSelector.Selection selection = routeSelector.next();
    dns.assertRequests(proxyAHost);
    Route route = selection.next();
    assertRoute(route, address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    routeDatabase.failed(route);

    routeSelector = new RouteSelector(address, routeDatabase, call, EventListener.NONE);

    // Confirm we enumerate both proxies, giving preference to the route from ProxyB.
    RouteSelector.Selection selection2 = routeSelector.next();
    dns.assertRequests(proxyAHost, proxyBHost);
    assertRoute(selection2.next(), address, proxyB, dns.lookup(proxyBHost, 0), proxyBPort);
    assertThat(selection2.hasNext()).isFalse();

    // Confirm the last selection contains the postponed route from ProxyA.
    RouteSelector.Selection selection3 = routeSelector.next();
    dns.assertRequests();
    assertRoute(selection3.next(), address, proxyA, dns.lookup(proxyAHost, 0), proxyAPort);
    assertThat(selection3.hasNext()).isFalse();

    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void queryForAllSelectedRoutes() throws IOException {
    Address address = httpAddress();
    RouteSelector routeSelector = new RouteSelector(address, routeDatabase, call,
        EventListener.NONE);

    dns.set(uriHost, dns.allocate(2));
    RouteSelector.Selection selection = routeSelector.next();
    dns.assertRequests(uriHost);

    List<Route> routes = selection.getRoutes();
    assertRoute(routes.get(0), address, NO_PROXY, dns.lookup(uriHost, 0), uriPort);
    assertRoute(routes.get(1), address, NO_PROXY, dns.lookup(uriHost, 1), uriPort);

    assertThat(selection.next()).isSameAs(routes.get(0));
    assertThat(selection.next()).isSameAs(routes.get(1));
    assertThat(selection.hasNext()).isFalse();
    assertThat(routeSelector.hasNext()).isFalse();
  }

  @Test public void getHostString() throws Exception {
    // Name proxy specification.
    InetSocketAddress socketAddress = InetSocketAddress.createUnresolved("host", 1234);
    assertThat(RouteSelector.Companion.getSocketHost(socketAddress)).isEqualTo("host");
    socketAddress = InetSocketAddress.createUnresolved("127.0.0.1", 1234);
    assertThat(RouteSelector.Companion.getSocketHost(socketAddress)).isEqualTo("127.0.0.1");

    // InetAddress proxy specification.
    socketAddress = new InetSocketAddress(InetAddress.getByName("localhost"), 1234);
    assertThat(RouteSelector.Companion.getSocketHost(socketAddress)).isEqualTo("127.0.0.1");
    socketAddress = new InetSocketAddress(
        InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 1234);
    assertThat(RouteSelector.Companion.getSocketHost(socketAddress)).isEqualTo("127.0.0.1");
    socketAddress = new InetSocketAddress(
        InetAddress.getByAddress("foobar", new byte[] {127, 0, 0, 1}), 1234);
    assertThat(RouteSelector.Companion.getSocketHost(socketAddress)).isEqualTo("127.0.0.1");
  }

  @Test public void routeToString() throws Exception {
    Route route = new Route(httpAddress(), Proxy.NO_PROXY,
        InetSocketAddress.createUnresolved("host", 1234));
    assertThat(route.toString()).isEqualTo("Route{host:1234}");
  }

  private void assertRoute(Route route, Address address, Proxy proxy, InetAddress socketAddress,
      int socketPort) {
    assertThat(route.address()).isEqualTo(address);
    assertThat(route.proxy()).isEqualTo(proxy);
    assertThat(route.socketAddress().getAddress()).isEqualTo(socketAddress);
    assertThat(route.socketAddress().getPort()).isEqualTo(socketPort);
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
}
