package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Connection;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.HostResolver;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EagerRouteSelectorTest {
  @Rule public MockWebServerRule serverRule = new MockWebServerRule();

  private OkHttpClient client;
  private RouteSelector routeSelector;
  private InetAddress[] ipsToReturn;

  @Before
  public void setUp() throws Exception {
    client = new OkHttpClient()
        .setAuthenticator(AuthenticatorAdapter.INSTANCE)
        .setProtocols(Arrays.asList(Protocol.HTTP_1_1))
        .setProxy(Proxy.NO_PROXY)
        .setMaxConcurrentHandshakes(2)
        .setConnectionPool(ConnectionPool.getDefault())
        .setSocketFactory(SocketFactory.getDefault())
        .setHostResolver(new TestHostResolver());
    client.setConnectTimeout(1, TimeUnit.SECONDS);

    Request request = new Request.Builder()
        .url("http://example.com:" + serverRule.getPort() + "/")
        .build();
    routeSelector = EagerRouteSelector.get(request, client);
  }

  @After
  public void tearDown() {
    routeSelector.close();
  }

  @Test
  public void testWithSingleIp() throws Exception {
    ipsToReturn = new InetAddress[] {InetAddress.getByName("localhost")};

    assertTrue(routeSelector.hasNext());
    Connection connection = routeSelector.next(null);
    assertFalse(routeSelector.hasNext());

    Internal.instance.recycle(client.getConnectionPool(), connection);
  }

  @Test
  public void testWithSeveralIps() throws Exception {
    ipsToReturn = new InetAddress[] {
        // These two will fail - 192.0.2.0-192.0.2.255 are reserved for documentation.
        InetAddress.getByName("192.0.2.0"),
        InetAddress.getByName("192.0.2.1"),
        // This one will work.
        InetAddress.getByName("localhost"),
    };

    for (int i = 0; i < 2; ++i) {
      assertTrue(routeSelector.hasNext());
      try {
        routeSelector.next(null);
        fail("Expected connection failure.");
      } catch (IOException expected) {
      }
    }
    assertTrue(routeSelector.hasNext());
    Connection goodConnection = routeSelector.next(null);
    assertFalse(routeSelector.hasNext());

    Internal.instance.recycle(client.getConnectionPool(), goodConnection);
  }

  /**
   * A mock {@link HostResolver} which only knows about example.com.
   */
  private class TestHostResolver implements HostResolver {
    @Override public InetAddress[] getAllByName(String host) throws UnknownHostException {
      if (host.equals("example.com")) {
        return ipsToReturn;
      }
      throw new UnknownHostException("Unknown host " + host);
    }
  }
}
