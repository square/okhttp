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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.RecordingEventListener.CallEnd;
import okhttp3.RecordingEventListener.ConnectionAcquired;
import okhttp3.RecordingEventListener.ConnectionReleased;
import okhttp3.RecordingEventListener.ResponseFailed;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.Version;
import okhttp3.internal.http.RecordingProxySelector;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSource;
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
import static java.util.Arrays.asList;
import static okhttp3.CipherSuite.TLS_DH_anon_WITH_AES_128_GCM_SHA256;
import static okhttp3.TestUtil.awaitGarbageCollection;
import static okhttp3.internal.Internal.addHeaderLenient;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

public final class CallTest {
  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final TestRule timeout = new Timeout(30_000, TimeUnit.MILLISECONDS);
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final MockWebServer server2 = new MockWebServer();
  @Rule public final InMemoryFileSystem fileSystem = new InMemoryFileSystem();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private RecordingEventListener listener = new RecordingEventListener();
  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = clientTestRule.newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build();
  private RecordingCallback callback = new RecordingCallback();
  private TestLogHandler logHandler = new TestLogHandler();
  private Cache cache = new Cache(new File("/cache/"), Integer.MAX_VALUE, fileSystem);
  private Logger logger = Logger.getLogger(OkHttpClient.class.getName());

  @Before public void setUp() {
    platform.assumeNotOpenJSSE();

    logger.addHandler(logHandler);
  }

  @After public void tearDown() throws Exception {
    cache.delete();
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
    assertThat(recordedRequest.getMethod()).isEqualTo("GET");
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isNull();
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
      assertThat(expected.getMessage()).isEqualTo(
          "Expected URL scheme 'http' or 'https' but was 'ftp'");
    }
  }

  @Test public void invalidPort() throws Exception {
    Request.Builder requestBuilder = new Request.Builder();
    try {
      requestBuilder.url("http://localhost:65536/");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("Invalid URL port: \"65536\"");
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
    assertThat(recordedRequest.getHeaders().values("A")).containsExactly("345", "456");
  }

  @Test public void repeatedHeaderNames_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    repeatedHeaderNames();
  }

  @Test public void getWithRequestBody() throws Exception {
    server.enqueue(new MockResponse());

    try {
      new Request.Builder().method("GET", RequestBody.create("abc", MediaType.get("text/plain")));
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
    assertThat(recordedRequest.getMethod()).isEqualTo("HEAD");
    assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("SyncApiTest");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isNull();
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
    Response response = client.newCall(headRequest).execute();
    assertThat(response.code()).isEqualTo(200);
    assertArrayEquals(new byte[0], response.body().bytes());

    Request getRequest = new Request.Builder()
        .url(server.url("/"))
        .build();
    executeSynchronously(getRequest)
        .assertCode(200)
        .assertBody("abc");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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
        .post(RequestBody.create("def", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8");
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
        .method("POST", RequestBody.create(new byte[0], null))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0");
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
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
        .method("POST", RequestBody.create(body, null))
        .build();

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, null))
        .build();

    Response response = client.newCall(request).execute();
    assertThat(response.code()).isEqualTo(200);
    response.body().close();

    RecordedRequest recordedRequest1 = server.takeRequest();
    assertThat(recordedRequest1.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest1.getBody().readUtf8()).isEqualTo(body);
    assertThat(recordedRequest1.getHeader("Authorization")).isNull();

    RecordedRequest recordedRequest2 = server.takeRequest();
    assertThat(recordedRequest2.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest2.getBody().readUtf8()).isEqualTo(body);
    assertThat(recordedRequest2.getHeader("Authorization")).isEqualTo(credential);
  }

  @Test public void attemptAuthorization20Times() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse().setResponseCode(401));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, null))
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
        .authenticator(new RecordingOkAuthenticator(credential, null))
        .build();

    try {
      client.newCall(new Request.Builder().url(server.url("/0")).build()).execute();
      fail();
    } catch (IOException expected) {
      assertThat(expected.getMessage()).isEqualTo("Too many follow-up requests: 21");
    }
  }

  /**
   * We had a bug where we were passing a null route to the authenticator.
   * https://github.com/square/okhttp/issues/3809
   */
  @Test public void authenticateWithNoConnection() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Connection: close")
        .setResponseCode(401)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));

    RecordingOkAuthenticator authenticator = new RecordingOkAuthenticator(null, null);

    client = client.newBuilder()
        .authenticator(authenticator)
        .build();

    executeSynchronously("/")
        .assertCode(401);

    assertThat(authenticator.onlyRoute()).isNotNull();
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
    assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
    assertThat(recordedRequest.getBody().size()).isEqualTo(0);
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("0");
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
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
        .method("DELETE", RequestBody.create("def", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
  }

  @Test public void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .put(RequestBody.create("def", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("PUT");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8");
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
        .patch(RequestBody.create("def", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("PATCH");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8");
  }

  @Test public void patch_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    patch();
  }

  @Test public void patch_HTTPS() throws Exception {
    enableTls();
    patch();
  }

  @Test public void customMethodWithBody() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("CUSTOM", RequestBody.create("def", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("CUSTOM");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8");
  }

  @Test public void unspecifiedRequestBodyContentTypeDoesNotGetDefault() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", RequestBody.create("abc", null))
        .build();

    executeSynchronously(request).assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Content-Type")).isNull();
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
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
      assertThat(e.getMessage()).isEqualTo("Already Executed");
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Already Executed");
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest");
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
      assertThat(e.getMessage()).isEqualTo("Already Executed");
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Already Executed");
    }

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("SyncApiTest");

    callback.await(request.url()).assertSuccessful();
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

    assertThat("abc").isEqualTo(response1.body().string());
    assertThat("def").isEqualTo(response2.body().string());
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

    assertThat(bodies).contains("abc");
    assertThat(bodies).contains("def");
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

    assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("AsyncApiTest");
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

    assertThat(logHandler.take()).isEqualTo(
        ("INFO: Callback failure for call to " + server.url("/") + "..."));
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    executeSynchronously("/a").assertBody("abc");
    executeSynchronously("/b").assertBody("def");
    executeSynchronously("/c").assertBody("ghi");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  /**
   * Each OkHttpClient used to get its own instance of NullProxySelector, and because these weren't
   * equal their connections weren't pooled. That's a nasty performance bug!
   *
   * https://github.com/square/okhttp/issues/5519
   */
  @Test public void connectionPoolingWithFreshClientSamePool() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    client = new OkHttpClient.Builder()
        .connectionPool(client.connectionPool())
        .proxy(server.toProxyAddress())
        .build();
    executeSynchronously("/a").assertBody("abc");

    client = new OkHttpClient.Builder()
        .connectionPool(client.connectionPool())
        .proxy(server.toProxyAddress())
        .build();
    executeSynchronously("/b").assertBody("def");

    client = new OkHttpClient.Builder()
        .connectionPool(client.connectionPool())
        .proxy(server.toProxyAddress())
        .build();
    executeSynchronously("/c").assertBody("ghi");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  @Test public void connectionPoolingWithClientBuiltOffProxy() throws Exception {
    client = new OkHttpClient.Builder()
        .proxy(server.toProxyAddress())
        .build();

    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));

    client = client.newBuilder().build();
    executeSynchronously("/a").assertBody("abc");

    client = client.newBuilder().build();
    executeSynchronously("/b").assertBody("def");

    client = client.newBuilder().build();
    executeSynchronously("/c").assertBody("ghi");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
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

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
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
        assertThat(bytes.read()).isEqualTo('a');
        assertThat(bytes.read()).isEqualTo('b');
        assertThat(bytes.read()).isEqualTo('c');

        // This request will share a connection with 'A' cause it's all done.
        client.newCall(new Request.Builder().url(server.url("/b")).build()).enqueue(callback);
      }
    });

    callback.await(server.url("/b")).assertCode(200).assertBody("def");
    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Connection reuse!
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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
    assertThat(bodySource.readByte()).isEqualTo((byte) 'd');

    // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
    long startNanos = System.nanoTime();
    try {
      bodySource.readByte();
      fail();
    } catch (IOException expected) {
      // Timed out as expected.
      long elapsedNanos = System.nanoTime() - startNanos;
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      assertThat(elapsedMillis).isLessThan(500);
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
    RecordingProxySelector proxySelector = new RecordingProxySelector();
    proxySelector.proxies.add(new Proxy(Proxy.Type.HTTP, TestUtil.UNREACHABLE_ADDRESS));
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

  /** https://github.com/square/okhttp/issues/4875 */
  @Test public void interceptorRecoversWhenRoutesExhausted() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    server.enqueue(new MockResponse());

    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            try {
              chain.proceed(chain.request());
              throw new AssertionError();
            } catch (IOException expected) {
              return chain.proceed(chain.request());
            }
          }
        })
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    executeSynchronously(request)
        .assertCode(200);
  }

  /** https://github.com/square/okhttp/issues/4761 */
  @Test
  public void interceptorCallsProceedWithoutClosingPriorResponse() throws Exception {
    server.enqueue(new MockResponse()
        .setBodyDelay(250, TimeUnit.MILLISECONDS)
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setBody("def"));

    client = clientTestRule.newClientBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            try {
              chain.proceed(chain.request());
              fail();
            } catch (IllegalStateException expected) {
              assertThat(expected).hasMessageContaining("please call response.close()");
            }
            return response;
          }
        })
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    executeSynchronously(request)
        .assertFailure(SocketException.class);
  }

  /**
   * Make a request with two routes. The first route will fail because the null server connects but
   * never responds. The manual retry will succeed.
   */
  @Test public void readTimeoutFails() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));
    server2.enqueue(new MockResponse()
        .setBody("success!"));

    RecordingProxySelector proxySelector = new RecordingProxySelector();
    proxySelector.proxies.add(server.toProxyAddress());
    proxySelector.proxies.add(server2.toProxyAddress());

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
    OkHttpClient c = client.newBuilder()
        .addInterceptor(chain -> { throw new IOException(); })
        .build();
    Request request = new Request.Builder().url(server.url("/")).build();
    c.newCall(request).enqueue(callback);
    RecordedResponse response = callback.await(request.url());
    assertThat(response.request).isEqualTo(request);
  }

  @Test public void reusedSinksGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    // Call 1: set a deadline on the request body.
    RequestBody requestBody1 = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
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
    assertThat(response1.code()).isEqualTo(200);

    // Call 2: check for the absence of a deadline on the request body.
    RequestBody requestBody2 = new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        assertThat(sink.timeout().hasDeadline()).isFalse();
        sink.writeUtf8("def");
      }
    };
    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .method("POST", requestBody2)
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.code()).isEqualTo(200);

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void reusedSourcesGetIndependentTimeoutInstances() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));

    // Call 1: set a deadline on the response body.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    BufferedSource body1 = response1.body().source();
    assertThat(body1.readUtf8()).isEqualTo("abc");
    body1.timeout().deadline(5, TimeUnit.SECONDS);

    // Call 2: check for the absence of a deadline on the request body.
    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = client.newCall(request2).execute();
    BufferedSource body2 = response2.body().source();
    assertThat(body2.readUtf8()).isEqualTo("def");
    assertThat(body2.timeout().hasDeadline()).isFalse();

    // Use sequence numbers to confirm the connection was pooled.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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
    // Set to 2 because the seeding request will count down before the retried request does.
    CountDownLatch requestFinished = new CountDownLatch(2);

    QueueDispatcher dispatcher = new QueueDispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        if (peek().getSocketPolicy() == SocketPolicy.DISCONNECT_AFTER_REQUEST) {
          requestFinished.await();
        }
        return super.dispatch(request);
      }
    };
    dispatcher.enqueueResponse(new MockResponse().setBody("seed connection pool"));
    dispatcher.enqueueResponse(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    dispatcher.enqueueResponse(new MockResponse().setBody("retry success"));
    server.setDispatcher(dispatcher);

    listener = new RecordingEventListener() {
      @Override public void responseHeadersStart(Call call) {
        requestFinished.countDown();
        super.responseHeadersStart(call);
      }
    };

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .eventListenerFactory(clientTestRule.wrap(listener))
        .build();
    assertThat(client.retryOnConnectionFailure()).isTrue();

    executeSynchronously("/").assertBody("seed connection pool");
    executeSynchronously("/").assertBody("retry success");

    // The call that seeds the connection pool.
    listener.removeUpToEvent(CallEnd.class);

    // The ResponseFailed event is not necessarily fatal!
    listener.removeUpToEvent(ConnectionAcquired.class);
    listener.removeUpToEvent(ResponseFailed.class);
    listener.removeUpToEvent(ConnectionReleased.class);
    listener.removeUpToEvent(ConnectionAcquired.class);
    listener.removeUpToEvent(ConnectionReleased.class);
    listener.removeUpToEvent(CallEnd.class);
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
            "unexpected end of stream on " + server.url("/").redact());
  }

  @Test public void recoverWhenRetryOnConnectionFailureIsFalse_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    noRecoverWhenRetryOnConnectionFailureIsFalse();
  }

  @Test public void tlsHandshakeFailure_noFallbackByDefault() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("response that will never be received"));
    RecordedResponse response = executeSynchronously("/");
    response.assertFailure(
            SSLException.class, // JDK 11 response to the FAIL_HANDSHAKE
            SSLProtocolException.class, // RI response to the FAIL_HANDSHAKE
            SSLHandshakeException.class // Android's response to the FAIL_HANDSHAKE
    );
    assertThat(client.connectionSpecs()).doesNotContain(ConnectionSpec.COMPATIBLE_TLS);
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    executeSynchronously("/").assertBody("abc");
  }

  @Test public void recoverFromTlsHandshakeFailure_tlsFallbackScsvEnabled() throws Exception {
    platform.assumeNotConscrypt();

    final String tlsFallbackScsv = "TLS_FALLBACK_SCSV";
    List<String> supportedCiphers =
        asList(handshakeCertificates.sslSocketFactory().getSupportedCipherSuites());
    if (!supportedCiphers.contains(tlsFallbackScsv)) {
      // This only works if the client socket supports TLS_FALLBACK_SCSV.
      return;
    }

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    RecordingSSLSocketFactory clientSocketFactory =
        new RecordingSSLSocketFactory(handshakeCertificates.sslSocketFactory());
    client = client.newBuilder()
        .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLHandshakeException expected) {
    }

    List<SSLSocket> clientSockets = clientSocketFactory.getSocketsCreated();
    SSLSocket firstSocket = clientSockets.get(0);
    assertThat(asList(firstSocket.getEnabledCipherSuites())).doesNotContain(tlsFallbackScsv);
    SSLSocket secondSocket = clientSockets.get(1);
    assertThat(asList(secondSocket.getEnabledCipherSuites())).contains(tlsFallbackScsv);
  }

  @Test public void recoverFromTlsHandshakeFailure_Async() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertBody("abc");
  }

  @Test public void noRecoveryFromTlsHandshakeFailureWhenTlsFallbackIsDisabled() throws Exception {
    client = client.newBuilder()
        .connectionSpecs(asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLProtocolException expected) {
      // RI response to the FAIL_HANDSHAKE
    } catch (SSLHandshakeException expected) {
      // Android's response to the FAIL_HANDSHAKE
    } catch (SSLException expected) {
      // JDK 11 response to the FAIL_HANDSHAKE
      String jvmVersion = System.getProperty("java.specification.version");
      assertThat(jvmVersion).isEqualTo("11");
    }
  }

  @Test public void tlsHostnameVerificationFailure() throws Exception {
    server.enqueue(new MockResponse());

    HeldCertificate serverCertificate = new HeldCertificate.Builder()
        .commonName("localhost") // Unusued for hostname verification.
        .addSubjectAlternativeName("wronghostname")
        .build();

    HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build();

    HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate())
        .build();

    client = client.newBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
        .build();
    server.useHttps(serverCertificates.sslSocketFactory(), false);

    executeSynchronously("/")
        .assertFailureMatches("(?s)Hostname localhost not verified.*");
  }

  /**
   * Anonymous cipher suites were disabled in OpenJDK because they're rarely used and permit
   * man-in-the-middle attacks. https://bugs.openjdk.java.net/browse/JDK-8212823
   */
  @Test public void anonCipherSuiteUnsupported() throws Exception {
    platform.assumeNotConscrypt();

    // The _anon_ suites became unsupported in "1.8.0_201" and "11.0.2".
    assumeFalse(System.getProperty("java.version", "unknown").matches("1\\.8\\.0_1\\d\\d"));

    server.enqueue(new MockResponse());

    CipherSuite cipherSuite = TLS_DH_anon_WITH_AES_128_GCM_SHA256;

    HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
        .build();
    client = client.newBuilder()
        .sslSocketFactory(
            socketFactoryWithCipherSuite(clientCertificates.sslSocketFactory(), cipherSuite),
            clientCertificates.trustManager())
        .connectionSpecs(asList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(cipherSuite)
            .build()))
        .build();

    HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
        .build();
    server.useHttps(socketFactoryWithCipherSuite(
        serverCertificates.sslSocketFactory(), cipherSuite), false);

    executeSynchronously("/")
        .assertFailure(SSLHandshakeException.class);
  }

  @Test public void cleartextCallsFailWhenCleartextIsDisabled() throws Exception {
    // Configure the client with only TLS configurations. No cleartext!
    client = client.newBuilder()
        .connectionSpecs(asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .build();

    server.enqueue(new MockResponse());

    Request request = new Request.Builder().url(server.url("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (UnknownServiceException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "CLEARTEXT communication not enabled for client");
    }
  }

  @Test public void httpsCallsFailWhenProtocolIsH2PriorKnowledge() throws Exception {
    client = client.newBuilder()
        .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
        .build();

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    try {
      call.execute();
      fail();
    } catch (UnknownServiceException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "H2_PRIOR_KNOWLEDGE cannot be used with HTTPS");
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
    assertThat(response.code()).isEqualTo(301);
    response.body().close();
  }

  @Test public void matchingPinnedCertificate() throws Exception {
    // Fails on 11.0.1 https://github.com/square/okhttp/issues/4703

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
    assertThat(response1.handshake()).isNotSameAs(response2.handshake());
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
      assertThat(expected.getMessage()).startsWith("Certificate pinning failure!");
    }
  }

  @Test public void post_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create("def", MediaType.get("text/plain")))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("def");
    assertThat(recordedRequest.getHeader("Content-Length")).isEqualTo("3");
    assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo(
        "text/plain; charset=utf-8");
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));

    // Seed the connection pool so we have something that can fail.
    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    assertThat(response1.body().string()).isEqualTo("abc");

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create("body!", MediaType.get("text/plain")))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("def");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getSequenceNumber()).isEqualTo(0);

    RecordedRequest post1 = server.takeRequest();
    assertThat(post1.getBody().readUtf8()).isEqualTo("body!");
    assertThat(post1.getSequenceNumber()).isEqualTo(1);

    RecordedRequest post2 = server.takeRequest();
    assertThat(post2.getBody().readUtf8()).isEqualTo("body!");
    assertThat(post2.getSequenceNumber()).isEqualTo(0);
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
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();

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
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();

    // Hit that stored response. It's different, but Vary says it doesn't matter.
    Thread.sleep(10); // Make sure the timestamps are unique.
    long request2SentAt = System.currentTimeMillis();
    RecordedResponse cacheHit = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8");
    long request2ReceivedAt = System.currentTimeMillis();
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1");

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
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("A");
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1");
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
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();

    // Different request, but Vary says it doesn't matter.
    Thread.sleep(10); // Make sure the timestamps are unique.
    long request2SentAt = System.currentTimeMillis();
    RecordedResponse cacheMiss = executeSynchronously(
        "/", "Accept-Language", "en-US", "Accept-Charset", "UTF-8");
    long request2ReceivedAt = System.currentTimeMillis();
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1");

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
    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("B");
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1");
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
    client = client.newBuilder()
        .dns(new FakeDns())
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

    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Connection reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    // Connection reused again!
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
  }

  @Test public void postRedirectsToGet() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    Response response = client.newCall(new Request.Builder()
        .url(server.url("/page1"))
        .post(RequestBody.create("Request Body", MediaType.get("text/plain")))
        .build()).execute();
    assertThat(response.body().string()).isEqualTo("Page 2");

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo("POST /page1 HTTP/1.1");
    assertThat(page1.getBody().readUtf8()).isEqualTo("Request Body");

    RecordedRequest page2 = server.takeRequest();
    assertThat(page2.getRequestLine()).isEqualTo("GET /page2 HTTP/1.1");
  }

  @Test public void getClientRequestTimeout() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse().setBody("Body"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.body().string()).isEqualTo("Body");
  }

  @Test public void getClientRequestTimeoutWithBackPressure() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "1")
        .setBody("You took too long!"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.body().string()).isEqualTo("You took too long!");
  }

  @Test public void requestBodyRetransmittedOnClientRequestTimeout() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse().setBody("Body"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create("Hello", MediaType.get("text/plain")))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.body().string()).isEqualTo("Body");

    RecordedRequest request1 = server.takeRequest();
    assertThat(request1.getBody().readUtf8()).isEqualTo("Hello");

    RecordedRequest request2 = server.takeRequest();
    assertThat(request2.getBody().readUtf8()).isEqualTo("Hello");
  }

  @Test public void disableClientRequestTimeoutRetry() throws IOException {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));

    client = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.code()).isEqualTo(408);
    assertThat(response.body().string()).isEqualTo("You took too long!");
  }

  @Test public void maxClientRequestTimeoutRetries() throws IOException {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(408)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.code()).isEqualTo(408);
    assertThat(response.body().string()).isEqualTo("You took too long!");

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test public void maxUnavailableTimeoutRetries() throws IOException {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(503)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "0")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(503)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "0")
        .setBody("You took too long!"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.code()).isEqualTo(503);
    assertThat(response.body().string()).isEqualTo("You took too long!");

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test public void retryOnUnavailableWith0RetryAfter() throws IOException {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(503)
        .setHeader("Connection", "Close")
        .setHeader("Retry-After", "0")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse().setBody("Body"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();

    assertThat(response.body().string()).isEqualTo("Body");
  }

  @Test public void canRetryNormalRequestBody() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(503)
        .setHeader("Retry-After", "0")
        .setBody("please retry"));
    server.enqueue(new MockResponse()
        .setBody("thank you for retrying"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          int attempt = 0;

          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.writeUtf8("attempt " + attempt++);
          }
        })
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("thank you for retrying");

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("attempt 0");
    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("attempt 1");
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test public void cannotRetryOneShotRequestBody() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(503)
        .setHeader("Retry-After", "0")
        .setBody("please retry"));
    server.enqueue(new MockResponse()
        .setBody("thank you for retrying"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          int attempt = 0;

          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.writeUtf8("attempt " + attempt++);
          }

          @Override public boolean isOneShot() {
            return true;
          }
        })
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.code()).isEqualTo(503);
    assertThat(response.body().string()).isEqualTo("please retry");

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("attempt 0");
    assertThat(server.getRequestCount()).isEqualTo(1);
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
        .method("PROPFIND", RequestBody.create("Request Body", MediaType.get("text/plain")))
        .build()).execute();

    // then
    assertThat(response.body().string()).isEqualTo("Page 2");

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo("PROPFIND /page1 HTTP/1.1");
    assertThat(page1.getBody().readUtf8()).isEqualTo("Request Body");

    RecordedRequest page2 = server.takeRequest();
    assertThat(page2.getRequestLine()).isEqualTo("PROPFIND /page2 HTTP/1.1");
    assertThat(page2.getBody().readUtf8()).isEqualTo("Request Body");
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
    assertThat(responseCookies.size()).isEqualTo(2);
    assertThat(responseCookies.get(0).toString()).isEqualTo(
        "a=b; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/");
    assertThat(responseCookies.get(1).toString()).isEqualTo(
        "c=d; expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure");
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
    assertThat(recordedRequest.getHeader("Cookie")).isEqualTo("a=b; c=d");
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
    assertThat(response.body().string()).isEqualTo("Page 2");

    RecordedRequest request1 = server.takeRequest();
    assertThat(request1.getHeader("Cookie")).isEqualTo("c=cookie");

    RecordedRequest request2 = server2.takeRequest();
    assertThat(request2.getHeader("Cookie")).isNull();
  }

  @Test public void redirectsDoNotIncludeTooManyAuthHeaders() throws Exception {
    server2.enqueue(new MockResponse().setBody("Page 2"));
    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: " + server2.url("/b")));

    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(Credentials.basic("jesse", "secret"), null))
        .build();

    Request request = new Request.Builder().url(server.url("/a")).build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("Page 2");

    RecordedRequest redirectRequest = server2.takeRequest();
    assertThat(redirectRequest.getHeader("Authorization")).isNull();
    assertThat(redirectRequest.getPath()).isEqualTo("/b");
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

    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Connection reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    // Connection reused again!
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
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
      assertThat(expected.getMessage()).isEqualTo("Too many follow-up requests: 21");
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

  @Test public void httpWithExcessiveHeaders() throws IOException {
    String longLine = "HTTP/1.1 200 " + stringFill('O', 256 * 1024) + "K";

    server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));

    server.enqueue(new MockResponse()
        .setStatus(longLine)
        .setBody("I'm not even supposed to be here today."));

    executeSynchronously("/")
        .assertFailureMatches(".*unexpected end of stream on " + server.url("/").redact());
  }

  private String stringFill(char fillChar, int length) {
    char[] value = new char[length];
    Arrays.fill(value, fillChar);
    return new String(value);
  }

  @Test public void canceledBeforeExecute() throws Exception {
    Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test public void cancelDuringHttpConnect() throws Exception {
    cancelDuringConnect("http");
  }

  @Test public void cancelDuringHttpsConnect() throws Exception {
    cancelDuringConnect("https");
  }

  /** Cancel a call that's waiting for connect to complete. */
  private void cancelDuringConnect(String scheme) throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));

    long cancelDelayMillis = 300L;
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/").newBuilder().scheme(scheme).build())
        .build());
    cancelLater(call, cancelDelayMillis);

    long startNanos = System.nanoTime();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    assertThat((float) TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).isCloseTo(
        (float) cancelDelayMillis, offset(100f));
  }

  @Test public void cancelImmediatelyAfterEnqueue() throws Exception {
    server.enqueue(new MockResponse());
    final CountDownLatch latch = new CountDownLatch(1);
    client = client.newBuilder()
        .addNetworkInterceptor(chain -> {
          try {
            latch.await();
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
          return chain.proceed(chain.request());
        })
        .build();

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/a"))
        .build());
    call.enqueue(callback);
    call.cancel();
    latch.countDown();

    callback.await(server.url("/a")).assertFailure("Canceled", "Socket closed", "Socket is closed");
  }

  @Test public void cancelAll() throws Exception {
    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());
    call.enqueue(callback);
    client.dispatcher().cancelAll();
    callback.await(server.url("/")).assertFailure("Canceled", "Socket closed", "Socket is closed");
  }

  @Test
  public void cancelWhileRequestHeadersAreSent() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    EventListener listener = new EventListener() {
      @Override public void requestHeadersStart(Call call) {
        try {
          // Cancel call from another thread to avoid reentrance.
          cancelLater(call, 0).join();
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
      }
    };
    client = client.newBuilder().eventListener(listener).build();

    Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void cancelWhileRequestHeadersAreSent_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    cancelWhileRequestHeadersAreSent();
  }

  @Test public void cancelBeforeBodyIsRead() throws Exception {
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));

    final Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Response> result = executor.submit(call::execute);

    Thread.sleep(100); // wait for it to go in flight.

    call.cancel();
    try {
      result.get().body().bytes();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(1);
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

    assertThat(server.takeRequest().getPath()).isEqualTo("/a");
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
    assertThat(server.takeRequest().getPath()).isEqualTo("/a");

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
    assertThat(server.takeRequest().getPath()).isEqualTo("/a");

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
    assertThat(bodyRef.get()).isEqualTo("A");
    assertThat(failureRef.get()).isFalse();
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
        .addInterceptor(chain -> {
          chain.proceed(chain.request());
          throw new AssertionError(); // We expect an exception.
        })
        .build();

    Call call = client.newCall(new Request.Builder().url(server.url("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
    assertThat(server.getRequestCount()).isEqualTo(0);
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
        .authenticator(new RecordingOkAuthenticator("password", null))
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
    assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
    assertThat(response.body().source().readByteString()).isEqualTo(gzippedBody.snapshot());

    // The request did not offer gzip support.
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("Accept-Encoding")).isNull();
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
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).isEqualTo("abc");

    // Make another request just to confirm that that connection can be reused...
    executeSynchronously("/").assertBody("def");
    // New connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    // Connection reused.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);

    // ... even before we close the response body!
    response.body().close();
  }

  @Test public void userAgentIsIncludedByDefault() throws Exception {
    server.enqueue(new MockResponse());

    executeSynchronously("/");

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getHeader("User-Agent")).matches(Version.userAgent);
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
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful();

    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("abc");
  }

  @Test public void expect100ContinueEmptyRequestBody() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful();
  }

  @Test public void expect100ContinueEmptyRequestBody_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    expect100ContinueEmptyRequestBody();
  }

  @Test public void expect100ContinueTimesOutWithoutContinue() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.NO_RESPONSE));

    client = client.newBuilder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    Call call = client.newCall(request);
    try {
      call.execute();
      fail();
    } catch (SocketTimeoutException expected) {
    }

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("");
  }

  @Test public void expect100ContinueTimesOutWithoutContinue_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    expect100ContinueTimesOutWithoutContinue();
  }

  @Test public void serverRespondsWithUnsolicited100Continue() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.CONTINUE_ALWAYS));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful();

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
  }

  @Test public void serverRespondsWithUnsolicited100Continue_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    serverRespondsWithUnsolicited100Continue();
  }

  @Test public void serverRespondsWith100ContinueOnly() throws Exception {
    client = client.newBuilder()
        .readTimeout(1, TimeUnit.SECONDS)
        .build();

    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 100 Continue"));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    Call call = client.newCall(request);
    try {
      call.execute();
      fail();
    } catch (SocketTimeoutException expected) {
    }

    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("abc");
  }

  @Test public void serverRespondsWith100ContinueOnly_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    serverRespondsWith100ContinueOnly();
  }

  @Test public void successfulExpectContinuePermitsConnectionReuse() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE));
    server.enqueue(new MockResponse());

    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build());
    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .build());

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void successfulExpectContinuePermitsConnectionReuseWithHttp2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    successfulExpectContinuePermitsConnectionReuse();
  }

  @Test public void unsuccessfulExpectContinuePreventsConnectionReuse() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build());
    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .build());

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
  }

  @Test public void unsuccessfulExpectContinuePermitsConnectionReuseWithHttp2() throws Exception {
    platform.assumeHttp2Support();

    enableProtocol(Protocol.HTTP_2);

    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build());
    executeSynchronously(new Request.Builder()
        .url(server.url("/"))
        .build());

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  /** We forbid non-ASCII characters in outgoing request headers, but accept UTF-8. */
  @Test public void responseHeaderParsingIsLenient() throws Exception {
    Headers.Builder headersBuilder = new Headers.Builder();
    headersBuilder.add("Content-Length", "0");
    addHeaderLenient(headersBuilder, "a\tb: c\u007fd");
    addHeaderLenient(headersBuilder, ": ef");
    addHeaderLenient(headersBuilder, "\ud83c\udf69: \u2615\ufe0f");
    Headers headers = headersBuilder.build();
    server.enqueue(new MockResponse().setHeaders(headers));

    executeSynchronously("/")
        .assertHeader("a\tb", "c\u007fd")
        .assertHeader("\ud83c\udf69", "\u2615\ufe0f")
        .assertHeader("", "ef");
  }

  @Test public void customDns() throws Exception {
    // Configure a DNS that returns our local MockWebServer for android.com.
    FakeDns dns = new FakeDns();
    dns.set("android.com", Dns.SYSTEM.lookup(server.url("/").host()));
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
  @Test public void dnsReturnsZeroIpAddresses() throws Exception {
    // Configure a DNS that returns our local MockWebServer for android.com.
    FakeDns dns = new FakeDns();
    List<InetAddress> ipAddresses = new ArrayList<>();
    dns.set("android.com", ipAddresses);
    client = client.newBuilder()
        .dns(dns)
        .build();

    server.enqueue(new MockResponse());
    Request request = new Request.Builder()
        .url(server.url("/").newBuilder().host("android.com").build())
        .build();
    executeSynchronously(request).assertFailure(dns + " returned no addresses for android.com");

    dns.assertRequests("android.com");
  }

  /** We had a bug where failed HTTP/2 calls could break the entire connection. */
  @Flaky
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
    try (Response response = call.execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.body().string()).isNotBlank();
    }

    long connectCount = listener.eventSequence.stream().filter((event) -> event instanceof RecordingEventListener.ConnectStart).count();
    long expected = platform.isJdk8() ? 2 : 1;
    assertThat(connectCount).isEqualTo(expected);
  }

  /** Test which headers are sent unencrypted to the HTTP proxy. */
  @Test public void proxyConnectOmitsApplicationHeaders() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("encrypted response from the origin server"));

    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .hostnameVerifier(hostnameVerifier)
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .header("Private", "Secret")
        .header("User-Agent", "App 1.0")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo(
        "encrypted response from the origin server");

    RecordedRequest connect = server.takeRequest();
    assertThat(connect.getHeader("Private")).isNull();
    assertThat(connect.getHeader("User-Agent")).isEqualTo(Version.userAgent);
    assertThat(connect.getHeader("Proxy-Connection")).isEqualTo("Keep-Alive");
    assertThat(connect.getHeader("Host")).isEqualTo("android.com:443");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getHeader("Private")).isEqualTo("Secret");
    assertThat(get.getHeader("User-Agent")).isEqualTo("App 1.0");

    assertThat(hostnameVerifier.calls).containsExactly("verify android.com");
  }

  /** Respond to a proxy authorization challenge. */
  @Test public void proxyAuthenticateOnConnect() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("response body");

    RecordedRequest connect1 = server.takeRequest();
    assertThat(connect1.getRequestLine()).isEqualTo("CONNECT android.com:443 HTTP/1.1");
    assertThat(connect1.getHeader("Proxy-Authorization")).isNull();

    RecordedRequest connect2 = server.takeRequest();
    assertThat(connect2.getRequestLine()).isEqualTo("CONNECT android.com:443 HTTP/1.1");
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo("password");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(get.getHeader("Proxy-Authorization")).isNull();
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
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
        .build();

    Request request = new Request.Builder()
        .url("http://android.com/foo")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("response body");

    RecordedRequest get1 = server.takeRequest();
    assertThat(get1.getRequestLine()).isEqualTo("GET http://android.com/foo HTTP/1.1");
    assertThat(get1.getHeader("Proxy-Authorization")).isNull();

    RecordedRequest get2 = server.takeRequest();
    assertThat(get2.getRequestLine()).isEqualTo("GET http://android.com/foo HTTP/1.1");
    assertThat(get2.getHeader("Proxy-Authorization")).isEqualTo("password");
  }

  /**
   * OkHttp has a bug where a `Connection: close` response header is not honored when establishing a
   * TLS tunnel. https://github.com/square/okhttp/issues/2426
   */
  @Test public void proxyAuthenticateOnConnectWithConnectionClose() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
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
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("response body");

    // First CONNECT call needs a new connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // Second CONNECT call needs a new connection.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    // GET reuses the connection from the second connect.
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  @Test public void tooManyProxyAuthFailuresWithConnectionClose() throws IOException {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(407)
          .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
          .addHeader("Connection: close"));
    }

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .proxyAuthenticator(new RecordingOkAuthenticator("password", "Basic"))
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
  @Test public void noPreemptiveProxyAuthorization() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .header("Proxy-Authorization", "password")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("response body");

    RecordedRequest connect1 = server.takeRequest();
    assertThat(connect1.getHeader("Proxy-Authorization")).isNull();

    RecordedRequest connect2 = server.takeRequest();
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo("password");
  }

  /** Confirm that we can send authentication information without being prompted first. */
  @Test public void preemptiveProxyAuthentication() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("encrypted response from the origin server"));

    final String credential = Credentials.basic("jesse", "password1");

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxyAuthenticator((route, response) -> {
          assertThat(response.request().method()).isEqualTo("CONNECT");
          assertThat(response.code()).isEqualTo(HttpURLConnection.HTTP_PROXY_AUTH);
          assertThat(response.request().url().host()).isEqualTo("android.com");

          List<Challenge> challenges = response.challenges();
          assertThat(challenges.get(0).scheme()).isEqualTo("OkHttp-Preemptive");

          return response.request().newBuilder()
              .header("Proxy-Authorization", credential)
              .build();
        })
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();

    executeSynchronously(request).assertSuccessful();

    RecordedRequest connect = server.takeRequest();
    assertThat(connect.getMethod()).isEqualTo("CONNECT");
    assertThat(connect.getHeader("Proxy-Authorization")).isEqualTo(credential);
    assertThat(connect.getPath()).isEqualTo("/");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getMethod()).isEqualTo("GET");
    assertThat(get.getHeader("Proxy-Authorization")).isNull();
    assertThat(get.getPath()).isEqualTo("/foo");
  }

  @Test public void preemptiveThenReactiveProxyAuthentication() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PROXY_AUTH)
        .addHeader("Proxy-Authenticate", "Basic realm=\"localhost\"")
        .setBody("proxy auth required"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse());

    final List<String> challengeSchemes = new ArrayList<>();
    final String credential = Credentials.basic("jesse", "password1");

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxyAuthenticator((route, response) -> {
          List<Challenge> challenges = response.challenges();
          challengeSchemes.add(challenges.get(0).scheme());
          return response.request().newBuilder()
              .header("Proxy-Authorization", credential)
              .build();
        })
        .build();

    Request request = new Request.Builder()
        .url("https://android.com/foo")
        .build();

    executeSynchronously(request).assertSuccessful();

    RecordedRequest connect1 = server.takeRequest();
    assertThat(connect1.getMethod()).isEqualTo("CONNECT");
    assertThat(connect1.getHeader("Proxy-Authorization")).isEqualTo(credential);

    RecordedRequest connect2 = server.takeRequest();
    assertThat(connect2.getMethod()).isEqualTo("CONNECT");
    assertThat(connect2.getHeader("Proxy-Authorization")).isEqualTo(credential);

    assertThat(challengeSchemes).containsExactly("OkHttp-Preemptive", "Basic");
  }

  /** https://github.com/square/okhttp/issues/4915 */
  @Test @Ignore public void proxyDisconnectsAfterRequest() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .proxy(server.toProxyAddress())
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    try {
      client.newCall(request).execute();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void interceptorGetsHttp2() throws Exception {
    platform.assumeHttp2Support();

    enableProtocol(Protocol.HTTP_2);

    // Capture the protocol as it is observed by the interceptor.
    final AtomicReference<Protocol> protocolRef = new AtomicReference<>();
    Interceptor interceptor = chain -> {
      protocolRef.set(chain.connection().protocol());
      return chain.proceed(chain.request());
    };
    client = client.newBuilder()
        .addNetworkInterceptor(interceptor)
        .build();

    // Make an HTTP/2 request and confirm that the protocol matches.
    server.enqueue(new MockResponse());
    executeSynchronously("/");
    assertThat(protocolRef.get()).isEqualTo(Protocol.HTTP_2);
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
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0x20 at 1 in header name: a b");
    }
  }

  @Test public void requestHeaderNameWithTabForbidden() throws Exception {
    try {
      new Request.Builder().addHeader("a\tb", "c");
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(
          "Unexpected char 0x09 at 1 in header name: a\tb");
    }
  }

  @Test public void responseHeaderNameWithSpacePermitted() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a b", "c"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.header("a b")).isEqualTo("c");
  }

  @Test public void responseHeaderNameWithTabPermitted() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("content-length: 0")
        .addHeaderLenient("a\tb", "c"));

    Call call = client.newCall(new Request.Builder().url(server.url("/")).build());
    Response response = call.execute();
    assertThat(response.header("a\tb")).isEqualTo("c");
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
        .post(RequestBody.create("abc", MediaType.get("text/plain")))
        .build();

    executeSynchronously(request);
    assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo("abc");
  }

  @Ignore // This may fail in DNS lookup, which we don't have timeouts for.
  @Test public void invalidHost() throws Exception {
    Request request = new Request.Builder()
        .url(HttpUrl.get("http://1234.1.1.1/"))
        .build();

    executeSynchronously(request)
        .assertFailure(UnknownHostException.class);
  }

  @Test public void uploadBodySmallChunkedEncoding() throws Exception {
    upload(true, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isNotEmpty();
  }

  @Test public void uploadBodyLargeChunkedEncoding() throws Exception {
    upload(true, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isNotEmpty();
  }

  @Test public void uploadBodySmallFixedLength() throws Exception {
    upload(false, 1048576, 256);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isEmpty();
  }

  @Test public void uploadBodyLargeFixedLength() throws Exception {
    upload(false, 1048576, 65536);
    RecordedRequest recordedRequest = server.takeRequest();
    assertThat(recordedRequest.getBodySize()).isEqualTo(1048576);
    assertThat(recordedRequest.getChunkSizes()).isEmpty();
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
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response body"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxy(server.toProxyAddress())
        .build();

    Request request = new Request.Builder()
        .url("https://[::1]/")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("response body");

    RecordedRequest connect = server.takeRequest();
    assertThat(connect.getRequestLine()).isEqualTo("CONNECT [::1]:443 HTTP/1.1");
    assertThat(connect.getHeader("Host")).isEqualTo("[::1]:443");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getRequestLine()).isEqualTo("GET / HTTP/1.1");
    assertThat(get.getHeader("Host")).isEqualTo("[::1]");
  }

  private RequestBody requestBody(final boolean chunked, final long size, final int writeSize) {
    final byte[] buffer = new byte[writeSize];
    Arrays.fill(buffer, (byte) 'x');

    return new RequestBody() {
      @Override public MediaType contentType() {
        return MediaType.get("text/plain; charset=utf-8");
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

    client = clientTestRule.newClientBuilder()
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
      assertThat(message).contains("A connection to " + server.url("/") + " was leaked."
            + " Did you forget to close a response body?");
      assertThat(message).contains("okhttp3.RealCall.execute(");
      assertThat(message).contains("okhttp3.CallTest.leakedResponseBodyLogsStackTrace(");
    } finally {
      logger.setLevel(original);
    }
  }

  @Test public void asyncLeakedResponseBodyLogsStackTrace() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("This gets leaked."));

    client = clientTestRule.newClientBuilder()
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
      assertThat(message).contains("A connection to " + server.url("/") + " was leaked."
            + " Did you forget to close a response body?");
      assertThat(message).contains("okhttp3.RealCall.enqueue(");
      assertThat(message).contains("okhttp3.CallTest.asyncLeakedResponseBodyLogsStackTrace(");
    } finally {
      logger.setLevel(original);
    }
  }

  @Test public void failedAuthenticatorReleasesConnection() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(401));

    client = client.newBuilder()
        .authenticator((route, response) -> { throw new IOException("IOException!"); })
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    executeSynchronously(request)
        .assertFailure(IOException.class);

    assertThat(client.connectionPool().idleConnectionCount()).isEqualTo(1);
  }

  @Test public void failedProxyAuthenticatorReleasesConnection() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(407));

    client = client.newBuilder()
        .proxyAuthenticator((route, response) -> { throw new IOException("IOException!"); })
        .build();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();

    executeSynchronously(request)
        .assertFailure(IOException.class);

    assertThat(client.connectionPool().idleConnectionCount()).isEqualTo(1);
  }

  @Test public void httpsWithIpAddress() throws Exception {
    String localIpAddress = InetAddress.getLoopbackAddress().getHostAddress();

    // Create a certificate with an IP address in the subject alt name.
    HeldCertificate heldCertificate = new HeldCertificate.Builder()
        .commonName("example.com")
        .addSubjectAlternativeName(localIpAddress)
        .build();
    HandshakeCertificates handshakeCertificates = new HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate())
        .build();

    // Use that certificate on the server and trust it on the client.
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
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
    assertThat(recordedRequest.getHeader("Host")).isEqualTo(
        (localIpAddress + ":" + server.getPort()));
  }

  @Test public void postWithFileNotFound() throws Exception {
    final AtomicInteger called = new AtomicInteger(0);

    RequestBody body = new RequestBody() {
      @Nullable @Override public MediaType contentType() {
        return MediaType.get("application/octet-stream");
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        called.incrementAndGet();
        throw new FileNotFoundException();
      }
    };

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(body)
        .build();

    client = client.newBuilder()
        .dns(new DoubleInetAddressDns())
        .build();

    executeSynchronously(request)
        .assertFailure(FileNotFoundException.class);

    assertThat(called.get()).isEqualTo(1L);
  }

  @Test public void clientReadsHeadersDataTrailersHttp1ChunkedTransferEncoding() throws Exception {
    MockResponse mockResponse = new MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setChunkedBody("HelloBonjour", 1024)
        .setTrailers(Headers.of("trailers", "boom"));
    server.enqueue(mockResponse);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    Response response = call.execute();
    BufferedSource source = response.body().source();

    assertThat(response.header("h1")).isEqualTo("v1");
    assertThat(response.header("h2")).isEqualTo("v2");

    assertThat(source.readUtf8(5)).isEqualTo("Hello");
    assertThat(source.readUtf8(7)).isEqualTo("Bonjour");

    assertThat(source.exhausted()).isTrue();
    assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"));
  }

  @Test public void clientReadsHeadersDataTrailersHttp2() throws IOException {
    platform.assumeHttp2Support();

    MockResponse mockResponse = new MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setBody("HelloBonjour")
        .setTrailers(Headers.of("trailers", "boom"));
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    try (Response response = call.execute()) {
      BufferedSource source = response.body().source();

      assertThat(response.header("h1")).isEqualTo("v1");
      assertThat(response.header("h2")).isEqualTo("v2");

      assertThat(source.readUtf8(5)).isEqualTo("Hello");
      assertThat(source.readUtf8(7)).isEqualTo("Bonjour");

      assertThat(source.exhausted()).isTrue();
      assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"));
    }
  }

  @Test public void requestBodyThrowsUnrelatedToNetwork() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.flush(); // For determinism, always send a partial request to the server.
            throw new IOException("boom");
          }
        })
        .build();

    executeSynchronously(request).assertFailure("boom");

    assertThat(server.takeRequest().getFailure()).isNotNull();
  }

  @Test public void requestBodyThrowsUnrelatedToNetwork_HTTP2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    requestBodyThrowsUnrelatedToNetwork();
  }

  /** https://github.com/square/okhttp/issues/4583 */
  @Test public void lateCancelCallsOnFailure() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    AtomicBoolean closed = new AtomicBoolean();

    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            chain.call().cancel(); // Cancel after we have the response.
            ForwardingSource closeTrackingSource = new ForwardingSource(response.body().source()) {
              @Override public void close() throws IOException {
                closed.set(true);
                super.close();
              }
            };
            return response.newBuilder()
                .body(ResponseBody.create(Okio.buffer(closeTrackingSource), null, -1L))
                .build();
          }
        })
        .build();

    executeSynchronously("/").assertFailure("Canceled");
    assertThat(closed.get()).isTrue();
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
        sink.flush(); // For determinism, always send a partial request to the server.
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
      assertThat(expected.getMessage()).isEqualTo("write body fail!");
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

  private Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  private Thread cancelLater(final Call call, final long delay) {
    Thread thread = new Thread("canceler") {
      @Override public void run() {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          throw new AssertionError();
        }
        call.cancel();
      }
    };
    thread.start();
    return thread;
  }

  private SSLSocketFactory socketFactoryWithCipherSuite(
      final SSLSocketFactory sslSocketFactory, final CipherSuite cipherSuite) {
    return new DelegatingSSLSocketFactory(sslSocketFactory) {
      @Override protected SSLSocket configureSocket(SSLSocket sslSocket) throws IOException {
        sslSocket.setEnabledCipherSuites(new String[] { cipherSuite.javaName() });
        return super.configureSocket(sslSocket);
      }
    };
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
    return new FallbackTestClientSocketFactory(handshakeCertificates.sslSocketFactory());
  }
}
