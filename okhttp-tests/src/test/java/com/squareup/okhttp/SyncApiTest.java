/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.RecordingHostnameVerifier;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.SocketPolicy;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SyncApiTest {
  private MockWebServer server = new MockWebServer();
  private OkHttpClient client = new OkHttpClient();

  private static final SSLContext sslContext = SslContextBuilder.localhost();
  private HttpResponseCache cache;

  @Before public void setUp() throws Exception {
    String tmp = System.getProperty("java.io.tmpdir");
    File cacheDir = new File(tmp, "HttpCache-" + UUID.randomUUID());
    cache = new HttpResponseCache(cacheDir, Integer.MAX_VALUE);
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    cache.delete();
  }

  @Test public void get() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    onSuccess(request)
        .assertCode(200)
        .assertContainsHeaders("Content-Type: text/plain")
        .assertBody("abc");

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: SyncApiTest"));
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));
    server.play();

    onSuccess(new Request.Builder().url(server.getUrl("/a")).build())
        .assertBody("abc");

    onSuccess(new Request.Builder().url(server.getUrl("/b")).build())
        .assertBody("def");

    onSuccess(new Request.Builder().url(server.getUrl("/c")).build())
        .assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void tls() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    onSuccess(new Request.Builder().url(server.getUrl("/")).build())
        .assertHandshake();
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    onSuccess(new Request.Builder().url(server.getUrl("/")).build())
        .assertBody("abc");
  }

  @Test public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "def"))
        .build();

    onSuccess(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void conditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    client.setOkResponseCache(cache);

    onSuccess(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    onSuccess(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("A");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void conditionalCacheMiss() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    client.setOkResponseCache(cache);

    onSuccess(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    onSuccess(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("B");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void redirect() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .addHeader("Test", "Redirect from /a to /b")
        .setBody("/a has moved!"));
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /c")
        .addHeader("Test", "Redirect from /b to /c")
        .setBody("/b has moved!"));
    server.enqueue(new MockResponse().setBody("C"));
    server.play();

    onSuccess(new Request.Builder().url(server.getUrl("/a")).build())
        .assertCode(200)
        .assertBody("C")
        .redirectedBy()
        .assertCode(302)
        .assertContainsHeaders("Test: Redirect from /b to /c")
        .redirectedBy()
        .assertCode(301)
        .assertContainsHeaders("Test: Redirect from /a to /b");

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reused.
    assertEquals(2, server.takeRequest().getSequenceNumber()); // Connection reused again!
  }

  @Test public void redirectWithRedirectsDisabled() throws Exception {
    client.setFollowSslRedirects(false);
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .addHeader("Test", "Redirect from /a to /b")
        .setBody("/a has moved!"));
    server.play();

    onSuccess(new Request.Builder().url(server.getUrl("/a")).build())
        .assertCode(301)
        .assertBody("/a has moved!")
        .assertContainsHeaders("Location: /b");
  }

  @Test public void follow20Redirects() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));
    server.play();

    onSuccess(new Request.Builder().url(server.getUrl("/0")).build())
        .assertCode(200)
        .assertBody("Success!");
  }

  @Test public void doesNotFollow21Redirects() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.play();

    try {
      client.execute(new Request.Builder().url(server.getUrl("/0")).build());
      fail();
    } catch (IOException e) {
      assertEquals("Too many redirects: 21", e.getMessage());
    }
  }

  @Test public void postBodyRetransmittedOnRedirect() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b")
        .setBody("Moved to /b !"));
    server.enqueue(new MockResponse()
        .setBody("This is b."));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "body!"))
        .build();

    onSuccess(request)
        .assertCode(200)
        .assertBody("This is b.");

    RecordedRequest request1 = server.takeRequest();
    assertEquals("body!", request1.getUtf8Body());
    assertEquals("5", request1.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", request1.getHeader("Content-Type"));
    assertEquals(0, request1.getSequenceNumber());

    RecordedRequest request2 = server.takeRequest();
    assertEquals("body!", request2.getUtf8Body());
    assertEquals("5", request2.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", request2.getHeader("Content-Type"));
    assertEquals(1, request2.getSequenceNumber());
  }

  private RecordedResponse onSuccess(Request request) throws IOException {
    Response response = client.execute(request);
    return new RecordedResponse(request, response, response.body().string(), null);
  }
}
