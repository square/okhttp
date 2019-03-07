/*
 * Copyright (C) 2009 The Android Open Source Project
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
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.Internal;
import okhttp3.internal.RecordingAuthenticator;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import okio.Utf8;
import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static okhttp3.mockwebserver.SocketPolicy.FAIL_HANDSHAKE;
import static okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_INPUT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_OUTPUT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.UPGRADE_TO_SSL_AT_END;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/** Android's URLConnectionTest, ported to exercise OkHttp's Call API. */
public final class URLConnectionTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final MockWebServer server2 = new MockWebServer();
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = clientTestRule.client;
  private @Nullable Cache cache;

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @After public void tearDown() throws Exception {
    Authenticator.setDefault(null);
    System.clearProperty("proxyHost");
    System.clearProperty("proxyPort");
    System.clearProperty("http.proxyHost");
    System.clearProperty("http.proxyPort");
    System.clearProperty("https.proxyHost");
    System.clearProperty("https.proxyPort");
    if (cache != null) {
      cache.delete();
    }
  }

  @Test public void requestHeaders() throws Exception {
    server.enqueue(new MockResponse());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .addHeader("D", "e")
        .addHeader("D", "f")
        .build();
    assertEquals("f", request.header("D"));
    assertEquals("f", request.header("d"));
    Headers requestHeaders = request.headers();
    assertEquals(newSet("e", "f"), new LinkedHashSet<>(requestHeaders.values("D")));
    assertEquals(newSet("e", "f"), new LinkedHashSet<>(requestHeaders.values("d")));
    try {
      new Request.Builder()
          .header(null, "j");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Request.Builder()
          .addHeader(null, "k");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Request.Builder()
          .addHeader("NullValue", null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      new Request.Builder()
          .addHeader("AnotherNullValue", null);
      fail();
    } catch (NullPointerException expected) {
    }

    Response response = getResponse(request);
    response.close();

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(Arrays.asList("e", "f"), recordedRequest.getHeaders().values("D"));
    assertNull(recordedRequest.getHeader("G"));
    assertNull(recordedRequest.getHeader("null"));
  }

  @Test public void getRequestPropertyReturnsLastValue() {
    Request request = new Request.Builder()
        .url(server.url("/"))
        .addHeader("A", "value1")
        .addHeader("A", "value2")
        .build();
    assertEquals("value2", request.header("A"));
  }

  @Test public void responseHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.0 200 Fantastic")
        .addHeader("A: c")
        .addHeader("B: d")
        .addHeader("A: e")
        .setChunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8));

    Request request = newRequest("/");
    Response response = getResponse(request);
    assertEquals(200, response.code());
    assertEquals("Fantastic", response.message());
    try {
      response.header(null);
      fail();
    } catch (NullPointerException expected) {
    }
    Headers responseHeaders = response.headers();
    assertEquals(newSet("c", "e"), new LinkedHashSet<>(responseHeaders.values("A")));
    assertEquals(newSet("c", "e"), new LinkedHashSet<>(responseHeaders.values("a")));
    assertEquals("A", responseHeaders.name(0));
    assertEquals("c", responseHeaders.value(0));
    assertEquals("B", responseHeaders.name(1));
    assertEquals("d", responseHeaders.value(1));
    assertEquals("A", responseHeaders.name(2));
    assertEquals("e", responseHeaders.value(2));
    response.body().close();
  }

  @Test public void serverSendsInvalidStatusLine() {
    server.enqueue(new MockResponse().setStatus("HTP/1.1 200 OK"));

    Request request = newRequest("/");

    try {
      getResponse(request);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void serverSendsInvalidCodeTooLarge() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 2147483648 OK"));

    Request request = newRequest("/");

    try {
      getResponse(request);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void serverSendsInvalidCodeNotANumber() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 00a OK"));

    Request request = newRequest("/");
    try {
      getResponse(request);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void serverSendsUnnecessaryWhitespace() {
    server.enqueue(new MockResponse().setStatus(" HTTP/1.1 2147483648 OK"));

    Request request = newRequest("/");
    try {
      getResponse(request);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void connectRetriesUntilConnectedOrFailed() throws Exception {
    Request request = newRequest("/foo");
    server.shutdown();

    try {
      getResponse(request);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void requestBodySurvivesRetriesWithFixedLength() throws Exception {
    testRequestBodySurvivesRetries(TransferKind.FIXED_LENGTH);
  }

  @Test public void requestBodySurvivesRetriesWithChunkedStreaming() throws Exception {
    testRequestBodySurvivesRetries(TransferKind.CHUNKED);
  }

  private void testRequestBodySurvivesRetries(TransferKind transferKind) throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    // Use a misconfigured proxy to guarantee that the request is retried.
    client = client.newBuilder()
        .proxySelector(new FakeProxySelector()
            .addProxy(server2.toProxyAddress())
            .addProxy(Proxy.NO_PROXY))
        .build();
    server2.shutdown();

    Request request = new Request.Builder()
        .url(server.url("/def"))
        .post(transferKind.newRequestBody("body"))
        .build();
    Response response = getResponse(request);
    assertContent("abc", response);

    assertEquals("body", server.takeRequest().getBody().readUtf8());
  }

  // Check that if we don't read to the end of a response, the next request on the
  // recycled connection doesn't get the unread tail of the first request's response.
  // http://code.google.com/p/android/issues/detail?id=2939
  @Test public void bug2939() throws Exception {
    MockResponse response = new MockResponse()
        .setChunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8);

    server.enqueue(response);
    server.enqueue(response);

    Request request = newRequest("/");
    Response c1 = getResponse(request);
    assertContent("ABCDE", c1, 5);
    Response c2 = getResponse(request);
    assertContent("ABCDE", c2, 5);

    c1.close();
    c2.close();
  }

  @Test public void connectionsArePooled() throws Exception {
    MockResponse response = new MockResponse()
        .setBody("ABCDEFGHIJKLMNOPQR");

    server.enqueue(response);
    server.enqueue(response);
    server.enqueue(response);

    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/foo")));
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/bar?baz=quux")));
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/z")));
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void chunkedConnectionsArePooled() throws Exception {
    MockResponse response = new MockResponse()
        .setChunkedBody("ABCDEFGHIJKLMNOPQR", 5);

    server.enqueue(response);
    server.enqueue(response);
    server.enqueue(response);

    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/foo")));
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/bar?baz=quux")));
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/z")));
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void serverClosesSocket() throws Exception {
    testServerClosesOutput(DISCONNECT_AT_END);
  }

  @Test public void serverShutdownInput() throws Exception {
    testServerClosesOutput(SHUTDOWN_INPUT_AT_END);
  }

  @Test public void serverShutdownOutput() throws Exception {
    testServerClosesOutput(SHUTDOWN_OUTPUT_AT_END);
  }

  @Test public void invalidHost() throws Exception {
    // Note that 1234.1.1.1 is an invalid host in a URI, but URL isn't as strict.
    client = client.newBuilder()
        .dns(new FakeDns())
        .build();
    try {
      getResponse(new Request.Builder()
          .url(HttpUrl.get("http://1234.1.1.1/index.html"))
          .build());
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  private void testServerClosesOutput(SocketPolicy socketPolicy) throws Exception {
    server.enqueue(new MockResponse()
        .setBody("This connection won't pool properly")
        .setSocketPolicy(socketPolicy));
    MockResponse responseAfter = new MockResponse()
        .setBody("This comes after a busted connection");
    server.enqueue(responseAfter);
    server.enqueue(responseAfter); // Enqueue 2x because the broken connection may be reused.

    Response response1 = getResponse(newRequest("/a"));
    response1.body().source().timeout().timeout(100, MILLISECONDS);
    assertContent("This connection won't pool properly", response1);
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Give the server time to enact the socket policy if it's one that could happen after the
    // client has received the response.
    Thread.sleep(500);

    Response response2 = getResponse(newRequest("/b"));
    response1.body().source().timeout().timeout(100, MILLISECONDS);
    assertContent("This comes after a busted connection", response2);

    // Check that a fresh connection was created, either immediately or after attempting reuse.
    // We know that a fresh connection was created if the server recorded a request with sequence
    // number 0. Since the client may have attempted to reuse the broken connection just before
    // creating a fresh connection, the server may have recorded 2 requests at this point. The order
    // of recording is non-deterministic.
    RecordedRequest requestAfter = server.takeRequest();
    assertTrue(
        requestAfter.getSequenceNumber() == 0
            || server.getRequestCount() == 3 && server.takeRequest().getSequenceNumber() == 0);
  }

  enum WriteKind {BYTE_BY_BYTE, SMALL_BUFFERS, LARGE_BUFFERS}

  @Test public void chunkedUpload_byteByByte() throws Exception {
    doUpload(TransferKind.CHUNKED, WriteKind.BYTE_BY_BYTE);
  }

  @Test public void chunkedUpload_smallBuffers() throws Exception {
    doUpload(TransferKind.CHUNKED, WriteKind.SMALL_BUFFERS);
  }

  @Test public void chunkedUpload_largeBuffers() throws Exception {
    doUpload(TransferKind.CHUNKED, WriteKind.LARGE_BUFFERS);
  }

  @Test public void fixedLengthUpload_byteByByte() throws Exception {
    doUpload(TransferKind.FIXED_LENGTH, WriteKind.BYTE_BY_BYTE);
  }

  @Test public void fixedLengthUpload_smallBuffers() throws Exception {
    doUpload(TransferKind.FIXED_LENGTH, WriteKind.SMALL_BUFFERS);
  }

  @Test public void fixedLengthUpload_largeBuffers() throws Exception {
    doUpload(TransferKind.FIXED_LENGTH, WriteKind.LARGE_BUFFERS);
  }

  private void doUpload(TransferKind uploadKind, WriteKind writeKind) throws Exception {
    int n = 512 * 1024;
    server.setBodyLimit(0);
    server.enqueue(new MockResponse());

    RequestBody requestBody = new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return uploadKind == TransferKind.CHUNKED ? -1L : n;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        if (writeKind == WriteKind.BYTE_BY_BYTE) {
          for (int i = 0; i < n; ++i) {
            sink.writeByte('x');
          }
        } else {
          byte[] buf = new byte[writeKind == WriteKind.SMALL_BUFFERS ? 256 : 64 * 1024];
          Arrays.fill(buf, (byte) 'x');
          for (int i = 0; i < n; i += buf.length) {
            sink.write(buf, 0, Math.min(buf.length, n - i));
          }
        }
      }
    };

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(requestBody)
        .build());
    assertEquals(200, response.code());
    RecordedRequest request = server.takeRequest();
    assertEquals(n, request.getBodySize());
    if (uploadKind == TransferKind.CHUNKED) {
      assertTrue(request.getChunkSizes().size() > 0);
    } else {
      assertTrue(request.getChunkSizes().isEmpty());
    }
  }

  @Test public void connectViaHttps() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("this response comes via HTTPS"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Response response = getResponse(newRequest("/foo"));
    assertContent("this response comes via HTTPS", response);

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
  }

  @Test public void connectViaHttpsReusingConnections() throws Exception {
    connectViaHttpsReusingConnections(false);
  }

  @Test public void connectViaHttpsReusingConnectionsAfterRebuildingClient() throws Exception {
    connectViaHttpsReusingConnections(true);
  }

  private void connectViaHttpsReusingConnections(boolean rebuildClient) throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("this response comes via HTTPS"));
    server.enqueue(new MockResponse()
        .setBody("another response via HTTPS"));

    // The pool will only reuse sockets if the SSL socket factories are the same.
    SSLSocketFactory clientSocketFactory = handshakeCertificates.sslSocketFactory();
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    CookieJar cookieJar = new JavaNetCookieJar(new CookieManager());
    ConnectionPool connectionPool = new ConnectionPool();

    client = new OkHttpClient.Builder()
        .cache(cache)
        .connectionPool(connectionPool)
        .cookieJar(cookieJar)
        .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager())
        .hostnameVerifier(hostnameVerifier)
        .build();
    Response response1 = getResponse(newRequest("/"));
    assertContent("this response comes via HTTPS", response1);

    if (rebuildClient) {
      client = new OkHttpClient.Builder()
          .cache(cache)
          .connectionPool(connectionPool)
          .cookieJar(cookieJar)
          .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager())
          .hostnameVerifier(hostnameVerifier)
          .build();
    }

    Response response2 = getResponse(newRequest("/"));
    assertContent("another response via HTTPS", response2);

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectViaHttpsReusingConnectionsDifferentFactories() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("this response comes via HTTPS"));
    server.enqueue(new MockResponse()
        .setBody("another response via HTTPS"));

    // install a custom SSL socket factory so the server can be authorized
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    Response response1 = getResponse(newRequest("/"));
    assertContent("this response comes via HTTPS", response1);

    SSLContext sslContext2 = Platform.get().getSSLContext();
    sslContext2.init(null, null, null);
    SSLSocketFactory sslSocketFactory2 = sslContext2.getSocketFactory();

    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init((KeyStore) null);
    X509TrustManager trustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

    client = client.newBuilder()
        .sslSocketFactory(sslSocketFactory2, trustManager)
        .build();
    try {
      getResponse(newRequest("/"));
      fail("without an SSL socket factory, the connection should fail");
    } catch (SSLException expected) {
    }
  }

  // TODO(jwilson): tests below this marker need to be migrated to OkHttp's request/response API.

  @Test public void connectViaHttpsWithSSLFallback() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(FAIL_HANDSHAKE));
    server.enqueue(new MockResponse()
        .setBody("this response comes via SSL"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();
    Response response = getResponse(newRequest("/foo"));

    assertContent("this response comes via SSL", response);

    RecordedRequest failHandshakeRequest = server.takeRequest();
    assertNull(failHandshakeRequest.getRequestLine());

    RecordedRequest fallbackRequest = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", fallbackRequest.getRequestLine());
    assertThat(fallbackRequest.getTlsVersion(),
        either(equalTo(TlsVersion.TLS_1_2)).or(equalTo(TlsVersion.TLS_1_3)));
  }

  @Test public void connectViaHttpsWithSSLFallbackFailuresRecorded() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setSocketPolicy(FAIL_HANDSHAKE));
    server.enqueue(new MockResponse()
        .setSocketPolicy(FAIL_HANDSHAKE));

    client = client.newBuilder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    try {
      getResponse(newRequest("/foo"));
      fail();
    } catch (IOException expected) {
      assertEquals(1, expected.getSuppressed().length);
    }
  }

  /**
   * When a pooled connection fails, don't blame the route. Otherwise pooled connection failures can
   * cause unnecessary SSL fallbacks.
   *
   * https://github.com/square/okhttp/issues/515
   */
  @Test public void sslFallbackNotUsedWhenRecycledConnectionFails() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setSocketPolicy(DISCONNECT_AT_END));
    server.enqueue(new MockResponse()
        .setBody("def"));

    client = client.newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(
            suppressTlsFallbackClientSocketFactory(), handshakeCertificates.trustManager())
        .build();

    assertContent("abc", getResponse(newRequest("/")));

    // Give the server time to disconnect.
    Thread.sleep(500);

    assertContent("def", getResponse(newRequest("/")));

    Set<TlsVersion> tlsVersions =
        EnumSet.of(TlsVersion.TLS_1_0, TlsVersion.TLS_1_2,
            TlsVersion.TLS_1_3); // v1.2 on OpenJDK 8.

    RecordedRequest request1 = server.takeRequest();
    assertTrue(tlsVersions.contains(request1.getTlsVersion()));

    RecordedRequest request2 = server.takeRequest();
    assertTrue(tlsVersions.contains(request2.getTlsVersion()));
  }

  /**
   * Verify that we don't retry connections on certificate verification errors.
   *
   * http://code.google.com/p/android/issues/detail?id=13178
   */
  @Test public void connectViaHttpsToUntrustedServer() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()); // unused

    try {
      getResponse(newRequest("/foo"));
      fail();
    } catch (SSLHandshakeException expected) {
      assertTrue(expected.getCause() instanceof CertificateException);
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test public void connectViaProxyUsingProxyArg() throws Exception {
    testConnectViaProxy(ProxyConfig.CREATE_ARG);
  }

  @Test public void connectViaProxyUsingProxySystemProperty() throws Exception {
    testConnectViaProxy(ProxyConfig.PROXY_SYSTEM_PROPERTY);
  }

  @Test public void connectViaProxyUsingHttpProxySystemProperty() throws Exception {
    testConnectViaProxy(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY);
  }

  private void testConnectViaProxy(ProxyConfig proxyConfig) throws Exception {
    MockResponse mockResponse = new MockResponse()
        .setBody("this response comes via a proxy");
    server.enqueue(mockResponse);

    HttpUrl url = HttpUrl.parse("http://android.com/foo");
    Response response = proxyConfig.connect(server, client, url).execute();
    assertContent("this response comes via a proxy", response);

    RecordedRequest request = server.takeRequest();
    assertEquals("GET http://android.com/foo HTTP/1.1", request.getRequestLine());
    assertEquals("android.com", request.getHeader("Host"));
  }

  @Test public void contentDisagreesWithContentLengthHeaderBodyTooLong() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc\r\nYOU SHOULD NOT SEE THIS")
        .clearHeaders()
        .addHeader("Content-Length: 3"));
    assertContent("abc", getResponse(newRequest("/")));
  }

  @Test public void contentDisagreesWithContentLengthHeaderBodyTooShort() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setHeader("Content-Length", "5")
        .setSocketPolicy(DISCONNECT_AT_END));
    try {
      Response response = getResponse(newRequest("/"));
      response.body().source().readUtf8(5);
      fail();
    } catch (ProtocolException expected) {
    }
  }

  private void testConnectViaSocketFactory(boolean useHttps) throws IOException {
    SocketFactory uselessSocketFactory = new SocketFactory() {
      public Socket createSocket() {
        throw new IllegalArgumentException("useless");
      }

      public Socket createSocket(InetAddress host, int port) {
        return null;
      }

      public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
          int localPort) {
        return null;
      }

      public Socket createSocket(String host, int port) {
        return null;
      }

      public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
        return null;
      }
    };

    if (useHttps) {
      server.useHttps(handshakeCertificates.sslSocketFactory(), false);
      client = client.newBuilder()
          .sslSocketFactory(
              handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
          .hostnameVerifier(new RecordingHostnameVerifier())
          .build();
    }

    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 200 OK"));

    client = client.newBuilder()
        .socketFactory(uselessSocketFactory)
        .build();
    try {
      getResponse(newRequest("/"));
      fail();
    } catch (IllegalArgumentException expected) {
    }

    client = client.newBuilder()
        .socketFactory(SocketFactory.getDefault())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals(200, response.code());
  }

  @Test public void connectHttpViaSocketFactory() throws Exception {
    testConnectViaSocketFactory(false);
  }

  @Test public void connectHttpsViaSocketFactory() throws Exception {
    testConnectViaSocketFactory(true);
  }

  @Test public void contentDisagreesWithChunkedHeaderBodyTooLong() throws IOException {
    MockResponse mockResponse = new MockResponse()
        .setChunkedBody("abc", 3);
    Buffer buffer = mockResponse.getBody();
    buffer.writeUtf8("\r\nYOU SHOULD NOT SEE THIS");
    mockResponse.setBody(buffer);
    mockResponse.clearHeaders();
    mockResponse.addHeader("Transfer-encoding: chunked");

    server.enqueue(mockResponse);

    assertContent("abc", getResponse(newRequest("/")));
  }

  @Test public void contentDisagreesWithChunkedHeaderBodyTooShort() {
    MockResponse mockResponse = new MockResponse()
        .setChunkedBody("abcdefg", 5);

    Buffer truncatedBody = new Buffer();
    Buffer fullBody = mockResponse.getBody();
    truncatedBody.write(fullBody, 4);
    mockResponse.setBody(truncatedBody);

    mockResponse.clearHeaders();
    mockResponse.addHeader("Transfer-encoding: chunked");
    mockResponse.setSocketPolicy(DISCONNECT_AT_END);

    server.enqueue(mockResponse);

    try {
      Response response = getResponse(newRequest("/"));
      response.body().source().readUtf8(7);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void connectViaHttpProxyToHttpsUsingProxyArgWithNoProxy() throws Exception {
    testConnectViaDirectProxyToHttps(ProxyConfig.NO_PROXY);
  }

  @Test public void connectViaHttpProxyToHttpsUsingHttpProxySystemProperty() throws Exception {
    // https should not use http proxy
    testConnectViaDirectProxyToHttps(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY);
  }

  private void testConnectViaDirectProxyToHttps(ProxyConfig proxyConfig) throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("this response comes via HTTPS"));

    HttpUrl url = server.url("/foo");
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    Call call = proxyConfig.connect(server, client, url);

    assertContent("this response comes via HTTPS", call.execute());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
  }

  @Test public void connectViaHttpProxyToHttpsUsingProxyArg() throws Exception {
    testConnectViaHttpProxyToHttps(ProxyConfig.CREATE_ARG);
  }

  /**
   * We weren't honoring all of the appropriate proxy system properties when connecting via HTTPS.
   * http://b/3097518
   */
  @Test public void connectViaHttpProxyToHttpsUsingProxySystemProperty() throws Exception {
    testConnectViaHttpProxyToHttps(ProxyConfig.PROXY_SYSTEM_PROPERTY);
  }

  @Test public void connectViaHttpProxyToHttpsUsingHttpsProxySystemProperty() throws Exception {
    testConnectViaHttpProxyToHttps(ProxyConfig.HTTPS_PROXY_SYSTEM_PROPERTY);
  }

  /**
   * We were verifying the wrong hostname when connecting to an HTTPS site through a proxy.
   * http://b/3097277
   */
  private void testConnectViaHttpProxyToHttps(ProxyConfig proxyConfig) throws Exception {
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("this response comes via a secure proxy"));

    HttpUrl url = HttpUrl.parse("https://android.com/foo");
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(hostnameVerifier)
        .build();
    Call call = proxyConfig.connect(server, client, url);

    assertContent("this response comes via a secure proxy", call.execute());

    RecordedRequest connect = server.takeRequest();
    assertEquals("Connect line failure on proxy", "CONNECT android.com:443 HTTP/1.1",
        connect.getRequestLine());
    assertEquals("android.com:443", connect.getHeader("Host"));

    RecordedRequest get = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
    assertEquals("android.com", get.getHeader("Host"));
    assertEquals(Arrays.asList("verify android.com"), hostnameVerifier.calls);
  }

  /** Tolerate bad https proxy response when using HttpResponseCache. Android bug 6754912. */
  @Test public void connectViaHttpProxyToHttpsUsingBadProxyAndHttpResponseCache() throws Exception {
    initResponseCache();

    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    // The inclusion of a body in the response to a CONNECT is key to reproducing b/6754912.
    MockResponse badProxyResponse = new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .setBody("bogus proxy connect response content");
    server.enqueue(badProxyResponse);
    server.enqueue(new MockResponse()
        .setBody("response"));

    // Configure a single IP address for the host and a single configuration, so we only need one
    // failure to fail permanently.
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .connectionSpecs(Util.immutableList(ConnectionSpec.MODERN_TLS))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxy(server.toProxyAddress())
        .build();

    Response response = getResponse(new Request.Builder()
        .url(HttpUrl.get("https://android.com/foo"))
        .build());
    assertContent("response", response);

    RecordedRequest connect = server.takeRequest();
    assertEquals("CONNECT android.com:443 HTTP/1.1", connect.getRequestLine());
    assertEquals("android.com:443", connect.getHeader("Host"));
  }

  private void initResponseCache() {
    cache = new Cache(tempDir.getRoot(), Integer.MAX_VALUE);
    client = client.newBuilder()
        .cache(cache)
        .build();
  }

  /** Test which headers are sent unencrypted to the HTTP proxy. */
  @Test public void proxyConnectIncludesProxyHeadersOnly() throws Exception {
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("encrypted response from the origin server"));

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(hostnameVerifier)
        .build();

    Response response = getResponse(new Request.Builder()
        .url(HttpUrl.get("https://android.com/foo"))
        .header("Private", "Secret")
        .header("Proxy-Authorization", "bar")
        .header("User-Agent", "baz")
        .build());
    assertContent("encrypted response from the origin server", response);

    RecordedRequest connect = server.takeRequest();
    assertNull(connect.getHeader("Private"));
    assertNull(connect.getHeader("Proxy-Authorization"));
    assertEquals(Version.userAgent(), connect.getHeader("User-Agent"));
    assertEquals("android.com:443", connect.getHeader("Host"));
    assertEquals("Keep-Alive", connect.getHeader("Proxy-Connection"));

    RecordedRequest get = server.takeRequest();
    assertEquals("Secret", get.getHeader("Private"));
    assertEquals(Arrays.asList("verify android.com"), hostnameVerifier.calls);
  }

  @Test public void proxyAuthenticateOnConnect() throws Exception {
    Authenticator.setDefault(new RecordingAuthenticator());
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""));
    server.enqueue(new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("A"));

    client = client.newBuilder()
        .proxyAuthenticator(new JavaNetAuthenticator())
        .proxy(server.toProxyAddress())
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Response response = getResponse(new Request.Builder()
        .url(HttpUrl.parse("https://android.com/foo"))
        .build());
    assertContent("A", response);

    RecordedRequest connect1 = server.takeRequest();
    assertEquals("CONNECT android.com:443 HTTP/1.1", connect1.getRequestLine());
    assertNull(connect1.getHeader("Proxy-Authorization"));

    RecordedRequest connect2 = server.takeRequest();
    assertEquals("CONNECT android.com:443 HTTP/1.1", connect2.getRequestLine());
    assertEquals("Basic " + RecordingAuthenticator.BASE_64_CREDENTIALS,
        connect2.getHeader("Proxy-Authorization"));

    RecordedRequest get = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
    assertNull(get.getHeader("Proxy-Authorization"));
  }

  // Don't disconnect after building a tunnel with CONNECT
  // http://code.google.com/p/android/issues/detail?id=37221
  @Test public void proxyWithConnectionClose() throws IOException {
    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("this response comes via a proxy"));

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    Response response = getResponse(new Request.Builder()
        .url("https://android.com/foo")
        .header("Connection", "close")
        .build());

    assertContent("this response comes via a proxy", response);
  }

  @Test public void proxyWithConnectionReuse() throws IOException {
    SSLSocketFactory socketFactory = handshakeCertificates.sslSocketFactory();
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    server.useHttps(socketFactory, true);
    server.enqueue(new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .clearHeaders());
    server.enqueue(new MockResponse()
        .setBody("response 1"));
    server.enqueue(new MockResponse()
        .setBody("response 2"));

    client = client.newBuilder()
        .proxy(server.toProxyAddress())
        .sslSocketFactory(socketFactory, handshakeCertificates.trustManager())
        .hostnameVerifier(hostnameVerifier)
        .build();

    assertContent("response 1", getResponse(newRequest(HttpUrl.get("https://android.com/foo"))));
    assertContent("response 2", getResponse(newRequest(HttpUrl.get("https://android.com/foo"))));
  }

  @Test public void proxySelectorHttpWithConnectionReuse() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("response 1"));
    server.enqueue(new MockResponse()
        .setResponseCode(407));

    client = client.newBuilder()
        .proxySelector(new ProxySelector() {
          @Override public List<Proxy> select(URI uri) {
            return Collections.singletonList(server.toProxyAddress());
          }

          @Override public void connectFailed(
              URI uri, SocketAddress socketAddress, IOException e) {
          }
        }).build();
    HttpUrl url = HttpUrl.get("http://android.com/foo");
    assertContent("response 1", getResponse(newRequest(url)));
    assertEquals(407, getResponse(newRequest(url)).code());
  }

  @Test public void disconnectedConnection() throws IOException {
    server.enqueue(new MockResponse()
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .setBody("ABCD"));

    Call call = client.newCall(newRequest("/"));
    Response response = call.execute();
    InputStream in = response.body().byteStream();
    assertEquals('A', (char) in.read());
    call.cancel();
    try {
      // Reading 'B' may succeed if it's buffered.
      in.read();

      // But 'C' shouldn't be buffered (the response is throttled) and this should fail.
      in.read();
      fail("Expected a connection closed exception");
    } catch (IOException expected) {
    }
    in.close();
  }

  @Test public void disconnectDuringConnect_cookieJar() {
    AtomicReference<Call> callReference = new AtomicReference<>();

    class DisconnectingCookieJar implements CookieJar {
      @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
      }

      @Override public List<Cookie> loadForRequest(HttpUrl url) {
        callReference.get().cancel();
        return Collections.emptyList();
      }
    }
    client = client.newBuilder()
        .cookieJar(new DisconnectingCookieJar())
        .build();

    Call call = client.newCall(newRequest("/"));
    callReference.set(call);
    try {
      call.execute();
      fail("Connection should not be established");
    } catch (IOException expected) {
      assertEquals("Canceled", expected.getMessage());
    }
  }

  @Test public void disconnectBeforeConnect() {
    server.enqueue(new MockResponse()
        .setBody("A"));

    Call call = client.newCall(newRequest("/"));
    call.cancel();
    try {
      call.execute();
      fail();
    } catch (IOException expected) {
    }
  }

  @SuppressWarnings("deprecation") @Test public void defaultRequestProperty() {
    URLConnection.setDefaultRequestProperty("X-testSetDefaultRequestProperty", "A");
    assertNull(URLConnection.getDefaultRequestProperty("X-setDefaultRequestProperty"));
  }

  /**
   * Reads {@code count} characters from the stream. If the stream is exhausted before {@code count}
   * characters can be read, the remaining characters are returned and the stream is closed.
   */
  private String readAscii(InputStream in, int count) throws IOException {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      int value = in.read();
      if (value == -1) {
        in.close();
        break;
      }
      result.append((char) value);
    }
    return result.toString();
  }

  @Test public void markAndResetWithContentLengthHeader() throws IOException {
    testMarkAndReset(TransferKind.FIXED_LENGTH);
  }

  @Test public void markAndResetWithChunkedEncoding() throws IOException {
    testMarkAndReset(TransferKind.CHUNKED);
  }

  @Test public void markAndResetWithNoLengthHeaders() throws IOException {
    testMarkAndReset(TransferKind.END_OF_STREAM);
  }

  private void testMarkAndReset(TransferKind transferKind) throws IOException {
    MockResponse response = new MockResponse();
    transferKind.setBody(response, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 1024);
    server.enqueue(response);
    server.enqueue(response);

    InputStream in = getResponse(newRequest("/")).body().byteStream();
    assertFalse("This implementation claims to support mark().", in.markSupported());
    in.mark(5);
    assertEquals("ABCDE", readAscii(in, 5));
    try {
      in.reset();
      fail();
    } catch (IOException expected) {
    }
    assertEquals("FGHIJKLMNOPQRSTUVWXYZ", readAscii(in, Integer.MAX_VALUE));
    in.close();
    assertContent("ABCDEFGHIJKLMNOPQRSTUVWXYZ", getResponse(newRequest("/")));
  }

  /**
   * We've had a bug where we forget the HTTP response when we see response code 401. This causes a
   * new HTTP request to be issued for every call into the URLConnection.
   */
  @Test public void unauthorizedResponseHandling() throws IOException {
    MockResponse mockResponse = new MockResponse()
        .addHeader("WWW-Authenticate: challenge")
        .setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED)
        .setBody("Unauthorized");
    server.enqueue(mockResponse);
    server.enqueue(mockResponse);
    server.enqueue(mockResponse);

    Response response = getResponse(newRequest("/"));

    assertEquals(401, response.code());
    assertEquals(401, response.code());
    assertEquals(401, response.code());
    assertEquals(1, server.getRequestCount());
    response.body().close();
  }

  @Test public void nonHexChunkSize() {
    server.enqueue(new MockResponse()
        .setBody("5\r\nABCDE\r\nG\r\nFGHIJKLMNOPQRSTU\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    try (Response response = getResponse(newRequest("/"))) {
      response.body().string();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void malformedChunkSize() {
    server.enqueue(new MockResponse()
        .setBody("5:x\r\nABCDE\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    try (Response response = getResponse(newRequest("/"))) {
      readAscii(response.body().byteStream(), Integer.MAX_VALUE);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void extensionAfterChunkSize() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("5;x\r\nABCDE\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    try (Response response = getResponse(newRequest("/"))) {
      assertContent("ABCDE", response);
    }
  }

  @Test public void missingChunkBody() {
    server.enqueue(new MockResponse()
        .setBody("5")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked")
        .setSocketPolicy(DISCONNECT_AT_END));

    try (Response response = getResponse(newRequest("/"))) {
      readAscii(response.body().byteStream(), Integer.MAX_VALUE);
      fail();
    } catch (IOException expected) {
    }
  }

  /**
   * This test checks whether connections are gzipped by default. This behavior in not required by
   * the API, so a failure of this test does not imply a bug in the implementation.
   */
  @Test public void gzipEncodingEnabledByDefault() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip"));

    Response response = getResponse(newRequest("/"));
    assertEquals("ABCABCABC", readAscii(response.body().byteStream(), Integer.MAX_VALUE));
    assertNull(response.header("Content-Encoding"));
    assertEquals(-1L, response.body().contentLength());

    RecordedRequest request = server.takeRequest();
    assertEquals("gzip", request.getHeader("Accept-Encoding"));
  }

  @Test public void clientConfiguredGzipContentEncoding() throws Exception {
    Buffer bodyBytes = gzip("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    server.enqueue(new MockResponse()
        .setBody(bodyBytes)
        .addHeader("Content-Encoding: gzip"));

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .header("Accept-Encoding", "gzip")
        .build());
    InputStream gunzippedIn = new GZIPInputStream(response.body().byteStream());
    assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", readAscii(gunzippedIn, Integer.MAX_VALUE));
    assertEquals(bodyBytes.size(), response.body().contentLength());

    RecordedRequest request = server.takeRequest();
    assertEquals("gzip", request.getHeader("Accept-Encoding"));
  }

  @Test public void gzipAndConnectionReuseWithFixedLength() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, false);
  }

  @Test public void gzipAndConnectionReuseWithChunkedEncoding() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, false);
  }

  @Test public void gzipAndConnectionReuseWithFixedLengthAndTls() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, true);
  }

  @Test public void gzipAndConnectionReuseWithChunkedEncodingAndTls() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, true);
  }

  @Test public void clientConfiguredCustomContentEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABCDE")
        .addHeader("Content-Encoding: custom"));

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .header("Accept-Encoding", "custom")
        .build());
    assertEquals("ABCDE", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest request = server.takeRequest();
    assertEquals("custom", request.getHeader("Accept-Encoding"));
  }

  /**
   * Test a bug where gzip input streams weren't exhausting the input stream, which corrupted the
   * request that followed or prevented connection reuse. http://code.google.com/p/android/issues/detail?id=7059
   * http://code.google.com/p/android/issues/detail?id=38817
   */
  private void testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind transferKind,
      boolean tls) throws Exception {
    if (tls) {
      SSLSocketFactory socketFactory = handshakeCertificates.sslSocketFactory();
      RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
      server.useHttps(socketFactory, false);
      client = client.newBuilder()
          .sslSocketFactory(socketFactory, handshakeCertificates.trustManager())
          .hostnameVerifier(hostnameVerifier)
          .build();
    }

    MockResponse responseOne = new MockResponse()
        .addHeader("Content-Encoding: gzip");
    transferKind.setBody(responseOne, gzip("one (gzipped)"), 5);
    server.enqueue(responseOne);
    MockResponse responseTwo = new MockResponse();
    transferKind.setBody(responseTwo, "two (identity)", 5);
    server.enqueue(responseTwo);

    Response response1 = getResponse(new Request.Builder()
        .header("Accept-Encoding", "gzip")
        .url(server.url("/"))
        .build());
    InputStream gunzippedIn = new GZIPInputStream(response1.body().byteStream());
    assertEquals("one (gzipped)", readAscii(gunzippedIn, Integer.MAX_VALUE));
    assertEquals(0, server.takeRequest().getSequenceNumber());

    Response response2 = getResponse(new Request.Builder()
        .url(server.url("/"))
        .build());
    assertEquals("two (identity)", readAscii(response2.body().byteStream(), Integer.MAX_VALUE));
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void transparentGzipWorksAfterExceptionRecovery() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("a")
        .setSocketPolicy(SHUTDOWN_INPUT_AT_END));
    server.enqueue(new MockResponse()
        .addHeader("Content-Encoding: gzip")
        .setBody(gzip("b")));

    // Seed the pool with a bad connection.
    assertContent("a", getResponse(newRequest("/")));

    // Give the server time to disconnect.
    Thread.sleep(500);

    // This connection will need to be recovered. When it is, transparent gzip should still work!
    assertContent("b", getResponse(newRequest("/")));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber()); // Connection is not pooled.
  }

  @Test public void endOfStreamResponseIsNotPooled() throws Exception {
    client.connectionPool().evictAll();
    server.enqueue(new MockResponse()
        .setBody("{}")
        .clearHeaders()
        .setSocketPolicy(DISCONNECT_AT_END));

    Response response = getResponse(newRequest("/"));
    assertContent("{}", response);
    assertEquals(0, client.connectionPool().idleConnectionCount());
  }

  @Test public void earlyDisconnectDoesntHarmPoolingWithChunkedEncoding() throws Exception {
    testEarlyDisconnectDoesntHarmPooling(TransferKind.CHUNKED);
  }

  @Test public void earlyDisconnectDoesntHarmPoolingWithFixedLengthEncoding() throws Exception {
    testEarlyDisconnectDoesntHarmPooling(TransferKind.FIXED_LENGTH);
  }

  private void testEarlyDisconnectDoesntHarmPooling(TransferKind transferKind) throws Exception {
    MockResponse mockResponse1 = new MockResponse();
    transferKind.setBody(mockResponse1, "ABCDEFGHIJK", 1024);
    server.enqueue(mockResponse1);

    MockResponse mockResponse2 = new MockResponse();
    transferKind.setBody(mockResponse2, "LMNOPQRSTUV", 1024);
    server.enqueue(mockResponse2);

    Call call1 = client.newCall(newRequest("/"));
    Response response1 = call1.execute();
    InputStream in1 = response1.body().byteStream();
    assertEquals("ABCDE", readAscii(in1, 5));
    in1.close();
    call1.cancel();

    Call call2 = client.newCall(newRequest("/"));
    Response response2 = call2.execute();
    InputStream in2 = response2.body().byteStream();
    assertEquals("LMNOP", readAscii(in2, 5));
    in2.close();
    call2.cancel();

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection is pooled!
  }

  @Test public void streamDiscardingIsTimely() throws Exception {
    // This response takes at least a full second to serve: 10,000 bytes served 100 bytes at a time.
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[10000]))
        .throttleBody(100, 10, MILLISECONDS));
    server.enqueue(new MockResponse()
        .setBody("A"));

    long startNanos = System.nanoTime();
    Response connection1 = getResponse(newRequest("/"));
    InputStream in = connection1.body().byteStream();
    in.close();
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);

    // If we're working correctly, this should be greater than 100ms, but less than double that.
    // Previously we had a bug where we would download the entire response body as long as no
    // individual read took longer than 100ms.
    assertTrue(Util.format("Time to close: %sms", elapsedMillis), elapsedMillis < 500);

    // Do another request to confirm that the discarded connection was not pooled.
    assertContent("A", getResponse(newRequest("/")));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber()); // Connection is not pooled.
  }

  @Test public void setChunkedStreamingMode() throws Exception {
    server.enqueue(new MockResponse());

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(TransferKind.CHUNKED.newRequestBody("ABCDEFGHIJKLMNOPQ"))
        .build());
    assertEquals(200, response.code());

    RecordedRequest request = server.takeRequest();
    assertEquals("ABCDEFGHIJKLMNOPQ", request.getBody().readUtf8());
    assertEquals(Arrays.asList("ABCDEFGHIJKLMNOPQ".length()), request.getChunkSizes());
  }

  @Test public void authenticateWithFixedLengthStreaming() throws Exception {
    testAuthenticateWithStreamingPost(TransferKind.FIXED_LENGTH);
  }

  @Test public void authenticateWithChunkedStreaming() throws Exception {
    testAuthenticateWithStreamingPost(TransferKind.CHUNKED);
  }

  private void testAuthenticateWithStreamingPost(TransferKind streamingMode) throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate."));
    server.enqueue(new MockResponse()
        .setBody("Authenticated!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(streamingMode.newRequestBody("ABCD"))
        .build();
    Response response = getResponse(request);
    assertEquals(200, response.code());
    assertContent("Authenticated!", response);

    // No authorization header for the request...
    RecordedRequest recordedRequest = server.takeRequest();
    assertNull(recordedRequest.getHeader("Authorization"));
    assertEquals("ABCD", recordedRequest.getBody().readUtf8());
  }

  @Test public void postBodyRetransmittedAfterAuthorizationFail() throws Exception {
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

  @Test public void postEmptyBodyRetransmittedAfterAuthorizationFail_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    postBodyRetransmittedAfterAuthorizationFail("");
  }

  private void postBodyRetransmittedAfterAuthorizationFail(String body) throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse());

    String credential = Credentials.basic("jesse", "secret");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, null))
        .build();

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(null, body))
        .build());
    assertEquals(200, response.code());
    response.body().byteStream().close();

    RecordedRequest recordedRequest1 = server.takeRequest();
    assertEquals("POST", recordedRequest1.getMethod());
    assertEquals(body, recordedRequest1.getBody().readUtf8());
    assertNull(recordedRequest1.getHeader("Authorization"));

    RecordedRequest recordedRequest2 = server.takeRequest();
    assertEquals("POST", recordedRequest2.getMethod());
    assertEquals(body, recordedRequest2.getBody().readUtf8());
    assertEquals(credential, recordedRequest2.getHeader("Authorization"));
  }

  @Test public void nonStandardAuthenticationScheme() throws Exception {
    List<String> calls = authCallsForHeader("WWW-Authenticate: Foo");
    assertEquals(Collections.<String>emptyList(), calls);
  }

  @Test public void nonStandardAuthenticationSchemeWithRealm() throws Exception {
    List<String> calls = authCallsForHeader("WWW-Authenticate: Foo realm=\"Bar\"");
    assertEquals(0, calls.size());
  }

  // Digest auth is currently unsupported. Test that digest requests should fail reasonably.
  // http://code.google.com/p/android/issues/detail?id=11140
  @Test public void digestAuthentication() throws Exception {
    List<String> calls = authCallsForHeader("WWW-Authenticate: Digest "
        + "realm=\"testrealm@host.com\", qop=\"auth,auth-int\", "
        + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
        + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
    assertEquals(0, calls.size());
  }

  @Test public void allAttributesSetInServerAuthenticationCallbacks() throws Exception {
    List<String> calls = authCallsForHeader("WWW-Authenticate: Basic realm=\"Bar\"");
    assertEquals(1, calls.size());
    URL url = server.url("/").url();
    String call = calls.get(0);
    assertTrue(call, call.contains("host=" + url.getHost()));
    assertTrue(call, call.contains("port=" + url.getPort()));
    assertTrue(call, call.contains("site=" + url.getHost()));
    assertTrue(call, call.contains("url=" + url));
    assertTrue(call, call.contains("type=" + Authenticator.RequestorType.SERVER));
    assertTrue(call, call.contains("prompt=Bar"));
    assertTrue(call, call.contains("protocol=http"));
    assertTrue(call, call.toLowerCase(US).contains("scheme=basic")); // lowercase for the RI.
  }

  @Test public void allAttributesSetInProxyAuthenticationCallbacks() throws Exception {
    List<String> calls = authCallsForHeader("Proxy-Authenticate: Basic realm=\"Bar\"");
    assertEquals(1, calls.size());
    URL url = server.url("/").url();
    String call = calls.get(0);
    assertTrue(call, call.contains("host=" + url.getHost()));
    assertTrue(call, call.contains("port=" + url.getPort()));
    assertTrue(call, call.contains("site=" + url.getHost()));
    assertTrue(call, call.contains("url=http://android.com"));
    assertTrue(call, call.contains("type=" + Authenticator.RequestorType.PROXY));
    assertTrue(call, call.contains("prompt=Bar"));
    assertTrue(call, call.contains("protocol=http"));
    assertTrue(call, call.toLowerCase(US).contains("scheme=basic")); // lowercase for the RI.
  }

  private List<String> authCallsForHeader(String authHeader) throws IOException {
    boolean proxy = authHeader.startsWith("Proxy-");
    int responseCode = proxy ? 407 : 401;
    RecordingAuthenticator authenticator = new RecordingAuthenticator(null);
    Authenticator.setDefault(authenticator);
    server.enqueue(new MockResponse()
        .setResponseCode(responseCode)
        .addHeader(authHeader)
        .setBody("Please authenticate."));

    Response response;
    if (proxy) {
      client = client.newBuilder()
          .proxy(server.toProxyAddress())
          .proxyAuthenticator(new JavaNetAuthenticator())
          .build();
      response = getResponse(newRequest(HttpUrl.get("http://android.com/")));
    } else {
      client = client.newBuilder()
          .authenticator(new JavaNetAuthenticator())
          .build();
      response = getResponse(newRequest("/"));
    }
    assertEquals(responseCode, response.code());
    response.body().byteStream().close();
    return authenticator.calls;
  }

  @Test public void setValidRequestMethod() {
    assertMethodForbidsRequestBody("GET");
    assertMethodPermitsRequestBody("DELETE");
    assertMethodForbidsRequestBody("HEAD");
    assertMethodPermitsRequestBody("OPTIONS");
    assertMethodPermitsRequestBody("POST");
    assertMethodPermitsRequestBody("PUT");
    assertMethodPermitsRequestBody("TRACE");
    assertMethodPermitsRequestBody("PATCH");

    assertMethodPermitsNoRequestBody("GET");
    assertMethodPermitsNoRequestBody("DELETE");
    assertMethodPermitsNoRequestBody("HEAD");
    assertMethodPermitsNoRequestBody("OPTIONS");
    assertMethodForbidsNoRequestBody("POST");
    assertMethodForbidsNoRequestBody("PUT");
    assertMethodPermitsNoRequestBody("TRACE");
    assertMethodForbidsNoRequestBody("PATCH");
  }

  private void assertMethodPermitsRequestBody(String requestMethod) {
    Request request = new Request.Builder()
        .url(server.url("/"))
        .method(requestMethod, RequestBody.create(null, "abc"))
        .build();
    assertEquals(requestMethod, request.method());
  }

  private void assertMethodForbidsRequestBody(String requestMethod) {
    try {
      new Request.Builder()
          .url(server.url("/"))
          .method(requestMethod, RequestBody.create(null, "abc"))
          .build();
    } catch (IllegalArgumentException expected) {
    }
  }

  private void assertMethodPermitsNoRequestBody(String requestMethod) {
    Request request = new Request.Builder()
        .url(server.url("/"))
        .method(requestMethod, null)
        .build();
    assertEquals(requestMethod, request.method());
  }

  private void assertMethodForbidsNoRequestBody(String requestMethod) {
    try {
      new Request.Builder()
          .url(server.url("/"))
          .method(requestMethod, null)
          .build();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setInvalidRequestMethodLowercase() throws Exception {
    assertValidRequestMethod("get");
  }

  @Test public void setInvalidRequestMethodConnect() throws Exception {
    assertValidRequestMethod("CONNECT");
  }

  private void assertValidRequestMethod(String requestMethod) throws Exception {
    server.enqueue(new MockResponse());
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .method(requestMethod, null)
        .build());
    assertEquals(200, response.code());
    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(requestMethod, recordedRequest.getMethod());
  }

  @Test public void shoutcast() throws Exception {
    server.enqueue(new MockResponse()
        .setStatus("ICY 200 OK")
        .addHeader("Accept-Ranges: none")
        .addHeader("Content-Type: audio/mpeg")
        .addHeader("icy-br:128")
        .addHeader("ice-audio-info: bitrate=128;samplerate=44100;channels=2")
        .addHeader("icy-br:128")
        .addHeader("icy-description:Rock")
        .addHeader("icy-genre:riders")
        .addHeader("icy-name:A2RRock")
        .addHeader("icy-pub:1")
        .addHeader("icy-url:http://www.A2Rradio.com")
        .addHeader("Server: Icecast 2.3.3-kh8")
        .addHeader("Cache-Control: no-cache")
        .addHeader("Pragma: no-cache")
        .addHeader("Expires: Mon, 26 Jul 1997 05:00:00 GMT")
        .addHeader("icy-metaint:16000")
        .setBody("mp3 data"));
    Response response = getResponse(newRequest("/"));
    assertEquals(200, response.code());
    assertEquals("OK", response.message());
    assertContent("mp3 data", response);
  }

  @Test public void secureFixedLengthStreaming() throws Exception {
    testSecureStreamingPost(TransferKind.FIXED_LENGTH);
  }

  @Test public void secureChunkedStreaming() throws Exception {
    testSecureStreamingPost(TransferKind.CHUNKED);
  }

  /**
   * Users have reported problems using HTTPS with streaming request bodies.
   * http://code.google.com/p/android/issues/detail?id=12860
   */
  private void testSecureStreamingPost(TransferKind streamingMode) throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("Success!"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(streamingMode.newRequestBody("ABCD"))
        .build());
    assertEquals("Success!", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest request = server.takeRequest();
    assertEquals("POST / HTTP/1.1", request.getRequestLine());
    if (streamingMode == TransferKind.FIXED_LENGTH) {
      assertEquals(Collections.<Integer>emptyList(), request.getChunkSizes());
    } else if (streamingMode == TransferKind.CHUNKED) {
      assertEquals(Arrays.asList(4), request.getChunkSizes());
    }
    assertEquals("ABCD", request.getBody().readUtf8());
  }

  @Test public void authenticateWithPost() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    // Fail auth three times...
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    // ...then succeed the fourth time.
    server.enqueue(new MockResponse()
        .setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(null, "ABCD"))
        .build());
    assertEquals("Successful auth!", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    // No authorization header for the first request...
    RecordedRequest request = server.takeRequest();
    assertNull(request.getHeader("Authorization"));

    // ...but the three requests that follow include an authorization header.
    for (int i = 0; i < 3; i++) {
      request = server.takeRequest();
      assertEquals("POST / HTTP/1.1", request.getRequestLine());
      assertEquals("Basic " + RecordingAuthenticator.BASE_64_CREDENTIALS,
          request.getHeader("Authorization"));
      assertEquals("ABCD", request.getBody().readUtf8());
    }
  }

  @Test public void authenticateWithGet() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    // Fail auth three times...
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    // ...then succeed the fourth time.
    server.enqueue(new MockResponse()
        .setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("Successful auth!", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    // No authorization header for the first request...
    RecordedRequest request = server.takeRequest();
    assertNull(request.getHeader("Authorization"));

    // ...but the three requests that follow requests include an authorization header.
    for (int i = 0; i < 3; i++) {
      request = server.takeRequest();
      assertEquals("GET / HTTP/1.1", request.getRequestLine());
      assertEquals("Basic " + RecordingAuthenticator.BASE_64_CREDENTIALS,
          request.getHeader("Authorization"));
    }
  }

  @Test public void authenticateWithCharset() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\", charset=\"UTF-8\"")
        .setBody("Please authenticate with UTF-8."));
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate with ISO-8859-1."));
    server.enqueue(new MockResponse()
        .setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator(
        new PasswordAuthentication("username", "mötorhead".toCharArray())));
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("Successful auth!", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    // No authorization header for the first request...
    RecordedRequest request1 = server.takeRequest();
    assertNull(request1.getHeader("Authorization"));

    // UTF-8 encoding for the first credential.
    RecordedRequest request2 = server.takeRequest();
    assertEquals("Basic dXNlcm5hbWU6bcO2dG9yaGVhZA==", request2.getHeader("Authorization"));

    // ISO-8859-1 encoding for the second credential.
    RecordedRequest request3 = server.takeRequest();
    assertEquals("Basic dXNlcm5hbWU6bfZ0b3JoZWFk", request3.getHeader("Authorization"));
  }

  /** https://code.google.com/p/android/issues/detail?id=74026 */
  @Test public void authenticateWithGetAndTransparentGzip() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    // Fail auth three times...
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    // ...then succeed the fourth time.
    MockResponse successfulResponse = new MockResponse()
        .addHeader("Content-Encoding", "gzip")
        .setBody(gzip("Successful auth!"));
    server.enqueue(successfulResponse);

    Authenticator.setDefault(new RecordingAuthenticator());
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("Successful auth!", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    // no authorization header for the first request...
    RecordedRequest request = server.takeRequest();
    assertNull(request.getHeader("Authorization"));

    // ...but the three requests that follow requests include an authorization header
    for (int i = 0; i < 3; i++) {
      request = server.takeRequest();
      assertEquals("GET / HTTP/1.1", request.getRequestLine());
      assertEquals("Basic " + RecordingAuthenticator.BASE_64_CREDENTIALS,
          request.getHeader("Authorization"));
    }
  }

  /** https://github.com/square/okhttp/issues/342 */
  @Test public void authenticateRealmUppercase() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("wWw-aUtHeNtIcAtE: bAsIc rEaLm=\"pRoTeCtEd aReA\"")
        .setBody("Please authenticate."));
    server.enqueue(new MockResponse()
        .setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("Successful auth!", readAscii(response.body().byteStream(), Integer.MAX_VALUE));
  }

  @Test public void redirectedWithChunkedEncoding() throws Exception {
    testRedirected(TransferKind.CHUNKED, true);
  }

  @Test public void redirectedWithContentLengthHeader() throws Exception {
    testRedirected(TransferKind.FIXED_LENGTH, true);
  }

  @Test public void redirectedWithNoLengthHeaders() throws Exception {
    testRedirected(TransferKind.END_OF_STREAM, false);
  }

  private void testRedirected(TransferKind transferKind, boolean reuse) throws Exception {
    MockResponse mockResponse = new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo");
    transferKind.setBody(mockResponse, "This page has moved!", 10);
    server.enqueue(mockResponse);
    server.enqueue(new MockResponse()
        .setBody("This is the new location!"));

    Response response = getResponse(newRequest("/"));
    assertEquals("This is the new location!",
        readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest first = server.takeRequest();
    assertEquals("GET / HTTP/1.1", first.getRequestLine());
    RecordedRequest retry = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", retry.getRequestLine());
    if (reuse) {
      assertEquals("Expected connection reuse", 1, retry.getSequenceNumber());
    }
  }

  @Test public void redirectedOnHttps() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse()
        .setBody("This is the new location!"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("This is the new location!",
        readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest first = server.takeRequest();
    assertEquals("GET / HTTP/1.1", first.getRequestLine());
    RecordedRequest retry = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", retry.getRequestLine());
    assertEquals("Expected connection reuse", 1, retry.getSequenceNumber());
  }

  @Test public void notRedirectedFromHttpsToHttp() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: http://anyhost/foo")
        .setBody("This page has moved!"));

    client = client.newBuilder()
        .followSslRedirects(false)
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("This page has moved!",
        readAscii(response.body().byteStream(), Integer.MAX_VALUE));
  }

  @Test public void notRedirectedFromHttpToHttps() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: https://anyhost/foo")
        .setBody("This page has moved!"));

    client = client.newBuilder()
        .followSslRedirects(false)
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals("This page has moved!",
        readAscii(response.body().byteStream(), Integer.MAX_VALUE));
  }

  @Test public void redirectedFromHttpsToHttpFollowingProtocolRedirects() throws Exception {
    server2.enqueue(new MockResponse()
        .setBody("This is insecure HTTP!"));

    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/").url())
        .setBody("This page has moved!"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .followSslRedirects(true)
        .build();
    Response response = getResponse(newRequest("/"));
    assertContent("This is insecure HTTP!", response);
    assertNull(response.handshake());
  }

  @Test public void redirectedFromHttpToHttpsFollowingProtocolRedirects() throws Exception {
    server2.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server2.enqueue(new MockResponse()
        .setBody("This is secure HTTPS!"));

    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/").url())
        .setBody("This page has moved!"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .followSslRedirects(true)
        .build();
    Response response = getResponse(newRequest("/"));
    assertContent("This is secure HTTPS!", response);
  }

  @Test public void redirectToAnotherOriginServer() throws Exception {
    redirectToAnotherOriginServer(false);
  }

  @Test public void redirectToAnotherOriginServerWithHttps() throws Exception {
    redirectToAnotherOriginServer(true);
  }

  private void redirectToAnotherOriginServer(boolean https) throws Exception {
    if (https) {
      server.useHttps(handshakeCertificates.sslSocketFactory(), false);
      server2.useHttps(handshakeCertificates.sslSocketFactory(), false);
      server2.setProtocolNegotiationEnabled(false);
      client = client.newBuilder()
          .sslSocketFactory(
              handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
          .hostnameVerifier(new RecordingHostnameVerifier())
          .build();
    }

    server2.enqueue(new MockResponse()
        .setBody("This is the 2nd server!"));
    server2.enqueue(new MockResponse()
        .setBody("This is the 2nd server, again!"));

    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/").url().toString())
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse()
        .setBody("This is the first server again!"));

    Response response = getResponse(newRequest("/"));
    assertContent("This is the 2nd server!", response);
    assertEquals(server2.url("/"), response.request().url());

    // make sure the first server was careful to recycle the connection
    assertContent("This is the first server again!", getResponse(newRequest(server.url("/"))));
    assertContent("This is the 2nd server, again!", getResponse(newRequest(server2.url("/"))));

    String server1Host = server.getHostName() + ":" + server.getPort();
    String server2Host = server2.getHostName() + ":" + server2.getPort();
    assertEquals(server1Host, server.takeRequest().getHeader("Host"));
    assertEquals(server2Host, server2.takeRequest().getHeader("Host"));
    assertEquals("Expected connection reuse", 1, server.takeRequest().getSequenceNumber());
    assertEquals("Expected connection reuse", 1, server2.takeRequest().getSequenceNumber());
  }

  @Test public void redirectWithProxySelector() throws Exception {
    final List<URI> proxySelectionRequests = new ArrayList<>();
    client = client.newBuilder()
        .proxySelector(new ProxySelector() {
          @Override public List<Proxy> select(URI uri) {
            proxySelectionRequests.add(uri);
            MockWebServer proxyServer = (uri.getPort() == server.getPort())
                ? server
                : server2;
            return Arrays.asList(proxyServer.toProxyAddress());
          }

          @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            throw new AssertionError();
          }
        })
        .build();

    server2.enqueue(new MockResponse()
        .setBody("This is the 2nd server!"));

    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/b").toString())
        .setBody("This page has moved!"));

    assertContent("This is the 2nd server!", getResponse(newRequest("/a")));

    assertEquals(Arrays.asList(server.url("/").url().toURI(), server2.url("/").url().toURI()),
        proxySelectionRequests);
  }

  @Test public void redirectWithAuthentication() throws Exception {
    server2.enqueue(new MockResponse()
        .setBody("Page 2"));

    server.enqueue(new MockResponse()
        .setResponseCode(401));
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: " + server2.url("/b")));

    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(Credentials.basic("jesse", "secret"), null))
        .build();
    assertContent("Page 2", getResponse(newRequest("/a")));

    RecordedRequest redirectRequest = server2.takeRequest();
    assertNull(redirectRequest.getHeader("Authorization"));
    assertEquals("/b", redirectRequest.getPath());
  }

  @Test public void response300MultipleChoiceWithPost() throws Exception {
    // Chrome doesn't follow the redirect, but Firefox and the RI both do
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MULT_CHOICE, TransferKind.END_OF_STREAM);
  }

  @Test public void response301MovedPermanentlyWithPost() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_PERM, TransferKind.END_OF_STREAM);
  }

  @Test public void response302MovedTemporarilyWithPost() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_TEMP, TransferKind.END_OF_STREAM);
  }

  @Test public void response303SeeOtherWithPost() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_SEE_OTHER, TransferKind.END_OF_STREAM);
  }

  @Test public void postRedirectToGetWithChunkedRequest() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_TEMP, TransferKind.CHUNKED);
  }

  @Test public void postRedirectToGetWithStreamedRequest() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_TEMP, TransferKind.FIXED_LENGTH);
  }

  private void testResponseRedirectedWithPost(int redirectCode, TransferKind transferKind)
      throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(redirectCode)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse()
        .setBody("Page 2"));

    Response response = getResponse(new Request.Builder()
        .url(server.url("/page1"))
        .post(transferKind.newRequestBody("ABCD"))
        .build());
    assertEquals("Page 2", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest page1 = server.takeRequest();
    assertEquals("POST /page1 HTTP/1.1", page1.getRequestLine());
    assertEquals("ABCD", page1.getBody().readUtf8());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void redirectedPostStripsRequestBodyHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2"));
    server.enqueue(new MockResponse()
        .setBody("Page 2"));

    Response response = getResponse(new Request.Builder()
        .url(server.url("/page1"))
        .post(RequestBody.create(MediaType.get("text/plain; charset=utf-8"), "ABCD"))
        .header("Transfer-Encoding", "identity")
        .build());
    assertEquals("Page 2", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    assertEquals("POST /page1 HTTP/1.1", server.takeRequest().getRequestLine());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
    assertNull(page2.getHeader("Content-Length"));
    assertNull(page2.getHeader("Content-Type"));
    assertNull(page2.getHeader("Transfer-Encoding"));
  }

  @Test public void response305UseProxy() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_USE_PROXY)
        .addHeader("Location: " + server.url("/").url())
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse()
        .setBody("Proxy Response"));

    Response response = getResponse(newRequest("/foo"));
    // Fails on the RI, which gets "Proxy Response".
    assertEquals("This page has moved!",
        readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest page1 = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", page1.getRequestLine());
    assertEquals(1, server.getRequestCount());
  }

  @Test public void response307WithGet() throws Exception {
    testRedirect(true, "GET");
  }

  @Test public void response307WithHead() throws Exception {
    testRedirect(true, "HEAD");
  }

  @Test public void response307WithOptions() throws Exception {
    testRedirect(true, "OPTIONS");
  }

  @Test public void response307WithPost() throws Exception {
    testRedirect(true, "POST");
  }

  @Test public void response308WithGet() throws Exception {
    testRedirect(false, "GET");
  }

  @Test public void response308WithHead() throws Exception {
    testRedirect(false, "HEAD");
  }

  @Test public void response308WithOptions() throws Exception {
    testRedirect(false, "OPTIONS");
  }

  @Test public void response308WithPost() throws Exception {
    testRedirect(false, "POST");
  }

  private void testRedirect(boolean temporary, String method) throws Exception {
    MockResponse response1 = new MockResponse()
        .setResponseCode(temporary ? HTTP_TEMP_REDIRECT : HTTP_PERM_REDIRECT)
        .addHeader("Location: /page2");
    if (!method.equals("HEAD")) {
      response1.setBody("This page has moved!");
    }
    server.enqueue(response1);
    server.enqueue(new MockResponse()
        .setBody("Page 2"));

    Request.Builder requestBuilder = new Request.Builder()
        .url(server.url("/page1"));
    if (method.equals("POST")) {
      requestBuilder.post(RequestBody.create(null, "ABCD"));
    } else {
      requestBuilder.method(method, null);
    }

    Response response = getResponse(requestBuilder.build());
    String responseString = readAscii(response.body().byteStream(), Integer.MAX_VALUE);

    RecordedRequest page1 = server.takeRequest();
    assertEquals(method + " /page1 HTTP/1.1", page1.getRequestLine());

    if (method.equals("GET")) {
      assertEquals("Page 2", responseString);
    } else if (method.equals("HEAD")) {
      assertEquals("", responseString);
    } else {
      // Methods other than GET/HEAD shouldn't follow the redirect.
      if (method.equals("POST")) {
        assertEquals("ABCD", page1.getBody().readUtf8());
      }
      assertEquals(1, server.getRequestCount());
      assertEquals("This page has moved!", responseString);
      return;
    }

    // GET/HEAD requests should have followed the redirect with the same method.
    assertEquals(2, server.getRequestCount());
    RecordedRequest page2 = server.takeRequest();
    assertEquals(method + " /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void follow20Redirects() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse()
        .setBody("Success!"));

    Response response = getResponse(newRequest("/0"));
    assertContent("Success!", response);
    assertEquals(server.url("/20"), response.request().url());
  }

  @Test public void doesNotFollow21Redirects() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }

    try {
      getResponse(newRequest("/0"));
      fail();
    } catch (ProtocolException expected) {
      assertEquals("Too many follow-up requests: 21", expected.getMessage());
    }
  }

  @Test public void httpsWithCustomTrustManager() throws Exception {
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
    RecordingTrustManager trustManager =
        new RecordingTrustManager(handshakeCertificates.trustManager());
    SSLContext sslContext = Platform.get().getSSLContext();
    sslContext.init(null, new TrustManager[] {trustManager}, null);

    client = client.newBuilder()
        .hostnameVerifier(hostnameVerifier)
        .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));
    server.enqueue(new MockResponse()
        .setBody("GHI"));

    assertContent("ABC", getResponse(newRequest("/")));
    assertContent("DEF", getResponse(newRequest("/")));
    assertContent("GHI", getResponse(newRequest("/")));

    assertEquals(Arrays.asList("verify " + server.getHostName()), hostnameVerifier.calls);
    assertEquals(Arrays.asList("checkServerTrusted [CN=localhost 1]"), trustManager.calls);
  }

  @Test public void getClientRequestTimeout() throws Exception {
    enqueueClientRequestTimeoutResponses();

    Response response = getResponse(newRequest("/"));

    assertEquals(200, response.code());
    assertEquals("Body", readAscii(response.body().byteStream(), Integer.MAX_VALUE));
  }

  private void enqueueClientRequestTimeoutResponses() {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(HttpURLConnection.HTTP_CLIENT_TIMEOUT)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse()
        .setBody("Body"));
  }

  @Test public void bufferedBodyWithClientRequestTimeout() throws Exception {
    enqueueClientRequestTimeoutResponses();

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(null, "Hello"))
        .build());

    assertEquals(200, response.code());
    assertEquals("Body", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    RecordedRequest request1 = server.takeRequest();
    assertEquals("Hello", request1.getBody().readUtf8());

    RecordedRequest request2 = server.takeRequest();
    assertEquals("Hello", request2.getBody().readUtf8());
  }

  @Test public void streamedBodyWithClientRequestTimeout() throws Exception {
    enqueueClientRequestTimeoutResponses();

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(TransferKind.CHUNKED.newRequestBody("Hello"))
        .build());

    assertEquals(200, response.code());
    assertContent("Body", response);
    response.close();
    assertEquals(2, server.getRequestCount());
  }

  @Test public void readTimeouts() throws IOException {
    // This relies on the fact that MockWebServer doesn't close the
    // connection after a response has been sent. This causes the client to
    // try to read more bytes than are sent, which results in a timeout.
    server.enqueue(new MockResponse()
        .setBody("ABC")
        .clearHeaders()
        .addHeader("Content-Length: 4"));
    server.enqueue(new MockResponse()
        .setBody("unused")); // to keep the server alive

    Response response = getResponse(newRequest("/"));
    BufferedSource in = response.body().source();
    in.timeout().timeout(1000, MILLISECONDS);
    assertEquals('A', in.readByte());
    assertEquals('B', in.readByte());
    assertEquals('C', in.readByte());
    try {
      in.readByte(); // If Content-Length was accurate, this would return -1 immediately.
      fail();
    } catch (SocketTimeoutException expected) {
    }
    in.close();
  }

  /** Confirm that an unacknowledged write times out. */
  @Test public void writeTimeouts() throws IOException {
    MockWebServer server = new MockWebServer();
    // Sockets on some platforms can have large buffers that mean writes do not block when
    // required. These socket factories explicitly set the buffer sizes on sockets created.
    final int SOCKET_BUFFER_SIZE = 4 * 1024;
    server.setServerSocketFactory(
        new DelegatingServerSocketFactory(ServerSocketFactory.getDefault()) {
          @Override
          protected ServerSocket configureServerSocket(ServerSocket serverSocket)
              throws IOException {
            serverSocket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
            return serverSocket;
          }
        });
    client = client.newBuilder()
        .socketFactory(new DelegatingSocketFactory(SocketFactory.getDefault()) {
          @Override protected Socket configureSocket(Socket socket) throws IOException {
            socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
            socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
            return socket;
          }
        })
        .writeTimeout(500, TimeUnit.MILLISECONDS)
        .build();

    server.start();
    server.enqueue(new MockResponse()
        .throttleBody(1, 1, TimeUnit.SECONDS)); // Prevent the server from reading!

    Request request = new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            byte[] data = new byte[2 * 1024 * 1024]; // 2 MiB.
            sink.write(data);
          }
        })
        .build();
    try {
      getResponse(request);
      fail();
    } catch (SocketTimeoutException expected) {
    }
  }

  @Test public void setChunkedEncodingAsRequestProperty() throws Exception {
    server.enqueue(new MockResponse());

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .header("Transfer-encoding", "chunked")
        .post(TransferKind.CHUNKED.newRequestBody("ABC"))
        .build());
    assertEquals(200, response.code());

    RecordedRequest request = server.takeRequest();
    assertEquals("ABC", request.getBody().readUtf8());
  }

  @Test public void connectionCloseInRequest() throws Exception {
    server.enqueue(new MockResponse()); // Server doesn't honor the connection: close header!
    server.enqueue(new MockResponse());

    Response a = getResponse(new Request.Builder()
        .url(server.url("/"))
        .header("Connection", "close")
        .build());
    assertEquals(200, a.code());

    Response b = getResponse(newRequest("/"));
    assertEquals(200, b.code());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals("When connection: close is used, each request should get its own connection", 0,
        server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionCloseInResponse() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Connection: close"));
    server.enqueue(new MockResponse());

    Response a = getResponse(newRequest("/"));
    assertEquals(200, a.code());

    Response b = getResponse(newRequest("/"));
    assertEquals(200, b.code());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals("When connection: close is used, each request should get its own connection", 0,
        server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionCloseWithRedirect() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .addHeader("Connection: close"));
    server.enqueue(new MockResponse()
        .setBody("This is the new location!"));

    Response response = getResponse(newRequest("/"));
    assertEquals("This is the new location!",
        readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals("When connection: close is used, each request should get its own connection", 0,
        server.takeRequest().getSequenceNumber());
  }

  /**
   * Retry redirects if the socket is closed.
   * https://code.google.com/p/android/issues/detail?id=41576
   */
  @Test public void sameConnectionRedirectAndReuse() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .setSocketPolicy(SHUTDOWN_INPUT_AT_END)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse()
        .setBody("This is the new page!"));

    assertContent("This is the new page!", getResponse(newRequest("/")));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void responseCodeDisagreesWithHeaders() {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NO_CONTENT)
        .setBody("This body is not allowed!"));

    try {
      getResponse(newRequest("/"));
      fail();
    } catch (IOException expected) {
      assertEquals("HTTP 204 had non-zero Content-Length: 25", expected.getMessage());
    }
  }

  @Test public void singleByteReadIsSigned() throws IOException {
    server.enqueue(new MockResponse()
        .setBody(new Buffer()
            .writeByte(-2)
            .writeByte(-1)));

    Response response = getResponse(newRequest("/"));
    InputStream in = response.body().byteStream();
    assertEquals(254, in.read());
    assertEquals(255, in.read());
    assertEquals(-1, in.read());
  }

  @Test public void flushAfterStreamTransmittedWithChunkedEncoding() throws IOException {
    testFlushAfterStreamTransmitted(TransferKind.CHUNKED);
  }

  @Test public void flushAfterStreamTransmittedWithFixedLength() throws IOException {
    testFlushAfterStreamTransmitted(TransferKind.FIXED_LENGTH);
  }

  @Test public void flushAfterStreamTransmittedWithNoLengthHeaders() throws IOException {
    testFlushAfterStreamTransmitted(TransferKind.END_OF_STREAM);
  }

  /**
   * We explicitly permit apps to close the upload stream even after it has been transmitted.  We
   * also permit flush so that buffered streams can do a no-op flush when they are closed.
   * http://b/3038470
   */
  private void testFlushAfterStreamTransmitted(TransferKind transferKind) throws IOException {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    AtomicReference<BufferedSink> sinkReference = new AtomicReference<>();
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(new ForwardingRequestBody(transferKind.newRequestBody("def")) {
          @Override public void writeTo(BufferedSink sink) throws IOException {
            sinkReference.set(sink);
            super.writeTo(sink);
          }
        })
        .build());

    assertEquals("abc", readAscii(response.body().byteStream(), Integer.MAX_VALUE));

    try {
      sinkReference.get().flush();
    } catch (IllegalStateException expected) {
    }
    try {
      sinkReference.get().write("ghi".getBytes(UTF_8));
      sinkReference.get().emit();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void getHeadersThrows() {
    server.enqueue(new MockResponse()
        .setSocketPolicy(DISCONNECT_AT_START));

    try {
      getResponse(newRequest("/"));
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void dnsFailureThrowsIOException() {
    client = client.newBuilder()
        .dns(new FakeDns())
        .build();
    try {
      getResponse(newRequest(HttpUrl.get("http://host.unlikelytld")));
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void malformedUrlThrowsUnknownHostException() throws IOException {
    try {
      getResponse(newRequest(HttpUrl.get("http://./foo.html")));
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void getKeepAlive() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    // The request should work once and then fail.
    Response connection1 = getResponse(newRequest("/"));
    BufferedSource source1 = connection1.body().source();
    source1.timeout().timeout(100, TimeUnit.MILLISECONDS);
    assertEquals("ABC", readAscii(source1.inputStream(), Integer.MAX_VALUE));
    server.shutdown();
    try {
      getResponse(newRequest("/"));
      fail();
    } catch (ConnectException expected) {
    }
  }

  /** http://code.google.com/p/android/issues/detail?id=14562 */
  @Test public void readAfterLastByte() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC")
        .clearHeaders()
        .addHeader("Connection: close")
        .setSocketPolicy(DISCONNECT_AT_END));

    Response response = getResponse(newRequest("/"));
    InputStream in = response.body().byteStream();
    assertEquals("ABC", readAscii(in, 3));
    assertEquals(-1, in.read());
    assertEquals(-1, in.read()); // throws IOException in Gingerbread.
  }

  @Test public void getOutputStreamOnGetFails() {
    try {
      new Request.Builder()
          .url(server.url("/"))
          .method("GET", RequestBody.create(null, "abc"))
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void clientSendsContentLength() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A"));
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(null, "ABC"))
        .build());
    assertEquals("A", readAscii(response.body().byteStream(), Integer.MAX_VALUE));
    RecordedRequest request = server.takeRequest();
    assertEquals("3", request.getHeader("Content-Length"));
    response.body().close();
  }

  @Test public void getContentLengthConnects() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("ABC"));
    Response response = getResponse(newRequest("/"));
    assertEquals(3L, response.body().contentLength());
    response.body().close();
  }

  @Test public void getContentTypeConnects() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Content-Type: text/plain")
        .setBody("ABC"));
    Response response = getResponse(newRequest("/"));
    assertEquals(MediaType.get("text/plain"), response.body().contentType());
    response.body().close();
  }

  @Test public void getContentEncodingConnects() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Content-Encoding: identity")
        .setBody("ABC"));
    Response response = getResponse(newRequest("/"));
    assertEquals("identity", response.header("Content-Encoding"));
    response.body().close();
  }

  @Test public void urlContainsQueryButNoPath() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A"));

    HttpUrl url = server.url("?query");
    Response response = getResponse(newRequest(url));
    assertEquals("A", readAscii(response.body().byteStream(), Integer.MAX_VALUE));
    RecordedRequest request = server.takeRequest();
    assertEquals("GET /?query HTTP/1.1", request.getRequestLine());
  }

  @Test public void doOutputForMethodThatDoesntSupportOutput() {
    try {
      new Request.Builder()
          .url(server.url("/"))
          .method("HEAD", RequestBody.create(null, ""))
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  // http://code.google.com/p/android/issues/detail?id=20442
  @Test public void inputStreamAvailableWithChunkedEncoding() throws Exception {
    testInputStreamAvailable(TransferKind.CHUNKED);
  }

  @Test public void inputStreamAvailableWithContentLengthHeader() throws Exception {
    testInputStreamAvailable(TransferKind.FIXED_LENGTH);
  }

  @Test public void inputStreamAvailableWithNoLengthHeaders() throws Exception {
    testInputStreamAvailable(TransferKind.END_OF_STREAM);
  }

  private void testInputStreamAvailable(TransferKind transferKind) throws IOException {
    String body = "ABCDEFGH";
    MockResponse mockResponse = new MockResponse();
    transferKind.setBody(mockResponse, body, 4);
    server.enqueue(mockResponse);
    Response response = getResponse(newRequest("/"));
    InputStream in = response.body().byteStream();
    for (int i = 0; i < body.length(); i++) {
      assertTrue(in.available() >= 0);
      assertEquals(body.charAt(i), in.read());
    }
    assertEquals(0, in.available());
    assertEquals(-1, in.read());
  }

  @Test public void postFailsWithBufferedRequestForSmallRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.END_OF_STREAM, 1024);
  }

  @Test public void postFailsWithBufferedRequestForLargeRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.END_OF_STREAM, 16384);
  }

  @Test public void postFailsWithChunkedRequestForSmallRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.CHUNKED, 1024);
  }

  @Test public void postFailsWithChunkedRequestForLargeRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.CHUNKED, 16384);
  }

  @Test public void postFailsWithFixedLengthRequestForSmallRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.FIXED_LENGTH, 1024);
  }

  @Test public void postFailsWithFixedLengthRequestForLargeRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.FIXED_LENGTH, 16384);
  }

  private void reusedConnectionFailsWithPost(TransferKind transferKind, int requestSize)
      throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A")
        .setSocketPolicy(DISCONNECT_AT_END));
    server.enqueue(new MockResponse()
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setBody("C"));

    assertContent("A", getResponse(newRequest("/a")));

    // Give the server time to disconnect.
    Thread.sleep(500);

    // If the request body is larger than OkHttp's replay buffer, the failure may still occur.
    char[] requestBodyChars = new char[requestSize];
    Arrays.fill(requestBodyChars, 'x');
    String requestBody = new String(requestBodyChars);

    for (int j = 0; j < 2; j++) {
      try {
        Response response = getResponse(new Request.Builder()
            .url(server.url("/b"))
            .post(transferKind.newRequestBody(requestBody))
            .build());
        assertContent("B", response);
        break;
      } catch (IOException socketException) {
        // If there's a socket exception, this must have a streamed request body.
        assertEquals(0, j);
        assertTrue(transferKind == TransferKind.CHUNKED
            || transferKind == TransferKind.FIXED_LENGTH);
      }
    }

    RecordedRequest requestA = server.takeRequest();
    assertEquals("/a", requestA.getPath());
    RecordedRequest requestB = server.takeRequest();
    assertEquals("/b", requestB.getPath());
    assertEquals(requestBody, requestB.getBody().readUtf8());
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setSocketPolicy(DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse()
        .setBody("def"));

    // Seed the connection pool so we have something that can fail.
    assertContent("abc", getResponse(newRequest("/")));

    Response post = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(null, "body!"))
        .build());
    assertContent("def", post);

    RecordedRequest get = server.takeRequest();
    assertEquals(0, get.getSequenceNumber());

    RecordedRequest post1 = server.takeRequest();
    assertEquals("body!", post1.getBody().readUtf8());
    assertEquals(1, post1.getSequenceNumber());

    RecordedRequest post2 = server.takeRequest();
    assertEquals("body!", post2.getBody().readUtf8());
    assertEquals(0, post2.getSequenceNumber());
  }

  @Test public void fullyBufferedPostIsTooShort() {
    server.enqueue(new MockResponse()
        .setBody("A"));

    RequestBody requestBody = new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 4L;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("abc");
      }
    };
    try {
      getResponse(new Request.Builder()
          .url(server.url("/b"))
          .post(requestBody)
          .build());
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void fullyBufferedPostIsTooLong() {
    server.enqueue(new MockResponse()
        .setBody("A"));

    RequestBody requestBody = new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 3L;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("abcd");
      }
    };
    try {
      getResponse(new Request.Builder()
          .url(server.url("/b"))
          .post(requestBody)
          .build());
      fail();
    } catch (IOException expected) {
    }
  }

  @Test @Ignore public void testPooledConnectionsDetectHttp10() {
    // TODO: write a test that shows pooled connections detect HTTP/1.0 (vs. HTTP/1.1)
    fail("TODO");
  }

  @Test @Ignore public void postBodiesRetransmittedOnAuthProblems() {
    fail("TODO");
  }

  @Test @Ignore public void cookiesAndTrailers() {
    // Do cookie headers get processed too many times?
    fail("TODO");
  }

  @Test public void emptyRequestHeaderValueIsAllowed() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("body"));
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .header("B", "")
        .build());
    assertContent("body", response);
    assertEquals("", response.request().header("B"));
  }

  @Test public void emptyResponseHeaderValueIsAllowed() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("A:")
        .setBody("body"));
    Response response = getResponse(newRequest("/"));
    assertContent("body", response);
    assertEquals("", response.header("A"));
  }

  @Test public void emptyRequestHeaderNameIsStrict() {
    try {
      new Request.Builder()
          .url(server.url("/"))
          .header("", "A")
          .build();
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void emptyResponseHeaderNameIsLenient() throws Exception {
    Headers.Builder headers = new Headers.Builder();
    Internal.instance.addLenient(headers, ":A");
    server.enqueue(new MockResponse()
        .setHeaders(headers.build())
        .setBody("body"));
    Response response = getResponse(newRequest("/"));
    assertEquals(200, response.code());
    assertEquals("A", response.header(""));
    response.body().close();
  }

  @Test public void requestHeaderValidationIsStrict() {
    try {
      new Request.Builder()
          .addHeader("a\tb", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new Request.Builder()
          .addHeader("Name", "c\u007fd");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new Request.Builder()
          .addHeader("", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new Request.Builder()
          .addHeader("\ud83c\udf69", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new Request.Builder()
          .addHeader("Name", "\u2615\ufe0f");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void responseHeaderParsingIsLenient() throws Exception {
    Headers headers = new Headers.Builder()
        .add("Content-Length", "0")
        .addLenient("a\tb: c\u007fd")
        .addLenient(": ef")
        .addLenient("\ud83c\udf69: \u2615\ufe0f")
        .build();
    server.enqueue(new MockResponse()
        .setHeaders(headers));

    Response response = getResponse(newRequest("/"));
    assertEquals(200, response.code());
    assertEquals("c\u007fd", response.header("a\tb"));
    assertEquals("\u2615\ufe0f", response.header("\ud83c\udf69"));
    assertEquals("ef", response.header(""));
  }

  @Test @Ignore public void deflateCompression() {
    fail("TODO");
  }

  @Test @Ignore public void postBodiesRetransmittedOnIpAddressProblems() {
    fail("TODO");
  }

  @Test @Ignore public void pooledConnectionProblemsNotReportedToProxySelector() {
    fail("TODO");
  }

  @Test public void customBasicAuthenticator() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate."));
    server.enqueue(new MockResponse()
        .setBody("A"));

    String credential = Credentials.basic("jesse", "peanutbutter");
    RecordingOkAuthenticator authenticator = new RecordingOkAuthenticator(credential, null);
    client = client.newBuilder()
        .authenticator(authenticator)
        .build();
    assertContent("A", getResponse(newRequest("/private")));

    assertNull(server.takeRequest().getHeader("Authorization"));
    assertEquals(credential, server.takeRequest().getHeader("Authorization"));

    assertEquals(Proxy.NO_PROXY, authenticator.onlyRoute().proxy());
    Response response = authenticator.onlyResponse();
    assertEquals("/private", response.request().url().url().getPath());
    assertEquals(Arrays.asList(new Challenge("Basic", "protected area")), response.challenges());
  }

  @Test public void customTokenAuthenticator() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Bearer realm=\"oauthed\"")
        .setBody("Please authenticate."));
    server.enqueue(new MockResponse()
        .setBody("A"));

    RecordingOkAuthenticator authenticator
        = new RecordingOkAuthenticator("oauthed abc123", "Bearer");
    client = client.newBuilder()
        .authenticator(authenticator)
        .build();
    assertContent("A", getResponse(newRequest("/private")));

    assertNull(server.takeRequest().getHeader("Authorization"));
    assertEquals("oauthed abc123", server.takeRequest().getHeader("Authorization"));

    Response response = authenticator.onlyResponse();
    assertEquals("/private", response.request().url().url().getPath());
    assertEquals(Arrays.asList(new Challenge("Bearer", "oauthed")), response.challenges());
  }

  @Test public void authenticateCallsTrackedAsRedirects() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b"));
    server.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\""));
    server.enqueue(new MockResponse()
        .setBody("c"));

    RecordingOkAuthenticator authenticator = new RecordingOkAuthenticator(
        Credentials.basic("jesse", "peanutbutter"), "Basic");
    client = client.newBuilder()
        .authenticator(authenticator)
        .build();
    assertContent("c", getResponse(newRequest("/a")));

    Response challengeResponse = authenticator.responses.get(0);
    assertEquals("/b", challengeResponse.request().url().url().getPath());

    Response redirectedBy = challengeResponse.priorResponse();
    assertEquals("/a", redirectedBy.request().url().url().getPath());
  }

  @Test public void attemptAuthorization20Times() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(401));
    }
    server.enqueue(new MockResponse()
        .setBody("Success!"));

    String credential = Credentials.basic("jesse", "peanutbutter");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, null))
        .build();

    Response response = getResponse(newRequest("/0"));
    assertContent("Success!", response);
  }

  @Test public void doesNotAttemptAuthorization21Times() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse()
          .setResponseCode(401));
    }

    String credential = Credentials.basic("jesse", "peanutbutter");
    client = client.newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential, null))
        .build();

    try {
      getResponse(newRequest("/"));
      fail();
    } catch (ProtocolException expected) {
      assertEquals("Too many follow-up requests: 21", expected.getMessage());
    }
  }

  @Test public void setsNegotiatedProtocolHeader_HTTP_2() throws Exception {
    setsNegotiatedProtocolHeader(Protocol.HTTP_2);
  }

  private void setsNegotiatedProtocolHeader(Protocol protocol) throws IOException {
    enableProtocol(protocol);
    server.enqueue(new MockResponse()
        .setBody("A"));
    client = client.newBuilder()
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals(protocol, response.protocol());
    assertContent("A", response);
  }

  @Test public void http10SelectedProtocol() throws IOException {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.0 200 OK"));
    Response response = getResponse(newRequest("/"));
    assertEquals(Protocol.HTTP_1_0, response.protocol());
  }

  @Test public void http11SelectedProtocol() throws IOException {
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 200 OK"));
    Response response = getResponse(newRequest("/"));
    assertEquals(Protocol.HTTP_1_1, response.protocol());
  }

  /** For example, empty Protobuf RPC messages end up as a zero-length POST. */
  @Test public void zeroLengthPost() throws Exception {
    zeroLengthPayload("POST");
  }

  @Test public void zeroLengthPost_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    zeroLengthPost();
  }

  /** For example, creating an Amazon S3 bucket ends up as a zero-length POST. */
  @Test public void zeroLengthPut() throws Exception {
    zeroLengthPayload("PUT");
  }

  @Test public void zeroLengthPut_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    zeroLengthPut();
  }

  private void zeroLengthPayload(String method) throws Exception {
    server.enqueue(new MockResponse());

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .method(method, RequestBody.create(null, ""))
        .build());
    assertContent("", response);
    RecordedRequest zeroLengthPayload = server.takeRequest();
    assertEquals(method, zeroLengthPayload.getMethod());
    assertEquals("0", zeroLengthPayload.getHeader("content-length"));
    assertEquals(0L, zeroLengthPayload.getBodySize());
  }

  @Test public void setProtocols() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A"));
    client = client.newBuilder()
        .protocols(Arrays.asList(Protocol.HTTP_1_1))
        .build();
    assertContent("A", getResponse(newRequest("/")));
  }

  @Test public void setProtocolsWithoutHttp11() {
    try {
      new OkHttpClient.Builder()
          .protocols(Arrays.asList(Protocol.HTTP_2));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setProtocolsWithNull() {
    try {
      new OkHttpClient.Builder()
          .protocols(Arrays.asList(Protocol.HTTP_1_1, null));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void veryLargeFixedLengthRequest() throws Exception {
    server.setBodyLimit(0);
    server.enqueue(new MockResponse());

    long contentLength = Integer.MAX_VALUE + 1L;
    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(new RequestBody() {
          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public long contentLength() {
            return contentLength;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            byte[] buffer = new byte[1024 * 1024];
            for (long bytesWritten = 0; bytesWritten < contentLength; ) {
              int byteCount = (int) Math.min(buffer.length, contentLength - bytesWritten);
              bytesWritten += byteCount;
              sink.write(buffer, 0, byteCount);
            }
          }
        })
        .build());

    assertContent("", response);

    RecordedRequest request = server.takeRequest();
    assertEquals(Long.toString(contentLength), request.getHeader("Content-Length"));
  }

  @Test public void testNoSslFallback() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false /* tunnelProxy */);
    server.enqueue(new MockResponse()
        .setSocketPolicy(FAIL_HANDSHAKE));
    server.enqueue(new MockResponse()
        .setBody("Response that would have needed fallbacks"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .build();
    try {
      getResponse(newRequest("/"));
      fail();
    } catch (SSLProtocolException expected) {
      // RI response to the FAIL_HANDSHAKE
    } catch (SSLHandshakeException expected) {
      // Android's response to the FAIL_HANDSHAKE
    } catch (SSLException expected) {
      // JDK 1.9 response to the FAIL_HANDSHAKE
      // javax.net.ssl.SSLException: Unexpected handshake message: client_hello
    } catch (SocketException expected) {
      // Conscrypt's response to the FAIL_HANDSHAKE
    }
  }

  /**
   * We had a bug where we attempted to gunzip responses that didn't have a body. This only came up
   * with 304s since that response code can include headers (like "Content-Encoding") without any
   * content to go along with it. https://github.com/square/okhttp/issues/358
   */
  @Test public void noTransparentGzipFor304NotModified() throws Exception {
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("Content-Encoding: gzip"));
    server.enqueue(new MockResponse()
        .setBody("b"));

    Response response1 = getResponse(newRequest("/"));
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, response1.code());
    assertContent("", response1);

    Response response2 = getResponse(newRequest("/"));
    assertEquals(HttpURLConnection.HTTP_OK, response2.code());
    assertContent("b", response2);

    RecordedRequest requestA = server.takeRequest();
    assertEquals(0, requestA.getSequenceNumber());

    RecordedRequest requestB = server.takeRequest();
    assertEquals(1, requestB.getSequenceNumber());
  }

  @Test public void nullSSLSocketFactory_throws() {
    try {
      client.newBuilder().sslSocketFactory(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  /**
   * We had a bug where we weren't closing Gzip streams on redirects.
   * https://github.com/square/okhttp/issues/441
   */
  @Test public void gzipWithRedirectAndConnectionReuse() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .addHeader("Content-Encoding: gzip")
        .setBody(gzip("Moved! Moved! Moved!")));
    server.enqueue(new MockResponse()
        .setBody("This is the new page!"));

    Response response = getResponse(newRequest("/"));
    assertContent("This is the new page!", response);

    RecordedRequest requestA = server.takeRequest();
    assertEquals(0, requestA.getSequenceNumber());

    RecordedRequest requestB = server.takeRequest();
    assertEquals(1, requestB.getSequenceNumber());
  }

  /**
   * The RFC is unclear in this regard as it only specifies that this should invalidate the cache
   * entry (if any).
   */
  @Test public void bodyPermittedOnDelete() throws Exception {
    server.enqueue(new MockResponse());

    Response response = getResponse(new Request.Builder()
        .url(server.url("/"))
        .delete(RequestBody.create(null, "BODY"))
        .build());
    assertEquals(200, response.code());

    RecordedRequest request = server.takeRequest();
    assertEquals("DELETE", request.getMethod());
    assertEquals("BODY", request.getBody().readUtf8());
  }

  @Test public void userAgentDefaultsToOkHttpVersion() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    assertContent("abc", getResponse(newRequest("/")));

    RecordedRequest request = server.takeRequest();
    assertEquals(Version.userAgent(), request.getHeader("User-Agent"));
  }

  @Test public void urlWithSpaceInHost() {
    try {
      HttpUrl.get("http://and roid.com/");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void urlWithSpaceInHostViaHttpProxy() {
    try {
      HttpUrl.get("http://and roid.com/");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void urlHostWithNul() {
    try {
      HttpUrl.get("http://host\u0000/");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void urlRedirectToHostWithNul() throws Exception {
    String redirectUrl = "http://host\u0000/";
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeaderLenient("Location", redirectUrl));

    Response response = getResponse(newRequest("/"));
    assertEquals(302, response.code());
    assertEquals(redirectUrl, response.header("Location"));
  }

  @Test public void urlWithBadAsciiHost() {
    try {
      HttpUrl.get("http://host\u0001/");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setSslSocketFactoryFailsOnJdk9() {
    assumeTrue(getPlatform().equals("jdk9"));

    try {
      client.newBuilder()
          .sslSocketFactory(handshakeCertificates.sslSocketFactory());
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  /** Confirm that runtime exceptions thrown inside of OkHttp propagate to the caller. */
  @Test public void unexpectedExceptionSync() throws Exception {
    client = client.newBuilder()
        .dns(hostname -> {
          throw new RuntimeException("boom!");
        })
        .build();

    server.enqueue(new MockResponse());

    try {
      getResponse(newRequest("/"));
      fail();
    } catch (RuntimeException expected) {
      assertEquals("boom!", expected.getMessage());
    }
  }

  @Test public void streamedBodyIsRetriedOnHttp2Shutdown() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setBody("def"));

    // Send a separate request which will trigger a GOAWAY frame on the healthy connection.
    Response response = getResponse(newRequest("/"));
    assertContent("abc", response);

    // Ensure the GOAWAY frame has time to be read and processed.
    Thread.sleep(500);

    assertContent("def", getResponse(new Request.Builder()
        .url(server.url("/"))
        .post(RequestBody.create(null, "123"))
        .build()));

    RecordedRequest request1 = server.takeRequest();
    assertEquals(0, request1.getSequenceNumber());

    RecordedRequest request2 = server.takeRequest();
    assertEquals("123", request2.getBody().readUtf8());
    assertEquals(0, request2.getSequenceNumber());
  }

  @Test public void authenticateNoConnection() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Connection: close")
        .setResponseCode(401)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));

    Authenticator.setDefault(new RecordingAuthenticator(null));
    client = client.newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build();
    Response response = getResponse(newRequest("/"));
    assertEquals(401, response.code());
  }

  private Request newRequest(String s) {
    return newRequest(server.url(s));
  }

  private Request newRequest(HttpUrl url) {
    return new Request.Builder()
        .url(url)
        .build();
  }

  private Response getResponse(Request request) throws IOException {
    return client.newCall(request).execute();
  }

  /** Returns a gzipped copy of {@code bytes}. */
  public Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink gzipSink = Okio.buffer(new GzipSink(result));
    gzipSink.writeUtf8(data);
    gzipSink.close();
    return result;
  }

  private void assertContent(String expected, Response response, int limit)
      throws IOException {
    assertEquals(expected, readAscii(response.body().byteStream(), limit));
  }

  private void assertContent(String expected, Response response) throws IOException {
    assertContent(expected, response, Integer.MAX_VALUE);
  }

  private Set<String> newSet(String... elements) {
    return new LinkedHashSet<>(Arrays.asList(elements));
  }

  enum TransferKind {
    CHUNKED {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setChunkedBody(content, chunkSize);
      }

      @Override RequestBody newRequestBody(String body) {
        return new RequestBody() {
          @Override public long contentLength() {
            return -1L;
          }

          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.writeUtf8(body);
          }
        };
      }
    },
    FIXED_LENGTH {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
      }

      @Override RequestBody newRequestBody(String body) {
        return new RequestBody() {
          @Override public long contentLength() {
            return Utf8.size(body);
          }

          @Override public @Nullable MediaType contentType() {
            return null;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.writeUtf8(body);
          }
        };
      }
    },
    END_OF_STREAM {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
        response.setSocketPolicy(DISCONNECT_AT_END);
        response.removeHeader("Content-Length");
      }

      @Override RequestBody newRequestBody(String body) {
        throw new AssumptionViolatedException("END_OF_STREAM not implemented for requests");
      }
    };

    abstract void setBody(MockResponse response, Buffer content, int chunkSize) throws IOException;

    abstract RequestBody newRequestBody(String body);

    void setBody(MockResponse response, String content, int chunkSize) throws IOException {
      setBody(response, new Buffer().writeUtf8(content), chunkSize);
    }
  }

  enum ProxyConfig {
    NO_PROXY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        return client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .build();
      }
    },

    CREATE_ARG() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        return client.newBuilder()
            .proxy(server.toProxyAddress())
            .build();
      }
    },

    PROXY_SYSTEM_PROPERTY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        System.setProperty("proxyHost", server.getHostName());
        System.setProperty("proxyPort", Integer.toString(server.getPort()));
        return client;
      }
    },

    HTTP_PROXY_SYSTEM_PROPERTY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        System.setProperty("http.proxyHost", server.getHostName());
        System.setProperty("http.proxyPort", Integer.toString(server.getPort()));
        return client;
      }
    },

    HTTPS_PROXY_SYSTEM_PROPERTY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        System.setProperty("https.proxyHost", server.getHostName());
        System.setProperty("https.proxyPort", Integer.toString(server.getPort()));
        return client;
      }
    };

    public abstract Call.Factory connect(MockWebServer server, OkHttpClient client)
        throws IOException;

    public Call connect(
        MockWebServer server, OkHttpClient client, HttpUrl url) throws IOException {
      Request request = new Request.Builder()
          .url(url)
          .build();
      return connect(server, client).newCall(request);
    }
  }

  private static class RecordingTrustManager implements X509TrustManager {
    private final List<String> calls = new ArrayList<>();
    private final X509TrustManager delegate;

    RecordingTrustManager(X509TrustManager delegate) {
      this.delegate = delegate;
    }

    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }

    public void checkClientTrusted(X509Certificate[] chain, String authType) {
      calls.add("checkClientTrusted " + certificatesToString(chain));
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType) {
      calls.add("checkServerTrusted " + certificatesToString(chain));
    }

    private String certificatesToString(X509Certificate[] certificates) {
      List<String> result = new ArrayList<>();
      for (X509Certificate certificate : certificates) {
        result.add(certificate.getSubjectDN() + " " + certificate.getSerialNumber());
      }
      return result.toString();
    }
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocolNegotiationEnabled(true);
    server.setProtocols(client.protocols());
  }

  /**
   * Used during tests that involve TLS connection fallback attempts. OkHttp includes the
   * TLS_FALLBACK_SCSV cipher on fallback connections. See {@link FallbackTestClientSocketFactory}
   * for details.
   */
  private FallbackTestClientSocketFactory suppressTlsFallbackClientSocketFactory() {
    return new FallbackTestClientSocketFactory(handshakeCertificates.sslSocketFactory());
  }

  private String getPlatform() {
    return System.getProperty("okhttp.platform", "platform");
  }
}
