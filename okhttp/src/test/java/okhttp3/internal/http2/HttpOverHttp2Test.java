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
package okhttp3.internal.http2;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.PushPromise;
import mockwebserver3.QueueDispatcher;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketPolicy;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.Cookie;
import okhttp3.Credentials;
import okhttp3.EventListener;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Protocol;
import okhttp3.RecordingCookieJar;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.SimpleProvider;
import okhttp3.TestLogHandler;
import okhttp3.TestUtil;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static okhttp3.internal.Util.discard;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Test how HTTP/2 interacts with HTTP features. */
@Timeout(60)
@Flaky
@Tag("Slow")
public final class HttpOverHttp2Test {
  // Flaky https://github.com/square/okhttp/issues/4632
  // Flaky https://github.com/square/okhttp/issues/4633

  private static final HandshakeCertificates handshakeCertificates = localhost();

  public static class ProtocolParamProvider extends SimpleProvider {
    @Override
    public List<Object> arguments() {
      return asList(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_2);
    }
  }

  @TempDir public File tempDir;
  @RegisterExtension public final PlatformRule platform = new PlatformRule();
  @RegisterExtension public final OkHttpClientTestRule clientTestRule = configureClientTestRule();
  @RegisterExtension public final TestLogHandler testLogHandler = new TestLogHandler(Http2.class);

  private MockWebServer server;
  private Protocol protocol;
  private OkHttpClient client;
  private Cache cache;
  private String scheme;

  private OkHttpClientTestRule configureClientTestRule() {
    OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
    clientTestRule.setRecordTaskRunner(true);
    return clientTestRule;
  }

  public void setUp(Protocol protocol, MockWebServer server) {
    this.server = server;
    this.protocol = protocol;

    platform.assumeNotOpenJSSE();
    platform.assumeNotBouncyCastle();

    if (protocol == Protocol.HTTP_2) {
      platform.assumeHttp2Support();
      server.useHttps(handshakeCertificates.sslSocketFactory(), false);
      client = clientTestRule.newClientBuilder()
          .protocols(asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
          .sslSocketFactory(
              handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
          .hostnameVerifier(new RecordingHostnameVerifier())
          .build();
      scheme = "https";
    } else {
      server.setProtocols(asList(Protocol.H2_PRIOR_KNOWLEDGE));
      client = clientTestRule.newClientBuilder()
          .protocols(asList(Protocol.H2_PRIOR_KNOWLEDGE))
          .build();
      scheme = "http";
    }

    cache = new Cache(tempDir, Integer.MAX_VALUE);
  }

  @AfterEach public void tearDown() {
    Authenticator.setDefault(null);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void get(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();

    assertThat(response.body().string()).isEqualTo("ABCDE");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.message()).isEqualTo("");
    assertThat(response.protocol()).isEqualTo(protocol);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme);
    assertThat(request.getHeader(":authority")).isEqualTo(
        (server.getHostName() + ":" + server.getPort()));
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void get204Response(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    MockResponse responseWithoutBody = new MockResponse();
    responseWithoutBody.status("HTTP/1.1 204");
    responseWithoutBody.removeHeader("Content-Length");
    server.enqueue(responseWithoutBody);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();

    // Body contains nothing.
    assertThat(response.body().bytes().length).isEqualTo(0);
    assertThat(response.body().contentLength()).isEqualTo(0);

    // Content-Length header doesn't exist in a 204 response.
    assertThat(response.header("content-length")).isNull();

    assertThat(response.code()).isEqualTo(204);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void head(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    MockResponse mockResponse = new MockResponse().setHeader("Content-Length", 5);
    mockResponse.status("HTTP/1.1 200");
    server.enqueue(mockResponse);

    Call call = client.newCall(new Request.Builder()
        .head()
        .url(server.url("/foo"))
        .build());

    Response response = call.execute();

    // Body contains nothing.
    assertThat(response.body().bytes().length).isEqualTo(0);
    assertThat(response.body().contentLength()).isEqualTo(0);

    // Content-Length header stays correctly.
    assertThat(response.header("content-length")).isEqualTo("5");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("HEAD /foo HTTP/1.1");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void emptyResponse(
      Protocol protocol, MockWebServer mockWebServer) throws IOException {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();

    assertThat(response.body().byteStream().read()).isEqualTo(-1);
    response.body().close();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void noDefaultContentLengthOnStreamingPost(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    byte[] postBytes = "FGHIJ".getBytes(UTF_8);

    server.enqueue(new MockResponse().setBody("ABCDE"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return MediaType.get("text/plain; charset=utf-8");
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.write(postBytes);
          }
        })
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABCDE");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("POST /foo HTTP/1.1");
    assertArrayEquals(postBytes, request.getBody().readByteArray());
    assertThat(request.getHeader("Content-Length")).isNull();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void userSuppliedContentLengthHeader(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    byte[] postBytes = "FGHIJ".getBytes(UTF_8);

    server.enqueue(new MockResponse().setBody("ABCDE"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return MediaType.get("text/plain; charset=utf-8");
          }

          @Override public long contentLength() {
            return postBytes.length;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.write(postBytes);
          }
        })
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABCDE");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("POST /foo HTTP/1.1");
    assertArrayEquals(postBytes, request.getBody().readByteArray());
    assertThat(Integer.parseInt(request.getHeader("Content-Length"))).isEqualTo(
        (long) postBytes.length);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void closeAfterFlush(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    byte[] postBytes = "FGHIJ".getBytes(UTF_8);

    server.enqueue(new MockResponse().setBody("ABCDE"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return MediaType.get("text/plain; charset=utf-8");
          }

          @Override public long contentLength() {
            return postBytes.length;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.write(postBytes);  // push bytes into the stream's buffer
            sink.flush(); // Http2Connection.writeData subject to write window
            sink.close(); // Http2Connection.writeData empty frame
          }
        })
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABCDE");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("POST /foo HTTP/1.1");
    assertArrayEquals(postBytes, request.getBody().readByteArray());
    assertThat(Integer.parseInt(request.getHeader("Content-Length"))).isEqualTo(
        (long) postBytes.length);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void connectionReuse(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse().setBody("ABCDEF"));
    server.enqueue(new MockResponse().setBody("GHIJKL"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/r1"))
        .build());
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/r1"))
        .build());
    Response response1 = call1.execute();
    Response response2 = call2.execute();

    assertThat(response1.body().source().readUtf8(3)).isEqualTo("ABC");
    assertThat(response2.body().source().readUtf8(3)).isEqualTo("GHI");
    assertThat(response1.body().source().readUtf8(3)).isEqualTo("DEF");
    assertThat(response2.body().source().readUtf8(3)).isEqualTo("JKL");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);

    response1.close();
    response2.close();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void connectionWindowUpdateAfterCanceling(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE + 1])));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();

    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE);

    // Cancel the call and discard what we've buffered for the response body. This should free up
    // the connection flow-control window so new requests can proceed.
    call1.cancel();
    assertThat(discard(response1.body().source(), 1, TimeUnit.SECONDS))
        .overridingErrorMessage("Call should not have completed successfully.")
        .isFalse();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("abc");
  }

  /** Wait for the client to receive {@code dataLength} DATA frames. */
  private void waitForDataFrames(int dataLength) throws Exception {
    int expectedFrameCount = dataLength / 16384;
    int dataFrameCount = 0;
    while (dataFrameCount < expectedFrameCount) {
      String log = testLogHandler.take();
      if (log.equals("FINE: << 0x00000003 16384 DATA          ")) {
        dataFrameCount++;
      }
    }
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void connectionWindowUpdateOnClose(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE + 1])));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();

    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE);

    // Cancel the call and close the response body. This should discard the buffered data and update
    // the connection flow-control window.
    call1.cancel();
    response1.close();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("abc");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void concurrentRequestWithEmptyFlowControlWindow(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE])));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();

    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE);

    assertThat(response1.body().contentLength()).isEqualTo(
        (long) Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE);
    int read = response1.body().source().read(new byte[8192]);
    assertThat(read).isEqualTo(8192);

    // Make a second call that should transmit the response headers. The response body won't be
    // transmitted until the flow-control window is updated from the first request.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.code()).isEqualTo(200);

    // Close the response body. This should discard the buffered data and update the connection
    // flow-control window.
    response1.close();

    assertThat(response2.body().string()).isEqualTo("abc");
  }

  /** https://github.com/square/okhttp/issues/373 */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  @Disabled public void synchronousRequest(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse().setBody("A"));
    server.enqueue(new MockResponse().setBody("A"));

    ExecutorService executor = Executors.newCachedThreadPool();
    CountDownLatch countDownLatch = new CountDownLatch(2);
    executor.execute(new AsyncRequest("/r1", countDownLatch));
    executor.execute(new AsyncRequest("/r2", countDownLatch));
    countDownLatch.await();
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void gzippedResponseBody(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .addHeader("Content-Encoding: gzip")
        .setBody(gzip("ABCABCABC")));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/r1"))
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABCABCABC");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void authenticate(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED)
        .addHeader("www-authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate."));
    server.enqueue(new MockResponse()
        .setBody("Successful auth!"));

    String credential = Credentials.basic("username", "password");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, "Basic"))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("Successful auth!");

    RecordedRequest denied = server.takeRequest();
    assertThat(denied.getHeader("Authorization")).isNull();
    RecordedRequest accepted = server.takeRequest();
    assertThat(accepted.getRequestLine()).isEqualTo("GET / HTTP/1.1");
    assertThat(accepted.getHeader("Authorization")).isEqualTo(credential);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void redirect(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("This is the new location!"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("This is the new location!");

    RecordedRequest request1 = server.takeRequest();
    assertThat(request1.getPath()).isEqualTo("/");
    RecordedRequest request2 = server.takeRequest();
    assertThat(request2.getPath()).isEqualTo("/foo");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void readAfterLastByte(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse().setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();

    InputStream in = response.body().byteStream();
    assertThat(in.read()).isEqualTo('A');
    assertThat(in.read()).isEqualTo('B');
    assertThat(in.read()).isEqualTo('C');
    assertThat(in.read()).isEqualTo(-1);
    assertThat(in.read()).isEqualTo(-1);

    in.close();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void readResponseHeaderTimeout(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse().setBody("A"));

    client = client.newBuilder()
        .readTimeout(Duration.ofSeconds(1))
        .build();

    // Make a call expecting a timeout reading the response headers.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call1.execute();
      fail("Should have timed out!");
    } catch (SocketTimeoutException expected) {
      assertThat(expected.getMessage()).isEqualTo("timeout");
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("A");

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  /**
   * Test to ensure we don't  throw a read timeout on responses that are progressing.  For this
   * case, we take a 4KiB body and throttle it to 1KiB/second.  We set the read timeout to two
   * seconds.  If our implementation is acting correctly, it will not throw, as it is progressing.
   */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void readTimeoutMoreGranularThanBodySize(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    char[] body = new char[4096]; // 4KiB to read.
    Arrays.fill(body, 'y');
    server.enqueue(new MockResponse().setBody(new String(body))
        .throttleBody(1024, 1, SECONDS)); // Slow connection 1KiB/second.

    client = client.newBuilder()
        .readTimeout(Duration.ofSeconds(2))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo(new String(body));
  }

  /**
   * Test to ensure we throw a read timeout on responses that are progressing too slowly.  For this
   * case, we take a 2KiB body and throttle it to 1KiB/second.  We set the read timeout to half a
   * second.  If our implementation is acting correctly, it will throw, as a byte doesn't arrive in
   * time.
   */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void readTimeoutOnSlowConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    String body = TestUtil.repeat('y', 2048);
    server.enqueue(new MockResponse()
        .setBody(body)
        .throttleBody(1024, 1, SECONDS)); // Slow connection 1KiB/second.
    server.enqueue(new MockResponse()
        .setBody(body));

    client = client.newBuilder()
        .readTimeout(Duration.ofMillis(500)) // Half a second to read something.
        .build();

    // Make a call expecting a timeout reading the response body.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    try {
      response1.body().string();
      fail("Should have timed out!");
    } catch (SocketTimeoutException expected) {
      assertThat(expected.getMessage()).isEqualTo("timeout");
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo(body);

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void connectionTimeout(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody("A")
        .setBodyDelay(1, SECONDS));

    OkHttpClient client1 = client.newBuilder()
        .readTimeout(Duration.ofSeconds(2))
        .build();
    Call call1 = client1
        .newCall(new Request.Builder()
            .url(server.url("/"))
            .build());

    OkHttpClient client2 = client.newBuilder()
        .readTimeout(Duration.ofMillis(200))
        .build();
    Call call2 = client2
        .newCall(new Request.Builder()
            .url(server.url("/"))
            .build());

    Response response1 = call1.execute();
    assertThat(response1.body().string()).isEqualTo("A");

    try {
      call2.execute();
      fail();
    } catch (IOException expected) {
    }

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void responsesAreCached(
      Protocol protocol, MockWebServer mockWebServer) throws IOException {
    setUp(protocol, mockWebServer);
    client = client.newBuilder()
        .cache(cache)
        .build();

    server.enqueue(new MockResponse()
        .addHeader("cache-control: max-age=60")
        .setBody("A"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();

    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(1);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("A");

    Call call3 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response3 = call3.execute();
    assertThat(response3.body().string()).isEqualTo("A");

    assertThat(cache.requestCount()).isEqualTo(3);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(2);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void conditionalCache(
      Protocol protocol, MockWebServer mockWebServer) throws IOException {
    setUp(protocol, mockWebServer);
    client = client.newBuilder()
        .cache(cache)
        .build();

    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertThat(response1.body().string()).isEqualTo("A");

    assertThat(cache.requestCount()).isEqualTo(1);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("A");

    assertThat(cache.requestCount()).isEqualTo(2);
    assertThat(cache.networkCount()).isEqualTo(2);
    assertThat(cache.hitCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void responseCachedWithoutConsumingFullBody(
      Protocol protocol, MockWebServer mockWebServer) throws IOException {
    setUp(protocol, mockWebServer);
    client = client.newBuilder()
        .cache(cache)
        .build();

    server.enqueue(new MockResponse()
        .addHeader("cache-control: max-age=60")
        .setBody("ABCD"));
    server.enqueue(new MockResponse()
        .addHeader("cache-control: max-age=60")
        .setBody("EFGH"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertThat(response1.body().source().readUtf8(2)).isEqualTo("AB");
    response1.body().close();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().source().readUtf8()).isEqualTo("ABCD");
    response2.body().close();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void sendRequestCookies(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    RecordingCookieJar cookieJar = new RecordingCookieJar();
    Cookie requestCookie = new Cookie.Builder()
        .name("a")
        .value("b")
        .domain(server.getHostName())
        .build();
    cookieJar.enqueueRequestCookies(requestCookie);
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    server.enqueue(new MockResponse());
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("Cookie")).isEqualTo("a=b");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void receiveResponseCookies(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    RecordingCookieJar cookieJar = new RecordingCookieJar();
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    server.enqueue(new MockResponse()
        .addHeader("set-cookie: a=b"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("");

    cookieJar.assertResponseCookies("a=b; path=/");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void cancelWithStreamNotCompleted(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setBody("def"));

    // Disconnect before the stream is created. A connection is still established!
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call1.execute();
    call1.cancel();

    // That connection is pooled, and it works.
    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("def");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // Clean up the connection.
    response.close();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromOneRefusedStreamReusesConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.getHttpCode()));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("abc");

    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Reused connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  /**
   * We had a bug where we'd perform infinite retries of route that fail with connection shutdown
   * errors. The problem was that the logic that decided whether to reuse a route didn't track
   * certain HTTP/2 errors. https://github.com/square/okhttp/issues/5547
   */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void noRecoveryFromTwoRefusedStreams(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.getHttpCode()));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.getHttpCode()));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (StreamResetException expected) {
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM);
    }
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromOneInternalErrorRequiresNewConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode.INTERNAL_ERROR);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromOneCancelRequiresNewConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode.CANCEL);
  }

  private void recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode errorCode) throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(errorCode.getHttpCode()));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("abc");

    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromMultipleRefusedStreamsRequiresNewConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.getHttpCode()));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.getHttpCode()));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("abc");

    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Reused connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromCancelReusesConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    List<CountDownLatch> responseDequeuedLatches = Arrays.asList(
        new CountDownLatch(1),
        // No synchronization is needed for the last request, which is not canceled.
        new CountDownLatch(0));
    List<CountDownLatch> requestCanceledLatches = Arrays.asList(
        new CountDownLatch(1),
        new CountDownLatch(0));

    QueueDispatcher dispatcher =
        new RespondAfterCancelDispatcher(responseDequeuedLatches, requestCanceledLatches);
    dispatcher.enqueueResponse(new MockResponse()
        .setBodyDelay(10, TimeUnit.SECONDS)
        .setBody("abc"));
    dispatcher.enqueueResponse(new MockResponse()
        .setBody("def"));
    server.setDispatcher(dispatcher);

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    callAndCancel(0, responseDequeuedLatches.get(0), requestCanceledLatches.get(0));

    // Make a second request to ensure the connection is reused.
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("def");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromMultipleCancelReusesConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    List<CountDownLatch> responseDequeuedLatches = Arrays.asList(
        new CountDownLatch(1),
        new CountDownLatch(1),
        // No synchronization is needed for the last request, which is not canceled.
        new CountDownLatch(0));
    List<CountDownLatch> requestCanceledLatches = Arrays.asList(
        new CountDownLatch(1),
        new CountDownLatch(1),
        new CountDownLatch(0));

    QueueDispatcher dispatcher =
        new RespondAfterCancelDispatcher(responseDequeuedLatches, requestCanceledLatches);
    dispatcher.enqueueResponse(new MockResponse()
        .setBodyDelay(10, TimeUnit.SECONDS)
        .setBody("abc"));
    dispatcher.enqueueResponse(new MockResponse()
        .setBodyDelay(10, TimeUnit.SECONDS)
        .setBody("def"));
    dispatcher.enqueueResponse(new MockResponse()
        .setBody("ghi"));
    server.setDispatcher(dispatcher);

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    callAndCancel(0, responseDequeuedLatches.get(0), requestCanceledLatches.get(0));
    callAndCancel(1, responseDequeuedLatches.get(1), requestCanceledLatches.get(1));

    // Make a third request to ensure the connection is reused.
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ghi");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  private static class RespondAfterCancelDispatcher extends QueueDispatcher {
    final private List<CountDownLatch> responseDequeuedLatches;
    final private List<CountDownLatch> requestCanceledLatches;
    private int responseIndex = 0;

    RespondAfterCancelDispatcher(
        List<CountDownLatch> responseDequeuedLatches,
        List<CountDownLatch> requestCanceledLatches) {
      this.responseDequeuedLatches = responseDequeuedLatches;
      this.requestCanceledLatches = requestCanceledLatches;
    }

    @Override
    synchronized public MockResponse dispatch(RecordedRequest request)
        throws InterruptedException {
      // This guarantees a deterministic sequence when handling the canceled request:
      // 1. Server reads request and dequeues first response
      // 2. Client cancels request
      // 3. Server tries to send response on the canceled stream
      // Otherwise, there is no guarantee for the sequence. For example, the server may use the
      // first mocked response to respond to the second request.
      MockResponse response = super.dispatch(request);
      responseDequeuedLatches.get(responseIndex).countDown();
      requestCanceledLatches.get(responseIndex).await();
      responseIndex++;
      return response;
    }
  }

  /** Make a call and canceling it as soon as it's accepted by the server. */
  private void callAndCancel(int expectedSequenceNumber, CountDownLatch responseDequeuedLatch,
      CountDownLatch requestCanceledLatch) throws Exception {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    CountDownLatch latch = new CountDownLatch(1);
    call.enqueue(new Callback() {
      @Override public void onFailure(Call call1, IOException e) {
        latch.countDown();
      }

      @Override public void onResponse(Call call1, Response response) {
        fail();
      }
    });
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(
        (long) expectedSequenceNumber);
    responseDequeuedLatch.await();
    call.cancel();
    requestCanceledLatch.countDown();
    latch.await();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void noRecoveryFromRefusedStreamWithRetryDisabled(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.REFUSED_STREAM);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void noRecoveryFromInternalErrorWithRetryDisabled(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.INTERNAL_ERROR);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void noRecoveryFromCancelWithRetryDisabled(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.CANCEL);
  }

  private void noRecoveryFromErrorWithRetryDisabled(ErrorCode errorCode) throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(errorCode.getHttpCode()));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    client = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (StreamResetException expected) {
      assertThat(expected.errorCode).isEqualTo(errorCode);
    }
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void recoverFromConnectionNoNewStreamsOnFollowUp(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.INTERNAL_ERROR.getHttpCode()));
    server.enqueue(new MockResponse()
        .setBody("DEF"));
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    CountDownLatch latch = new CountDownLatch(1);
    BlockingQueue<String> responses = new SynchronousQueue<>();
    okhttp3.Authenticator authenticator = (route, response) -> {
      responses.offer(response.body().string());
      try {
        latch.await();
      } catch (InterruptedException e) {
        throw new AssertionError();
      }
      return response.request();
    };

    OkHttpClient blockingAuthClient = client.newBuilder()
        .authenticator(authenticator)
        .build();

    Callback callback = new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        fail();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        responses.offer(response.body().string());
      }
    };

    // Make the first request waiting until we get our auth challenge.
    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    blockingAuthClient.newCall(request).enqueue(callback);
    String response1 = responses.take();
    assertThat(response1).isEqualTo("");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // Now make the second request which will restrict the first HTTP/2 connection from creating new
    // streams.
    client.newCall(request).enqueue(callback);
    String response2 = responses.take();
    assertThat(response2).isEqualTo("DEF");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // Let the first request proceed. It should discard the the held HTTP/2 connection and get a new
    // one.
    latch.countDown();
    String response3 = responses.take();
    assertThat(response3).isEqualTo("ABC");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void nonAsciiResponseHeader(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .addHeaderLenient("Alpha", "α")
        .addHeaderLenient("β", "Beta"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    response.close();

    assertThat(response.header("Alpha")).isEqualTo("α");
    assertThat(response.header("β")).isEqualTo("Beta");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void serverSendsPushPromise_GET(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    PushPromise pushPromise = new PushPromise("GET", "/foo/bar", Headers.of("foo", "bar"),
        new MockResponse().setBody("bar").setStatus("HTTP/1.1 200 Sweet"));
    server.enqueue(new MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
        .withPush(pushPromise));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();

    assertThat(response.body().string()).isEqualTo("ABCDE");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.message()).isEqualTo("");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme);
    assertThat(request.getHeader(":authority")).isEqualTo(
        (server.getHostName() + ":" + server.getPort()));

    RecordedRequest pushedRequest = server.takeRequest();
    assertThat(pushedRequest.getRequestLine()).isEqualTo(
        "GET /foo/bar HTTP/1.1");
    assertThat(pushedRequest.getHeader("foo")).isEqualTo("bar");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void serverSendsPushPromise_HEAD(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    PushPromise pushPromise = new PushPromise("HEAD", "/foo/bar", Headers.of("foo", "bar"),
        new MockResponse().setStatus("HTTP/1.1 204 Sweet"));
    server.enqueue(new MockResponse()
        .setBody("ABCDE")
        .setStatus("HTTP/1.1 200 Sweet")
        .withPush(pushPromise));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABCDE");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.message()).isEqualTo("");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(request.getHeader(":scheme")).isEqualTo(scheme);
    assertThat(request.getHeader(":authority")).isEqualTo(
        (server.getHostName() + ":" + server.getPort()));

    RecordedRequest pushedRequest = server.takeRequest();
    assertThat(pushedRequest.getRequestLine()).isEqualTo(
        "HEAD /foo/bar HTTP/1.1");
    assertThat(pushedRequest.getHeader("foo")).isEqualTo("bar");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void noDataFramesSentWithNullRequestBody(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", null)
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABC");

    assertThat(response.protocol()).isEqualTo(protocol);

    List<String> logs = testLogHandler.takeAll();

    assertThat(firstFrame(logs, "HEADERS"))
        .overridingErrorMessage("header logged")
        .contains("HEADERS       END_STREAM|END_HEADERS");
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void emptyDataFrameSentWithEmptyBody(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", Util.EMPTY_REQUEST)
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABC");

    assertThat(response.protocol()).isEqualTo(protocol);

    List<String> logs = testLogHandler.takeAll();

    assertThat(firstFrame(logs, "HEADERS"))
        .overridingErrorMessage("header logged")
        .contains("HEADERS       END_HEADERS");
    // While MockWebServer waits to read the client's HEADERS frame before sending the response, it
    // doesn't wait to read the client's DATA frame and may send a DATA frame before the client
    // does. So we can't assume the client's empty DATA will be logged first.
    assertThat(countFrames(logs, "FINE: >> 0x00000003     0 DATA          END_STREAM"))
        .isEqualTo((long) 2);
    assertThat(countFrames(logs, "FINE: >> 0x00000003     3 DATA          "))
        .isEqualTo((long) 1);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void pingsTransmitted(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    // Ping every 500 ms, starting at 500 ms.
    client = client.newBuilder()
        .pingInterval(Duration.ofMillis(500))
        .build();

    // Delay the response to give 1 ping enough time to be sent and replied to.
    server.enqueue(new MockResponse()
        .setBodyDelay(750, TimeUnit.MILLISECONDS)
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABC");

    assertThat(response.protocol()).isEqualTo(protocol);

    // Confirm a single ping was sent and received, and its reply was sent and received.
    List<String> logs = testLogHandler.takeAll();
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          ")).isEqualTo(
        (long) 1);
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ")).isEqualTo(
        (long) 1);
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          ACK")).isEqualTo(
        (long) 1);
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK")).isEqualTo(
        (long) 1);
  }

  @Flaky
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void missingPongsFailsConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    if (protocol == Protocol.HTTP_2) {
      // https://github.com/square/okhttp/issues/5221
      platform.expectFailureOnJdkVersion(12);
    }

    // Ping every 500 ms, starting at 500 ms.
    client = client.newBuilder()
        .readTimeout(Duration.ofSeconds(10)) // Confirm we fail before the read timeout.
        .pingInterval(Duration.ofMillis(500))
        .build();

    // Set up the server to ignore the socket. It won't respond to pings!
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));

    // Make a call. It'll fail as soon as our pings detect a problem.
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    long executeAtNanos = System.nanoTime();
    try {
      call.execute();
      fail();
    } catch (StreamResetException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "stream was reset: PROTOCOL_ERROR");
    }

    long elapsedUntilFailure = System.nanoTime() - executeAtNanos;
    assertThat((double) TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure)).isCloseTo(
        (double) 1000, offset(250d));

    // Confirm a single ping was sent but not acknowledged.
    List<String> logs = testLogHandler.takeAll();
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          ")).isEqualTo(
        (long) 1);
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK")).isEqualTo(
        (long) 0);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void streamTimeoutDegradesConnectionAfterNoPong(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    TestUtil.assumeNotWindows();

    client = client.newBuilder()
        .readTimeout(Duration.ofMillis(500))
        .build();

    // Stalling the socket will cause TWO requests to time out!
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));

    // The 3rd request should be sent to a fresh connection.
    server.enqueue(new MockResponse()
        .setBody("fresh connection"));

    // The first call times out.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call1.execute();
      fail();
    } catch (SocketTimeoutException | SSLException expected) {
    }

    // The second call times out because it uses the same bad connection.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call2.execute();
      fail();
    } catch (SocketTimeoutException expected) {
    }

    // But after the degraded pong timeout, that connection is abandoned.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS));
    Call call3 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try (Response response = call3.execute()) {
      assertThat(response.body().string()).isEqualTo("fresh connection");
    }
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void oneStreamTimeoutDoesNotBreakConnection(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    client = client.newBuilder()
        .readTimeout(Duration.ofMillis(500))
        .build();

    server.enqueue(new MockResponse()
        .setBodyDelay(1_000, MILLISECONDS)
        .setBody("a"));
    server.enqueue(new MockResponse()
        .setBody("b"));
    server.enqueue(new MockResponse()
        .setBody("c"));

    // The first call times out.
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try (Response response = call1.execute()) {
      response.body().string();
      fail();
    } catch (SocketTimeoutException expected) {
    }

    // The second call succeeds.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try (Response response = call2.execute()) {
      assertThat(response.body().string()).isEqualTo("b");
    }

    // Calls succeed after the degraded pong timeout because the degraded pong was received.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS));
    Call call3 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try (Response response = call3.execute()) {
      assertThat(response.body().string()).isEqualTo("c");
    }

    // All calls share a connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  private String firstFrame(List<String> logs, String type) {
    for (String log : logs) {
      if (log.contains(type)) {
        return log;
      }
    }
    return null;
  }

  private int countFrames(List<String> logs, String message) {
    int result = 0;
    for (String log : logs) {
      if (log.equals(message)) {
        result++;
      }
    }
    return result;
  }

  /**
   * Push a setting that permits up to 2 concurrent streams, then make 3 concurrent requests and
   * confirm that the third concurrent request prepared a new connection.
   */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void settingsLimitsMaxConcurrentStreams(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    Settings settings = new Settings();
    settings.set(Settings.MAX_CONCURRENT_STREAMS, 2);

    // Read & write a full request to confirm settings are accepted.
    server.enqueue(new MockResponse().withSettings(settings));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("");

    server.enqueue(new MockResponse()
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));
    server.enqueue(new MockResponse()
        .setBody("GHI"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();

    Call call3 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response3 = call3.execute();

    assertThat(response1.body().string()).isEqualTo("ABC");
    assertThat(response2.body().string()).isEqualTo("DEF");
    assertThat(response3.body().string()).isEqualTo("GHI");
    // Settings connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Reuse settings connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    // Reuse settings connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
    // New connection!
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void connectionNotReusedAfterShutdown(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));

    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response1 = call1.execute();
    assertThat(response1.body().string()).isEqualTo("ABC");

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertThat(response2.body().string()).isEqualTo("DEF");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  /**
   * This simulates a race condition where we receive a healthy HTTP/2 connection and just prior to
   * writing our request, we get a GOAWAY frame from the server.
   */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void connectionShutdownAfterHealthCheck(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));

    OkHttpClient client2 = client.newBuilder()
        .addNetworkInterceptor(new Interceptor() {
          boolean executedCall;

          @Override public Response intercept(Chain chain) throws IOException {
            if (!executedCall) {
              // At this point, we have a healthy HTTP/2 connection. This call will trigger the
              // server to send a GOAWAY frame, leaving the connection in a shutdown state.
              executedCall = true;
              Call call = client.newCall(new Request.Builder()
                  .url(server.url("/"))
                  .build());
              Response response = call.execute();
              assertThat(response.body().string()).isEqualTo("ABC");
              // Wait until the GOAWAY has been processed.
              RealConnection connection = (RealConnection) chain.connection();
              while (connection.isHealthy(false));
            }
            return chain.proceed(chain.request());
          }
        })
        .build();

    Call call = client2.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("DEF");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Flaky
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void responseHeadersAfterGoaway(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    // Flaky https://github.com/square/okhttp/issues/4836
    server.enqueue(new MockResponse()
        .setHeadersDelay(1, SECONDS)
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("DEF"));

    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    Callback callback = new Callback() {
      @Override public void onResponse(Call call, Response response) throws IOException {
        bodies.add(response.body().string());
      }

      @Override public void onFailure(Call call, IOException e) {
        System.out.println(e);
      }
    };
    client.newCall(new Request.Builder().url(server.url("/")).build()).enqueue(callback);
    client.newCall(new Request.Builder().url(server.url("/")).build()).enqueue(callback);

    assertThat(bodies.poll(2, SECONDS)).isEqualTo("DEF");
    assertThat(bodies.poll(2, SECONDS)).isEqualTo("ABC");
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  /**
   * We don't know if the connection will support HTTP/2 until after we've connected. When multiple
   * connections are requested concurrently OkHttp will pessimistically connect multiple times, then
   * close any unnecessary connections. This test confirms that behavior works as intended.
   *
   * <p>This test uses proxy tunnels to get a hook while a connection is being established.
   */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void concurrentHttp2ConnectionsDeduplicated(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    assumeTrue(protocol == Protocol.HTTP_2);

    server.useHttps(handshakeCertificates.sslSocketFactory(), true);

    QueueDispatcher queueDispatcher = new QueueDispatcher();
    queueDispatcher.enqueueResponse(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    queueDispatcher.enqueueResponse(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    queueDispatcher.enqueueResponse(new MockResponse()
        .setBody("call2 response"));
    queueDispatcher.enqueueResponse(new MockResponse()
        .setBody("call1 response"));

    // We use a re-entrant dispatcher to initiate one HTTPS connection while the other is in flight.
    server.setDispatcher(new Dispatcher() {
      int requestCount;

      @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        MockResponse result = queueDispatcher.dispatch(request);

        requestCount++;
        if (requestCount == 1) {
          // Before handling call1's CONNECT we do all of call2. This part re-entrant!
          try {
            Call call2 = client.newCall(new Request.Builder()
                .url("https://android.com/call2")
                .build());
            Response response2 = call2.execute();
            assertThat(response2.body().string()).isEqualTo("call2 response");
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }

        return result;
      }

      @Override public MockResponse peek() {
        return queueDispatcher.peek();
      }

      @Override public void shutdown() {
        queueDispatcher.shutdown();
      }
    });

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .build();

    Call call1 = client.newCall(new Request.Builder()
        .url("https://android.com/call1")
        .build());
    Response response2 = call1.execute();
    assertThat(response2.body().string()).isEqualTo("call1 response");

    RecordedRequest call1Connect = server.takeRequest();
    assertThat(call1Connect.getMethod()).isEqualTo("CONNECT");
    assertThat(call1Connect.getSequenceNumber()).isEqualTo(0);

    RecordedRequest call2Connect = server.takeRequest();
    assertThat(call2Connect.getMethod()).isEqualTo("CONNECT");
    assertThat(call2Connect.getSequenceNumber()).isEqualTo(0);

    RecordedRequest call2Get = server.takeRequest();
    assertThat(call2Get.getMethod()).isEqualTo("GET");
    assertThat(call2Get.getPath()).isEqualTo("/call2");
    assertThat(call2Get.getSequenceNumber()).isEqualTo(0);

    RecordedRequest call1Get = server.takeRequest();
    assertThat(call1Get.getMethod()).isEqualTo("GET");
    assertThat(call1Get.getPath()).isEqualTo("/call1");
    assertThat(call1Get.getSequenceNumber()).isEqualTo(1);

    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  /** https://github.com/square/okhttp/issues/3103 */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void domainFronting(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    client = client.newBuilder()
        .addNetworkInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            Request request = chain.request().newBuilder()
                .header("Host", "privateobject.com")
                .build();
            return chain.proceed(request);
          }
        })
        .build();

    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader(":authority")).isEqualTo(
        "privateobject.com");
  }

  private Buffer gzip(String bytes) throws IOException {
    Buffer bytesOut = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(bytesOut));
    sink.writeUtf8(bytes);
    sink.close();
    return bytesOut;
  }

  class AsyncRequest implements Runnable {
    String path;
    CountDownLatch countDownLatch;

    AsyncRequest(String path, CountDownLatch countDownLatch) {
      this.path = path;
      this.countDownLatch = countDownLatch;
    }

    @Override public void run() {
      try {
        Call call = client.newCall(new Request.Builder()
            .url(server.url(path))
            .build());
        Response response = call.execute();
        assertThat(response.body().string()).isEqualTo("A");
        countDownLatch.countDown();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** https://github.com/square/okhttp/issues/4875 */
  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void shutdownAfterLateCoalescing(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    CountDownLatch latch = new CountDownLatch(2);

    Callback callback = new Callback() {
      @Override public void onResponse(Call call, Response response) {
        fail();
      }

      @Override public void onFailure(Call call, IOException e) {
        latch.countDown();
      }
    };

    client = client.newBuilder().eventListenerFactory(clientTestRule.wrap(new EventListener() {
      int callCount;

      @Override public void connectionAcquired(Call call, Connection connection) {
        try {
          if (callCount++ == 1) {
            server.shutdown();
          }
        } catch (IOException e) {
          fail();
        }
      }
    })).build();

    client.newCall(new Request.Builder().url(server.url("")).build()).enqueue(callback);
    client.newCall(new Request.Builder().url(server.url("")).build()).enqueue(callback);

    latch.await();
  }

  @ParameterizedTest
  @ArgumentsSource(ProtocolParamProvider.class)
  public void cancelWhileWritingRequestBodySendsCancelToServer(
      Protocol protocol, MockWebServer mockWebServer) throws Exception {
    setUp(protocol, mockWebServer);
    server.enqueue(new MockResponse());

    AtomicReference<Call> callReference = new AtomicReference<>();
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          @Override public @Nullable MediaType contentType() {
            return MediaType.get("text/plain; charset=utf-8");
          }

          @Override public void writeTo(BufferedSink sink) {
            callReference.get().cancel();
          }
        })
        .build());
    callReference.set(call);

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertThat(call.isCanceled()).isTrue();
    }

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getFailure()).hasMessage("stream was reset: CANCEL");
  }
}
