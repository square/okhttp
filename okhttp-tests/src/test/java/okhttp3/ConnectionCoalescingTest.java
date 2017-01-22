package okhttp3;

import java.io.IOException;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

// TODO implement the test against a local server
public class ConnectionCoalescingTest {
  private OkHttpClient client = new OkHttpClient();

  // TODO test dns don't match
  @Test
  public void testAutoCoalescing() throws IOException {
    // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/
    // Used when both IP + subjectAlternativeName match

    Response twitterResponse = execute("https://twitter.com/robots.txt");
    Response wwwResponse = execute("https://www.twitter.com/robots.txt");

    assertEquals(200, twitterResponse.code());
    assertEquals("twitter.com", twitterResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, twitterResponse.protocol());

    assertEquals(200, wwwResponse.code());
    assertEquals("www.twitter.com", wwwResponse.request().url().host());
    assertEquals(Protocol.HTTP_2, wwwResponse.protocol());

    assertEquals(1, client.connectionPool().connectionCount());
  }

  private Response execute(String url) throws IOException {
    return client.newCall(new Request.Builder().url(url).build()).execute();
  }
}
