package com.squareup.okhttp.apache;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import java.io.IOException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OkApacheClientTest {
  private MockWebServer server = new MockWebServer();
  private OkApacheClient client = new OkApacheClient();

  @After public void tearDown() throws IOException {
    server.shutdown();
  }

  @Test public void success() throws Exception {
    server.enqueue(new MockResponse().setBody("Hello, World!"));
    server.play();

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = client.execute(request);
    String actual = EntityUtils.toString(response.getEntity());
    assertEquals("Hello, World!", actual);
  }

  @Test public void redirect() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/foo"));
    server.enqueue(new MockResponse().setBody("Hello, Redirect!"));
    server.play();

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = client.execute(request);
    String actual = EntityUtils.toString(response.getEntity());
    assertEquals("Hello, Redirect!", actual);
  }

  @Test public void sessionExpired() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(422));
    server.play();

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = client.execute(request);
    assertEquals(422, response.getStatusLine().getStatusCode());
  }

  @Test public void headers() throws Exception {
    server.enqueue(new MockResponse().addHeader("Foo", "Bar"));
    server.enqueue(new MockResponse().addHeader("Foo", "Bar").addHeader("Foo", "Baz"));
    server.play();

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response1 = client.execute(request1);
    Header[] headers1 = response1.getHeaders("Foo");
    assertEquals(1, headers1.length);
    assertEquals("Bar", headers1[0].getValue());

    HttpGet request2 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response2 = client.execute(request2);
    Header[] headers2 = response2.getHeaders("Foo");
    assertEquals(2, headers2.length);
    assertEquals("Bar", headers2[0].getValue());
    assertEquals("Baz", headers2[1].getValue());
  }

  @Test public void noEntity() throws Exception {
    server.enqueue(new MockResponse());
    server.play();

    HttpPost post = new HttpPost(server.getUrl("/").toURI());
    client.execute(post);
  }
}
