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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import okhttp3.internal.SslContextBuilder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.RecordingHostnameVerifier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static org.junit.Assert.assertEquals;

public final class ConnectionReuseTest {
  @Rule public final TestRule timeout = new Timeout(30_000);
  @Rule public final MockWebServer server = new MockWebServer();

  private SSLContext sslContext = SslContextBuilder.localhost();
  private OkHttpClient client = new OkHttpClient();

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
    client.setConnectionPool(new ConnectionPool(0, 5000));
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    assertConnectionNotReused(request, request);
  }

  @Test public void connectionsReusedWithRedirectEvenIfPoolIsSizeZero() throws Exception {
    client.setConnectionPool(new ConnectionPool(0, 5000));
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
    client.setConnectionPool(new ConnectionPool(0, 5000));
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

    Request requestB = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "b"))
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

    client.setConnectionPool(new ConnectionPool(5, 250, TimeUnit.MILLISECONDS));
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

  private void enableHttp2() {
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    client.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    server.useHttps(sslContext.getSocketFactory(), false);
    server.setProtocols(client.getProtocols());
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
