package com.squareup.okhttp.apache;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

  @Test public void contentType() throws Exception {
    server.enqueue(new MockResponse().setBody("<html><body><h1>Hello, World!</h1></body></html>")
        .setHeader("Content-Type", "text/html"));
    server.enqueue(new MockResponse().setBody("{\"Message\": { \"text\": \"Hello, World!\" } }")
        .setHeader("Content-Type", "application/json"));
    server.enqueue(new MockResponse().setBody("Hello, World!"));

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response1 = client.execute(request1);
    Header[] headers1 = response1.getHeaders("Content-Type");
    assertEquals(1, headers1.length);
    assertEquals("text/html", headers1[0].getValue());
    assertNotNull(response1.getEntity().getContentType());
    assertEquals("text/html", response1.getEntity().getContentType().getValue());

    HttpGet request2 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response2 = client.execute(request2);
    Header[] headers2 = response2.getHeaders("Content-Type");
    assertEquals(1, headers2.length);
    assertEquals("application/json", headers2[0].getValue());
    assertNotNull(response2.getEntity().getContentType());
    assertEquals("application/json", response2.getEntity().getContentType().getValue());

    HttpGet request3 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response3 = client.execute(request3);
    Header[] headers3 = response3.getHeaders("Content-Type");
    assertEquals(0, headers3.length);
    assertNull(response3.getEntity().getContentType());
  }

  @Test public void contentEncoding() throws Exception {
    String text = "{\"Message\": { \"text\": \"Hello, World!\" } }";
    ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
    OutputStreamWriter body = new OutputStreamWriter(new GZIPOutputStream(bodyBytes),
        Charset.forName("UTF-8"));
    body.write(text);
    body.close();
    server.enqueue(new MockResponse().setBody(bodyBytes.toByteArray())
        .setHeader("Content-Encoding", "gzip"));

    byte[] tmp = new byte[32];

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    request1.setHeader("Accept-encoding", "gzip"); // not transparent gzip
    HttpResponse response1 = client.execute(request1);
    Header[] headers1 = response1.getHeaders("Content-Encoding");
    assertEquals(1, headers1.length);
    assertEquals("gzip", headers1[0].getValue());
    assertNotNull(response1.getEntity().getContentEncoding());
    assertEquals("gzip", response1.getEntity().getContentEncoding().getValue());
    InputStream content = new GZIPInputStream(response1.getEntity().getContent());
    ByteArrayOutputStream rspBodyBytes = new ByteArrayOutputStream();
    for (int len = content.read(tmp); len >= 0; len = content.read(tmp)) {
      rspBodyBytes.write(tmp, 0, len);
    }
    String decodedContent = rspBodyBytes.toString("UTF-8");
    assertEquals(text, decodedContent);
  }

  @Test public void jsonGzipResponse() throws Exception {
    String text = "{\"Message\": { \"text\": \"Hello, World!\" } }";
    ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
    OutputStreamWriter body = new OutputStreamWriter(new GZIPOutputStream(bodyBytes),
        Charset.forName("UTF-8"));
    body.write(text);
    body.close();
    server.enqueue(new MockResponse().setBody(bodyBytes.toByteArray())
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", "application/json"));

    byte[] tmp = new byte[32];

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    request1.setHeader("Accept-encoding", "gzip"); // not transparent gzip
    HttpResponse response1 = client.execute(request1);
    Header[] headers1a = response1.getHeaders("Content-Encoding");
    assertEquals(1, headers1a.length);
    assertEquals("gzip", headers1a[0].getValue());
    assertNotNull(response1.getEntity().getContentEncoding());
    assertEquals("gzip", response1.getEntity().getContentEncoding().getValue());
    Header[] headers1b = response1.getHeaders("Content-Type");
    assertEquals(1, headers1b.length);
    assertEquals("application/json", headers1b[0].getValue());
    assertNotNull(response1.getEntity().getContentType());
    assertEquals("application/json", response1.getEntity().getContentType().getValue());
    InputStream content = new GZIPInputStream(response1.getEntity().getContent());
    ByteArrayOutputStream rspBodyBytes = new ByteArrayOutputStream();
    for (int len = content.read(tmp); len >= 0; len = content.read(tmp)) {
      rspBodyBytes.write(tmp, 0, len);
    }
    String decodedContent = rspBodyBytes.toString("UTF-8");
    assertEquals(text, decodedContent);
  }

  @Test public void jsonTransparentGzipResponse() throws Exception {
    String text = "{\"Message\": { \"text\": \"Hello, World!\" } }";
    ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
    OutputStreamWriter body = new OutputStreamWriter(new GZIPOutputStream(bodyBytes),
        Charset.forName("UTF-8"));
    body.write(text);
    body.close();
    server.enqueue(new MockResponse().setBody(bodyBytes.toByteArray())
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", "application/json"));

    byte[] tmp = new byte[32];

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    // expecting transparent gzip response by not adding header "Accept-encoding: gzip"
    HttpResponse response1 = client.execute(request1);
    Header[] headers1a = response1.getHeaders("Content-Encoding");
    assertEquals(0, headers1a.length);
    assertNull(response1.getEntity().getContentEncoding());
    // content length should also be absent
    Header[] headers1b = response1.getHeaders("Content-Length");
    assertEquals(0, headers1b.length);
    assertTrue(response1.getEntity().getContentLength() < 0);
    Header[] headers1c = response1.getHeaders("Content-Type");
    assertEquals(1, headers1c.length);
    assertEquals("application/json", headers1c[0].getValue());
    assertNotNull(response1.getEntity().getContentType());
    assertEquals("application/json", response1.getEntity().getContentType().getValue());
    InputStream content = response1.getEntity().getContent();
    ByteArrayOutputStream rspBodyBytes = new ByteArrayOutputStream();
    for (int len = content.read(tmp); len >= 0; len = content.read(tmp)) {
      rspBodyBytes.write(tmp, 0, len);
    }
    String decodedContent = rspBodyBytes.toString("UTF-8");
    assertEquals(text, decodedContent);
  }
}
