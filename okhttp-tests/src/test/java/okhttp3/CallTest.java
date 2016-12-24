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
package okhttp3;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.http.RecordingProxySelector;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.internal.tls.HeldCertificate;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER;
import static okhttp3.TestUtil.awaitGarbageCollection;
import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CallTest {
  @Rule public final TestRule timeout = new Timeout(30_000, TimeUnit.MILLISECONDS);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final MockWebServer server2 = new MockWebServer();
  @Rule public final InMemoryFileSystem fileSystem = new InMemoryFileSystem();

  private SslClient sslClient = SslClient.localhost();
  private OkHttpClient client = defaultClient();
  private RecordingCallback callback = new RecordingCallback();
  private TestLogHandler logHandler = new TestLogHandler();
  private Cache cache = new Cache(new File("/cache/"), Integer.MAX_VALUE, fileSystem);
  private ServerSocket nullServer;
  private Logger logger = Logger.getLogger(OkHttpClient.class.getName());

  @Before public void setUp() throws Exception {
    logger.addHandler(logHandler);
  }

  @After public void tearDown() throws Exception {
    cache.delete();
    Util.closeQuietly(nullServer);
    logger.removeHandler(logHandler);
  }

  @Test public void get() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .clearHeaders()
        .addHeader("content-type: text/plain")
        .addHeader("content-length", "3"));

    long sentAt = System.currentTimeMillis();
    RecordedResponse recordedResponse = executeSynchronously("/", "User-Agent", "SyncApiTest");
    long receivedAt = System.currentTimeMillis();

    recordedResponse.assertCode(200)
        .assertSuccessful()
        .assertHeaders(new Headers.Builder()
            .add("content-type", "text/plain")
            .add("content-length", "3")
            .build())
        .assertBody("abc")
        .assertSentRequestAtMillis(sentAt, receivedAt)
        .assertReceivedResponseAtMillis(sentAt, receivedAt);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().size());
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void buildRequestUsingHttpUrl() throws Exception {
    server.enqueue(new MockResponse());
    executeSynchronously("/").assertSuccessful();
  }

  @Test public void invalidScheme() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("ftp://hostname/path");
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals(expected.getMessage(), "unexpected url: ftp://hostname/path");
    }
  }

  @Test public void invalidPort() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("http://localhost:65536/");
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals(expected.getMessage(), "unexpected url: http://localhost:65536/");
    }
  }

  @Test public void getReturns500() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    executeSynchronously("/")
        .assertCode(500)
        .assertNotSuccessful();
  }

  @Test public void get_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    get();
  }

  @Test public void get_HTTPS() throws Exception {
    enableTls();
    get();
  }

  @Test public void repeatedHeaderNames() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("B", "123")
        .addHeader("B", "234"));

    executeSynchronously("/", "A", "345", "A", "456")
        .assertCode(200)
        .assertHeader("B", "123", "234");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(Arrays.asList("345", "456"), recordedRequest.getHeaders().values("A"));
  }

  @Test public void repeatedHeaderNames_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    repeatedHeaderNames();
  }

  @Test public void getWithRequestBody() throws Exception {
    server.enqueue(new MockResponse());

    try {
      new Request.Builder().method("GET", RequestBody.create(MediaType.parse("text/plain"), "abc"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void head() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .head()
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("HEAD", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().size());
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void headResponseContentLengthIsIgnored() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("Content-Length", "100"));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Request headRequest = new Request.Builder()
        .url(server.url("/"))
        .head()
        .build();
    executeSynchronously(headRequest)
        .assertCode(200)
        .assertHeader("Content-Length", "100")
        .assertBody("");

    Request getRequest = new Request.Builder()
        .url(server.url("/"))
        .build();
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void headResponseContentEncodingIsIgnored() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("Content-Encoding", "chunked"));
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Request headRequest = new Request.Builder()
        .url(server.url("/"))
        .head()
        .build();
    executeSynchronously(headRequest)
        .assertCode(200)
        .assertHeader("Content-Encoding", "chunked")
        .assertBody("");

    Request getRequest = new Request.Builder()
        .url(server.url("/"))
        .build();
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void head_HTTPS() throws Exception {
    enableTls();
    head();
  }

  @Test public void head_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    head();
  }

  @Test public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void post_HTTPS() throws Exception {
    enableTls();
    post();
  }

  @Test public void post_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    post();
  }

  @Test public void postZeroLength() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", RequestBody.create(null, new byte[0]))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().size());
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void postZerolength_HTTPS() throws Exception {
    enableTls();
    postZeroLength();
  }

  @Test public void postZerolength_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postZeroLength();
  }

  @Test public void postBodyRetransmittedAfterAuthorizationFail() throws Exception {
    postBodyRetransmittedAfterAuthorizationFail("abc");
  }

  @Test public void postBodyRetransmittedAfterAuthorizationFail_HTTPS() throws Exception {
    enableTls();
    postBodyRetransmittedAfterAuthorizationFail("abc");
  }

  @Test public void postBodyRetransmittedAfterAuthorizationFail_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postBodyRetransmittedAfterAuthorizationFail("abc");
  }

  /** Don't explode when resending an empty post. https://github.com/square/okhttp/issues/1131 */
  @Test public void postEmptyBodyRetransmittedAfterAuthorizationFail() throws Exception {
    postBodyRetransmittedAfterAuthorizationFail("");
  }

  @Test public void postEmptyBodyRetransmittedAfterAuthorizationFail_HTTPS() throws Exception {
    enableTls();
    postBodyRetransmittedAfterAuthorizationFail("");
  }

  @Test public void postEmptyBodyRetransmittedAfterAuthorizationFail_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postBodyRetransmittedAfterAuthorizationFail("");
  }

  private void postBodyRetransmittedAfterAuthorizationFail(String body) throws Exception {
    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", RequestBody.create(null, body))
        .build();

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential))
        .build();

    Response response = client.newCall(request).execute();
    assertEquals(200, response.code());
    response.body().close();

    RecordedRequest recordedRequest1 = server.takeRequest();
    assertEquals("POST", recordedRequest1.getMethod());
    assertEquals(body, recordedRequest1.getBody().readUtf8());
    assertNull(recordedRequest1.getHeader("Authorization"));

    RecordedRequest recordedRequest2 = server.takeRequest();
    assertEquals("POST", recordedRequest2.getMethod());
    assertEquals(body, recordedRequest2.getBody().readUtf8());
    assertEquals(credential, recordedRequest2.getHeader("Authorization"));
  }

  @Test public void attemptAuthorization20Times() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse().setResponseCode(401));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential))
        .build();

    executeSynchronously("/")
        .assertCode(200)
        .assertBody("Success!");
  }

  @Test public void doesNotAttemptAuthorization21Times() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse().setResponseCode(401));
    }

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential))
        .build();

    try {
      client.newCall(new Request.Builder().url(server.url("/0")).build()).execute();
      fail();
    } catch (IOException expected) {
      assertEquals("Too many follow-up requests: 21", expected.getMessage());
    }
  }

  @Test public void delete() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .delete()
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("DELETE", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().size());
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void delete_HTTPS() throws Exception {
    enableTls();
    delete();
  }

  @Test public void delete_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    delete();
  }

  @Test public void deleteWithRequestBody() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("DELETE", RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("DELETE", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
  }

  @Test public void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .put(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PUT", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void put_HTTPS() throws Exception {
    enableTls();
    put();
  }

  @Test public void put_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    put();
  }

  @Test public void patch() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .patch(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PATCH", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void patch_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    patch();
  }

  @Test public void patch_HTTPS() throws Exception {
    enableTls();
    patch();
  }

  @Test public void unspecifiedRequestBodyContentTypeDoesNotGetDefault() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", RequestBody.create(null, "abc"))
        .build();

    executeSynchronously(request).assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("abc", recordedRequest.getBody().readUtf8());
  }

  @Test public void illegalToExecuteTwice() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    Response response = call.execute();
    response.body().close();

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Already Executed", e.getMessage());
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Already Executed", e.getMessage());
    }

    assertEquals("SyncApiTest", server.takeRequest().getHeader("User-Agent"));
  }

  @Test public void illegalToExecuteTwice_Async() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    call.enqueue(callback);

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Already Executed", e.getMessage());
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Already Executed", e.getMessage());
    }

    assertEquals("SyncApiTest", server.takeRequest().getHeader("User-Agent"));
  }

  @Test public void legalToExecuteTwiceCloning() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Call call = client.newCall(request);
    Response response1 = call.execute();

    Call cloned = call.clone();
    Response response2 = cloned.execute();

    assertEquals(response1.body().string(), "abc");
    assertEquals(response2.body().string(), "def");
  }

  @Test public void legalToExecuteTwiceCloning_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Call call = client.newCall(request);
    call.enqueue(callback);

    Call cloned = call.clone();
    cloned.enqueue(callback);

    RecordedResponse firstResponse = callback.await(request.url()).assertSuccessful();
    RecordedResponse secondResponse = callback.await(request.url()).assertSuccessful();

    Set<String> bodies = new LinkedHashSet<>();
    bodies.add(firstResponse.getBody());
    bodies.add(secondResponse.getBody());

    assertTrue(bodies.contains("abc"));
    assertTrue(bodies.contains("def"));
  }

  @Test public void get_Async() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "AsyncApiTest")
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    assertEquals("AsyncApiTest", server.takeRequest().getHeader("User-Agent"));
  }

  @Test public void exceptionThrownByOnResponseIsRedactedAndLogged() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/secret"))
        .build();

    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        fail();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        throw new IOException("a");
      }
    });

    assertEquals("INFO: Callback failure for call to " + server.url("/") + "...",
        logHandler.take());
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    executeSynchronously("/a").assertBody("abc");
    executeSynchronously("/b").assertBody("def");
    executeSynchronously("/c").assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionPooling_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    client.newCall(new Request.Builder().url(server.url("/a")).build()).enqueue(callback);
    callback.await(server.url("/a")).assertBody("abc");

    client.newCall(new Request.Builder().url(server.url("/b")).build()).enqueue(callback);
    callback.await(server.url("/b")).assertBody("def");

    client.newCall(new Request.Builder().url(server.url("/c")).build()).enqueue(callback);
    callback.await(server.url("/c")).assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionReuseWhenResponseBodyConsumed_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder().url(server.url("/a")).build();
    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        throw new AssertionError();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        InputStream bytes = response.body().byteStream();
        assertEquals('a', bytes.read());
        assertEquals('b', bytes.read());
        assertEquals('c', bytes.read());

        // This request will share a connection with 'A' cause it's all done.
        client.newCall(new Request.Builder().url(server.url("/b")).build()).enqueue(callback);
      }
    });

    callback.await(server.url("/b")).assertCode(200).assertBody("def");
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reuse!
  }

  @Test public void timeoutsUpdatedOnReusedConnections() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));

    // First request: time out after 1000ms.
    client = client.newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build();
    executeSynchronously("/a").assertBody("abc");

    // Second request: time out after 250ms.
    client = client.newBuilder()
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build();
    Request request = new Request.Builder().url(server.url("/b")).build();
    Response response = client.newCall(request).execute();
    BufferedSource bodySource = response.body().source();
    assertEquals('d', bodySource.readByte());

    // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
    long startNanos = System.nanoTime();
    try {
      bodySource.readByte();
      fail();
    } catch (IOException expected) {
      // Timed out as expected.
      long elapsedNanos = System.nanoTime() - startNanos;
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      assertTrue(Util.format("Timed out: %sms", elapsedMillis), elapsedMillis < 500);
    } finally {
      bodySource.close();
    }
  }

  /** https://github.com/square/okhttp/issues/442 */
  @Test public void tlsTimeoutsNotRetried() throws Exception {
    enableTls();
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse()
        .setBody("unreachable!"));

    client = client.newBuilder()
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      // If this succeeds, too many requests were made.
      client.newCall(request).execute();
      fail();
    } catch (InterruptedIOException expected) {
    }
  }

  /**
   * Make a request with two routes. The first route will time out because it's connecting to a
   * special address that never connects. The automatic retry will succeed.
   */
  @Test public void connectTimeoutsAttemptsAlternateRoute() throws Exception {
    InetSocketAddress unreachableAddress = new InetSocketAddress("10.255.255.1", 8080);

    RecordingProxySelector proxySelector = new RecordingProxySelector();
    proxySelector.proxies.add(new Proxy(Proxy.Type.HTTP, unreachableAddress));
    proxySelector.proxies.add(server.toProxyAddress());

    server.enqueue(new MockResponse()
        .setBody("success!"));

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder().url("http://android.com/").build();
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("success!");
  }

  /**
   * Make a request with two routes. The first route will fail because the null server connects but
   * never responds. The manual retry will succeed.
   */
  @Test public void readTimeoutFails() throws Exception {
    InetSocketAddress nullServerAddress = startNullServer();

    RecordingProxySelector proxySelector = new RecordingProxySelector();
    proxySelector.proxies.add(new Proxy(Proxy.Type.HTTP, nullServerAddress));
    proxySelector.proxies.add(server.toProxyAddress());

    server.enqueue(new MockResponse()
        .setBody("success!"));

    client = client.newBuilder()
        .proxySelector(proxySelector)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder().url("http://android.com/").build();
    executeSynchronously(request)
        .assertFailure(SocketTimeoutException.class);
    executeSynchronously(request)
        .assertCode(200)
        .assertBody("success!");
  }

  /** https://github.com/square/okhttp/issues/1801 */
  @Test public void asyncCallEngineInitialized() throws Exception {
    OkHttpClient c = defaultClient().newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            throw new IOException();
          }
        })
        .build();
    Request request = new Request.Builder().url(server.url("/")).build();
    c.newCall(request).enqueue(callback);
    RecordedResponse response = callback.await(request.url());
    assertEquals(request, response.request);
  }

  @Test public void reusedSinksGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Call 1: set a deadline on the request body.
    RequestBody requestBody1 = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("abc");
        sink.timeout().deadline(5, TimeUnit.SECONDS);
      }
    };
    Request request1 = new Request.Builder()
        .url(server.url("/"))
        .method("POST", requestBody1)
        .build();
    Response response1 = client.newCall(request1).execute();
    assertEquals(200, response1.code());

    // Call 2: check for the absence of a deadline on the request body.
    RequestBody requestBody2 = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        assertFalse(sink.timeout().hasDeadline());
        sink.writeUtf8("def");
      }
    };
    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .method("POST", requestBody2)
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals(200, response2.code());

    // Use sequence numbers to confirm the connection was pooled.
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void reusedSourcesGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    // Call 1: set a deadline on the response body.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    BufferedSource body1 = response1.body().source();
    assertEquals("abc", body1.readUtf8());
    body1.timeout().deadline(5, TimeUnit.SECONDS);

    // Call 2: check for the absence of a deadline on the request body.
    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = client.newCall(request2).execute();
    BufferedSource body2 = response2.body().source();
    assertEquals("def", body2.readUtf8());
    assertFalse(body2.timeout().hasDeadline());

    // Use sequence numbers to confirm the connection was pooled.
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void tls() throws Exception {
    enableTls();
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    executeSynchronously("/").assertHandshake();
  }

  @Test public void tls_Async() throws Exception {
    enableTls();
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertHandshake();
  }

  @Test public void recoverWhenRetryOnConnectionFailureIsTrue() throws Exception {
    server.enqueue(new MockResponse().setBody("seed connection pool"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("retry success"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();
    assertTrue(client.retryOnConnectionFailure());

    executeSynchronously("/").assertBody("seed connection pool");
    executeSynchronously("/").assertBody("retry success");
  }

  @Test public void recoverWhenRetryOnConnectionFailureIsTrue_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    recoverWhenRetryOnConnectionFailureIsTrue();
  }

  @Test public void noRecoverWhenRetryOnConnectionFailureIsFalse() throws Exception {
    server.enqueue(new MockResponse().setBody("seed connection pool"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("unreachable!"));

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .retryOnConnectionFailure(false)
        .build();

    executeSynchronously("/").assertBody("seed connection pool");

    // If this succeeds, too many requests were made.
    executeSynchronously("/")
        .assertFailure(IOException.class)
        .assertFailureMatches("stream was reset: CANCEL",
            "unexpected end of stream on Connection.*"
                + server.getHostName() + ":" + server.getPort() + ".*");
  }

  @Test public void recoverWhenRetryOnConnectionFailureIsFalse_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    noRecoverWhenRetryOnConnectionFailureIsFalse();
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .dns(new SingleInetAddressDns())
        .sslSocketFactory(suppressTlsFallbackClientSocketFactory(), sslClient.trustManager)
        .build();

    executeSynchronously("/").assertBody("abc");
  }

  @Test public void recoverFromTlsHandshakeFailure_tlsFallbackScsvEnabled() throws Exception {
    final String tlsFallbackScsv = "TLS_FALLBACK_SCSV";
    List<String> supportedCiphers =
        Arrays.asList(sslClient.socketFactory.getSupportedCipherSuites());
    if (!supportedCiphers.contains(tlsFallbackScsv)) {
      // This only works if the client socket supports TLS_FALLBACK_SCSV.
      return;
    }

    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    RecordingSSLSocketFactory clientSocketFactory =
        new RecordingSSLSocketFactory(sslClient.socketFactory);
    client = client.newBuilder()
        .sslSocketFactory(clientSocketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .dns(new SingleInetAddressDns())
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLHandshakeException expected) {
    }

    List<SSLSocket> clientSockets = clientSocketFactory.getSocketsCreated();
    SSLSocket firstSocket = clientSockets.get(0);
    assertFalse(Arrays.asList(firstSocket.getEnabledCipherSuites()).contains(tlsFallbackScsv));
    SSLSocket secondSocket = clientSockets.get(1);
    assertTrue(Arrays.asList(secondSocket.getEnabledCipherSuites()).contains(tlsFallbackScsv));
  }

  @Test public void recoverFromTlsHandshakeFailure_Async() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(suppressTlsFallbackClientSocketFactory(), sslClient.trustManager)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertBody("abc");
  }

  @Test public void noRecoveryFromTlsHandshakeFailureWhenTlsFallbackIsDisabled() throws Exception {
    client = client.newBuilder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .dns(new SingleInetAddressDns())
        .sslSocketFactory(suppressTlsFallbackClientSocketFactory(), sslClient.trustManager)
        .build();

    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLProtocolException expected) {
      // RI response to the FAIL_HANDSHAKE
    } catch (SSLHandshakeException expected) {
      // Android's response to the FAIL_HANDSHAKE
    }
  }

  @Test public void cleartextCallsFailWhenCleartextIsDisabled() throws Exception {
    // Configure the client with only TLS configurations. No cleartext!
    client = client.newBuilder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .build();

    server.enqueue(new MockResponse());

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (UnknownServiceException expected) {
      assertEquals("CLEARTEXT communication not enabled for client", expected.getMessage());
    }
  }

  @Test public void setFollowSslRedirectsFalse() throws Exception {
    enableTls();
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: http://square.com"));

    client = client.newBuilder()
        .followSslRedirects(false)
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    Response response = client.newCall(request).execute();
    assertEquals(301, response.code());
    response.body().close();
  }

  @Test public void matchingPinnedCertificate() throws Exception {
    enableTls();
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Make a first request without certificate pinning. Use it to collect certificates to pin.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    CertificatePinner.Builder certificatePinnerBuilder = new CertificatePinner.Builder();
    for (Certificate certificate : response1.handshake().peerCertificates()) {
      certificatePinnerBuilder.add(server.getHostName(), CertificatePinner.pin(certificate));
    }
    response1.body().close();

    // Make another request with certificate pinning. It should complete normally.
    client = client.newBuilder()
        .certificatePinner(certificatePinnerBuilder.build())
        .build();
    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = client.newCall(request2).execute();
    assertNotSame(response2.handshake(), response1.handshake());
    response2.body().close();
  }

  @Test public void unmatchingPinnedCertificate() throws Exception {
    enableTls();
    server.enqueue(new MockResponse());

    // Pin publicobject.com's cert.
    client = client.newBuilder()
        .certificatePinner(new CertificatePinner.Builder()
            .add(server.getHostName(), "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
            .build())
        .build();

    // When we pin the wrong certificate, connectivity fails.
    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLPeerUnverifiedException expected) {
      assertTrue(expected.getMessage().startsWith("Certificate pinning failure!"));
    }
  }

  @Test public void post_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("def", recordedRequest.getBody().readUtf8());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));

    // Seed the connection pool so we have something that can fail.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("abc", response1.body().string());

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "body!"))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("def", response2.body().string());

    RecordedRequest get = server.takeRequest();
    assertEquals(0, get.getSequenceNumber());

    RecordedRequest post1 = server.takeRequest();
    assertEquals("body!", post1.getBody().readUtf8());
    assertEquals(1, post1.getSequenceNumber());

    RecordedRequest post2 = server.takeRequest();
    assertEquals("body!", post2.getBody().readUtf8());
    assertEquals(0, post2.getSequenceNumber());
  }

  @Test public void postBodyRetransmittedOnFailureRecovery_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postBodyRetransmittedOnFailureRecovery();
  }

  @Test public void cacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Charset")
        .setBody("A"));

    client = client.newBuilder()
        .cache(cache)
        .build();

    // Store a response in the cache.
    HttpUrl url = server.url("/");
    long request1SentAt = System.currentTimeMillis();
    executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
        .assertCode(200)
        .assertBody("A");
    long request1ReceivedAt = System.currentTimeMillis();
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    // Hit that stored response. It's different, but Vary says it doesn't matter.
    Thread.sleep(10); // Make sure the timestamps are unique.
    RecordedResponse cacheHit = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8");

    // Check the merged response. The request is the application's original request.
    cacheHit.assertCode(200)
        .assertBody("A")
        .assertHeaders(new Headers.Builder()
            .add("ETag", "v1")
            .add("Cache-Control", "max-age=60")
            .add("Vary", "Accept-Charset")
            .add("Content-Length", "1")
            .build())
        .assertRequestUrl(url)
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt);

    // Check the cached response. Its request contains only the saved Vary headers.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeaders(new Headers.Builder()
            .add("ETag", "v1")
            .add("Cache-Control", "max-age=60")
            .add("Vary", "Accept-Charset")
            .add("Content-Length", "1")
            .build())
        .assertRequestMethod("GET")
        .assertRequestUrl(url)
        .assertRequestHeader("Accept-Language")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt);

    cacheHit.assertNoNetworkResponse();
  }

  @Test public void conditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Vary: Accept-Charset")
        .addHeader("Donut: a")
        .setBody("A"));
    server.enqueue(new MockResponse().clearHeaders()
        .addHeader("Donut: b")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    client = client.newBuilder()
        .cache(cache)
        .build();

    // Store a response in the cache.
    long request1SentAt = System.currentTimeMillis();
    executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertBody("A");
    long request1ReceivedAt = System.currentTimeMillis();
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    // Hit that stored response. It's different, but Vary says it doesn't matter.
    Thread.sleep(10); // Make sure the timestamps are unique.
    long request2SentAt = System.currentTimeMillis();
    RecordedResponse cacheHit = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8");
    long request2ReceivedAt = System.currentTimeMillis();
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));

    // Check the merged response. The request is the application's original request.
    cacheHit.assertCode(200)
        .assertBody("A")
        .assertHeader("Donut", "b")
        .assertRequestUrl(server.url("/"))
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertRequestHeader("If-None-Match") // No If-None-Match on the user's request.
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt);

    // Check the cached response. Its request contains only the saved Vary headers.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(server.url("/"))
        .assertRequestHeader("Accept-Language") // No Vary on Accept-Language.
        .assertRequestHeader("Accept-Charset", "UTF-8") // Because of Vary on Accept-Charset.
        .assertRequestHeader("If-None-Match") // This wasn't present in the original request.
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt);

    // Check the network response. It has the caller's request, plus some caching headers.
    cacheHit.networkResponse()
        .assertCode(304)
        .assertHeader("Donut", "b")
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertRequestHeader("If-None-Match", "v1") // If-None-Match in the validation request.
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt);
  }

  @Test public void conditionalCacheHit_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    client = client.newBuilder()
        .cache(cache)
        .build();

    Request request1 = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request1).enqueue(callback);
    callback.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("A");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void conditionalCacheMiss() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Vary: Accept-Charset")
        .addHeader("Donut: a")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Donut: b")
        .setBody("B"));

    client = client.newBuilder()
        .cache(cache)
        .build();

    long request1SentAt = System.currentTimeMillis();
    executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
        .assertCode(200)
        .assertBody("A");
    long request1ReceivedAt = System.currentTimeMillis();
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    // Different request, but Vary says it doesn't matter.
    Thread.sleep(10); // Make sure the timestamps are unique.
    long request2SentAt = System.currentTimeMillis();
    RecordedResponse cacheMiss = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8");
    long request2ReceivedAt = System.currentTimeMillis();
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));

    // Check the user response. It has the application's original request.
    cacheMiss.assertCode(200)
        .assertBody("B")
        .assertHeader("Donut", "b")
        .assertRequestUrl(server.url("/"))
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt);

    // Check the cache response. Even though it's a miss, we used the cache.
    cacheMiss.cacheResponse()
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(server.url("/"))
        .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
        .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt);

    // Check the network response. It has the network request, plus caching headers.
    cacheMiss.networkResponse()
        .assertCode(200)
        .assertHeader("Donut", "b")
        .assertRequestHeader("If-None-Match", "v1")  // If-None-Match in the validation request.
        .assertRequestUrl(server.url("/"))
        .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
        .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt);
  }

  @Test public void conditionalCacheMiss_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse().setBody("B"));

    client = client.newBuilder()
        .cache(cache)
        .build();

    Request request1 = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request1).enqueue(callback);
    callback.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("B");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void onlyIfCachedReturns504WhenNotCached() throws Exception {
    executeSynchronously("/", "Cache-Control", "only-if-cached")
        .assertCode(504)
        .assertBody("")
        .assertNoNetworkResponse()
        .assertNoCacheResponse();
  }

  @Test public void networkDropsOnConditionalGet() throws IOException {
    client = client.newBuilder()
        .cache(cache)
        .build();

    // Seed the cache.
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    executeSynchronously("/")
        .assertCode(200)
        .assertBody("A");

    // Attempt conditional cache validation and a DNS miss.
    client.connectionPool().evictAll();
    client = client.newBuilder()
        .dns(new FakeDns().unknownHost())
        .build();
    executeSynchronously("/").assertFailure(UnknownHostException.class);
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

    executeSynchronously("/a")
        .assertCode(200)
        .assertBody("C")
        .priorResponse()
        .assertCode(302)
        .assertHeader("Test", "Redirect from /b to /c")
        .priorResponse()
        .assertCode(301)
        .assertHeader("Test", "Redirect from /a to /b");

    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reused.
    assertEquals(2, server.takeRequest().getSequenceNumber()); // Connection reused again!
  }

  @Test public void postRedirectsToGet() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    Response response = client.newCall(new Request.Builder()
        .url(server.url("/page1"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "Request Body"))
        .build()).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest page1 = server.takeRequest();
    assertEquals("POST /page1 HTTP/1.1", page1.getRequestLine());
    assertEquals("Request Body", page1.getBody().readUtf8());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void getClientRequestTimeout() throws Exception {
    enqueueRequestTimeoutResponses();

    Response response = client.newCall(new Request.Builder()
        .url(server.url("/")).build()).execute();

    assertEquals("Body", response.body().string());
  }

  private void enqueueRequestTimeoutResponses() {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(HttpURLConnection.HTTP_CLIENT_TIMEOUT)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse().setBody("Body"));
  }

  @Test public void requestBodyRetransmittedOnClientRequestTimeout() throws Exception {
    enqueueRequestTimeoutResponses();

    Response response = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "Hello"))
        .build()).execute();

    assertEquals("Body", response.body().string());

    RecordedRequest request1 = server.takeRequest();
    assertEquals("Hello", request1.getBody().readUtf8());

    RecordedRequest request2 = server.takeRequest();
    assertEquals("Hello", request2.getBody().readUtf8());
  }

  @Test public void propfindRedirectsToPropfindAndMaintainsRequestBody() throws Exception {
    // given
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    // when
    Response response = client.newCall(new Request.Builder()
        .url(server.url("/page1"))
        .method("PROPFIND", RequestBody.create(MediaType.parse("text/plain"), "Request Body"))
        .build()).execute();

    // then
    assertEquals("Page 2", response.body().string());

    RecordedRequest page1 = server.takeRequest();
    assertEquals("PROPFIND /page1 HTTP/1.1", page1.getRequestLine());
    assertEquals("Request Body", page1.getBody().readUtf8());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("PROPFIND /page2 HTTP/1.1", page2.getRequestLine());
    assertEquals("Request Body", page2.getBody().readUtf8());
  }

  @Test public void responseCookies() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie", "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        .addHeader("Set-Cookie", "c=d; Expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure"));

    RecordingCookieJar cookieJar = new RecordingCookieJar();
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    executeSynchronously("/").assertCode(200);

    List<Cookie> responseCookies = cookieJar.takeResponseCookies();
    assertEquals(2, responseCookies.size());
    assertEquals("a=b; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/",
        responseCookies.get(0).toString());
    assertEquals("c=d; expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure",
        responseCookies.get(1).toString());
  }

  @Test public void requestCookies() throws Exception {
    server.enqueue(new MockResponse());

    RecordingCookieJar cookieJar = new RecordingCookieJar();

    cookieJar.enqueueRequestCookies(
        new Cookie.Builder().name("a").value("b").domain(server.getHostName()).build(),
        new Cookie.Builder().name("c").value("d").domain(server.getHostName()).build());
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    executeSynchronously("/").assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("a=b; c=d", recordedRequest.getHeader("Cookie"));
  }

  @Test public void redirectsDoNotIncludeTooManyCookies() throws Exception {
    server2.enqueue(new MockResponse().setBody("Page 2"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/")));

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookie = new HttpCookie("c", "cookie");
    cookie.setDomain(server.getHostName());
    cookie.setPath("/");
    String portList = Integer.toString(server.getPort());
    cookie.setPortlist(portList);
    cookieManager.getCookieStore().add(server.url("/").uri(), cookie);
    client = client.newBuilder()
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build();

    Response response = client.newCall(new Request.Builder()
        .url(server.url("/page1"))
        .build()).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest request1 = server.takeRequest();
    assertEquals("c=cookie", request1.getHeader("Cookie"));

    RecordedRequest request2 = server2.takeRequest();
    assertNull(request2.getHeader("Cookie"));
  }

  @Test public void redirectsDoNotIncludeTooManyAuthHeaders() throws Exception {
    server2.enqueue(new MockResponse().setBody("Page 2"));
    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: " + server2.url("/b")));

    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(Credentials.basic("jesse", "secret")))
        .build();

    Request request = new Request.Builder().url(server.url("/a")).build();
    Response response = client.newCall(request).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest redirectRequest = server2.takeRequest();
    assertNull(redirectRequest.getHeader("Authorization"));
    assertEquals("/b", redirectRequest.getPath());
  }

  @Test public void redirect_Async() throws Exception {
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

    Request request = new Request.Builder().url(server.url("/a")).build();
    client.newCall(request).enqueue(callback);

    callback.await(server.url("/a"))
        .assertCode(200)
        .assertBody("C")
        .priorResponse()
        .assertCode(302)
        .assertHeader("Test", "Redirect from /b to /c")
        .priorResponse()
        .assertCode(301)
        .assertHeader("Test", "Redirect from /a to /b");

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

    executeSynchronously("/0")
        .assertCode(200)
        .assertBody("Success!");
  }

  @Test public void follow20Redirects_Async() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    Request request = new Request.Builder().url(server.url("/0")).build();
    client.newCall(request).enqueue(callback);
    callback.await(server.url("/0"))
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

    try {
      client.newCall(new Request.Builder().url(server.url("/0")).build()).execute();
      fail();
    } catch (IOException expected) {
      assertEquals("Too many follow-up requests: 21", expected.getMessage());
    }
  }

  @Test public void doesNotFollow21Redirects_Async() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }

    Request request = new Request.Builder().url(server.url("/0")).build();
    client.newCall(request).enqueue(callback);
    callback.await(server.url("/0")).assertFailure("Too many follow-up requests: 21");
  }

  @Test public void http204WithBodyDisallowed() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(204)
        .setBody("I'm not even supposed to be here today."));

    executeSynchronously("/")
        .assertFailure("HTTP 204 had non-zero Content-Length: 39");
  }

  @Test public void http205WithBodyDisallowed() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(205)
        .setBody("I'm not even supposed to be here today."));

    executeSynchronously("/")
        .assertFailure("HTTP 205 had non-zero Content-Length: 39");
  }

  @Test public void canceledBeforeExecute() throws Exception {
    Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test public void cancelDuringHttpConnect() throws Exception {
    cancelDuringConnect("http");
  }

  @Test public void cancelDuringHttpsConnect() throws Exception {
    cancelDuringConnect("https");
  }

  /** Cancel a call that's waiting for connect to complete. */
  private void cancelDuringConnect(String scheme) throws Exception {
    InetSocketAddress socketAddress = startNullServer();

    HttpUrl url = new HttpUrl.Builder()
        .scheme(scheme)
        .host(socketAddress.getHostName())
        .port(socketAddress.getPort())
        .build();

    long cancelDelayMillis = 300L;
    Call call = client.newCall(new Request.Builder().url(url).build());
    cancelLater(call, cancelDelayMillis);

    long startNanos = System.nanoTime();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertEquals(cancelDelayMillis, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), 100f);
  }

  private InetSocketAddress startNullServer() throws IOException {
    InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("localhost"), 0);
    nullServer = ServerSocketFactory.getDefault().createServerSocket();
    nullServer.bind(address);
    return new InetSocketAddress(address.getAddress(), nullServer.getLocalPort());
  }

  @Test public void cancelImmediatelyAfterEnqueue() throws Exception {
    server.enqueue(new MockResponse());
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/a"))
        .build());
    call.enqueue(callback);
    call.cancel();
    callback.await(server.url("/a")).assertFailure("Canceled", "Socket closed");
  }

  @Test public void cancelAll() throws Exception {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call.enqueue(callback);
    client.dispatcher().cancelAll();
    callback.await(server.url("/")).assertFailure("Canceled", "Socket closed");
  }

  @Test public void cancelBeforeBodyIsRead() throws Exception {
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));

    final Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Response> result = executor.submit(new Callable<Response>() {
      @Override public Response call() throws Exception {
        return call.execute();
      }
    });

    Thread.sleep(100); // wait for it to go in flight.

    call.cancel();
    try {
      result.get().body().bytes();
      fail();
    } catch (IOException expected) {
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE() throws Exception {
    Request request = new Request.Builder().url(server.url("/a")).build();
    final Call call = client.newCall(request);

    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        call.cancel();
        return new MockResponse().setBody("A");
      }
    });

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE_HTTPS() throws Exception {
    enableTls();
    cancelInFlightBeforeResponseReadThrowsIOE();
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    cancelInFlightBeforeResponseReadThrowsIOE();
  }

  /**
   * This test puts a request in front of one that is to be canceled, so that it is canceled before
   * I/O takes place.
   */
  @Test public void canceledBeforeIOSignalsOnFailure() throws Exception {
    // Force requests to be executed serially.
    okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher(client.dispatcher().executorService());
    dispatcher.setMaxRequests(1);
    client = client.newBuilder()
        .dispatcher(dispatcher)
        .build();

    Request requestA = new Request.Builder().url(server.url("/a")).build();
    Request requestB = new Request.Builder().url(server.url("/b")).build();
    final Call callA = client.newCall(requestA);
    final Call callB = client.newCall(requestB);

    server.setDispatcher(new Dispatcher() {
      char nextResponse = 'A';

      @Override public MockResponse dispatch(RecordedRequest request) {
        callB.cancel();
        return new MockResponse().setBody(Character.toString(nextResponse++));
      }
    });

    callA.enqueue(callback);
    callB.enqueue(callback);
    assertEquals("/a", server.takeRequest().getPath());

    callback.await(requestA.url()).assertBody("A");
    // At this point we know the callback is ready, and that it will receive a cancel failure.
    callback.await(requestB.url()).assertFailure("Canceled", "Socket closed");
  }

  @Test public void canceledBeforeIOSignalsOnFailure_HTTPS() throws Exception {
    enableTls();
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeIOSignalsOnFailure_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure() throws Exception {
    Request requestA = new Request.Builder().url(server.url("/a")).build();
    final Call call = client.newCall(requestA);
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        call.cancel();
        return new MockResponse().setBody("A");
      }
    });

    call.enqueue(callback);
    assertEquals("/a", server.takeRequest().getPath());

    callback.await(requestA.url()).assertFailure("Canceled", "stream was reset: CANCEL",
        "Socket closed");
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_HTTPS() throws Exception {
    enableTls();
    canceledBeforeResponseReadSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledBeforeResponseReadSignalsOnFailure();
  }

  /**
   * There's a race condition where the cancel may apply after the stream has already been
   * processed.
   */
  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<String> bodyRef = new AtomicReference<>();
    final AtomicBoolean failureRef = new AtomicBoolean();

    Request request = new Request.Builder().url(server.url("/a")).build();
    final Call call = client.newCall(request);
    call.enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        failureRef.set(true);
        latch.countDown();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        call.cancel();
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
    assertFalse(failureRef.get());
  }

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTPS()
      throws Exception {
    enableTls();
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTP_2()
      throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void cancelWithInterceptor() throws Exception {
    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            chain.proceed(chain.request());
            throw new AssertionError(); // We expect an exception.
          }
        }).build();

    Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test public void gzip() throws Exception {
    Buffer gzippedBody = gzip("abcabcabc");
    String bodySize = Long.toString(gzippedBody.size());

    server.enqueue(new MockResponse()
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip"));

    // Confirm that the user request doesn't have Accept-Encoding, and the user
    // response doesn't have a Content-Encoding or Content-Length.
    RecordedResponse userResponse = executeSynchronously("/");
    userResponse.assertCode(200)
        .assertRequestHeader("Accept-Encoding")
        .assertHeader("Content-Encoding")
        .assertHeader("Content-Length")
        .assertBody("abcabcabc");

    // But the network request doesn't lie. OkHttp used gzip for this call.
    userResponse.networkResponse()
        .assertHeader("Content-Encoding", "gzip")
        .assertHeader("Content-Length", bodySize)
        .assertRequestHeader("Accept-Encoding", "gzip");
  }

  /** https://github.com/square/okhttp/issues/1927 */
  @Test public void gzipResponseAfterAuthenticationChallenge() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setBody(gzip("abcabcabc"))
        .addHeader("Content-Encoding: gzip"));
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator("password"))
        .build();

    executeSynchronously("/").assertBody("abcabcabc");
  }

  @Test public void rangeHeaderPreventsAutomaticGzip() throws Exception {
    Buffer gzippedBody = gzip("abcabcabc");

    // Enqueue a gzipped response. Our request isn't expecting it, but that's okay.
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip")
        .addHeader("Content-Range: bytes 0-" + (gzippedBody.size() - 1)));

    // Make a range request.
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Range", "bytes=0-")
        .build();
    Call call = client.newCall(request);

    // The response is not decompressed.
    Response response = call.execute();
    assertEquals("gzip", response.header("Content-Encoding"));
    assertEquals(gzippedBody.snapshot(), response.body().source().readByteString());

    // The request did not offer gzip support.
    RecordedRequest recordedRequest = server.takeRequest();
    assertNull(recordedRequest.getHeader("Accept-Encoding"));
  }

  @Test public void asyncResponseCanBeConsumedLater() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    final BlockingQueue<Response> responseRef = new SynchronousQueue<>();
    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        throw new AssertionError();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        try {
          responseRef.put(response);
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
      }
    });

    Response response = responseRef.take();
    assertEquals(200, response.code());
    assertEquals("abc", response.body().string());

    // Make another request just to confirm that that connection can be reused...
    executeSynchronously("/").assertBody("def");
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reused.

    // ... even before we close the response body!
    response.body().close();
  }

  @Test public void userAgentIsIncludedByDefault() throws Exception {
    server.enqueue(new MockResponse());

    executeSynchronously("/");

    RecordedRequest recordedRequest = server.takeRequest();
    assertTrue(recordedRequest.getHeader("User-Agent")
        .matches(Version.userAgent()));
  }

  @Test public void setFollowRedirectsFalse() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    client = client.newBuilder()
        .followRedirects(false)
        .build();
    executeSynchronously("/a")
        .assertBody("A")
        .assertCode(302);
  }

  @Test public void expect100ContinueNonEmptyRequestBody() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create(MediaType.parse("text/plain"), "abc"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful();

    assertEquals("abc", server.takeRequest().getBody().readUtf8());
  }

  @Test public void expect100ContinueEmptyRequestBody() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create(MediaType.parse("text/plain"), ""))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful();
  }

  /** We forbid non-ASCII characters in outgoing request headers, but accept UTF-8. */
  @Test public void responseHeaderParsingIsLenient() throws Exception {
    Headers headers = new Headers.Builder()
        .add("Content-Length", "0")
        .addLenient("a\tb: c\u007fd")
        .addLenient(": ef")
        .addLenient("\ud83c\udf69: \u2615\ufe0f")
        .build();
    server.enqueue(new MockResponse().setHeaders(headers));

    executeSynchronously("/")
        .assertHeader("a\tb", "c\u007fd")
        .assertHeader("\ud83c\udf69", "\u2615\ufe0f")
        .assertHeader("", "ef");
  }

  @Test public void customDns() throws Exception {
    // Configure a DNS that returns our MockWebServer for every hostname.
    FakeDns dns = new FakeDns();
    dns.addresses(Dns.SYSTEM.lookup(server.url("/").host()));
    client = client.newBuilder()
        .dns(dns)
        .build();

    server.enqueue(new MockResponse());
    Request request = new Request.Builder()
        .url(server.url("/").newBuilder().host("android.com").build())
        .build();
    executeSynchronously(request).assertCode(200);

    dns.assertRequests("android.com");
  }

  /** We had a bug where failed HTTP/2 calls could break the entire connection. */
  @Test public void failingCallsDoNotInterfereWithConnection() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse().setBody("Response 1"));
    server.enqueue(new MockResponse().setBody("Response 2"));

    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("abc");
        sink.flush();

        makeFailingCall();

        sink.writeUtf8("def");
        sink.flush();
      }
    };
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build());
    assertEquals("Response 1", call.execute().body().string());
  }

  /** Test which headers are sent unencrypted to the HTTP proxy. */
  @Test public void proxyConnectOmitsApplicationHeaders() throws Exception {
    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("encrypted response from the origin server"));

    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .proxy(server.toProxyAddress())
        .hostnameVerifier(hostnameVerifier)
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .header("Private", "Secret")
        .header("User-Agent", "App 1.0")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("encrypted response from the origin server", response.body().string());

    RecordedRequest connect = server.takeRequest();
    assertNull(connect.getHeader("Private"));
    assertEquals(Version.userAgent(), connect.getHeader("User-Agent"));
    assertEquals("Keep-Alive", connect.getHeader("Proxy-Connection"));
    assertEquals("android.com:443", connect.getHeader("Host"));

    RecordedRequest get = server.takeRequest();
    assertEquals("Secret", get.getHeader("Private"));
    assertEquals("App 1.0", get.getHeader("User-Agent"));

    assertEquals(Arrays.asList("verify android.com"), hostnameVerifier.calls);
  }

  /** Respond to a proxy authorization challenge. */
  @Test public void proxyAuthenticateOnConnect() throws Exception {
    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password"))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("response body", response.body().string());

    RecordedRequest connect1 = server.takeRequest();
    assertEquals("CONNECT android.com:443 HTTP/1.1", connect1.getRequestLine());
    assertNull(connect1.getHeader("Proxy-Authorization"));

    RecordedRequest connect2 = server.takeRequest();
    assertEquals("CONNECT android.com:443 HTTP/1.1", connect2.getRequestLine());
    assertEquals("password", connect2.getHeader("Proxy-Authorization"));

    RecordedRequest get = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
    assertNull(get.getHeader("Proxy-Authorization"));
  }

  /** Confirm that the proxy authenticator works for unencrypted HTTP proxies. */
  @Test public void httpProxyAuthenticate() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""));
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password"))
        .build();

    Request request = new Request.Builder()
        .url("http://android.com/foo")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("response body", response.body().string());

    RecordedRequest get1 = server.takeRequest();
    assertEquals("GET http://android.com/foo HTTP/1.1", get1.getRequestLine());
    assertNull(get1.getHeader("Proxy-Authorization"));

    RecordedRequest get2 = server.takeRequest();
    assertEquals("GET http://android.com/foo HTTP/1.1", get2.getRequestLine());
    assertEquals("password", get2.getHeader("Proxy-Authorization"));
  }

  /**
   * OkHttp has a bug where a `Connection: close` response header is not honored when establishing a
   * TLS tunnel. https://github.com/square/okhttp/issues/2426
   */
  @Test public void proxyAuthenticateOnConnectWithConnectionClose() throws Exception {
    server.useHttps(sslClient.socketFactory, true);
    server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password"))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("response body", response.body().string());

    // First CONNECT call needs a new connection.
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Second CONNECT call needs a new connection.
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // GET reuses the connection from the second connect.
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void tooManyProxyAuthFailuresWithConnectionClose() throws IOException {
    server.useHttps(sslClient.socketFactory, true);
    server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(407)
          .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
          .addHeader("Connection: close"));
    }

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password"))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (ProtocolException expected) {
    }
  }

  /**
   * Confirm that we don't send the Proxy-Authorization header from the request to the proxy server.
   * We used to have that behavior but it is problematic because unrelated requests end up sharing
   * credentials. Worse, that approach leaks proxy credentials to the origin server.
   */
  @Test public void noProactiveProxyAuthorization() throws Exception {
    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .proxy(server.toProxyAddress())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .header("Proxy-Authorization", "password")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("response body", response.body().string());

    RecordedRequest connect = server.takeRequest();
    assertNull(connect.getHeader("Proxy-Authorization"));

    RecordedRequest get = server.takeRequest();
    assertEquals("password", get.getHeader("Proxy-Authorization"));
  }

  @Test public void interceptorGetsFramedProtocol() throws Exception {
    enableProtocol(Protocol.HTTP_2);

    // Capture the protocol as it is observed by the interceptor.
    final AtomicReference<Protocol> protocolRef = new AtomicReference<>();
    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        protocolRef.set(chain.connection().protocol());
        return chain.proceed(chain.request());
      }
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    // Make an HTTP/2 request and confirm that the protocol matches.
    server.enqueue(new MockResponse());
    executeSynchronously("/");
    assertEquals(Protocol.HTTP_2, protocolRef.get());
  }

  @Test public void serverSendsInvalidResponseHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTP/1.1 200 OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTP/1.1 200 OK");
  }

  @Test public void serverSendsInvalidCodeTooLarge() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 2147483648 OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTTP/1.1 2147483648 OK");
  }

  @Test public void serverSendsInvalidCodeNotANumber() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 00a OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line: HTTP/1.1 00a OK");
  }

  @Test public void serverSendsUnnecessaryWhitespace() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus(" HTTP/1.1 200 OK"));

    executeSynchronously("/")
        .assertFailure("Unexpected status line:  HTTP/1.1 200 OK");
  }

  @Test public void requestHeaderNameWithSpaceForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a b", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0x20 at 1 in header name: a b", expected.getMessage());
    }
  }

  @Test public void requestHeaderNameWithTabForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a\tb", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("Unexpected char 0x09 at 1 in header name: a\tb", expected.getMessage());
    }
  }

  @Test public void responseHeaderNameWithSpacePermitted() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a b", "c"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals("c", response.header("a b"));
  }

  @Test public void responseHeaderNameWithTabPermitted() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a\tb", "c"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertEquals("c", response.header("a\tb"));
  }

  @Test public void connectFails() throws Exception {
    server.shutdown();

    executeSynchronously("/")
        .assertFailure(IOException.class);
  }

  @Test public void requestBodySurvivesRetries() throws Exception {
    server.enqueue(new MockResponse());

    // Enable a misconfigured proxy selector to guarantee that the request is retried.
    client = client.newBuilder()
        .proxySelector(new FakeProxySelector()
            .addProxy(server2.toProxyAddress())
            .addProxy(Proxy.NO_PROXY))
        .build();
    server2.shutdown();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "abc"))
        .build();

    executeSynchronously(request);
    assertEquals("abc", server.takeRequest().getBody().readUtf8());
  }

  @Ignore // This may fail in DNS lookup, which we don't have timeouts for.
  @Test public void invalidHost() throws Exception {
    Request request = new Request.Builder()
        .url(HttpUrl.parse("http://1234.1.1.1/"))
        .build();

    executeSynchronously(request)
        .assertFailure(UnknownHostException.class);
  }

  @Test public void uploadBodySmallChunkedEncoding() throws Exception {
    upload(true, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(1048576, recordedRequest.getBodySize());
    assertFalse(recordedRequest.getChunkSizes().isEmpty());
  }

  @Test public void uploadBodyLargeChunkedEncoding() throws Exception {
    upload(true, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(1048576, recordedRequest.getBodySize());
    assertFalse(recordedRequest.getChunkSizes().isEmpty());
  }

  @Test public void uploadBodySmallFixedLength() throws Exception {
    upload(false, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(1048576, recordedRequest.getBodySize());
    assertTrue(recordedRequest.getChunkSizes().isEmpty());
  }

  @Test public void uploadBodyLargeFixedLength() throws Exception {
    upload(false, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(1048576, recordedRequest.getBodySize());
    assertTrue(recordedRequest.getChunkSizes().isEmpty());
  }

  private void upload(
      final boolean chunked, final int size, final int writeSize) throws Exception {
    server.enqueue(new MockResponse());
    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody(chunked, size, writeSize))
        .build());
  }

  /** https://github.com/square/okhttp/issues/2344 */
  @Test public void ipv6HostHasSquareBraces() throws Exception {
    // Use a proxy to fake IPv6 connectivity, even if localhost doesn't have IPv6.
    server.useHttps(sslClient.socketFactory, true);
    server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxy(server.toProxyAddress())
        .build();

    Request request = new Request.Builder()
        .url("https://[::1]/")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("response body", response.body().string());

    RecordedRequest connect = server.takeRequest();
    assertEquals("CONNECT [::1]:443 HTTP/1.1", connect.getRequestLine());
    assertEquals("[::1]:443", connect.getHeader("Host"));

    RecordedRequest get = server.takeRequest();
    assertEquals("GET / HTTP/1.1", get.getRequestLine());
    assertEquals("[::1]", get.getHeader("Host"));
  }

  private RequestBody requestBody(final boolean chunked, final long size, final int writeSize) {
    final byte[] buffer = new byte[writeSize];
    Arrays.fill(buffer, (byte) 'x');

    return new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain; charset=utf-8");
      }

      @Override public long contentLength() throws IOException {
        return chunked ? -1L : size;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        for (int count = 0; count < size; count += writeSize) {
          sink.write(buffer, 0, (int) Math.min(size - count, writeSize));
        }
      }
    };
  }

  @Test public void emptyResponseBody() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("abc", "def"));
    executeSynchronously("/")
        .assertCode(200)
        .assertHeader("abc", "def")
        .assertBody("");
  }

  @Test public void leakedResponseBodyLogsStackTrace() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("This gets leaked."));

    client = defaultClient().newBuilder()
        .connectionPool(new ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Level original = logger.getLevel();
    logger.setLevel(Level.FINE);
    logHandler.setFormatter(new SimpleFormatter());
    try {
      client.newCall(request).execute(); // Ignore the response so it gets leaked then GC'd.
      awaitGarbageCollection();

      String message = logHandler.take();
      assertTrue(message.contains("WARNING: A connection to " + server.url("/") + " was leaked."
          + " Did you forget to close a response body?"));
      assertTrue(message.contains("okhttp3.RealCall.execute("));
      assertTrue(message.contains("okhttp3.CallTest.leakedResponseBodyLogsStackTrace("));
    } finally {
      logger.setLevel(original);
    }
  }

  @Test public void asyncLeakedResponseBodyLogsStackTrace() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("This gets leaked."));

    client = defaultClient().newBuilder()
        .connectionPool(new ConnectionPool(0, 10, TimeUnit.MILLISECONDS))
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    Level original = logger.getLevel();
    logger.setLevel(Level.FINE);
    logHandler.setFormatter(new SimpleFormatter());
    try {
      final CountDownLatch latch = new CountDownLatch(1);
      client.newCall(request).enqueue(new Callback() {
        @Override public void onFailure(Call call, IOException e) {
          fail();
        }

        @Override public void onResponse(Call call, Response response) throws IOException {
          // Ignore the response so it gets leaked then GC'd.
          latch.countDown();
        }
      });
      latch.await();
      // There's some flakiness when triggering a GC for objects in a separate thread. Adding a
      // small delay appears to ensure the objects will get GC'd.
      Thread.sleep(200);
      awaitGarbageCollection();

      String message = logHandler.take();
      assertTrue(message.contains("WARNING: A connection to " + server.url("/") + " was leaked."
          + " Did you forget to close a response body?"));
      assertTrue(message.contains("okhttp3.RealCall.enqueue("));
      assertTrue(message.contains("okhttp3.CallTest.asyncLeakedResponseBodyLogsStackTrace("));
    } finally {
      logger.setLevel(original);
    }
  }

  @Test public void httpsWithIpAddress() throws Exception {
    String localIpAddress = InetAddress.getLoopbackAddress().getHostAddress();

    // Create a certificate with an IP address in the subject alt name.
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("example.com")
        .subjectAlternativeName(localIpAddress)
        .build();
    SslClient sslClient = new SslClient.Builder()
        .certificateChain(heldCertificate.keyPair, heldCertificate.certificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build();

    // Use that certificate on the server and trust it on the client.
    server.useHttps(sslClient.socketFactory, false);
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
        .build();

    // Make a request.
    server.enqueue(new MockResponse());
    HttpUrl url = server.url("/").newBuilder()
        .host(localIpAddress)
        .build();
    Request request = new Request.Builder()
        .url(url)
        .build();
    executeSynchronously(request)
        .assertCode(200);

    // Confirm that the IP address was used in the host header.
    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(localIpAddress + ":" + server.getPort(), recordedRequest.getHeader("Host"));
  }

  private void makeFailingCall() {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() throws IOException {
        return 1;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        throw new IOException("write body fail!");
      }
    };
    OkHttpClient nonRetryingClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build();
    Call call = nonRetryingClient.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
      assertEquals("write body fail!", expected.getMessage());
    }
  }

  private RecordedResponse executeSynchronously(String path, String... headers) throws IOException {
    Request.Builder builder = new Request.Builder();
    builder.url(server.url(path));
    for (int i = 0, size = headers.length; i < size; i += 2) {
      builder.addHeader(headers[i], headers[i + 1]);
    }
    return executeSynchronously(builder.build());
  }

  private RecordedResponse executeSynchronously(Request request) throws IOException {
    Call call = client.newCall(request);
    try {
      Response response = call.execute();
      String bodyString = response.body().string();
      return new RecordedResponse(request, response, null, bodyString, null);
    } catch (IOException e) {
      return new RecordedResponse(request, null, null, null, e);
    }
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    enableTls();
    client = client.newBuilder()
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build();
    server.setProtocols(client.protocols());
  }

  private void enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(sslClient.socketFactory, false);
  }

  private Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  private void cancelLater(final Call call, final long delay) {
    new Thread("canceler") {
      @Override public void run() {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        call.cancel();
      }
    }.start();
  }

  private static class RecordingSSLSocketFactory extends DelegatingSSLSocketFactory {

    private List<SSLSocket> socketsCreated = new ArrayList<>();

    public RecordingSSLSocketFactory(SSLSocketFactory delegate) {
      super(delegate);
    }

    @Override
    protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
      socketsCreated.add(sslSocket);
      return sslSocket;
    }

    public List<SSLSocket> getSocketsCreated() {
      return socketsCreated;
    }
  }

  /**
   * Used during tests that involve TLS connection fallback attempts. OkHttp includes the
   * TLS_FALLBACK_SCSV cipher on fallback connections. See {@link FallbackTestClientSocketFactory}
   * for details.
   */
  private FallbackTestClientSocketFactory suppressTlsFallbackClientSocketFactory() {
    return new FallbackTestClientSocketFactory(sslClient.socketFactory);
  }
}
