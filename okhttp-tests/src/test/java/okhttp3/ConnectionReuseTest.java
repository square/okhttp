/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ConnectionReuseTest {
  @Rule public final TestRule timeout = new Timeout(30_000);
  @Rule public final MockWebServer server = new MockWebServer();

  private SslClient sslClient = SslClient.localhost();
  private OkHttpClient client = defaultClient();

  @Test public void connectionsAreReused() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionReused(request, request);
  }

  @Test public void connectionsAreReusedWithHttp2() throws Exception {
    enableHttp2();
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionReused(request, request);
  }

  @Test public void connectionsAreNotReusedWithRequestConnectionClose() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/"))
        .header("Connection", "close")
        .build();
    Request requestB = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionNotReused(requestA, requestB);
  }

  @Test public void connectionsAreNotReusedWithResponseConnectionClose() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Connection", "close")
        .setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/"))
        .build();
    Request requestB = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionNotReused(requestA, requestB);
  }

  @Test public void connectionsAreNotReusedWithUnknownLengthResponseBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("a")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionNotReused(request, request);
  }

  @Test public void connectionsAreNotReusedIfPoolIsSizeZero() throws Exception {
    client = client.newBuilder()
        .connectionPool(new ConnectionPool(0, 5, TimeUnit.SECONDS))
        .build();
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionNotReused(request, request);
  }

  @Test public void connectionsReusedWithRedirectEvenIfPoolIsSizeZero() throws Exception {
    client = client.newBuilder()
        .connectionPool(new ConnectionPool(0, 5, TimeUnit.SECONDS))
        .build();
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("b", response.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionsNotReusedWithRedirectIfDiscardingResponseIsSlow() throws Exception {
    client = client.newBuilder()
        .connectionPool(new ConnectionPool(0, 5, TimeUnit.SECONDS))
        .build();
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBodyDelay(1, TimeUnit.SECONDS)
        .setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("b", response.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void silentRetryWhenIdempotentRequestFailsOnReusedConnection() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response responseA = client.newCall(request).execute();
    assertEquals("a", responseA.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());

    Response responseB = client.newCall(request).execute();
    assertEquals("b", responseB.body().string());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void staleConnectionNotReusedForNonIdempotentRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")
        .setSocketPolicy(SocketPolicy.SHUTDOWN_OUTPUT_AT_END));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response responseA = client.newCall(requestA).execute();
    assertEquals("a", responseA.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Give the socket a chance to become stale.
    Thread.sleep(250);

    Request requestB = new Request.Builder()
        .url(server.url("/"))
        .post(Body.create(MediaType.parse("text/plain"), "b"))
        .build();
    Response responseB = client.newCall(requestB).execute();
    assertEquals("b", responseB.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void http2ConnectionsAreSharedBeforeResponseIsConsumed() throws Exception {
    enableHttp2();
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response1 = client.newCall(request).execute();
    Response response2 = client.newCall(request).execute();
    response1.body().string(); // Discard the response body.
    response2.body().string(); // Discard the response body.
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionsAreEvicted() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client = client.newBuilder()
        .connectionPool(new ConnectionPool(5, 250, TimeUnit.MILLISECONDS))
        .build();
    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response1 = client.newCall(request).execute();
    assertEquals("a", response1.body().string());

    // Give the thread pool a chance to evict.
    Thread.sleep(500);

    Response response2 = client.newCall(request).execute();
    assertEquals("b", response2.body().string());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionsAreNotReusedIfSslSocketFactoryChanges() throws Exception {
    enableHttps();
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response = client.newCall(request).execute();
    response.body().close();

    // This client shares a connection pool but has a different SSL socket factory.
    SslClient sslClient2 = new SslClient.Builder().build();
    OkHttpClient anotherClient = client.newBuilder()
        .sslSocketFactory(sslClient2.socketFactory, sslClient2.trustManager)
        .build();

    // This client fails to connect because the new SSL socket factory refuses.
    try {
      anotherClient.newCall(request).execute();
      fail();
    } catch (SSLException expected) {
    }
  }

  @Test public void connectionsAreNotReusedIfHostnameVerifierChanges() throws Exception {
    enableHttps();
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response response1 = client.newCall(request).execute();
    response1.body().close();

    // This client shares a connection pool but has a different SSL socket factory.
    OkHttpClient anotherClient = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Response response2 = anotherClient.newCall(request).execute();
    response2.body().close();

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  /**
   * Regression test for an edge case where closing response body in the HTTP engine doesn't release
   * the corresponding stream allocation. This test keeps those response bodies alive and reads
   * them after the redirect has completed. This forces a connection to not be reused where it would
   * be otherwise.
   *
   * https://github.com/square/okhttp/issues/2409
   */
  @Test public void connectionsAreNotReusedIfNetworkInterceptorInterferes() throws Exception {
    client = client.newBuilder().addNetworkInterceptor(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        return response.newBuilder()
            .body(Body.create(null, "unrelated response body!"))
            .build();
      }
    }).build();

    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBody("/a has moved!"));
    server.enqueue(new MockResponse()
        .setBody("/b is here"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().startsWith("Closing the body of"));
    }
  }

  private void enableHttps() {
    enableHttpsAndAlpn(Protocol.HTTP_1_1);
  }

  private void enableHttp2() {
    enableHttpsAndAlpn(Protocol.HTTP_2, Protocol.HTTP_1_1);
  }

  private void enableHttpsAndAlpn(Protocol... protocols) {
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .protocols(Arrays.asList(protocols))
        .build();
    server.useHttps(sslClient.socketFactory, false);
    server.setProtocols(client.protocols());
  }

  private void assertConnectionReused(Request... requests) throws Exception {
    for (int i = 0; i < requests.length; i++) {
      Response response = client.newCall(requests[i]).execute();
      response.body().string(); // Discard the response body.
      assertEquals(i, server.takeRequest().getSequenceNumber());
    }
  }

  private void assertConnectionNotReused(Request... requests) throws Exception {
    for (Request request : requests) {
      Response response = client.newCall(request).execute();
      response.body().string(); // Discard the response body.
      assertEquals(0, server.takeRequest().getSequenceNumber());
    }
  }
}
