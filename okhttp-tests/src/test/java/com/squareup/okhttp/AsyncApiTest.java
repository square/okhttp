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
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class AsyncApiTest {
  private MockWebServer server = new MockWebServer();
  private OkHttpClient client = new OkHttpClient();
  private RecordingReceiver receiver = new RecordingReceiver();

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
        .header("User-Agent", "AsyncApiTest")
        .build();
    client.enqueue(request, receiver);

    receiver.await(request.url())
        .assertCode(200)
        .assertContainsHeaders("Content-Type: text/plain")
        .assertBody("abc");

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: AsyncApiTest"));
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));
    server.play();

    client.enqueue(new Request.Builder().url(server.getUrl("/a")).build(), receiver);
    receiver.await(server.getUrl("/a")).assertBody("abc");

    client.enqueue(new Request.Builder().url(server.getUrl("/b")).build(), receiver);
    receiver.await(server.getUrl("/b")).assertBody("def");

    client.enqueue(new Request.Builder().url(server.getUrl("/c")).build(), receiver);
    receiver.await(server.getUrl("/c")).assertBody("ghi");

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

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.enqueue(request, receiver);

    receiver.await(request.url()).assertHandshake();
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.enqueue(request, receiver);

    receiver.await(request.url()).assertBody("abc");
  }

  @Test public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(Request.Body.create(MediaType.parse("text/plain"), "def"))
        .build();
    client.enqueue(request, receiver);

    receiver.await(request.url())
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

    Request request1 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.enqueue(request1, receiver);
    receiver.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.enqueue(request2, receiver);
    receiver.await(request2.url()).assertCode(200).assertBody("A");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void conditionalCacheMiss() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    client.setOkResponseCache(cache);

    Request request1 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.enqueue(request1, receiver);
    receiver.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.enqueue(request2, receiver);
    receiver.await(request2.url()).assertCode(200).assertBody("B");
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

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    client.enqueue(request, receiver);

    receiver.await(server.getUrl("/c"))
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

  @Test public void follow20Redirects() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/0")).build();
    client.enqueue(request, receiver);
    receiver.await(server.getUrl("/20"))
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

    Request request = new Request.Builder().url(server.getUrl("/0")).build();
    client.enqueue(request, receiver);
    receiver.await(server.getUrl("/20")).assertFailure("Too many redirects: 21");
  }

  /**
   * This test puts a request in front of one that is to be canceled, so that it is canceled
   * before I/O takes place.
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
    client.enqueue(requestA, receiver);
    assertEquals("/a", server.takeRequest().getPath());

    Request requestB = new Request.Builder().url(server.getUrl("/b")).tag("request B").build();
    client.enqueue(requestB, receiver);
    assertEquals("/b", server.takeRequest().getPath());

    receiver.await(requestA.url()).assertBody("A");
    // At this point we know the receiver is ready, and that it will receive a cancel failure.
    receiver.await(requestB.url()).assertFailure("Canceled");
  }

  @Test public void canceledBeforeIOSignalsOnFailure_HTTP_2() throws Exception {
    enableNpn(Protocol.HTTP_2);
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeIOSignalsOnFailure_SPDY_3() throws Exception {
    enableNpn(Protocol.SPDY_3);
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
    client.enqueue(requestA, receiver);
    assertEquals("/a", server.takeRequest().getPath());

    receiver.await(requestA.url()).assertFailure("Canceled");
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_HTTP_2() throws Exception {
    enableNpn(Protocol.HTTP_2);
    canceledBeforeResponseReadSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_SPDY_3() throws Exception {
    enableNpn(Protocol.SPDY_3);
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
    client.enqueue(request, new Response.Receiver() {
      @Override public void onFailure(Failure failure) {
        latch.countDown();
        failureRef.set(failure); // This should never occur as we don't signal twice.
      }

      @Override public void onResponse(Response response) throws IOException {
        client.cancel("request A");
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
    enableNpn(Protocol.HTTP_2);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_SPDY_3()
      throws Exception {
    enableNpn(Protocol.SPDY_3);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void connectionReuseWhenResponseBodyConsumed() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    client.enqueue(request, new Response.Receiver() {
      @Override public void onFailure(Failure failure) {
        throw new AssertionError();
      }
      @Override public void onResponse(Response response) throws IOException {
        InputStream bytes = response.body().byteStream();
        assertEquals('a', bytes.read());
        assertEquals('b', bytes.read());
        assertEquals('c', bytes.read());

        // This request will share a connection with 'A' cause it's all done.
        client.enqueue(new Request.Builder().url(server.getUrl("/b")).build(), receiver);
      }
    });

    receiver.await(server.getUrl("/b")).assertCode(200).assertBody("def");
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reuse!
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/npn-boot-1.1.7.v20140316.jar}
   */
  private void enableNpn(Protocol protocol) {
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    client.setProtocols(Arrays.asList(protocol, Protocol.HTTP_1_1));
    server.useHttps(sslContext.getSocketFactory(), false);
    server.setNpnEnabled(true);
    server.setNpnProtocols(client.getProtocols());
  }
}
