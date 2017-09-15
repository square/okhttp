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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HostnameVerifier;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RecordingCookieJar;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.TestLogHandler;
import okhttp3.TestUtil;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.PushPromise;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Test how SPDY interacts with HTTP/2 features. */
public final class HttpOverHttp2Test {
  private static final Logger http2Logger = Logger.getLogger(Http2.class.getName());

  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final MockWebServer server = new MockWebServer();

  private SslClient sslClient = SslClient.localhost();
  private HostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
  private OkHttpClient client;
  private Cache cache;
  private TestLogHandler http2Handler = new TestLogHandler();
  private Level previousLevel;

  @Before public void setUp() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    cache = new Cache(tempDir.getRoot(), Integer.MAX_VALUE);
    client = defaultClient().newBuilder()
        .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .dns(new SingleInetAddressDns())
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build();

    http2Logger.addHandler(http2Handler);
    previousLevel = http2Logger.getLevel();
    http2Logger.setLevel(Level.FINE);
  }

  @After public void tearDown() throws Exception {
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

    assertEquals("ABCDE", response.body().string());
    assertEquals(200, response.code());
    assertEquals("", response.message());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertEquals("https", request.getHeader(":scheme"));
    assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader(":authority"));
  }

  @Test public void emptyResponse() throws IOException {
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .build());
    Response response = call.execute();

    assertEquals(-1, response.body().byteStream().read());
    response.body().close();
  }

  @Test public void noDefaultContentLengthOnStreamingPost() throws Exception {
    final byte[] postBytes = "FGHIJ".getBytes(Util.UTF_8);

    server.enqueue(new MockResponse().setBody("ABCDE"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return MediaType.parse("text/plain; charset=utf-8");
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.write(postBytes);
          }
        })
        .build());

    Response response = call.execute();
    assertEquals("ABCDE", response.body().string());

    RecordedRequest request = server.takeRequest();
    assertEquals("POST /foo HTTP/1.1", request.getRequestLine());
    assertArrayEquals(postBytes, request.getBody().readByteArray());
    assertNull(request.getHeader("Content-Length"));
  }

  @Test public void userSuppliedContentLengthHeader() throws Exception {
    final byte[] postBytes = "FGHIJ".getBytes(Util.UTF_8);

    server.enqueue(new MockResponse().setBody("ABCDE"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return MediaType.parse("text/plain; charset=utf-8");
          }

          @Override public long contentLength() throws IOException {
            return postBytes.length;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.write(postBytes);
          }
        })
        .build());

    Response response = call.execute();
    assertEquals("ABCDE", response.body().string());

    RecordedRequest request = server.takeRequest();
    assertEquals("POST /foo HTTP/1.1", request.getRequestLine());
    assertArrayEquals(postBytes, request.getBody().readByteArray());
    assertEquals(postBytes.length, Integer.parseInt(request.getHeader("Content-Length")));
  }

  @Test public void closeAfterFlush() throws Exception {
    final byte[] postBytes = "FGHIJ".getBytes(Util.UTF_8);

    server.enqueue(new MockResponse().setBody("ABCDE"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/foo"))
        .post(new RequestBody() {
          @Override public MediaType contentType() {
            return MediaType.parse("text/plain; charset=utf-8");
          }

          @Override public long contentLength() throws IOException {
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
    assertEquals("ABCDE", response.body().string());

    RecordedRequest request = server.takeRequest();
    assertEquals("POST /foo HTTP/1.1", request.getRequestLine());
    assertArrayEquals(postBytes, request.getBody().readByteArray());
    assertEquals(postBytes.length, Integer.parseInt(request.getHeader("Content-Length")));
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

    assertEquals("ABC", response1.body().source().readUtf8(3));
    assertEquals("GHI", response2.body().source().readUtf8(3));
    assertEquals("DEF", response1.body().source().readUtf8(3));
    assertEquals("JKL", response2.body().source().readUtf8(3));
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());

    response1.close();
    response2.close();
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
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void gzippedResponseBody() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Content-Encoding: gzip")
        .setBody(gzip("ABCABCABC")));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/r1"))
        .build());

    Response response = call.execute();
    assertEquals("ABCABCABC", response.body().string());
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
        .authenticator(new RecordingOkAuthenticator(credential))
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("Successful auth!", response.body().string());

    RecordedRequest denied = server.takeRequest();
    assertNull(denied.getHeader("Authorization"));
    RecordedRequest accepted = server.takeRequest();
    assertEquals("GET / HTTP/1.1", accepted.getRequestLine());
    assertEquals(credential, accepted.getHeader("Authorization"));
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
    assertEquals("This is the new location!", response.body().string());

    RecordedRequest request1 = server.takeRequest();
    assertEquals("/", request1.getPath());
    RecordedRequest request2 = server.takeRequest();
    assertEquals("/foo", request2.getPath());
  }

  @Test public void readAfterLastByte() throws Exception {
    server.enqueue(new MockResponse().setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();

    InputStream in = response.body().byteStream();
    assertEquals('A', in.read());
    assertEquals('B', in.read());
    assertEquals('C', in.read());
    assertEquals(-1, in.read());
    assertEquals(-1, in.read());

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
      assertEquals("timeout", expected.getMessage());
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("A", response2.body().string());

    // Confirm that the connection was reused.
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
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
    assertEquals(new String(body), response.body().string());
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
      assertEquals("timeout", expected.getMessage());
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals(body, response2.body().string());

    // Confirm that the connection was reused.
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
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
    assertEquals("A", response1.body().string());

    try {
      call2.execute();
      fail();
    } catch (IOException expected) {
    }

    // Confirm that the connection was reused.
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
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

    assertEquals("A", response1.body().string());
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("A", response2.body().string());

    Call call3 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response3 = call3.execute();
    assertEquals("A", response3.body().string());

    assertEquals(3, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(2, cache.hitCount());
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
    assertEquals("A", response1.body().string());

    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("A", response2.body().string());

    assertEquals(2, cache.requestCount());
    assertEquals(2, cache.networkCount());
    assertEquals(1, cache.hitCount());
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
    assertEquals("AB", response1.body().source().readUtf8(2));
    response1.body().close();

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("ABCD", response2.body().source().readUtf8());
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
    assertEquals("", response.body().string());

    RecordedRequest request = server.takeRequest();
    assertEquals("a=b", request.getHeader("Cookie"));
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
    assertEquals("", response.body().string());

    cookieJar.assertResponseCookies("a=b; path=/");
  }

  /** https://github.com/square/okhttp/issues/1191 */
  @Ignore // TODO: recover gracefully when a connection is shutdown.
  @Test public void cancelWithStreamNotCompleted() throws Exception {
    // Ensure that the (shared) connection pool is in a consistent state.
    client.connectionPool().evictAll();
    assertEquals(0, client.connectionPool().connectionCount());

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
    assertEquals(1, client.connectionPool().connectionCount());
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("def", response2.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Clean up the connection.
    response.close();
  }

  @Test public void recoverFromOneRefusedStreamReusesConnection() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("abc", response.body().string());

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Reused connection.
  }

  @Test public void recoverFromOneInternalErrorRequiresNewConnection() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.INTERNAL_ERROR.httpCode));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("abc", response.body().string());

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
  }

  @Test public void recoverFromMultipleRefusedStreamsRequiresNewConnection() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("abc", response.body().string());

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Reused connection.
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
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
        .setHttp2ErrorCode(errorCode.httpCode));
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
      assertEquals(errorCode, expected.errorCode);
    }
  }

  @Test public void recoverFromConnectionNoNewStreamsOnFollowUp() throws InterruptedException {
    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
        .setHttp2ErrorCode(ErrorCode.CANCEL.httpCode));
    server.enqueue(new MockResponse()
        .setBody("DEF"));
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/foo"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    final CountDownLatch latch = new CountDownLatch(1);
    final BlockingQueue<String> responses = new SynchronousQueue<>();
    okhttp3.Authenticator authenticator = new okhttp3.Authenticator() {
      @Override public Request authenticate(Route route, Response response) throws IOException {
        responses.offer(response.body().string());
        try {
          latch.await();
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        return response.request();
      }
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
    assertEquals("", response1);
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Now make the second request which will restrict the first HTTP/2 connection from creating new
    // streams.
    client.newCall(request).enqueue(callback);
    String response2 = responses.take();
    assertEquals("DEF", response2);
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Let the first request proceed. It should discard the the held HTTP/2 connection and get a new
    // one.
    latch.countDown();
    String response3 = responses.take();
    assertEquals("ABC", response3);
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
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

    assertEquals("α", response.header("Alpha"));
    assertEquals("Beta", response.header("β"));
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

    assertEquals("ABCDE", response.body().string());
    assertEquals(200, response.code());
    assertEquals("", response.message());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertEquals("https", request.getHeader(":scheme"));
    assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader(":authority"));

    RecordedRequest pushedRequest = server.takeRequest();
    assertEquals("GET /foo/bar HTTP/1.1", pushedRequest.getRequestLine());
    assertEquals("bar", pushedRequest.getHeader("foo"));
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
    assertEquals("ABCDE", response.body().string());
    assertEquals(200, response.code());
    assertEquals("", response.message());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertEquals("https", request.getHeader(":scheme"));
    assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader(":authority"));

    RecordedRequest pushedRequest = server.takeRequest();
    assertEquals("HEAD /foo/bar HTTP/1.1", pushedRequest.getRequestLine());
    assertEquals("bar", pushedRequest.getHeader("foo"));
  }

  @Test public void noDataFramesSentWithNullRequestBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", null)
        .build());
    Response response = call.execute();
    assertEquals("ABC", response.body().string());

    assertEquals(Protocol.HTTP_2, response.protocol());

    List<String> logs = http2Handler.takeAll();
    assertEquals(20, logs.size());
    assertEquals("FINE: >> 0x00000003    47 HEADERS       END_STREAM|END_HEADERS", firstFrame(logs, "HEADERS"));
  }

  @Test public void emptyDataFrameSentWithEmptyBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", Util.EMPTY_REQUEST)
        .build());
    Response response = call.execute();
    assertEquals("ABC", response.body().string());

    assertEquals(Protocol.HTTP_2, response.protocol());

    List<String> logs = http2Handler.takeAll();
    assertEquals(22, logs.size());
    assertEquals("FINE: >> 0x00000003    50 HEADERS       END_HEADERS", firstFrame(logs, "HEADERS"));
    assertEquals("FINE: >> 0x00000003     0 DATA          END_STREAM", firstFrame(logs, "DATA"));
  }

  private String firstFrame(List<String> logs, String type) {
    for (String l: logs) {
      if (l.contains(type)) {
        return l;
      }
    }
    return null;
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
    assertEquals("", response.body().string());

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

    assertEquals("ABC", response1.body().string());
    assertEquals("DEF", response2.body().string());
    assertEquals("GHI", response3.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber()); // Settings connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Reuse settings connection.
    assertEquals(2, server.takeRequest().getSequenceNumber()); // Reuse settings connection.
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection!
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
    assertEquals("ABC", response1.body().string());

    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("DEF", response2.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
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
              assertEquals("ABC", response.body().string());
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
    assertEquals("DEF", response.body().string());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void responseHeadersAfterGoaway() throws Exception {
    server.enqueue(new MockResponse()
        .setHeadersDelay(1, SECONDS)
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("DEF"));

    final BlockingQueue<String> bodies = new SynchronousQueue<>();
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

    assertEquals("DEF", bodies.poll(2, SECONDS));
    assertEquals("ABC", bodies.poll(2, SECONDS));
    assertEquals(2, server.getRequestCount());
  }

  /**
   * We don't know if the connection will support HTTP/2 until after we've connected. When multiple
   * connections are requested concurrently OkHttp will pessimistically connect multiple times, then
   * close any unnecessary connections. This test confirms that behavior works as intended.
   *
   * <p>This test uses proxy tunnels to get a hook while a connection is being established.
   */
  @Test public void concurrentHttp2ConnectionsDeduplicated() throws Exception {
    server.useHttps(sslClient.socketFactory, true);

    // Force a fresh connection pool for the test.
    client.connectionPool().evictAll();

    final QueueDispatcher queueDispatcher = new QueueDispatcher();
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
            assertEquals("call2 response", response2.body().string());
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
    assertEquals("call1 response", response2.body().string());

    RecordedRequest call1Connect = server.takeRequest();
    assertEquals("CONNECT", call1Connect.getMethod());
    assertEquals(0, call1Connect.getSequenceNumber());

    RecordedRequest call2Connect = server.takeRequest();
    assertEquals("CONNECT", call2Connect.getMethod());
    assertEquals(0, call2Connect.getSequenceNumber());

    RecordedRequest call2Get = server.takeRequest();
    assertEquals("GET", call2Get.getMethod());
    assertEquals("/call2", call2Get.getPath());
    assertEquals(0, call2Get.getSequenceNumber());

    RecordedRequest call1Get = server.takeRequest();
    assertEquals("GET", call1Get.getMethod());
    assertEquals("/call1", call1Get.getPath());
    assertEquals(1, call1Get.getSequenceNumber());

    assertEquals(1, client.connectionPool().connectionCount());
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
    assertEquals("", response.body().string());

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("privateobject.com", recordedRequest.getHeader(":authority"));
  }

  public Buffer gzip(String bytes) throws IOException {
    Buffer bytesOut = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(bytesOut));
    sink.writeUtf8(bytes);
    sink.close();
    return bytesOut;
  }

  class AsyncRequest implements Runnable {
    String path;
    CountDownLatch countDownLatch;

    public AsyncRequest(String path, CountDownLatch countDownLatch) {
      this.path = path;
      this.countDownLatch = countDownLatch;
    }

    @Override public void run() {
      try {
        Call call = client.newCall(new Request.Builder()
            .url(server.url(path))
            .build());
        Response response = call.execute();
        assertEquals("A", response.body().string());
        countDownLatch.countDown();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final class RecordingHandler extends BaseTestHandler {
    int headerFrameCount;
    final List<Integer> dataFrames = new ArrayList<>();

    @Override public void settings(boolean clearPrevious, Settings settings) {
    }

    @Override public void ackSettings() {
    }

    @Override public void windowUpdate(int streamId, long windowSizeIncrement) {
    }

    @Override public void data(boolean inFinished, int streamId, BufferedSource source, int length)
        throws IOException {
      dataFrames.add(length);
    }

    @Override public void headers(boolean inFinished, int streamId, int associatedStreamId,
        List<Header> headerBlock) {
      headerFrameCount++;
    }
  }
}
