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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.RecordingHostnameVerifier;
import com.squareup.okhttp.internal.RecordingOkAuthenticator;
import com.squareup.okhttp.internal.SingleInetAddressNetwork;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.mockwebserver.Dispatcher;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.SocketPolicy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static com.squareup.okhttp.internal.Internal.logger;
import static java.lang.Thread.UncaughtExceptionHandler;
import static java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CallTest {
  private MockWebServer server = new MockWebServer();
  private MockWebServer server2 = new MockWebServer();
  private OkHttpClient client = new OkHttpClient();
  private RecordingCallback callback = new RecordingCallback();
  private TestLogHandler logHandler = new TestLogHandler();
  private UncaughtExceptionHandler defaultUncaughtExceptionHandler;

  private static final SSLContext sslContext = SslContextBuilder.localhost();
  private Cache cache;

  @Before public void setUp() throws Exception {
    String tmp = System.getProperty("java.io.tmpdir");
    File cacheDir = new File(tmp, "HttpCache-" + UUID.randomUUID());
    cache = new Cache(cacheDir, Integer.MAX_VALUE);
    defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
    logger.addHandler(logHandler);
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    server2.shutdown();
    cache.delete();
    Thread.setDefaultUncaughtExceptionHandler(defaultUncaughtExceptionHandler);
    logger.removeHandler(logHandler);
  }

  @Test public void get() throws Exception {
    server.enqueue(new MockResponse().setBody("abc").addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertSuccessful()
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("GET", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().length);
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void lazilyEvaluateRequestUrl() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request1 = new Request.Builder()
        .url("foo://bar?baz")
        .build();
    Request request2 = request1.newBuilder()
        .url(server.getUrl("/"))
        .build();
    executeSynchronously(request2)
        .assertCode(200)
        .assertSuccessful()
        .assertBody("abc");
  }

  @Ignore // TODO(jwilson): fix.
  @Test public void invalidScheme() throws Exception {
    try {
      Request request = new Request.Builder()
          .url("ftp://hostname/path")
          .build();
      executeSynchronously(request);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void getReturns500() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    executeSynchronously(request)
        .assertCode(500)
        .assertNotSuccessful();
  }

  @Test public void get_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    get();
  }

  @Test public void get_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    get();
  }

  @Test public void getWithRequestBody() throws Exception {
    server.enqueue(new MockResponse());
    server.play();

    try {
      new Request.Builder().method("GET", RequestBody.create(MediaType.parse("text/plain"), "abc"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void head() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .head()
        .header("User-Agent", "SyncApiTest")
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("HEAD", recordedRequest.getMethod());
    assertEquals("SyncApiTest", recordedRequest.getHeader("User-Agent"));
    assertEquals(0, recordedRequest.getBody().length);
    assertNull(recordedRequest.getHeader("Content-Length"));
  }

  @Test public void head_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    head();
  }

  @Test public void head_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    head();
  }

  @Test public void post() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void post_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    post();
  }

  @Test public void post_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    post();
  }

  @Test public void postZeroLength() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .method("POST", null)
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("POST", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().length);
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void postZeroLength_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    postZeroLength();
  }

  @Test public void postZerolength_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postZeroLength();
  }

  @Test public void delete() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .delete()
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("DELETE", recordedRequest.getMethod());
    assertEquals(0, recordedRequest.getBody().length);
    assertEquals("0", recordedRequest.getHeader("Content-Length"));
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
  }

  @Test public void delete_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    delete();
  }

  @Test public void delete_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    delete();
  }

  @Test public void put() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .put(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PUT", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void put_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    put();
  }

  @Test public void put_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    put();
  }

  @Test public void patch() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .patch(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();

    executeSynchronously(request)
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("PATCH", recordedRequest.getMethod());
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void patch_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    patch();
  }

  @Test public void patch_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    patch();
  }

  @Test public void unspecifiedRequestBodyContentTypeDoesNotGetDefault() throws Exception {
    server.enqueue(new MockResponse());
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .method("POST", RequestBody.create(null, "abc"))
        .build();

    executeSynchronously(request).assertCode(200);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(null, recordedRequest.getHeader("Content-Type"));
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("abc", recordedRequest.getUtf8Body());
  }

  @Test public void illegalToExecuteTwice() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    call.execute();

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: SyncApiTest"));
  }

  @Test public void illegalToExecuteTwice_Async() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    Call call = client.newCall(request);
    call.enqueue(callback);

    try {
      call.execute();
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    try {
      call.enqueue(callback);
      fail();
    } catch (IllegalStateException e){
      assertEquals("Already Executed", e.getMessage());
    }

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: SyncApiTest"));
  }

  @Test public void get_Async() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "AsyncApiTest")
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertHeader("Content-Type", "text/plain")
        .assertBody("abc");

    assertTrue(server.takeRequest().getHeaders().contains("User-Agent: AsyncApiTest"));
  }

  @Test public void exceptionThrownByOnResponseIsRedactedAndLogged() throws Exception {
    server.enqueue(new MockResponse());
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/secret"))
        .build();

    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Request request, IOException e) {
        fail();
      }

      @Override public void onResponse(Response response) throws IOException {
        throw new IOException("a");
      }
    });

    assertEquals("INFO: Callback failure for call to " + server.getUrl("/") + "...",
        logHandler.take());
  }

  @Test public void connectionPooling() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));
    server.play();

    executeSynchronously(new Request.Builder().url(server.getUrl("/a")).build())
        .assertBody("abc");

    executeSynchronously(new Request.Builder().url(server.getUrl("/b")).build())
        .assertBody("def");

    executeSynchronously(new Request.Builder().url(server.getUrl("/c")).build())
        .assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionPooling_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.enqueue(new MockResponse().setBody("ghi"));
    server.play();

    client.newCall(new Request.Builder().url(server.getUrl("/a")).build()).enqueue(callback);
    callback.await(server.getUrl("/a")).assertBody("abc");

    client.newCall(new Request.Builder().url(server.getUrl("/b")).build()).enqueue(callback);
    callback.await(server.getUrl("/b")).assertBody("def");

    client.newCall(new Request.Builder().url(server.getUrl("/c")).build()).enqueue(callback);
    callback.await(server.getUrl("/c")).assertBody("ghi");

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionReuseWhenResponseBodyConsumed_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Request request, IOException e) {
        throw new AssertionError();
      }

      @Override public void onResponse(Response response) throws IOException {
        InputStream bytes = response.body().byteStream();
        assertEquals('a', bytes.read());
        assertEquals('b', bytes.read());
        assertEquals('c', bytes.read());

        // This request will share a connection with 'A' cause it's all done.
        client.newCall(new Request.Builder().url(server.getUrl("/b")).build()).enqueue(callback);
      }
    });

    callback.await(server.getUrl("/b")).assertCode(200).assertBody("def");
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reuse!
  }

  @Test public void timeoutsUpdatedOnReusedConnections() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));
    server.play();

    // First request: time out after 1000ms.
    client.setReadTimeout(1000, TimeUnit.MILLISECONDS);
    executeSynchronously(new Request.Builder().url(server.getUrl("/a")).build()).assertBody("abc");

    // Second request: time out after 250ms.
    client.setReadTimeout(250, TimeUnit.MILLISECONDS);
    Request request = new Request.Builder().url(server.getUrl("/b")).build();
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
      assertTrue(String.format("Timed out: %sms", elapsedMillis), elapsedMillis < 500);
    }
  }

  @Test public void tls() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertHandshake();
  }

  @Test public void tls_Async() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .addHeader("Content-Type: text/plain"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertHandshake();
  }

  @Test public void recoverFromTlsHandshakeFailure() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    Internal.instance.setNetwork(client, new SingleInetAddressNetwork());

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build())
        .assertBody("abc");
  }

  @Test public void recoverFromTlsHandshakeFailure_Async() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url()).assertBody("abc");
  }

  @Test public void noRecoveryFromTlsHandshakeFailureWhenTlsFallbackIsDisabled() throws Exception {
    client.setConnectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT));

    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    Internal.instance.setNetwork(client, new SingleInetAddressNetwork());

    Request request = new Request.Builder().url(server.getUrl("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLProtocolException expected) {
    }
  }

  @Test public void cleartextCallsFailWhenCleartextIsDisabled() throws Exception {
    // Configure the client with only TLS configurations. No cleartext!
    client.setConnectionSpecs(Arrays.asList(
        ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS));

    server.enqueue(new MockResponse());
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SocketException expected) {
      assertTrue(expected.getMessage().contains("exhausted connection specs"));
    }
  }

  @Test public void setFollowSslRedirectsFalse() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location: http://square.com"));
    server.play();

    client.setFollowSslRedirects(false);
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    Request request = new Request.Builder().url(server.getUrl("/")).build();
    Response response = client.newCall(request).execute();
    assertEquals(301, response.code());
  }

  @Test public void matchingPinnedCertificate() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    // Make a first request without certificate pinning. Use it to collect certificates to pin.
    Request request1 = new Request.Builder().url(server.getUrl("/")).build();
    Response response1 = client.newCall(request1).execute();
    CertificatePinner.Builder certificatePinnerBuilder = new CertificatePinner.Builder();
    for (Certificate certificate : response1.handshake().peerCertificates()) {
      certificatePinnerBuilder.add(server.getHostName(), CertificatePinner.pin(certificate));
    }

    // Make another request with certificate pinning. It should complete normally.
    client.setCertificatePinner(certificatePinnerBuilder.build());
    Request request2 = new Request.Builder().url(server.getUrl("/")).build();
    Response response2 = client.newCall(request2).execute();
    assertNotSame(response2.handshake(), response1.handshake());
  }

  @Test public void unmatchingPinnedCertificate() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse());
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    client.setCertificatePinner(new CertificatePinner.Builder()
        .add(server.getHostName(), "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=") // publicobject.com's cert.
        .build());

    // When we pin the wrong certificate, connectivity fails.
    Request request = new Request.Builder().url(server.getUrl("/")).build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (SSLPeerUnverifiedException expected) {
      assertTrue(expected.getMessage().startsWith("Certificate pinning failure!"));
    }
  }

  @Test public void post_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "def"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertBody("abc");

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("def", recordedRequest.getUtf8Body());
    assertEquals("3", recordedRequest.getHeader("Content-Length"));
    assertEquals("text/plain; charset=utf-8", recordedRequest.getHeader("Content-Type"));
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));
    server.play();

    // Seed the connection pool so we have something that can fail.
    Request request1 = new Request.Builder().url(server.getUrl("/")).build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("abc", response1.body().string());

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "body!"))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("def", response2.body().string());

    RecordedRequest get = server.takeRequest();
    assertEquals(0, get.getSequenceNumber());

    RecordedRequest post1 = server.takeRequest();
    assertEquals("body!", post1.getUtf8Body());
    assertEquals(1, post1.getSequenceNumber());

    RecordedRequest post2 = server.takeRequest();
    assertEquals("body!", post2.getUtf8Body());
    assertEquals(0, post2.getSequenceNumber());
  }

  @Test public void cacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Charset")
        .setBody("A"));
    server.play();

    client.setCache(cache);

    // Store a response in the cache.
    URL url = server.getUrl("/");
    Request cacheStoreRequest = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA")
        .addHeader("Accept-Charset", "UTF-8")
        .build();
    executeSynchronously(cacheStoreRequest)
        .assertCode(200)
        .assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    // Hit that stored response.
    Request cacheHitRequest = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "en-US") // Different, but Vary says it doesn't matter.
        .addHeader("Accept-Charset", "UTF-8")
        .build();
    RecordedResponse cacheHit = executeSynchronously(cacheHitRequest);

    // Check the merged response. The request is the application's original request.
    cacheHit.assertCode(200)
        .assertBody("A")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(cacheStoreRequest.url())
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8");

    // Check the cached response. Its request contains only the saved Vary headers.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeader("ETag", "v1")
        .assertRequestMethod("GET")
        .assertRequestUrl(cacheStoreRequest.url())
        .assertRequestHeader("Accept-Language")
        .assertRequestHeader("Accept-Charset", "UTF-8");

    cacheHit.assertNoNetworkResponse();
  }

  @Test public void conditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Vary: Accept-Charset")
        .addHeader("Donut: a")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .addHeader("Donut: b")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    client.setCache(cache);

    // Store a response in the cache.
    URL url = server.getUrl("/");
    Request cacheStoreRequest = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA")
        .addHeader("Accept-Charset", "UTF-8")
        .build();
    executeSynchronously(cacheStoreRequest)
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    // Hit that stored response.
    Request cacheHitRequest = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "en-US") // Different, but Vary says it doesn't matter.
        .addHeader("Accept-Charset", "UTF-8")
        .build();
    RecordedResponse cacheHit = executeSynchronously(cacheHitRequest);
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));

    // Check the merged response. The request is the application's original request.
    cacheHit.assertCode(200)
        .assertBody("A")
        .assertHeader("Donut", "b")
        .assertRequestUrl(cacheStoreRequest.url())
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertRequestHeader("If-None-Match"); // No If-None-Match on the user's request.

    // Check the cached response. Its request contains only the saved Vary headers.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(cacheStoreRequest.url())
        .assertRequestHeader("Accept-Language") // No Vary on Accept-Language.
        .assertRequestHeader("Accept-Charset", "UTF-8") // Because of Vary on Accept-Charset.
        .assertRequestHeader("If-None-Match"); // This wasn't present in the original request.

    // Check the network response. It has the caller's request, plus some caching headers.
    cacheHit.networkResponse()
        .assertCode(304)
        .assertHeader("Donut", "b")
        .assertRequestHeader("Accept-Language", "en-US")
        .assertRequestHeader("Accept-Charset", "UTF-8")
        .assertRequestHeader("If-None-Match", "v1"); // If-None-Match in the validation request.
  }

  @Test public void conditionalCacheHit_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    client.setCache(cache);

    Request request1 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request1).enqueue(callback);
    callback.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
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
    server.play();

    client.setCache(cache);

    Request cacheStoreRequest = new Request.Builder()
        .url(server.getUrl("/"))
        .addHeader("Accept-Language", "fr-CA")
        .addHeader("Accept-Charset", "UTF-8")
        .build();
    executeSynchronously(cacheStoreRequest)
        .assertCode(200)
        .assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request cacheMissRequest = new Request.Builder()
        .url(server.getUrl("/"))
        .addHeader("Accept-Language", "en-US") // Different, but Vary says it doesn't matter.
        .addHeader("Accept-Charset", "UTF-8")
        .build();
    RecordedResponse cacheHit = executeSynchronously(cacheMissRequest);
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));

    // Check the user response. It has the application's original request.
    cacheHit.assertCode(200)
        .assertBody("B")
        .assertHeader("Donut", "b")
        .assertRequestUrl(cacheStoreRequest.url());

    // Check the cache response. Even though it's a miss, we used the cache.
    cacheHit.cacheResponse()
        .assertCode(200)
        .assertHeader("Donut", "a")
        .assertHeader("ETag", "v1")
        .assertRequestUrl(cacheStoreRequest.url());

    // Check the network response. It has the network request, plus caching headers.
    cacheHit.networkResponse()
        .assertCode(200)
        .assertHeader("Donut", "b")
        .assertRequestHeader("If-None-Match", "v1")  // If-None-Match in the validation request.
        .assertRequestUrl(cacheStoreRequest.url());
  }

  @Test public void conditionalCacheMiss_Async() throws Exception {
    server.enqueue(new MockResponse().setBody("A").addHeader("ETag: v1"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    client.setCache(cache);

    Request request1 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request1).enqueue(callback);
    callback.await(request1.url()).assertCode(200).assertBody("A");
    assertNull(server.takeRequest().getHeader("If-None-Match"));

    Request request2 = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request2).enqueue(callback);
    callback.await(request2.url()).assertCode(200).assertBody("B");
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void onlyIfCachedReturns504WhenNotCached() throws Exception {
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("Cache-Control", "only-if-cached")
        .build();

    executeSynchronously(request)
        .assertCode(504)
        .assertBody("")
        .assertNoNetworkResponse()
        .assertNoCacheResponse();
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
    server.play();

    executeSynchronously(new Request.Builder().url(server.getUrl("/a")).build())
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
    server.play();

    Response response = client.newCall(new Request.Builder()
        .url(server.getUrl("/page1"))
        .post(RequestBody.create(MediaType.parse("text/plain"), "Request Body"))
        .build()).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest page1 = server.takeRequest();
    assertEquals("POST /page1 HTTP/1.1", page1.getRequestLine());
    assertEquals("Request Body", page1.getUtf8Body());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void redirectsDoNotIncludeTooManyCookies() throws Exception {
    server2.enqueue(new MockResponse().setBody("Page 2"));
    server2.play();

    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.getUrl("/")));
    server.play();

    CookieManager cookieManager = new CookieManager(null, ACCEPT_ORIGINAL_SERVER);
    HttpCookie cookie = new HttpCookie("c", "cookie");
    cookie.setDomain(server.getCookieDomain());
    cookie.setPath("/");
    String portList = Integer.toString(server.getPort());
    cookie.setPortlist(portList);
    cookieManager.getCookieStore().add(server.getUrl("/").toURI(), cookie);
    client.setCookieHandler(cookieManager);

    Response response = client.newCall(new Request.Builder()
        .url(server.getUrl("/page1"))
        .build()).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest request1 = server.takeRequest();
    assertContains(request1.getHeaders(), "Cookie: $Version=\"1\"; "
        + "c=\"cookie\";$Path=\"/\";$Domain=\"" + server.getCookieDomain()
        + "\";$Port=\"" + portList + "\"");

    RecordedRequest request2 = server2.takeRequest();
    assertContainsNoneMatching(request2.getHeaders(), "Cookie.*");
  }

  @Test public void redirectsDoNotIncludeTooManyAuthHeaders() throws Exception {
    server2.enqueue(new MockResponse().setBody("Page 2"));
    server2.play();

    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: " + server2.getUrl("/b")));
    server.play();

    client.setAuthenticator(new RecordingOkAuthenticator(Credentials.basic("jesse", "secret")));

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    Response response = client.newCall(request).execute();
    assertEquals("Page 2", response.body().string());

    RecordedRequest redirectRequest = server2.takeRequest();
    assertContainsNoneMatching(redirectRequest.getHeaders(), "Authorization.*");
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
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/a")).build();
    client.newCall(request).enqueue(callback);

    callback.await(server.getUrl("/c"))
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
    server.play();

    executeSynchronously(new Request.Builder().url(server.getUrl("/0")).build())
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
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/0")).build();
    client.newCall(request).enqueue(callback);
    callback.await(server.getUrl("/20"))
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
    server.play();

    try {
      client.newCall(new Request.Builder().url(server.getUrl("/0")).build()).execute();
      fail();
    } catch (IOException e) {
      assertEquals("Too many redirects: 21", e.getMessage());
    }
  }

  @Test public void doesNotFollow21Redirects_Async() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(301)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/0")).build();
    client.newCall(request).enqueue(callback);
    callback.await(server.getUrl("/20")).assertFailure("Too many redirects: 21");
  }

  @Test public void canceledBeforeExecute() throws Exception {
    server.play();

    Call call = client.newCall(new Request.Builder().url(server.getUrl("/a")).build());
    call.cancel();

    try {
      call.execute();
      fail();
    } catch (IOException e){
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test public void cancelTagImmediatelyAfterEnqueue() throws Exception {
    server.play();
    Call call = client.newCall(new Request.Builder()
        .url(server.getUrl("/a"))
        .tag("request")
        .build());
    call.enqueue(callback);
    client.cancel("request");
    assertEquals(0, server.getRequestCount());
    callback.await(server.getUrl("/a")).assertFailure("Canceled");
  }

  @Test public void cancelBeforeBodyIsRead() throws Exception {
    server.enqueue(new MockResponse().setBody("def").throttleBody(1, 750, TimeUnit.MILLISECONDS));
    server.play();

    final Call call = client.newCall(new Request.Builder().url(server.getUrl("/a")).build());
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
    } catch (IOException e) {
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE() throws Exception {
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        client.cancel("request");
        return new MockResponse().setBody("A");
      }
    });
    server.play();

    Request request = new Request.Builder().url(server.getUrl("/a")).tag("request").build();
    try {
      client.newCall(request).execute();
      fail();
    } catch (IOException e) {
    }
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    cancelInFlightBeforeResponseReadThrowsIOE();
  }

  @Test public void cancelInFlightBeforeResponseReadThrowsIOE_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    cancelInFlightBeforeResponseReadThrowsIOE();
  }

  /**
   * This test puts a request in front of one that is to be canceled, so that it is canceled before
   * I/O takes place.
   */
  @Test public void canceledBeforeIOSignalsOnFailure() throws Exception {
    client.getDispatcher().setMaxRequests(1); // Force requests to be executed serially.
    server.setDispatcher(new Dispatcher() {
      char nextResponse = 'A';

      @Override public MockResponse dispatch(RecordedRequest request) {
        client.cancel("request B");
        return new MockResponse().setBody(Character.toString(nextResponse++));
      }
    });
    server.play();

    Request requestA = new Request.Builder().url(server.getUrl("/a")).tag("request A").build();
    client.newCall(requestA).enqueue(callback);
    assertEquals("/a", server.takeRequest().getPath());

    Request requestB = new Request.Builder().url(server.getUrl("/b")).tag("request B").build();
    client.newCall(requestB).enqueue(callback);
    assertEquals("/b", server.takeRequest().getPath());

    callback.await(requestA.url()).assertBody("A");
    // At this point we know the callback is ready, and that it will receive a cancel failure.
    callback.await(requestB.url()).assertFailure("Canceled");
  }

  @Test public void canceledBeforeIOSignalsOnFailure_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeIOSignalsOnFailure_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    canceledBeforeIOSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure() throws Exception {
    server.play();
    Request requestA = new Request.Builder().url(server.getUrl("/a")).tag("request A").build();
    final Call call = client.newCall(requestA);
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        call.cancel();
        return new MockResponse().setBody("A");
      }
    });

    call.enqueue(callback);
    assertEquals("/a", server.takeRequest().getPath());

    callback.await(requestA.url()).assertFailure("Canceled");
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledBeforeResponseReadSignalsOnFailure();
  }

  @Test public void canceledBeforeResponseReadSignalsOnFailure_SPDY_3() throws Exception {
    enableProtocol(Protocol.SPDY_3);
    canceledBeforeResponseReadSignalsOnFailure();
  }

  /**
   * There's a race condition where the cancel may apply after the stream has already been
   * processed.
   */
  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<String> bodyRef = new AtomicReference<>();
    final AtomicBoolean failureRef = new AtomicBoolean();

    Request request = new Request.Builder().url(server.getUrl("/a")).tag("request A").build();
    final Call call = client.newCall(request);
    call.enqueue(new Callback() {
      @Override public void onFailure(Request request, IOException e) {
        failureRef.set(true);
        latch.countDown();
      }

      @Override public void onResponse(Response response) throws IOException {
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

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTP_2()
      throws Exception {
    enableProtocol(Protocol.HTTP_2);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_SPDY_3()
      throws Exception {
    enableProtocol(Protocol.SPDY_3);
    canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce();
  }

  @Test public void gzip() throws Exception {
    Buffer gzippedBody = gzip("abcabcabc");
    String bodySize = Long.toString(gzippedBody.size());

    server.enqueue(new MockResponse()
        .setBody(gzippedBody)
        .addHeader("Content-Encoding: gzip"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    // Confirm that the user request doesn't have Accept-Encoding, and the user
    // response doesn't have a Content-Encoding or Content-Length.
    RecordedResponse userResponse = executeSynchronously(request);
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

  @Test public void asyncResponseCanBeConsumedLater() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setBody("def"));
    server.play();

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .header("User-Agent", "SyncApiTest")
        .build();

    final BlockingQueue<Response> responseRef = new SynchronousQueue<>();
    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Request request, IOException e) {
        throw new AssertionError();
      }

      @Override public void onResponse(Response response) throws IOException {
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
    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build()).assertBody("def");
    assertEquals(0, server.takeRequest().getSequenceNumber()); // New connection.
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection reused.

    // ... even before we close the response body!
    response.body().close();
  }

  @Test public void userAgentIsIncludedByDefault() throws Exception {
    server.enqueue(new MockResponse());
    server.play();

    executeSynchronously(new Request.Builder().url(server.getUrl("/")).build());

    RecordedRequest recordedRequest = server.takeRequest();
    assertTrue(recordedRequest.getHeader("User-Agent")
        .matches("okhttp/\\d\\.\\d\\.\\d(-SNAPSHOT)?"));
  }

  @Test public void setFollowRedirectsFalse() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));
    server.play();

    client.setFollowRedirects(false);
    RecordedResponse recordedResponse = executeSynchronously(
        new Request.Builder().url(server.getUrl("/a")).build());

    recordedResponse
        .assertBody("A")
        .assertCode(302);
  }

  private RecordedResponse executeSynchronously(Request request) throws IOException {
    Response response = client.newCall(request).execute();
    return new RecordedResponse(request, response, response.body().string(), null);
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());
    client.setProtocols(Arrays.asList(protocol, Protocol.HTTP_1_1));
    server.useHttps(sslContext.getSocketFactory(), false);
    server.setProtocols(client.getProtocols());
  }

  private Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  private void assertContains(Collection<String> collection, String element) {
    for (String c : collection) {
      if (c != null && c.equalsIgnoreCase(element)) return;
    }
    fail("No " + element + " in " + collection);
  }

  private void assertContainsNoneMatching(List<String> headers, String pattern) {
    for (String header : headers) {
      if (header.matches(pattern)) {
        fail("Header " + header + " matches " + pattern);
      }
    }
  }
}
