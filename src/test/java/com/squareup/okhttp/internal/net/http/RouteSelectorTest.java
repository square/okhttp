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
package com.squareup.okhttp.internal.net.http;

import com.squareup.okhttp.Address;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.internal.net.Dns;
import com.squareup.okhttp.internal.net.ssl.SslContextBuilder;
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public final class RouteSelectorTest {
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
    private static final ConnectionPool pool;
    static {
        try {
            uri = new URI("http://" + uriHost + ":" + uriPort + "/path");
            sslContext = new SslContextBuilder(InetAddress.getLocalHost().getHostName()).build();
            socketFactory = sslContext.getSocketFactory();
            pool = ConnectionPool.getDefault();
            hostnameVerifier = HttpsURLConnectionImpl.getDefaultHostnameVerifier();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private final FakeDns dns = new FakeDns();
    private final FakeProxySelector proxySelector = new FakeProxySelector();

    @Test public void singleRoute() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, null);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
        try {
            routeSelector.next();
            fail();
        } catch (NoSuchElementException expected) {
        }
    }

    @Test public void explicitProxyTriesThatProxiesAddressesOnly() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, proxyA);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, false);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, false);

        assertFalse(routeSelector.hasNext());
        dns.assertRequests(proxyAHost);
        proxySelector.assertRequests(); // No proxy selector requests!
    }

    @Test public void explicitDirectProxy() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, NO_PROXY);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, false);

        assertFalse(routeSelector.hasNext());
        dns.assertRequests(uri.getHost());
        proxySelector.assertRequests(); // No proxy selector requests!
    }

    @Test public void proxySelectorReturnsNull() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, null);

        proxySelector.proxies = null;
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);
        proxySelector.assertRequests(uri);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    @Test public void proxySelectorReturnsNoProxies() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, null);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, false);

        assertFalse(routeSelector.hasNext());
        dns.assertRequests(uri.getHost());
        proxySelector.assertRequests(uri);
    }

    @Test public void proxySelectorReturnsMultipleProxies() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, null);

        proxySelector.proxies.add(proxyA);
        proxySelector.proxies.add(proxyB);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);
        proxySelector.assertRequests(uri);

        // First try the IP addresses of the first proxy, in sequence.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, false);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, false);
        dns.assertRequests(proxyAHost);

        // Next try the IP address of the second proxy.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(254, 1);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[0], proxyBPort, false);
        dns.assertRequests(proxyBHost);

        // Finally try the only IP address of the origin server.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(253, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    @Test public void proxySelectorDirectConnectionsAreSkipped() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, null);

        proxySelector.proxies.add(NO_PROXY);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);
        proxySelector.assertRequests(uri);

        // Only the origin server will be attempted.
        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    @Test public void proxyDnsFailureContinuesToNextProxy() throws Exception {
        Address address = new Address(uriHost, uriPort, null, null, null);

        proxySelector.proxies.add(proxyA);
        proxySelector.proxies.add(proxyB);
        proxySelector.proxies.add(proxyA);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);
        proxySelector.assertRequests(uri);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, false);
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
                address, proxyA, dns.inetAddresses[0], proxyAPort, false);
        dns.assertRequests(proxyAHost);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(254, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        dns.assertRequests(uriHost);

        assertFalse(routeSelector.hasNext());
    }

    @Test public void multipleTlsModes() throws Exception {
        Address address = new Address(
                uriHost, uriPort, socketFactory, hostnameVerifier, Proxy.NO_PROXY);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);

        assertTrue(routeSelector.hasNext());
        dns.inetAddresses = makeFakeAddresses(255, 1);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, true);
        dns.assertRequests(uriHost);

        assertTrue(routeSelector.hasNext());
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        dns.assertRequests(); // No more DNS requests since the previous!

        assertFalse(routeSelector.hasNext());
    }

    @Test public void multipleProxiesMultipleInetAddressesMultipleTlsModes() throws Exception {
        Address address = new Address(
                uriHost, uriPort, socketFactory, hostnameVerifier, null);
        proxySelector.proxies.add(proxyA);
        proxySelector.proxies.add(proxyB);
        RouteSelector routeSelector = new RouteSelector(address, uri, proxySelector, pool, dns);

        // Proxy A
        dns.inetAddresses = makeFakeAddresses(255, 2);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, true);
        dns.assertRequests(proxyAHost);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[0], proxyAPort, false);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, true);
        assertConnection(routeSelector.next(),
                address, proxyA, dns.inetAddresses[1], proxyAPort, false);

        // Proxy B
        dns.inetAddresses = makeFakeAddresses(254, 2);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[0], proxyBPort, true);
        dns.assertRequests(proxyBHost);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[0], proxyBPort, false);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[1], proxyBPort, true);
        assertConnection(routeSelector.next(),
                address, proxyB, dns.inetAddresses[1], proxyBPort, false);

        // Origin
        dns.inetAddresses = makeFakeAddresses(253, 2);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, true);
        dns.assertRequests(uriHost);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[0], uriPort, false);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, true);
        assertConnection(routeSelector.next(),
                address, NO_PROXY, dns.inetAddresses[1], uriPort, false);

        assertFalse(routeSelector.hasNext());
    }

    private void assertConnection(Connection connection, Address address,
            Proxy proxy, InetAddress socketAddress, int socketPort, boolean modernTls) {
        assertEquals(address, connection.getAddress());
        assertEquals(proxy, connection.getProxy());
        assertEquals(socketAddress, connection.getSocketAddress().getAddress());
        assertEquals(socketPort, connection.getSocketAddress().getPort());
        assertEquals(modernTls, connection.isModernTls());
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
