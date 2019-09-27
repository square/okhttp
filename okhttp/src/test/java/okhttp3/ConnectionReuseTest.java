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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static java.util.Arrays.asList;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ConnectionReuseTest {
  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final TestRule timeout = new Timeout(30_000);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = clientTestRule.newClient();

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
    assertThat(response.body().string()).isEqualTo("b");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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
    assertThat(response.body().string()).isEqualTo("b");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Test public void silentRetryWhenIdempotentRequestFailsOnReusedConnection() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("b"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Response responseA = client.newCall(request).execute();
    assertThat(responseA.body().string()).isEqualTo("a");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    Response responseB = client.newCall(request).execute();
    assertThat(responseB.body().string()).isEqualTo("b");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Test public void staleConnectionNotReusedForNonIdempotentRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("a")
        .setSocketPolicy(SocketPolicy.SHUTDOWN_OUTPUT_AT_END));
    server.enqueue(new MockResponse().setBody("b"));

    Request requestA = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response responseA = client.newCall(requestA).execute();
    assertThat(responseA.body().string()).isEqualTo("a");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // Give the socket a chance to become stale.
    Thread.sleep(250);

    Request requestB = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create("b", MediaType.get("text/plain")))
        .build();
    Response responseB = client.newCall(requestB).execute();
    assertThat(responseB.body().string()).isEqualTo("b");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
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
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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
    assertThat(response1.body().string()).isEqualTo("a");

    // Give the thread pool a chance to evict.
    Thread.sleep(500);

    Response response2 = client.newCall(request).execute();
    assertThat(response2.body().string()).isEqualTo("b");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
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
    HandshakeCertificates handshakeCertificates2 = new HandshakeCertificates.Builder().build();
    OkHttpClient anotherClient = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates2.sslSocketFactory(), handshakeCertificates2.trustManager())
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

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  /**
   * Regression test for an edge case where closing response body in the HTTP engine doesn't release
   * the corresponding stream allocation. This test keeps those response bodies alive and reads
   * them after the redirect has completed. This forces a connection to not be reused where it would
   * be otherwise.
   *
   * <p>This test leaks a response body by not closing it.
   *
   * https://github.com/square/okhttp/issues/2409
   */
  @Test public void connectionsAreNotReusedIfNetworkInterceptorInterferes() throws Exception {
    List<Response> responsesNotClosed = new ArrayList<>();

    client = client.newBuilder()
        // Since this test knowingly leaks a connection, avoid using the default shared connection
        // pool, which should remain clean for subsequent tests.
        .connectionPool(new ConnectionPool())
        .addNetworkInterceptor(chain -> {
          Response response = chain.proceed(chain.request());
          responsesNotClosed.add(response);
          return response
              .newBuilder()
              .body(ResponseBody.create("unrelated response body!", null))
              .build();
        })
        .build();

    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: /b")
        .setBody("/a has moved!"));
    server.enqueue(new MockResponse()
        .setBody("/b is here"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Call call = client.newCall(request);
    try (Response response = call.execute()) {
      assertThat(response.body().string()).isEqualTo("unrelated response body!");
    }

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // No connection reuse.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    for (Response response : responsesNotClosed) {
      closeQuietly(response);
    }
  }

  private void enableHttps() {
    enableHttpsAndAlpn(Protocol.HTTP_1_1);
  }

  private void enableHttp2() {
    platform.assumeHttp2Support();
    enableHttpsAndAlpn(Protocol.HTTP_2, Protocol.HTTP_1_1);
  }

  private void enableHttpsAndAlpn(Protocol... protocols) {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .protocols(asList(protocols))
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocols(client.protocols());
  }

  private void assertConnectionReused(Request... requests) throws Exception {
    for (int i = 0; i < requests.length; i++) {
      Response response = client.newCall(requests[i]).execute();
      response.body().string(); // Discard the response body.
      assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(i);
    }
  }

  private void assertConnectionNotReused(Request... requests) throws Exception {
    for (Request request : requests) {
      Response response = client.newCall(request).execute();
      response.body().string(); // Discard the response body.
      assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    }
  }
}
