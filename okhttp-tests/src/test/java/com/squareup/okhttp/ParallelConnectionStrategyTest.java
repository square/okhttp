package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.AuthenticatorAdapter;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import javax.net.SocketFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static com.squareup.okhttp.internal.http.RouteSelector.TLS_V1;

public class ParallelConnectionStrategyTest {
  @Rule public MockWebServerRule serverRule = new MockWebServerRule();
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test public void testConnectionToLocalServer() throws Exception {
    Address httpAddress = new Address(serverRule.get().getHostName(), serverRule.getPort(),
        SocketFactory.getDefault(), null, null, AuthenticatorAdapter.INSTANCE, null,
        Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1));
    InetSocketAddress httpSocketAddress = new InetSocketAddress(
        InetAddress.getByName(serverRule.get().getHostName()),
        serverRule.getPort());
    Route route = new Route(httpAddress, Proxy.NO_PROXY, httpSocketAddress, TLS_V1);
    Socket socket = ParallelConnectionStrategy.DEFAULT.connect(route, 200);
    socket.close();
  }

  @Test public void testConnectionToGoodAndBadIp() throws Exception {
    Address address = new Address("example.com", serverRule.getPort(), SocketFactory.getDefault(),
        null, null, AuthenticatorAdapter.INSTANCE, null,
        Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1));
    Route route = new Route(address, Proxy.NO_PROXY,
        InetSocketAddress.createUnresolved("example.com", serverRule.getPort()), TLS_V1);

    HostResolver hostResolver = createExampleHostResolver(new InetAddress[] {
        InetAddress.getByName("192.0.2.0"), // This will fail - IP is reserved for documentation.
        InetAddress.getLocalHost() // This will succeed and connect us to the mock web server.
    });

    // Since one of the two IPs will respond, overall the connection should succeed.
    new ParallelConnectionStrategy(hostResolver).connect(route, 200);
  }

  @Test public void testConnectionMultipleBadIps() throws Exception {
    Address address = new Address("example.com", serverRule.getPort(), SocketFactory.getDefault(),
        null, null, AuthenticatorAdapter.INSTANCE, null,
        Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1));
    Route route = new Route(address, Proxy.NO_PROXY,
        InetSocketAddress.createUnresolved("example.com", serverRule.getPort()), TLS_V1);

    HostResolver hostResolver = createExampleHostResolver(new InetAddress[] {
        // These will both fail - 192.0.2.0-192.0.2.255 are reserved for documentation.
        InetAddress.getByName("192.0.2.0"),
        InetAddress.getByName("192.0.2.1")
    });

    expectedException.expect(IOException.class);
    expectedException.expectMessage("Failed to connect");
    new ParallelConnectionStrategy(hostResolver).connect(route, 200);
  }

  /**
   * Create a {@link HostResolver} which resolves example.com to the given IPs.
   */
  private static HostResolver createExampleHostResolver(final InetAddress[] ips) {
    return new HostResolver() {
      @Override public InetAddress[] getAllByName(String host) throws UnknownHostException {
        if (host.equals("example.com")) {
          return ips;
        }
        throw new UnknownHostException("Unknown host " + host);
      }
    };
  }
}
