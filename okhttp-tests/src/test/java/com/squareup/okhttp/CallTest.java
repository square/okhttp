/*
 * Copyright (C) 2013 Square, Inc.
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
import com.squareup.okhttp.mockwebserver.Dispatcher;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.SocketPolicy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import okio.BufferedSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CallTest {
  private MockWebServer server = new MockWebServer();
  private OkHttpClient client = new OkHttpClient();
  private RecordingCallback callback = new RecordingCallback();

  private static final SSLContext sslContext = SslContextBuilder.localhost();
  private Cache cache;

  @Before public void setUp() throws Exception {
    String tmp = System.getProperty("java.io.tmpdir");
    File cacheDir = new File(tmp, "HttpCache-" + UUID.randomUUID());
    cache = new Cache(cacheDir, Integer.MAX_VALUE);
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    cache.delete();
  }

  @Test public void get() throws Exception {
    server.enqueue(new MockResponse().setBody("abc").addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().length);
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void get_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    get();
  }

  @Test public void get_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    get();
  }

  @Test public void head() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .head()
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("HEAD", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().length);
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void head_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    head();
  }

  @Test public void head_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    head();
  }

  @Test public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void post_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    post();
  }

  @Test public void post_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    post();
  }

  @Test public void postZeroLength() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .method("POST", null)
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().length);
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void postZeroLength_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    postZeroLength();
  }

  @Test public void postZerolength_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postZeroLength();
  }

  @Test public void delete() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .delete()
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("DELETE", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().length);
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void delete_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    delete();
  }

  @Test public void delete_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    delete();
  }

  @Test public void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .put(Request.Body.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PUT", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void put_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    put();
  }

  @Test public void put_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    put();
  }

  @Test public void patch() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .patch(Request.Body.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PATCH", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void patch_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    patch();
  }

  @Test public void patch_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    patch();
  }

  @Test public void illegalToExecuteTwice() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    call.execute();

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: SyncApiTest"));
  }

  @Test public void illegalToExecuteTwice_Async() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    call.enqueue(callback);

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: SyncApiTest"));
  }

  @Test public void get_Async() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "AsyncApiTest")
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: AsyncApiTest"));
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));
    server.play();

    executeSynchronously(new Request.Builder().url(server.getUrl("/a")).build())
        .assertBody("abc");

    executeSynchronously(new Request.Builder().url(server.getUrl("/b")).build())
        .assertBody("def");

    executeSynchronously(new Request.Builder().url(server.getUrl("/c")).build())
        .assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionPooling_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));
    server.play();

    client.newCall(new Request.Builder().url(server.getUrl("/a")).build()).enqueue(callback);
    callback.await(server.getUrl("/a")).assertBody("abc");

    client.newCall(new Request.Builder().url(server.getUrl("/b")).build()).enqueue(callback);
    callback.await(server.getUrl("/b")).assertBody("def");

    client.newCall(new Request.Builder().url(server.getUrl("/c")).build()).enqueue(callback);
    callback.await(server.getUrl("/c")).assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionReuseWhenResponseBodyConsumed_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    client.newCall(request).enqueue(new Response.Callback() {
      @Override public void onFailure(Failure failure) {
        throw new AssertionError();
      }

      @Override public void onResponse(Response response) throws IOException {
        InputStream bytes = response.body().byteStream();
        assertEquals('a', bytes.read());
        assertEquals('b', bytes.read());
        assertEquals('c', bytes.read());

        // This request will share a connection with 'A' cause it's all done.
        client.newCall(new Request.Builder().url(server.getUrl("/b")).build()).enqueue(callback);
      }
    });

    callback.await(server.getUrl("/b")).assertCode(200).assertBody("def");
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reuse!
  }

  @Test public void timeoutsUpdatedOnReusedConnections() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));
    server.play();

    // First request: time out after 1000ms.
    client.setReadTimeout(1000, TimeUnit.MILLISECONDS);
    executeSynchronously(new Request.Builder().url(server.getUrl("/a")).build()).assertBody("abc");

    // Second request: time out after 250ms.
    client.setReadTimeout(250, TimeUnit.MILLISECONDS);
    Request request = new Request.Builder().url(server.getUrl("/b")).build();
    Response response = client.newCall(request).execute();
    BufferedSource bodySource = response.body().source();
    assertEquals('d', bodySource.readByte());

    // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
    long startNanos = System.nanoTime();
    try {
      bodySource.readByte();
      fail();
    } catch (IOException expected) {
      // Timed out as expected.
      long elapsedNanos = System.nanoTime() - startNanos;
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      assertTrue(String.format("Timed out: %sms", elapsedMillis), elapsedMillis < 500);
    }
  }

  @Test public void tls() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertHandshake();
  }

  @Test public void tls_Async() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertHandshake();
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertBody("abc");
  }

  @Test public void recoverFromTlsHandshakeFailure_Async() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertBody("abc");
  }

  @Test public void setFollowSslRedirectsFalse() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: http://square.com"));
    server.play();

    client.setFollowSslRedirects(false);
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder().url(server.getUrl("/")).build();
    Response response = client.newCall(request).execute();
    assertEquals(301, response.code());
  }

  @Test public void post_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "def"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));
    server.play();

    // Seed the connection pool so we have something that can fail.
    Request request1 = new Request.Builder().url(server.getUrl("/")).build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("abc", response1.body().string());

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "body!"))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("def", response2.body().string());

    RecordedRequest get = server.takeRequest();
    assertEquals(0, get.getSequenceNumber());

    RecordedRequest post1 = server.takeRequest();
    assertEquals("body!", post1.getUtf8Body());
    assertEquals(1, post1.getSequenceNumber());

    RecordedRequest post2 = server.takeRequest();
    assertEquals("body!", post2.getUtf8Body());
    assertEquals(0, post2.getSequenceNumber());
  }

  @Test public void conditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    client.setCache(cache);

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("A");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void conditionalCacheHit_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    client.setCache(cache);

    Request request1 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request1).enqueue(callback);
    callback.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("A");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void conditionalCacheMiss() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    client.setCache(cache);

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertCode(200).assertBody("B");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void conditionalCacheMiss_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    client.setCache(cache);

    Request request1 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request1).enqueue(callback);
    callback.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("B");
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

    executeSynchronously(new Request.Builder().url(server.getUrl("/a")).build())
        .assertCode(200)
        .assertBody("C")
        .redirectedBy()
        .assertCode(302)
        .assertHeader("Test", "Redirect from /b to /c")
        .redirectedBy()
        .assertCode(301)
        .assertHeader("Test", "Redirect from /a to /b");

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reused.
    assertEquals(2, server.takeRequest().getSequenceNumber()); // Connection reused again!
  }

  @Test public void postRedirectsToGet() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));
    server.play();

    Response response = client.newCall(new Request.Builder()
        .url(server.getUrl("/page1"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "Request Body"))
        .build()).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest page1 = server.takeRequest();
    assertEquals("POST /page1 HTTP/1.1", page1.getRequestLine());
    assertEquals("Request Body", page1.getUtf8Body());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void redirectsDoNotIncludeTooManyCookies() throws Exception {
    MockWebServer redirectTarget = new MockWebServer();
    redirectTarget.enqueue(new MockResponse().setBody("Page 2"));
    redirectTarget.play();

    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + redirectTarget.getUrl("/")));
    server.play();

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookie = new HttpCookie("c", "cookie");
    cookie.setDomain(server.getCookieDomain());
    cookie.setPath("/");
    String portList = Integer.toString(server.getPort());
    cookie.setPortlist(portList);
    cookieManager.getCookieStore().add(server.getUrl("/").toURI(), cookie);
    client.setCookieHandler(cookieManager);

    Response response = client.newCall(new Request.Builder()
        .url(server.getUrl("/page1"))
        .build()).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest request1 = server.takeRequest();
    assertContains(request1.getHeaders(), "Cookie: $Version=\"1\"; "
        + "c=\"cookie\";$Path=\"/\";$Domain=\"" + server.getCookieDomain()
        + "\";$Port=\"" + portList + "\"");

    RecordedRequest request2 = redirectTarget.takeRequest();
    assertContainsNoneMatching(request2.getHeaders(), "Cookie.*");
  }

  @Test public void redirect_Async() throws Exception {
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

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    client.newCall(request).enqueue(callback);

    callback.await(server.getUrl("/c"))
        .assertCode(200)
        .assertBody("C")
        .redirectedBy()
        .assertCode(302)
        .assertHeader("Test", "Redirect from /b to /c")
        .redirectedBy()
        .assertCode(301)
        .assertHeader("Test", "Redirect from /a to /b");

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reused.
    assertEquals(2, server.takeRequest().getSequenceNumber()); // Connection reused again!
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

    executeSynchronously(new Request.Builder().url(server.getUrl("/0")).build())
        .assertCode(200)
        .assertBody("Success!");
  }

  @Test public void follow20Redirects_Async() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/0")).build();
    client.newCall(request).enqueue(callback);
    callback.await(server.getUrl("/20"))
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
      client.newCall(new Request.Builder().url(server.getUrl("/0")).build()).execute();
      fail();
    } catch (IOException e) {
      assertEquals("Too many redirects: 21", e.getMessage());
    }
  }

  @Test public void doesNotFollow21Redirects_Async() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/0")).build();
    client.newCall(request).enqueue(callback);
    callback.await(server.getUrl("/20")).assertFailure("Too many redirects: 21");
  }

  @Test public void canceledBeforeExecute() throws Exception {
    server.play();

    Call call = client.newCall(new Request.Builder().url(server.getUrl("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException e){
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test public void cancelBeforeBodyIsRead() throws Exception {
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));
    server.play();

    final Call call = client.newCall(new Request.Builder().url(server.getUrl("/a")).build());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Response> result = executor.submit(new Callable<Response>() {
      @Override public Response call() throws Exception {
        return call.execute();
      }
    });

    Thread.sleep(100); // wait for it to go in flight.

    call.cancel();
    try {
      result.get().body().bytes();
      fail();
    } catch (IOException e) {
    }
    assertEquals(1, server.getRequestCount());
  }

  /**
   * This test puts a request in front of one that is to be canceled, so that it is canceled before
   * I/O takes place.
   */
  @Test public void canceledBeforeIOSignalsOnFailure() throws Exception {
    client.getDispatcher().setMaxRequests(1); // Force requests to be executed serially.
    server.setDispatcher(new Dispatcher() {
      char nextResponse = 'A';

      @Override public MockResponse dispatch(RecordedRequest request) {
        client.cancel("request B");
        return new MockResponse().setBody(Character.toString(nextResponse++));
      }
    });
    server.play();

    Request requestA = new Request.Builder().url(server.getUrl("/a")).tag("request A").build();
    client.newCall(requestA).enqueue(callback);
    assertEquals("/a", server.takeRequest().getPath());

    Request requestB = new Request.Builder().url(server.getUrl("/b")).tag("request B").build();
    client.newCall(requestB).enqueue(callback);
    assertEquals("/b", server.takeRequest().getPath());

    callback.await(requestA.url()).assertBody("A");
    // At this point we know the callback is ready, and that it will receive a cancel failure.
    callback.await(requestB.url()).assertFailure("Canceled");
  }

  @Test public void canceledBeforeIOSignalsOnFailure_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeIOSignalsOnFailure_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure() throws Exception {
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        client.cancel("request A");
        return new MockResponse().setBody("A");
      }
    });
    server.play();

    Request requestA = new Request.Builder().url(server.getUrl("/a")).tag("request A").build();
    client.newCall(requestA).enqueue(callback);
    assertEquals("/a", server.takeRequest().getPath());

    callback.await(requestA.url()).assertFailure("Canceled");
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledBeforeResponseReadSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    canceledBeforeResponseReadSignalsOnFailure();
  }

  /**
   * There's a race condition where the cancel may apply after the stream has already been
   * processed.
   */
  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<String> bodyRef = new AtomicReference<String>();
    final AtomicReference<Failure> failureRef = new AtomicReference<Failure>();

    Request request = new Request.Builder().url(server.getUrl("/a")).tag("request A").build();
    final Call call = client.newCall(request);
    call.enqueue(new Response.Callback() {
      @Override public void onFailure(Failure failure) {
        latch.countDown();
        failureRef.set(failure); // This should never occur as we don't signal twice.
      }

      @Override public void onResponse(Response response) throws IOException {
        call.cancel();
        try {
          bodyRef.set(response.body().string());
        } catch (IOException e) { // It is ok if this broke the stream.
          bodyRef.set("A");
          throw e; // We expect to not loop into onFailure in this case.
        } finally {
          latch.countDown();
        }
      }
    });

    latch.await();
    assertEquals("A", bodyRef.get());
    assertNull(failureRef.get());
  }

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTP_2()
      throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_SPDY_3()
      throws Exception {
    enableProtocol(Protocol.SPDY_3);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  private RecordedResponse executeSynchronously(Request request) throws IOException {
    Response response = client.newCall(request).execute();
    return new RecordedResponse(request, response, response.body().string(), null);
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    client.setProtocols(Arrays.asList(protocol, Protocol.HTTP_1_1));
    server.useHttps(sslContext.getSocketFactory(), false);
    server.setProtocols(client.getProtocols());
  }

  private void assertContains(Collection<String> collection, String element) {
    for (String c : collection) {
      if (c != null && c.equalsIgnoreCase(element)) return;
    }
    fail("No " + element + " in " + collection);
  }

  private void assertContainsNoneMatching(List<String> headers, String pattern) {
    for (String header : headers) {
      if (header.matches(pattern)) {
        fail("Header " + header + " matches " + pattern);
      }
    }
  }
}
