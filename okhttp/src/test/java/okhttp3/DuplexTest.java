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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.internal.duplex.MockStreamHandler;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.duplex.AsyncRequestBody;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.BufferedSink;
import okio.BufferedSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
@Tag("Slowish")
public final class DuplexTest {
  @RegisterExtension public final PlatformRule platform = new PlatformRule();
  @RegisterExtension public OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private MockWebServer server;
  private RecordingEventListener listener = new RecordingEventListener();
  private final HandshakeCertificates handshakeCertificates
    = platform.localhostHandshakeCertificates();
  private OkHttpClient client = clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build();

  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

  @BeforeEach public void setUp(MockWebServer server) {
    this.server = server;
    platform.assumeNotOpenJSSE();
    platform.assumeHttp2Support();
  }

  @AfterEach public void tearDown() {
    executorService.shutdown();
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
    MockStreamHandler body = new MockStreamHandler()
        .receiveRequest("request A\n")
        .sendResponse("response B\n")
        .receiveRequest("request C\n")
        .sendResponse("response D\n")
        .receiveRequest("request E\n")
        .sendResponse("response F\n")
        .exhaustRequest()
        .exhaustResponse();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
  }

  @Test public void trueDuplexServerWritesFirst() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockStreamHandler body = new MockStreamHandler()
        .sendResponse("response A\n")
        .receiveRequest("request B\n")
        .sendResponse("response C\n")
        .receiveRequest("request D\n")
        .sendResponse("response E\n")
        .receiveRequest("request F\n")
        .exhaustResponse()
        .exhaustRequest();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
  }

  @Test public void clientReadsHeadersDataTrailers() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockStreamHandler body = new MockStreamHandler()
        .sendResponse("ok")
        .exhaustResponse();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .trailers(Headers.of("trailers", "boom"))
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
  }

  @Test public void serverReadsHeadersData() throws Exception {
    TestUtil.assumeNotWindows();

    enableProtocol(Protocol.HTTP_2);
    MockStreamHandler body = new MockStreamHandler()
        .exhaustResponse()
        .receiveRequest("hey\n")
        .receiveRequest("whats going on\n")
        .exhaustRequest();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
  }

  @Test public void requestBodyEndsAfterResponseBody() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockStreamHandler body = new MockStreamHandler()
        .exhaustResponse()
        .receiveRequest("request A\n")
        .exhaustRequest();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();

    assertThat(listener.recordedEventTypes()).containsExactly(
        "CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd", "ConnectStart",
        "SecureConnectStart", "SecureConnectEnd", "ConnectEnd", "ConnectionAcquired",
        "RequestHeadersStart", "RequestHeadersEnd", "RequestBodyStart", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "RequestBodyEnd",
        "ConnectionReleased", "CallEnd");
  }

  @Test public void duplexWith100Continue() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    MockStreamHandler body = new MockStreamHandler()
        .receiveRequest("request body\n")
        .sendResponse("response body\n")
        .exhaustRequest();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .add100Continue()
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
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

    MockStreamHandler body = new MockStreamHandler()
        .sendResponse("/a has moved!\n", duplexResponseSent)
        .requestIOException()
        .exhaustResponse();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /b")
        .streamHandler(body)
        .build());
    server.enqueue(new MockResponse.Builder()
        .body("this is /b")
        .build());

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

    body.awaitSuccess();

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

    MockStreamHandler body1 = new MockStreamHandler()
        .sendResponse("please authenticate!\n")
        .requestIOException()
        .exhaustResponse();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_UNAUTHORIZED)
        .streamHandler(body1)
        .build());
    MockStreamHandler body = new MockStreamHandler()
        .sendResponse("response body\n")
        .exhaustResponse()
        .receiveRequest("request body\n")
        .exhaustRequest();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

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
    body1.awaitSuccess();

    // Second duplex request proceeds normally.
    BufferedSink requestBody2 = ((AsyncRequestBody) call.request().body()).takeSink();
    requestBody2.writeUtf8("request body\n");
    requestBody2.close();
    BufferedSource responseBody2 = response2.body().source();
    assertThat(responseBody2.readUtf8Line()).isEqualTo("response body");
    assertTrue(responseBody2.exhausted());
    body.awaitSuccess();

    // No more requests attempted!
    ((AsyncRequestBody) call.request().body()).assertNoMoreSinks();
  }

  @Test public void fullCallTimeoutAppliesToSetup() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse.Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build());

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

    MockStreamHandler body = new MockStreamHandler()
        .sendResponse("response A\n")
        .sleep(750, TimeUnit.MILLISECONDS)
        .sendResponse("response B\n")
        .receiveRequest("request C\n")
        .exhaustResponse()
        .exhaustRequest();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
  }

  @Test public void duplexWithRewriteInterceptors() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockStreamHandler body = new MockStreamHandler()
        .receiveRequest("REQUEST A\n")
        .sendResponse("response B\n")
        .exhaustRequest()
        .exhaustResponse();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

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

    body.awaitSuccess();
  }

  /**
   * OkHttp currently doesn't implement failing the request body stream independently of failing the
   * corresponding response body stream. This is necessary if we want servers to be able to stop
   * inbound data and send an early 400 before the request body completes.
   *
   * This test sends a slow request that is canceled by the server. It expects the response to still
   * be readable after the request stream is canceled.
   */
  @Disabled
  @Test public void serverCancelsRequestBodyAndSendsResponseBody() throws Exception {
    client = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build();

    BlockingQueue<String> log = new LinkedBlockingQueue<>();

    enableProtocol(Protocol.HTTP_2);
    MockStreamHandler body = new MockStreamHandler()
        .sendResponse("success!")
        .exhaustResponse()
        .cancelStream();
    server.enqueue(new MockResponse.Builder()
        .clearHeaders()
        .streamHandler(body)
        .build());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            try {
              for (int i = 0; i < 10; i++) {
                sink.writeUtf8(".");
                sink.flush();
                Thread.sleep(100);
              }
            } catch (IOException e) {
              log.add(e.toString());
              throw e;
            } catch (Exception e) {
              log.add(e.toString());
            }
          }
        })
        .build());

    try (Response response = call.execute()) {
      assertThat(response.body().string()).isEqualTo("success!");
    }

    body.awaitSuccess();

    assertThat(log.take()).contains("StreamResetException: stream was reset: CANCEL");
  }

  /**
   * We delay sending the last byte of the request body 1500 ms. The 1000 ms read timeout should
   * only elapse 1000 ms after the request body is sent.
   */
  @Test public void headersReadTimeoutDoesNotStartUntilLastRequestBodyByteFire() {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse.Builder()
        .headersDelay(1500, TimeUnit.MILLISECONDS)
        .build());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new DelayedRequestBody(RequestBody.create("hello", null), 1500, TimeUnit.MILLISECONDS))
        .build();

    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(request);
    try {
      call.execute();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("timeout");
    }
  }

  /** Same as the previous test, but the server stalls sending the response body. */
  @Test public void bodyReadTimeoutDoesNotStartUntilLastRequestBodyByteFire() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse.Builder()
        .bodyDelay(1500, TimeUnit.MILLISECONDS)
        .body("this should never be received")
        .build());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new DelayedRequestBody(RequestBody.create("hello", null), 1500, TimeUnit.MILLISECONDS))
        .build();

    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(request);
    Response response = call.execute();
    try {
      response.body().string();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo("timeout");
    }
  }

  /**
   * We delay sending the last byte of the request body 1500 ms. The 1000 ms read timeout shouldn't
   * elapse because it shouldn't start until the request body is sent.
   */
  @Test public void headersReadTimeoutDoesNotStartUntilLastRequestBodyByteNoFire() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse.Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new DelayedRequestBody(RequestBody.create("hello", null), 1500, TimeUnit.MILLISECONDS))
        .build();

    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(request);
    Response response = call.execute();
    assertThat(response.isSuccessful()).isTrue();
  }

  /**
   * We delay sending the last byte of the request body 1500 ms. The 1000 ms read timeout shouldn't
   * elapse because it shouldn't start until the request body is sent.
   */
  @Test public void bodyReadTimeoutDoesNotStartUntilLastRequestBodyByteNoFire() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse.Builder()
        .bodyDelay(500, TimeUnit.MILLISECONDS)
        .body("success")
        .build());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new DelayedRequestBody(RequestBody.create("hello", null), 1500, TimeUnit.MILLISECONDS))
        .build();

    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build();

    Call call = client.newCall(request);
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("success");
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
    server.useHttps(handshakeCertificates.sslSocketFactory());
  }

  private class DelayedRequestBody extends RequestBody {
    private final RequestBody delegate;
    private final long delayMillis;

    public DelayedRequestBody(RequestBody delegate, long delay, TimeUnit timeUnit) {
      this.delegate = delegate;
      this.delayMillis = timeUnit.toMillis(delay);
    }

    @Override public MediaType contentType() {
      return delegate.contentType();
    }

    @Override public boolean isDuplex() {
      return true;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      executorService.schedule(() -> {
        try {
          delegate.writeTo(sink);
          sink.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, delayMillis, TimeUnit.MILLISECONDS);
    }
  }
}
