package okhttp3;

import java.io.IOException;
import java.util.List;
import okhttp3.coalescing.ConnectionInterceptor;
import okhttp3.coalescing.ConnectionTarget;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConnectionCoalescingTest {
  private OkHttpClient client;
  private ConnectionTarget target = new ConnectionTarget();

  @Before
  public void setup() {
    client = new OkHttpClient.Builder().addInterceptor(new ConnectionInterceptor(target)).build();
  }

  @Test
  public void testManualConnectionCoalescing() throws IOException {
    // test that a specific domain can be routed through another API
    // useful for connection coalescing or domain fronting
    // TODO use a real example through google.com if possible

    target.addTarget("pbs.twimg.com", "api.twitter.com");

    Response pbsResponse =
        execute("https://pbs.twimg.com/profile_background_images/481546505468145664/a59ZFvIP.jpeg");

    assertEquals(200, pbsResponse.code());
    assertEquals("pbs.twimg.com", pbsResponse.request().header("Host"));
    // not supported without internal support or more work in interceptor
    //assertEquals("pbs.twimg.com", pbsResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, pbsResponse.protocol());

    assertEquals("api.twitter.com",
        client.connectionPool().liveConnections().get(0).route().socketAddress().getHostName());
  }

  @Test
  public void testManualCoalescing() throws IOException {
    // example that could be best practice for many APIs
    // send all known owned targets to a single host

    target.addTarget("*.twimg.com", "twitter.com");
    target.addTarget("*.twitter.com", "twitter.com");

    Response pbsResponse =
        execute("https://pbs.twimg.com/profile_background_images/481546505468145664/a59ZFvIP.jpeg");
    Response apiResponse = execute("https://api.twitter.com/robots.txt");

    assertEquals(200, pbsResponse.code());
    assertEquals("pbs.twimg.com", pbsResponse.request().header("Host"));
    // not supported without internal support or more work in interceptor
    //assertEquals("pbs.twimg.com", pbsResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, pbsResponse.protocol());

    assertEquals(200, apiResponse.code());
    assertEquals("api.twitter.com", apiResponse.request().header("Host"));
    // not supported without internal support or more work in interceptor
    //assertEquals("api.twitter.com", apiResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, apiResponse.protocol());

    List<Connection> connections = client.connectionPool().liveConnections();
    assertEquals(1, connections.size());
    assertEquals("twitter.com", connections.get(0).route().socketAddress().getHostName());
  }

  @Test
  public void testAutoCoalescing() throws IOException {
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/
    // IP + subjectAlternativeName

    target.setAutoCoalescing(true);

    Response twitterResponse = execute("https://twitter.com/robots.txt");
    Response wwwResponse = execute("https://www.twitter.com/robots.txt");

    assertEquals(200, twitterResponse.code());
    //assertEquals("twitter.com", twitterResponse.request().header("Host"));
    assertEquals("twitter.com", twitterResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, twitterResponse.protocol());

    assertEquals(200, wwwResponse.code());
    assertEquals("www.twitter.com", wwwResponse.request().header("Host"));
    // not supported without internal support or more work in interceptor
    //assertEquals("api.twitter.com", wwwResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, wwwResponse.protocol());

    List<Connection> connections = client.connectionPool().liveConnections();
    assertEquals(1, connections.size());
    assertEquals("twitter.com", connections.get(0).route().socketAddress().getHostName());
  }

  private Response execute(String url) throws IOException {
    return client.newCall(new Request.Builder().url(url).build()).execute();
  }
}
