/*
 * Copyright (C) 2018 Square, Inc.
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
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.duplex.AsyncRequestBody;
import okhttp3.internal.duplex.MwsDuplexAccess;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.mockwebserver.internal.duplex.MockDuplexResponseBody;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.BufferedSink;
import okio.BufferedSource;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertTrue;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class DuplexTest {
  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final TestRule timeout = new Timeout(30_000, TimeUnit.MILLISECONDS);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private RecordingEventListener listener = new RecordingEventListener();
  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build();

  @Before public void setUp() {
    platform.assumeNotOpenJSSE();
    platform.assumeHttp2Support();
  }

  @Test public void http1DoesntSupportDuplex() throws IOException {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());
    try {
      call.execute();
      fail();
    } catch (ProtocolException expected) {
    }
  }

  @Test public void trueDuplexClientWritesFirst() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .receiveRequest("request A\n")
            .sendResponse("response B\n")
            .receiveRequest("request C\n")
            .sendResponse("response D\n")
            .receiveRequest("request E\n")
            .sendResponse("response F\n")
            .exhaustRequest()
            .exhaustResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      requestBody.writeUtf8("request A\n");
      requestBody.flush();

      BufferedSource responseBody = response.body().source();
      assertThat(responseBody.readUtf8Line()).isEqualTo("response B");

      requestBody.writeUtf8("request C\n");
      requestBody.flush();
      assertThat(responseBody.readUtf8Line()).isEqualTo("response D");

      requestBody.writeUtf8("request E\n");
      requestBody.flush();
      assertThat(responseBody.readUtf8Line()).isEqualTo("response F");

      requestBody.close();
      assertThat(responseBody.readUtf8Line()).isNull();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void trueDuplexServerWritesFirst() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .sendResponse("response A\n")
            .receiveRequest("request B\n")
            .sendResponse("response C\n")
            .receiveRequest("request D\n")
            .sendResponse("response E\n")
            .receiveRequest("request F\n")
            .exhaustResponse()
            .exhaustRequest());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      BufferedSource responseBody = response.body().source();

      assertThat(responseBody.readUtf8Line()).isEqualTo("response A");
      requestBody.writeUtf8("request B\n");
      requestBody.flush();

      assertThat(responseBody.readUtf8Line()).isEqualTo("response C");
      requestBody.writeUtf8("request D\n");
      requestBody.flush();

      assertThat(responseBody.readUtf8Line()).isEqualTo("response E");
      requestBody.writeUtf8("request F\n");
      requestBody.flush();

      assertThat(responseBody.readUtf8Line()).isNull();
      requestBody.close();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void clientReadsHeadersDataTrailers() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .addHeader("h1", "v1")
            .addHeader("h2", "v2")
            .setTrailers(Headers.of("trailers", "boom")),
        new MockDuplexResponseBody()
            .sendResponse("ok")
            .exhaustResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    try (Response response = call.execute()) {
      assertThat(response.headers()).isEqualTo(Headers.of("h1", "v1", "h2", "v2"));

      BufferedSource responseBody = response.body().source();
      assertThat(responseBody.readUtf8(2)).isEqualTo("ok");
      assertTrue(responseBody.exhausted());
      assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"));
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void serverReadsHeadersData() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .addHeader("h1", "v1")
            .addHeader("h2", "v2"),
        new MockDuplexResponseBody()
            .exhaustResponse()
            .receiveRequest("hey\n")
            .receiveRequest("whats going on\n")
            .exhaustRequest());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", new AsyncRequestBody())
        .build();
    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      BufferedSink sink = ((AsyncRequestBody) request.body()).takeSink();
      sink.writeUtf8("hey\n");
      sink.writeUtf8("whats going on\n");
      sink.close();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void requestBodyEndsAfterResponseBody() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .exhaustResponse()
            .receiveRequest("request A\n")
            .exhaustRequest());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSource responseBody = response.body().source();
      assertTrue(responseBody.exhausted());

      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      requestBody.writeUtf8("request A\n");
      requestBody.close();
    }

    mockDuplexResponseBody.awaitSuccess();

    assertThat(listener.recordedEventTypes()).containsExactly(
        "CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "RequestBodyStart", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "RequestBodyEnd",
        "ConnectionReleased", "CallEnd");
  }

  @Test public void duplexWith100Continue() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE),
        new MockDuplexResponseBody()
            .receiveRequest("request body\n")
            .sendResponse("response body\n")
            .exhaustRequest());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      requestBody.writeUtf8("request body\n");
      requestBody.flush();

      BufferedSource responseBody = response.body().source();
      assertThat(responseBody.readUtf8Line()).isEqualTo("response body");

      requestBody.close();
      assertThat(responseBody.readUtf8Line()).isNull();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  /**
   * Duplex calls that have follow-ups are weird. By the time we know there's a follow-up we've
   * already split off another thread to stream the request body. Because we permit at most one
   * exchange at a time we break the request stream out from under that writer.
   */
  @Test public void duplexWithRedirect() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    CountDownLatch duplexResponseSent = new CountDownLatch(1);
    listener = new RecordingEventListener() {
      @Override public void responseHeadersEnd(Call call, Response response) {
        try {
          // Wait for the server to send the duplex response before acting on the 301 response
          // and resetting the stream.
          duplexResponseSent.await();
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        super.responseHeadersEnd(call, response);
      }
    };

    client = client.newBuilder()
        .eventListener(listener)
        .build();

    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
            .addHeader("Location: /b"),
        new MockDuplexResponseBody()
            .sendResponse("/a has moved!\n", duplexResponseSent)
            .requestIOException()
            .exhaustResponse());
    server.enqueue(new MockResponse()
        .setBody("this is /b"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSource responseBody = response.body().source();
      assertThat(responseBody.readUtf8Line()).isEqualTo("this is /b");
    }

    BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
    try {
      requestBody.writeUtf8("request body\n");
      requestBody.flush();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: CANCEL");
    }

    mockDuplexResponseBody.awaitSuccess();

    assertThat(listener.recordedEventTypes()).containsExactly(
        "CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "RequestBodyStart", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "RequestHeadersStart",
        "RequestHeadersEnd", "ResponseHeadersStart", "ResponseHeadersEnd", "ResponseBodyStart",
        "ResponseBodyEnd", "ConnectionReleased", "CallEnd", "RequestFailed");
  }

  /**
   * Auth requires follow-ups. Unlike redirects, the auth follow-up also has a request body. This
   * test makes a single call with two duplex requests!
   */
  @Test public void duplexWithAuthChallenge() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, null))
        .build();

    MockDuplexResponseBody mockResponseBody1 = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED),
        new MockDuplexResponseBody()
            .sendResponse("please authenticate!\n")
            .requestIOException()
            .exhaustResponse());
    MockDuplexResponseBody mockResponseBody2 = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .sendResponse("response body\n")
            .exhaustResponse()
            .receiveRequest("request body\n")
            .exhaustRequest());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    Response response2 = call.execute();

    // First duplex request is detached with violence.
    BufferedSink requestBody1 = ((AsyncRequestBody) call.request().body()).takeSink();
    try {
      requestBody1.writeUtf8("not authenticated\n");
      requestBody1.flush();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("stream was reset: CANCEL");
    }
    mockResponseBody1.awaitSuccess();

    // Second duplex request proceeds normally.
    BufferedSink requestBody2 = ((AsyncRequestBody) call.request().body()).takeSink();
    requestBody2.writeUtf8("request body\n");
    requestBody2.close();
    BufferedSource responseBody2 = response2.body().source();
    assertThat(responseBody2.readUtf8Line()).isEqualTo("response body");
    assertTrue(responseBody2.exhausted());
    mockResponseBody2.awaitSuccess();

    // No more requests attempted!
    ((AsyncRequestBody) call.request().body()).assertNoMoreSinks();
  }

  @Test public void fullCallTimeoutAppliesToSetup() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse()
        .setHeadersDelay(500, TimeUnit.MILLISECONDS));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build();

    Call call = client.newCall(request);
    call.timeout().timeout(250, TimeUnit.MILLISECONDS);
    try {
      call.execute();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("timeout");
      assertTrue(call.isCanceled());
    }
  }

  @Test public void fullCallTimeoutDoesNotApplyOnceConnected() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .sendResponse("response A\n")
            .sleep(750, TimeUnit.MILLISECONDS)
            .sendResponse("response B\n")
            .receiveRequest("request C\n")
            .exhaustResponse()
            .exhaustRequest());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build();

    Call call = client.newCall(request);
    call.timeout().timeout(500, TimeUnit.MILLISECONDS); // Long enough for the first TLS handshake.

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();

      BufferedSource responseBody = response.body().source();
      assertThat(responseBody.readUtf8Line()).isEqualTo("response A");
      assertThat(responseBody.readUtf8Line()).isEqualTo("response B");

      requestBody.writeUtf8("request C\n");
      requestBody.close();
      assertThat(responseBody.readUtf8Line()).isNull();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void duplexWithRewriteInterceptors() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .receiveRequest("REQUEST A\n")
            .sendResponse("response B\n")
            .exhaustRequest()
            .exhaustResponse());

    client = client.newBuilder()
        .addInterceptor(new UppercaseRequestInterceptor())
        .addInterceptor(new UppercaseResponseInterceptor())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      requestBody.writeUtf8("request A\n");
      requestBody.flush();

      BufferedSource responseBody = response.body().source();
      assertThat(responseBody.readUtf8Line()).isEqualTo("RESPONSE B");

      requestBody.close();
      assertThat(responseBody.readUtf8Line()).isNull();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  private MockDuplexResponseBody enqueueResponseWithBody(
      MockResponse response, MockDuplexResponseBody body) {
    MwsDuplexAccess.instance.setBody(response, body);
    server.enqueue(response);
    return body;
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    enableTls();
    client = client.newBuilder()
        .protocols(asList(protocol, Protocol.HTTP_1_1))
        .build();
    server.setProtocols(client.protocols());
  }

  private void enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
  }
}
