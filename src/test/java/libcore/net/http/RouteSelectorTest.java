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
package libcore.net.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import static java.net.Proxy.NO_PROXY;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import junit.framework.TestCase;
import libcore.net.Dns;
import static libcore.net.http.HttpConnection.TLS_MODE_AGGRESSIVE;
import static libcore.net.http.HttpConnection.TLS_MODE_COMPATIBLE;
import libcore.net.ssl.SslContextBuilder;

public final class RouteSelectorTest extends TestCase {
    private static final int proxyAPort = 1001;
    private static final String proxyAHost = "proxyA";
    private static final Proxy proxyA
            = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyAHost, proxyAPort));
    private static final int proxyBPort = 1002;
    private static final String proxyBHost = "proxyB";
    private static final Proxy proxyB
            = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyBHost, proxyBPort));
    private static final URI uri;
    private static final String uriHost = "hostA";
    private static final int uriPort = 80;

    private static final SSLContext sslContext;
    private static final SSLSocketFactory socketFactory;
    private static final HostnameVerifier hostnameVerifier;
    static {
        try {
            uri = new URI("http://" + uriHost + ":" + uriPort + "/path");
            sslContext = new SslContextBuilder(InetAddress.getLocalHost().getHostName()).build();
            socketFactory = sslContext.getSocketFactory();
            hostnameVerifier = HttpsURLConnectionImpl.getDefaultHostnameVerifier();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private final FakeDns dns = new FakeDns();
    private final FakeProxySelector proxySelector = new FakeProxySelector();

    public void testSingleRoute() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, null);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
        try {
            routeSelector.next();
            fail();
        } catch (NoSuchElementException expected) {
        }
    }

    public void testExplicitProxyTriesThatProxiesAddressesOnly() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, proxyA);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, TLS_MODE_COMPATIBLE);

        assertFalse(routeSelector.hasNext());
        dns.assertRequests(proxyAHost);
        proxySelector.assertRequests(); // No proxy selector requests!
    }

    public void testExplicitDirectProxy() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, NO_PROXY);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, TLS_MODE_COMPATIBLE);

        assertFalse(routeSelector.hasNext());
        dns.assertRequests(uri.getHost());
        proxySelector.assertRequests(); // No proxy selector requests!
    }

    public void testProxySelectorReturnsNull() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, null);

        proxySelector.proxies = null;
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);
        proxySelector.assertRequests(uri);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    public void testProxySelectorReturnsNoProxies() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, null);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, TLS_MODE_COMPATIBLE);

        assertFalse(routeSelector.hasNext());
        dns.assertRequests(uri.getHost());
        proxySelector.assertRequests(uri);
    }

    public void testProxySelectorReturnsMultipleProxies() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, null);

        proxySelector.proxies.add(proxyA);
        proxySelector.proxies.add(proxyB);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);
        proxySelector.assertRequests(uri);

        // First try the IP addresses of the first proxy, in sequence.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(proxyAHost);

        // Next try the IP address of the second proxy.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(254, 1);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[0], proxyBPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(proxyBHost);

        // Finally try the only IP address of the origin server.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(253, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    public void testProxySelectorDirectConnectionsAreSkipped() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, null);

        proxySelector.proxies.add(NO_PROXY);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);
        proxySelector.assertRequests(uri);

        // Only the origin server will be attempted.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    public void testProxyDnsFailureContinuesToNextProxy() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(uri, null, null, null);

        proxySelector.proxies.add(proxyA);
        proxySelector.proxies.add(proxyB);
        proxySelector.proxies.add(proxyA);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);
        proxySelector.assertRequests(uri);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(proxyAHost);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = null;
        try {
            routeSelector.next();
            fail();
        } catch (UnknownHostException expected) {
        }
        dns.assertRequests(proxyBHost);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(proxyAHost);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(254, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    public void testMultipleTlsModes() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(
                uri, socketFactory, hostnameVerifier, Proxy.NO_PROXY);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_AGGRESSIVE);
        dns.assertRequests(uriHost);

        assertTrue(routeSelector.hasNext());
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        dns.assertRequests(); // No more DNS requests since the previous!

        assertFalse(routeSelector.hasNext());
    }

    public void testMultipleProxiesMultipleInetAddressesMultipleTlsModes() throws Exception {
        HttpConnection.Address address = new HttpConnection.Address(
                uri, socketFactory, hostnameVerifier, null);
        proxySelector.proxies.add(proxyA);
        proxySelector.proxies.add(proxyB);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, dns);

        // Proxy A
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, TLS_MODE_AGGRESSIVE);
        dns.assertRequests(proxyAHost);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, TLS_MODE_AGGRESSIVE);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, TLS_MODE_COMPATIBLE);

        // Proxy B
        dns.inetAddresses = makeFakeAddresses(254, 2);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[0], proxyBPort, TLS_MODE_AGGRESSIVE);
        dns.assertRequests(proxyBHost);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[0], proxyBPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[1], proxyBPort, TLS_MODE_AGGRESSIVE);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[1], proxyBPort, TLS_MODE_COMPATIBLE);

        // Origin
        dns.inetAddresses = makeFakeAddresses(253, 2);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_AGGRESSIVE);
        dns.assertRequests(uriHost);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, TLS_MODE_COMPATIBLE);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, TLS_MODE_AGGRESSIVE);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, TLS_MODE_COMPATIBLE);

        assertFalse(routeSelector.hasNext());
    }

    private void assertConnection(HttpConnection connection, HttpConnection.Address address,
            Proxy proxy, InetAddress socketAddress, int socketPort, int tlsMode) {
        assertEquals(address, connection.address);
        assertEquals(proxy, connection.proxy);
        assertEquals(socketAddress, connection.inetSocketAddress.getAddress());
        assertEquals(socketPort, connection.inetSocketAddress.getPort());
        assertEquals(tlsMode, connection.tlsMode);
    }

    private static InetAddress[] makeFakeAddresses(int prefix, int count) {
        try {
            InetAddress[] result = new InetAddress[count];
            for (int i = 0; i < count; i++) {
                result[i] = InetAddress.getByAddress(
                        new byte[] { (byte) prefix, (byte) 0, (byte) 0, (byte) i });
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

    private static class FakeProxySelector extends ProxySelector {
        List<URI> requestedUris = new ArrayList<URI>();
        List<Proxy> proxies = new ArrayList<Proxy>();
        List<String> failures = new ArrayList<String>();

        @Override public List<Proxy> select(URI uri) {
            requestedUris.add(uri);
            return proxies;
        }

        public void assertRequests(URI... expectedUris) {
            assertEquals(Arrays.asList(expectedUris), requestedUris);
            requestedUris.clear();
        }

        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            InetSocketAddress socketAddress = (InetSocketAddress) sa;
            failures.add(String.format("%s %s:%d %s", uri, socketAddress.getHostName(),
                    socketAddress.getPort(), ioe.getMessage()));
        }
    }
}
