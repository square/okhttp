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

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
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
import okhttp3.TestLogHandler;
import okhttp3.TestUtil;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.PushPromise;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static okhttp3.internal.Util.discard;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/** Test how HTTP/2 interacts with HTTP features. */
@RunWith(Parameterized.class)
@Flaky
public final class HttpOverHttp2Test {
  // Flaky https://github.com/square/okhttp/issues/4632
  // Flaky https://github.com/square/okhttp/issues/4633

  private static final Logger http2Logger = Logger.getLogger(Http2.class.getName());
  private static final HandshakeCertificates handshakeCertificates = localhost();

  @Parameters(name = "{0}")
  public static Collection<Protocol> data() {
    return asList(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_2);
  }

  private final PlatformRule platform = new PlatformRule();
  private final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
  @Rule public final TestRule chain =
      RuleChain.outerRule(platform).around(clientTestRule).around(new Timeout(5, SECONDS));
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final MockWebServer server = new MockWebServer();

  private OkHttpClient client;
  private Cache cache;
  private TestLogHandler http2Handler = new TestLogHandler();
  private Level previousLevel;
  private String scheme;
  private Protocol protocol;

  public HttpOverHttp2Test(Protocol protocol) {
    this.protocol = protocol;
  }

  @Before public void setUp() {
    platform.assumeNotOpenJSSE();

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

    cache = new Cache(tempDir.getRoot(), Integer.MAX_VALUE);
    http2Logger.addHandler(http2Handler);
    previousLevel = http2Logger.getLevel();
    http2Logger.setLevel(Level.FINE);
  }

  @After public void tearDown() {
    Authenticator.setDefault(null);
    http2Logger.removeHandler(http2Handler);
    http2Logger.setLevel(previousLevel);
  }

  @Test public void get() throws Exception {
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

  @Test public void emptyResponse() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();

    assertThat(response.body().byteStream().read()).isEqualTo(-1);
    response.body().close();
  }

  @Test public void noDefaultContentLengthOnStreamingPost() throws Exception {
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

  @Test public void userSuppliedContentLengthHeader() throws Exception {
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

  @Test public void closeAfterFlush() throws Exception {
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

  @Test public void connectionReuse() throws Exception {
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

  @Test public void connectionWindowUpdateAfterCanceling() throws Exception {
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
      String log = http2Handler.take();
      if (log.equals("FINE: << 0x00000003 16384 DATA          ")) {
        dataFrameCount++;
      }
    }
  }

  @Test public void connectionWindowUpdateOnClose() throws Exception {
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

  @Test public void concurrentRequestWithEmptyFlowControlWindow() throws Exception {
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
  @Test @Ignore public void synchronousRequest() throws Exception {
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

  @Test public void gzippedResponseBody() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Content-Encoding: gzip")
        .setBody(gzip("ABCABCABC")));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/r1"))
        .build());

    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABCABCABC");
  }

  @Test public void authenticate() throws Exception {
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

  @Test public void redirect() throws Exception {
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

  @Test public void readAfterLastByte() throws Exception {
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

  @Test public void readResponseHeaderTimeout() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse().setBody("A"));

    client = client.newBuilder()
        .readTimeout(1000, MILLISECONDS)
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
  @Test public void readTimeoutMoreGranularThanBodySize() throws Exception {
    char[] body = new char[4096]; // 4KiB to read.
    Arrays.fill(body, 'y');
    server.enqueue(new MockResponse().setBody(new String(body))
        .throttleBody(1024, 1, SECONDS)); // Slow connection 1KiB/second.

    client = client.newBuilder()
        .readTimeout(2, SECONDS)
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
  @Test public void readTimeoutOnSlowConnection() throws Exception {
    String body = TestUtil.repeat('y', 2048);
    server.enqueue(new MockResponse()
        .setBody(body)
        .throttleBody(1024, 1, SECONDS)); // Slow connection 1KiB/second.
    server.enqueue(new MockResponse()
        .setBody(body));

    client = client.newBuilder()
        .readTimeout(500, MILLISECONDS) // Half a second to read something.
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

  @Test public void connectionTimeout() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A")
        .setBodyDelay(1, SECONDS));

    OkHttpClient client1 = client.newBuilder()
        .readTimeout(2000, MILLISECONDS)
        .build();
    Call call1 = client1
        .newCall(new Request.Builder()
            .url(server.url("/"))
            .build());

    OkHttpClient client2 = client.newBuilder()
        .readTimeout(200, MILLISECONDS)
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

  @Test public void responsesAreCached() throws IOException {
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

  @Test public void conditionalCache() throws IOException {
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

  @Test public void responseCachedWithoutConsumingFullBody() throws IOException {
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

  @Test public void sendRequestCookies() throws Exception {
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

  @Test public void receiveResponseCookies() throws Exception {
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

  @Test public void cancelWithStreamNotCompleted() throws Exception {
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

  @Test public void recoverFromOneRefusedStreamReusesConnection() throws Exception {
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

  @Test public void recoverFromOneInternalErrorRequiresNewConnection() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.INTERNAL_ERROR.getHttpCode()));
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

  @Test public void recoverFromMultipleRefusedStreamsRequiresNewConnection() throws Exception {
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

  @Test public void recoverFromCancelReusesConnection() throws Exception {
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

  @Test public void recoverFromMultipleCancelReusesConnection() throws Exception {
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

  @Test public void noRecoveryFromRefusedStreamWithRetryDisabled() throws Exception {
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.REFUSED_STREAM);
  }

  @Test public void noRecoveryFromInternalErrorWithRetryDisabled() throws Exception {
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.INTERNAL_ERROR);
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

  @Test public void recoverFromConnectionNoNewStreamsOnFollowUp() throws Exception {
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

  @Test public void nonAsciiResponseHeader() throws Exception {
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

  @Test public void serverSendsPushPromise_GET() throws Exception {
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

  @Test public void serverSendsPushPromise_HEAD() throws Exception {
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

  @Test public void noDataFramesSentWithNullRequestBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", null)
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABC");

    assertThat(response.protocol()).isEqualTo(protocol);

    List<String> logs = http2Handler.takeAll();

    assertThat(firstFrame(logs, "HEADERS"))
        .overridingErrorMessage("header logged")
        .contains("HEADERS       END_STREAM|END_HEADERS");
  }

  @Test public void emptyDataFrameSentWithEmptyBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", Util.EMPTY_REQUEST)
        .build());
    Response response = call.execute();
    assertThat(response.body().string()).isEqualTo("ABC");

    assertThat(response.protocol()).isEqualTo(protocol);

    List<String> logs = http2Handler.takeAll();

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

  @Test public void pingsTransmitted() throws Exception {
    // Ping every 500 ms, starting at 500 ms.
    client = client.newBuilder()
        .pingInterval(500, TimeUnit.MILLISECONDS)
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
    List<String> logs = http2Handler.takeAll();
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
  @Test public void missingPongsFailsConnection() throws Exception {
    if (protocol == Protocol.HTTP_2) {
      // https://github.com/square/okhttp/issues/5221
      platform.expectFailureOnJdkVersion(12);
    }

    // Ping every 500 ms, starting at 500 ms.
    client = client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS) // Confirm we fail before the read timeout.
        .pingInterval(500, TimeUnit.MILLISECONDS)
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
    List<String> logs = http2Handler.takeAll();
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          ")).isEqualTo(
        (long) 1);
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK")).isEqualTo(
        (long) 0);
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
  @Test public void settingsLimitsMaxConcurrentStreams() throws Exception {
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

  @Test public void connectionNotReusedAfterShutdown() throws Exception {
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
  @Test public void connectionShutdownAfterHealthCheck() throws Exception {
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
              while (connection.isHealthy(false)) ;
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
  @Test public void responseHeadersAfterGoaway() throws Exception {
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
  @Test public void concurrentHttp2ConnectionsDeduplicated() throws Exception {
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
  @Test public void domainFronting() throws Exception {
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
  @Test
  public void shutdownAfterLateCoalescing() throws Exception {
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

  @Test public void cancelWhileWritingRequestBodySendsCancelToServer() throws Exception {
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
