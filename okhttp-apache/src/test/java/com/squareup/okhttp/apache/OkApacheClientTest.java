package com.squareup.okhttp.apache;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.RecordedRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OkApacheClientTest {
  private MockWebServer server;
  private OkApacheClient client;

  @Before public void setUp() throws IOException {
    client = new OkApacheClient();
    server = new MockWebServer();
    server.play();
  }

  @After public void tearDown() throws IOException {
    server.shutdown();
  }

  @Test public void success() throws Exception {
    server.enqueue(new MockResponse().setBody("Hello, World!"));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = client.execute(request);
    String actual = EntityUtils.toString(response.getEntity());
    assertEquals("Hello, World!", actual);
  }

  @Test public void redirect() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/foo"));
    server.enqueue(new MockResponse().setBody("Hello, Redirect!"));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = client.execute(request);
    String actual = EntityUtils.toString(response.getEntity());
    assertEquals("Hello, Redirect!", actual);
  }

  @Test public void sessionExpired() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(422));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = client.execute(request);
    assertEquals(422, response.getStatusLine().getStatusCode());
  }

  @Test public void headers() throws Exception {
    server.enqueue(new MockResponse().addHeader("Foo", "Bar"));
    server.enqueue(new MockResponse().addHeader("Foo", "Bar").addHeader("Foo", "Baz"));

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

    HttpPost post = new HttpPost(server.getUrl("/").toURI());
    client.execute(post);
  }

  @Test public void postByteEntity() throws Exception {
    server.enqueue(new MockResponse());

    final HttpPost post = new HttpPost(server.getUrl("/").toURI());
    byte[] body = "Hello, world!".getBytes("UTF-8");
    post.setEntity(new ByteArrayEntity(body));
    client.execute(post);

    RecordedRequest request = server.takeRequest();
    assertTrue(Arrays.equals(body, request.getBody()));
    assertEquals(request.getHeader("Content-Length"), "13");
  }

  @Test public void postInputStreamEntity() throws Exception {
    server.enqueue(new MockResponse());

    final HttpPost post = new HttpPost(server.getUrl("/").toURI());
    byte[] body = "Hello, world!".getBytes("UTF-8");
    post.setEntity(new InputStreamEntity(new ByteArrayInputStream(body), body.length));
    client.execute(post);

    RecordedRequest request = server.takeRequest();
    assertTrue(Arrays.equals(body, request.getBody()));
    assertEquals(request.getHeader("Content-Length"), "13");
  }
}
