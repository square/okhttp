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
package okhttp3.internal.framed;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RecordingCookieJar;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.SslContextBuilder;
import okhttp3.internal.Util;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.RecordingHostnameVerifier;
import okio.Buffer;
import okio.BufferedSink;
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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Test how SPDY interacts with HTTP features. */
public abstract class HttpOverSpdyTest {
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final MockWebServer server = new MockWebServer();

  /** Protocol to test, for example {@link Protocol#SPDY_3} */
  private final Protocol protocol;
  protected String hostHeader = ":host";

  protected SSLContext sslContext = SslContextBuilder.localhost();
  protected HostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
  protected OkHttpClient client;
  protected Cache cache;

  protected HttpOverSpdyTest(Protocol protocol) {
    this.protocol = protocol;
  }

  @Before public void setUp() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    cache = new Cache(tempDir.getRoot(), Integer.MAX_VALUE);
    client = new OkHttpClient.Builder()
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .sslSocketFactory(sslContext.getSocketFactory())
        .hostnameVerifier(hostnameVerifier)
        .build();
  }

  @After public void tearDown() throws Exception {
    Authenticator.setDefault(null);
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
    assertEquals("Sweet", response.message());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    assertEquals("https", request.getHeader(":scheme"));
    assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader(hostHeader));
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

  byte[] postBytes = "FGHIJ".getBytes(Util.UTF_8);

  @Test public void noDefaultContentLengthOnStreamingPost() throws Exception {
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
            sink.write(postBytes);  // push bytes into SpdyDataOutputStream.buffer
            sink.flush(); // FramedConnection.writeData subject to write window
            sink.close(); // FramedConnection.writeData empty frame
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

  @Test public void spdyConnectionReuse() throws Exception {
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
  }

  @Test @Ignore public void synchronousSpdyRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    server.enqueue(new MockResponse().setBody("A"));

    ExecutorService executor = Executors.newCachedThreadPool();
    CountDownLatch countDownLatch = new CountDownLatch(2);
    executor.execute(new SpdyRequest("/r1", countDownLatch));
    executor.execute(new SpdyRequest("/r2", countDownLatch));
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
  }

  @Ignore // See https://github.com/square/okhttp/issues/578
  @Test(timeout = 3000) public void readResponseHeaderTimeout() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse().setBody("A"));

    client = client.newBuilder()
        .readTimeout(1000, MILLISECONDS)
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();
    assertEquals("A", response.body().string());
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
    char[] body = new char[2048]; // 2KiB to read.
    Arrays.fill(body, 'y');
    server.enqueue(new MockResponse()
        .setBody(new String(body))
        .throttleBody(1024, 1, SECONDS)); // Slow connection 1KiB/second.

    client = client.newBuilder()
        .readTimeout(500, MILLISECONDS) // Half a second to read something.
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response = call.execute();

    try {
      response.body().string();
      fail("Should have timed out!");
    } catch (SocketTimeoutException expected) {
      assertEquals("timeout", expected.getMessage());
    }
  }

  @Test public void spdyConnectionTimeout() throws Exception {
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
  @Test public void cancelWithStreamNotCompleted() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setBody("def"));

    // Disconnect before the stream is created. A connection is still established!
    Call call1 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call1.execute();
    call1.cancel();

    // That connection is pooled, and it works.
    assertEquals(1, client.connectionPool().connectionCount());
    Call call2 = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    Response response2 = call2.execute();
    assertEquals("def", response2.body().string());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  public Buffer gzip(String bytes) throws IOException {
    Buffer bytesOut = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(bytesOut));
    sink.writeUtf8(bytes);
    sink.close();
    return bytesOut;
  }

  class SpdyRequest implements Runnable {
    String path;
    CountDownLatch countDownLatch;

    public SpdyRequest(String path, CountDownLatch countDownLatch) {
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
}
