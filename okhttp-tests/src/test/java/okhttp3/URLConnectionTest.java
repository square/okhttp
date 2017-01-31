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
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.DoubleInetAddressDns;
import okhttp3.internal.Internal;
import okhttp3.internal.RecordingAuthenticator;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.SingleInetAddressDns;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.huc.OkHttpURLConnection;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
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
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static okhttp3.TestUtil.defaultClient;
import static okhttp3.internal.Util.UTF_8;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static okhttp3.internal.huc.OkHttpURLConnection.SELECTED_PROTOCOL;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static okhttp3.mockwebserver.SocketPolicy.FAIL_HANDSHAKE;
import static okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_INPUT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_OUTPUT_AT_END;
import static okhttp3.mockwebserver.SocketPolicy.UPGRADE_TO_SSL_AT_END;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/** Android's URLConnectionTest. */
public final class URLConnectionTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final MockWebServer server2 = new MockWebServer();
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();

  private SslClient sslClient = SslClient.localhost();
  private OkUrlFactory urlFactory;
  private HttpURLConnection connection;
  private Cache cache;

  @Before public void setUp() throws Exception {
    server.setProtocolNegotiationEnabled(false);
    urlFactory = new OkUrlFactory(defaultClient());
  }

  @After public void tearDown() throws Exception {
    Authenticator.setDefault(null);
    System.clearProperty("proxyHost");
    System.clearProperty("proxyPort");
    System.clearProperty("http.agent");
    System.clearProperty("http.proxyHost");
    System.clearProperty("http.proxyPort");
    System.clearProperty("https.proxyHost");
    System.clearProperty("https.proxyPort");
    if (cache != null) {
      cache.delete();
    }
  }

  @Test public void requestHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse());

    connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("D", "e");
    connection.addRequestProperty("D", "f");
    assertEquals("f", connection.getRequestProperty("D"));
    assertEquals("f", connection.getRequestProperty("d"));
    Map<String, List<String>> requestHeaders = connection.getRequestProperties();
    assertEquals(newSet("e", "f"), new LinkedHashSet<>(requestHeaders.get("D")));
    assertEquals(newSet("e", "f"), new LinkedHashSet<>(requestHeaders.get("d")));
    try {
      requestHeaders.put("G", Arrays.asList("h"));
      fail("Modified an unmodifiable view.");
    } catch (UnsupportedOperationException expected) {
    }
    try {
      requestHeaders.get("D").add("i");
      fail("Modified an unmodifiable view.");
    } catch (UnsupportedOperationException expected) {
    }
    try {
      connection.setRequestProperty(null, "j");
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      connection.addRequestProperty(null, "k");
      fail();
    } catch (NullPointerException expected) {
    }
    connection.setRequestProperty("NullValue", null);
    assertNull(connection.getRequestProperty("NullValue"));
    connection.addRequestProperty("AnotherNullValue", null);
    assertNull(connection.getRequestProperty("AnotherNullValue"));

    connection.getResponseCode();
    RecordedRequest request = server.takeRequest();
    assertEquals(Arrays.asList("e", "f"), request.getHeaders().values("D"));
    assertNull(request.getHeader("NullValue"));
    assertNull(request.getHeader("AnotherNullValue"));
    assertNull(request.getHeader("G"));
    assertNull(request.getHeader("null"));

    try {
      connection.addRequestProperty("N", "o");
      fail("Set header after connect");
    } catch (IllegalStateException expected) {
    }
    try {
      connection.setRequestProperty("P", "q");
      fail("Set header after connect");
    } catch (IllegalStateException expected) {
    }
    try {
      connection.getRequestProperties();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void getRequestPropertyReturnsLastValue() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("A", "value1");
    connection.addRequestProperty("A", "value2");
    assertEquals("value2", connection.getRequestProperty("A"));
  }

  @Test public void responseHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setStatus("HTTP/1.0 200 Fantastic")
        .addHeader("A: c")
        .addHeader("B: d")
        .addHeader("A: e")
        .setChunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8));

    connection = urlFactory.open(server.url("/").url());
    assertEquals(200, connection.getResponseCode());
    assertEquals("Fantastic", connection.getResponseMessage());
    assertEquals("HTTP/1.0 200 Fantastic", connection.getHeaderField(null));
    Map<String, List<String>> responseHeaders = connection.getHeaderFields();
    assertEquals(Arrays.asList("HTTP/1.0 200 Fantastic"), responseHeaders.get(null));
    assertEquals(newSet("c", "e"), new LinkedHashSet<>(responseHeaders.get("A")));
    assertEquals(newSet("c", "e"), new LinkedHashSet<>(responseHeaders.get("a")));
    try {
      responseHeaders.put("N", Arrays.asList("o"));
      fail("Modified an unmodifiable view.");
    } catch (UnsupportedOperationException expected) {
    }
    try {
      responseHeaders.get("A").add("f");
      fail("Modified an unmodifiable view.");
    } catch (UnsupportedOperationException expected) {
    }
    assertEquals("A", connection.getHeaderFieldKey(0));
    assertEquals("c", connection.getHeaderField(0));
    assertEquals("B", connection.getHeaderFieldKey(1));
    assertEquals("d", connection.getHeaderField(1));
    assertEquals("A", connection.getHeaderFieldKey(2));
    assertEquals("e", connection.getHeaderField(2));
    connection.getInputStream().close();
  }

  @Test public void serverSendsInvalidResponseHeaders() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTP/1.1 200 OK"));

    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getResponseCode();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void serverSendsInvalidCodeTooLarge() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 2147483648 OK"));

    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getResponseCode();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void serverSendsInvalidCodeNotANumber() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 00a OK"));

    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getResponseCode();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void serverSendsUnnecessaryWhitespace() throws Exception {
    server.enqueue(new MockResponse().setStatus(" HTTP/1.1 2147483648 OK"));

    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getResponseCode();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void connectRetriesUntilConnectedOrFailed() throws Exception {
    URL url = server.url("/foo").url();
    server.shutdown();

    connection = urlFactory.open(url);
    try {
      connection.connect();
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

  @Test public void requestBodySurvivesRetriesWithBufferedBody() throws Exception {
    testRequestBodySurvivesRetries(TransferKind.END_OF_STREAM);
  }

  private void testRequestBodySurvivesRetries(TransferKind transferKind) throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    // Use a misconfigured proxy to guarantee that the request is retried.
    urlFactory.setClient(urlFactory.client().newBuilder()
        .proxySelector(new FakeProxySelector()
            .addProxy(server2.toProxyAddress())
            .addProxy(Proxy.NO_PROXY))
        .build());
    server2.shutdown();

    connection = urlFactory.open(server.url("/def").url());
    connection.setDoOutput(true);
    transferKind.setForRequest(connection, 4);
    connection.getOutputStream().write("body".getBytes("UTF-8"));
    assertContent("abc", connection);

    assertEquals("body", server.takeRequest().getBody().readUtf8());
  }

  @Test public void streamedBodyIsNotRetried() throws Exception {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

    urlFactory = new OkUrlFactory(defaultClient().newBuilder()
        .dns(new DoubleInetAddressDns())
        .build());
    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    connection.setChunkedStreamingMode(100);
    OutputStream os = connection.getOutputStream();
    os.write("OutputStream is no fun.".getBytes("UTF-8"));
    os.close();

    try {
      connection.getResponseCode();
      fail();
    } catch (IOException expected) {
    }

    assertEquals(1, server.getRequestCount());
  }

  @Test public void getErrorStreamOnSuccessfulRequest() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    assertNull(connection.getErrorStream());
    connection.getInputStream().close();
  }

  @Test public void getErrorStreamOnUnsuccessfulRequest() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection.getErrorStream(), Integer.MAX_VALUE));
  }

  // Check that if we don't read to the end of a response, the next request on the
  // recycled connection doesn't get the unread tail of the first request's response.
  // http://code.google.com/p/android/issues/detail?id=2939
  @Test public void bug2939() throws Exception {
    MockResponse response = new MockResponse().setChunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8);

    server.enqueue(response);
    server.enqueue(response);

    HttpURLConnection c1 = urlFactory.open(server.url("/").url());
    assertContent("ABCDE", c1, 5);
    HttpURLConnection c2 = urlFactory.open(server.url("/").url());
    assertContent("ABCDE", c2, 5);

    c1.getInputStream().close();
    c2.getInputStream().close();
  }

  // Check that we recognize a few basic mime types by extension.
  // http://code.google.com/p/android/issues/detail?id=10100
  @Test public void bug10100() throws Exception {
    assertEquals("image/jpeg", URLConnection.guessContentTypeFromName("someFile.jpg"));
    assertEquals("application/pdf", URLConnection.guessContentTypeFromName("stuff.pdf"));
  }

  @Test public void connectionsArePooled() throws Exception {
    MockResponse response = new MockResponse().setBody("ABCDEFGHIJKLMNOPQR");

    server.enqueue(response);
    server.enqueue(response);
    server.enqueue(response);

    assertContent("ABCDEFGHIJKLMNOPQR", urlFactory.open(server.url("/foo").url()));
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", urlFactory.open(server.url("/bar?baz=quux").url()));
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", urlFactory.open(server.url("/z").url()));
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void chunkedConnectionsArePooled() throws Exception {
    MockResponse response = new MockResponse().setChunkedBody("ABCDEFGHIJKLMNOPQR", 5);

    server.enqueue(response);
    server.enqueue(response);
    server.enqueue(response);

    assertContent("ABCDEFGHIJKLMNOPQR", urlFactory.open(server.url("/foo").url()));
    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", urlFactory.open(server.url("/bar?baz=quux").url()));
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertContent("ABCDEFGHIJKLMNOPQR", urlFactory.open(server.url("/z").url()));
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
    URL url = new URL("http://1234.1.1.1/index.html");
    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new FakeDns())
        .build());
    HttpURLConnection connection = urlFactory.open(url);
    try {
      connection.connect();
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  private void testServerClosesOutput(SocketPolicy socketPolicy) throws Exception {
    server.enqueue(new MockResponse().setBody("This connection won't pool properly")
        .setSocketPolicy(socketPolicy));
    MockResponse responseAfter = new MockResponse().setBody("This comes after a busted connection");
    server.enqueue(responseAfter);
    server.enqueue(responseAfter); // Enqueue 2x because the broken connection may be reused.

    HttpURLConnection connection1 = urlFactory.open(server.url("/a").url());
    connection1.setReadTimeout(100);
    assertContent("This connection won't pool properly", connection1);
    assertEquals(0, server.takeRequest().getSequenceNumber());

    // Give the server time to enact the socket policy if it's one that could happen after the
    // client has received the response.
    Thread.sleep(500);

    HttpURLConnection connection2 = urlFactory.open(server.url("/b").url());
    connection2.setReadTimeout(100);
    assertContent("This comes after a busted connection", connection2);

    // Check that a fresh connection was created, either immediately or after attempting reuse.
    RecordedRequest requestAfter = server.takeRequest();
    if (server.getRequestCount() == 3) {
      requestAfter = server.takeRequest(); // The failure consumed a response.
    }
    // sequence number 0 means the HTTP socket connection was not reused
    assertEquals(0, requestAfter.getSequenceNumber());
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

    HttpURLConnection conn = urlFactory.open(server.url("/").url());
    conn.setDoOutput(true);
    conn.setRequestMethod("POST");
    if (uploadKind == TransferKind.CHUNKED) {
      conn.setChunkedStreamingMode(-1);
    } else {
      conn.setFixedLengthStreamingMode(n);
    }
    OutputStream out = conn.getOutputStream();
    if (writeKind == WriteKind.BYTE_BY_BYTE) {
      for (int i = 0; i < n; ++i) {
        out.write('x');
      }
    } else {
      byte[] buf = new byte[writeKind == WriteKind.SMALL_BUFFERS ? 256 : 64 * 1024];
      Arrays.fill(buf, (byte) 'x');
      for (int i = 0; i < n; i += buf.length) {
        out.write(buf, 0, Math.min(buf.length, n - i));
      }
    }
    out.close();
    assertEquals(200, conn.getResponseCode());
    RecordedRequest request = server.takeRequest();
    assertEquals(n, request.getBodySize());
    if (uploadKind == TransferKind.CHUNKED) {
      assertTrue(request.getChunkSizes().size() > 0);
    } else {
      assertTrue(request.getChunkSizes().isEmpty());
    }
  }

  @Test public void getResponseCodeNoResponseBody() throws Exception {
    server.enqueue(new MockResponse().addHeader("abc: def"));

    URL url = server.url("/").url();
    HttpURLConnection conn = urlFactory.open(url);
    conn.setDoInput(false);
    assertEquals("def", conn.getHeaderField("abc"));
    assertEquals(200, conn.getResponseCode());
    try {
      conn.getInputStream();
      fail();
    } catch (ProtocolException expected) {
    }
  }

  @Test public void connectViaHttps() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());
    connection = urlFactory.open(server.url("/foo").url());

    assertContent("this response comes via HTTPS", connection);

    RecordedRequest request = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
  }

  @Test public void inspectHandshakeThroughoutRequestLifecycle() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse());

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());

    HttpsURLConnection httpsConnection
        = (HttpsURLConnection) urlFactory.open(server.url("/foo").url());

    // Prior to calling connect(), getting the cipher suite is forbidden.
    try {
      httpsConnection.getCipherSuite();
      fail();
    } catch (IllegalStateException expected) {
    }

    // Calling connect establishes a handshake...
    httpsConnection.connect();
    assertNotNull(httpsConnection.getCipherSuite());

    // ...which remains after we read the response body...
    assertContent("", httpsConnection);
    assertNotNull(httpsConnection.getCipherSuite());

    // ...and after we disconnect.
    httpsConnection.disconnect();
    assertNotNull(httpsConnection.getCipherSuite());
  }

  @Test public void connectViaHttpsReusingConnections() throws Exception {
    connectViaHttpsReusingConnections(false);
  }

  @Test public void connectViaHttpsReusingConnectionsAfterRebuildingClient() throws Exception {
    connectViaHttpsReusingConnections(true);
  }

  private void connectViaHttpsReusingConnections(boolean rebuildClient) throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));
    server.enqueue(new MockResponse().setBody("another response via HTTPS"));

    // The pool will only reuse sockets if the SSL socket factories are the same.
    SSLSocketFactory clientSocketFactory = sslClient.socketFactory;
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    CookieJar cookieJar = new JavaNetCookieJar(new CookieManager());
    ConnectionPool connectionPool = new ConnectionPool();

    urlFactory.setClient(new OkHttpClient.Builder()
        .cache(cache)
        .connectionPool(connectionPool)
        .cookieJar(cookieJar)
        .sslSocketFactory(clientSocketFactory, sslClient.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertContent("this response comes via HTTPS", connection);

    if (rebuildClient) {
      urlFactory.setClient(new OkHttpClient.Builder()
          .cache(cache)
          .connectionPool(connectionPool)
          .cookieJar(cookieJar)
          .sslSocketFactory(clientSocketFactory, sslClient.trustManager)
          .hostnameVerifier(hostnameVerifier)
          .build());
    }

    connection = urlFactory.open(server.url("/").url());
    assertContent("another response via HTTPS", connection);

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
  }

  @Test public void connectViaHttpsReusingConnectionsDifferentFactories() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));
    server.enqueue(new MockResponse().setBody("another response via HTTPS"));

    // install a custom SSL socket factory so the server can be authorized
    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());
    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    assertContent("this response comes via HTTPS", connection1);

    SSLContext sslContext2 = SSLContext.getInstance("TLS");
    sslContext2.init(null, null, null);
    SSLSocketFactory sslSocketFactory2 = sslContext2.getSocketFactory();

    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init((KeyStore) null);
    X509TrustManager trustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslSocketFactory2, trustManager)
        .build());
    HttpURLConnection connection2 = urlFactory.open(server.url("/").url());
    try {
      readAscii(connection2.getInputStream(), Integer.MAX_VALUE);
      fail("without an SSL socket factory, the connection should fail");
    } catch (SSLException expected) {
    }
  }

  // TODO(jwilson): tests below this marker need to be migrated to OkHttp's request/response API.

  @Test public void connectViaHttpsWithSSLFallback() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setSocketPolicy(FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("this response comes via SSL"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .sslSocketFactory(suppressTlsFallbackClientSocketFactory(), sslClient.trustManager)
        .build());
    connection = urlFactory.open(server.url("/foo").url());

    assertContent("this response comes via SSL", connection);

    RecordedRequest failHandshakeRequest = server.takeRequest();
    assertNull(failHandshakeRequest.getRequestLine());

    RecordedRequest fallbackRequest = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", fallbackRequest.getRequestLine());
    assertEquals(TlsVersion.TLS_1_0, fallbackRequest.getTlsVersion());
  }

  @Test public void connectViaHttpsWithSSLFallbackFailuresRecorded() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setSocketPolicy(FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setSocketPolicy(FAIL_HANDSHAKE));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new SingleInetAddressDns())
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(suppressTlsFallbackClientSocketFactory(), sslClient.trustManager)
        .build());
    connection = urlFactory.open(server.url("/foo").url());

    try {
      connection.getResponseCode();
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
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse()
        .setBody("abc")
        .setSocketPolicy(DISCONNECT_AT_END));
    server.enqueue(new MockResponse().setBody("def"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .hostnameVerifier(new RecordingHostnameVerifier())
        .sslSocketFactory(suppressTlsFallbackClientSocketFactory(), sslClient.trustManager)
        .build());

    assertContent("abc", urlFactory.open(server.url("/").url()));

    // Give the server time to disconnect.
    Thread.sleep(500);

    assertContent("def", urlFactory.open(server.url("/").url()));

    Set<TlsVersion> tlsVersions =
        EnumSet.of(TlsVersion.TLS_1_0, TlsVersion.TLS_1_2); // v1.2 on OpenJDK 8.

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
  @Test public void connectViaHttpsToUntrustedServer() throws IOException, InterruptedException {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse()); // unused

    connection = urlFactory.open(server.url("/foo").url());
    try {
      connection.getInputStream();
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
    MockResponse mockResponse = new MockResponse().setBody("this response comes via a proxy");
    server.enqueue(mockResponse);

    URL url = new URL("http://android.com/foo");
    connection = proxyConfig.connect(server, urlFactory, url);
    assertContent("this response comes via a proxy", connection);
    assertTrue(connection.usingProxy());

    RecordedRequest request = server.takeRequest();
    assertEquals("GET http://android.com/foo HTTP/1.1", request.getRequestLine());
    assertEquals("android.com", request.getHeader("Host"));
  }

  @Test public void contentDisagreesWithContentLengthHeaderBodyTooLong() throws IOException {
    server.enqueue(new MockResponse().setBody("abc\r\nYOU SHOULD NOT SEE THIS")
        .clearHeaders()
        .addHeader("Content-Length: 3"));
    assertContent("abc", urlFactory.open(server.url("/").url()));
  }

  @Test public void contentDisagreesWithContentLengthHeaderBodyTooShort() throws IOException {
    server.enqueue(new MockResponse().setBody("abc")
        .setHeader("Content-Length", "5")
        .setSocketPolicy(DISCONNECT_AT_END));
    try {
      readAscii(urlFactory.open(server.url("/").url()).getInputStream(), 5);
      fail();
    } catch (ProtocolException expected) {
    }
  }

  public void testConnectViaSocketFactory(boolean useHttps) throws IOException {
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
      server.useHttps(sslClient.socketFactory, false);
      urlFactory.setClient(urlFactory.client().newBuilder()
          .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
          .hostnameVerifier(new RecordingHostnameVerifier())
          .build());
    }

    server.enqueue(new MockResponse().setStatus("HTTP/1.1 200 OK"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .socketFactory(uselessSocketFactory)
        .build());
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getResponseCode();
      fail();
    } catch (IllegalArgumentException expected) {
    }

    urlFactory.setClient(urlFactory.client().newBuilder()
        .socketFactory(SocketFactory.getDefault())
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals(200, connection.getResponseCode());
  }

  @Test public void connectHttpViaSocketFactory() throws Exception {
    testConnectViaSocketFactory(false);
  }

  @Test public void connectHttpsViaSocketFactory() throws Exception {
    testConnectViaSocketFactory(true);
  }

  @Test public void contentDisagreesWithChunkedHeaderBodyTooLong() throws IOException {
    MockResponse mockResponse = new MockResponse();
    mockResponse.setChunkedBody("abc", 3);
    Buffer buffer = mockResponse.getBody();
    buffer.writeUtf8("\r\nYOU SHOULD NOT SEE THIS");
    mockResponse.setBody(buffer);
    mockResponse.clearHeaders();
    mockResponse.addHeader("Transfer-encoding: chunked");

    server.enqueue(mockResponse);

    assertContent("abc", urlFactory.open(server.url("/").url()));
  }

  @Test public void contentDisagreesWithChunkedHeaderBodyTooShort() throws IOException {
    MockResponse mockResponse = new MockResponse();
    mockResponse.setChunkedBody("abcde", 5);

    Buffer truncatedBody = new Buffer();
    Buffer fullBody = mockResponse.getBody();
    truncatedBody.write(fullBody, fullBody.indexOf((byte) 'e'));
    mockResponse.setBody(truncatedBody);

    mockResponse.clearHeaders();
    mockResponse.addHeader("Transfer-encoding: chunked");
    mockResponse.setSocketPolicy(DISCONNECT_AT_END);

    server.enqueue(mockResponse);

    try {
      readAscii(urlFactory.open(server.url("/").url()).getInputStream(), 5);
      fail();
    } catch (ProtocolException expected) {
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
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));

    URL url = server.url("/foo").url();
    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());
    connection = proxyConfig.connect(server, urlFactory, url);

    assertContent("this response comes via HTTPS", connection);

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

    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(
        new MockResponse().setSocketPolicy(UPGRADE_TO_SSL_AT_END).clearHeaders());
    server.enqueue(new MockResponse().setBody("this response comes via a secure proxy"));

    URL url = new URL("https://android.com/foo");
    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build());
    connection = proxyConfig.connect(server, urlFactory, url);

    assertContent("this response comes via a secure proxy", connection);

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

    server.useHttps(sslClient.socketFactory, true);
    // The inclusion of a body in the response to a CONNECT is key to reproducing b/6754912.
    MockResponse badProxyResponse = new MockResponse()
        .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
        .setBody("bogus proxy connect response content");
    server.enqueue(badProxyResponse);
    server.enqueue(new MockResponse().setBody("response"));

    // Configure a single IP address for the host and a single configuration, so we only need one
    // failure to fail permanently.
    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new SingleInetAddressDns())
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .connectionSpecs(Util.immutableList(ConnectionSpec.MODERN_TLS))
        .hostnameVerifier(new RecordingHostnameVerifier())
        .proxy(server.toProxyAddress())
        .build());

    URL url = new URL("https://android.com/foo");
    connection = urlFactory.open(url);
    assertContent("response", connection);

    RecordedRequest connect = server.takeRequest();
    assertEquals("CONNECT android.com:443 HTTP/1.1", connect.getRequestLine());
    assertEquals("android.com:443", connect.getHeader("Host"));
  }

  private void initResponseCache() throws IOException {
    cache = new Cache(tempDir.getRoot(), Integer.MAX_VALUE);
    urlFactory.setClient(urlFactory.client().newBuilder()
        .cache(cache)
        .build());
  }

  /** Test which headers are sent unencrypted to the HTTP proxy. */
  @Test public void proxyConnectIncludesProxyHeadersOnly()
      throws IOException, InterruptedException {
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(
        new MockResponse().setSocketPolicy(UPGRADE_TO_SSL_AT_END).clearHeaders());
    server.enqueue(new MockResponse().setBody("encrypted response from the origin server"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .proxy(server.toProxyAddress())
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build());

    URL url = new URL("https://android.com/foo");
    connection = urlFactory.open(url);
    connection.addRequestProperty("Private", "Secret");
    connection.addRequestProperty("Proxy-Authorization", "bar");
    connection.addRequestProperty("User-Agent", "baz");
    assertContent("encrypted response from the origin server", connection);

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
    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(new MockResponse().setResponseCode(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""));
    server.enqueue(
        new MockResponse().setSocketPolicy(UPGRADE_TO_SSL_AT_END).clearHeaders());
    server.enqueue(new MockResponse().setBody("A"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .proxyAuthenticator(new JavaNetAuthenticator())
        .proxy(server.toProxyAddress())
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());

    URL url = new URL("https://android.com/foo");
    connection = urlFactory.open(url);
    assertContent("A", connection);

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
    server.useHttps(sslClient.socketFactory, true);
    server.enqueue(
        new MockResponse().setSocketPolicy(UPGRADE_TO_SSL_AT_END).clearHeaders());
    server.enqueue(new MockResponse().setBody("this response comes via a proxy"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .proxy(server.toProxyAddress())
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());

    URL url = new URL("https://android.com/foo");
    connection = urlFactory.open(url);
    connection.setRequestProperty("Connection", "close");

    assertContent("this response comes via a proxy", connection);
  }

  @Test public void proxyWithConnectionReuse() throws IOException {
    SSLSocketFactory socketFactory = sslClient.socketFactory;
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

    server.useHttps(socketFactory, true);
    server.enqueue(
        new MockResponse().setSocketPolicy(UPGRADE_TO_SSL_AT_END).clearHeaders());
    server.enqueue(new MockResponse().setBody("response 1"));
    server.enqueue(new MockResponse().setBody("response 2"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .proxy(server.toProxyAddress())
        .sslSocketFactory(socketFactory, sslClient.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build());
    URL url = new URL("https://android.com/foo");
    assertContent("response 1", urlFactory.open(url));
    assertContent("response 2", urlFactory.open(url));
  }

  @Test public void disconnectedConnection() throws IOException {
    server.enqueue(new MockResponse()
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .setBody("ABCD"));

    connection = urlFactory.open(server.url("/").url());
    InputStream in = connection.getInputStream();
    assertEquals('A', (char) in.read());
    connection.disconnect();
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

  @Test public void disconnectDuringConnect_cookieJar() throws Exception {
    final AtomicReference<HttpURLConnection> connectionHolder = new AtomicReference<>();
    class DisconnectingCookieJar implements CookieJar {
      @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) { }
      @Override
      public List<Cookie> loadForRequest(HttpUrl url) {
        connectionHolder.get().disconnect();
        return Collections.emptyList();
      }
    }
    OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .cookieJar(new DisconnectingCookieJar())
            .build();

    URL url = server.url("path that should never be accessed").url();
    HttpURLConnection connection = new OkHttpURLConnection(url, client);
    connectionHolder.set(connection);
    try {
      connection.getInputStream();
      fail("Connection should not be established");
    } catch (IOException expected) {
      assertEquals("Canceled", expected.getMessage());
    } finally {
      connection.disconnect();
    }
  }

  @Test public void disconnectBeforeConnect() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));

    connection = urlFactory.open(server.url("/").url());
    connection.disconnect();
    assertContent("A", connection);
    assertEquals(200, connection.getResponseCode());
  }

  @SuppressWarnings("deprecation") @Test public void defaultRequestProperty() throws Exception {
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

    InputStream in = urlFactory.open(server.url("/").url()).getInputStream();
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
    assertContent("ABCDEFGHIJKLMNOPQRSTUVWXYZ", urlFactory.open(server.url("/").url()));
  }

  /**
   * We've had a bug where we forget the HTTP response when we see response code 401. This causes a
   * new HTTP request to be issued for every call into the URLConnection.
   */
  @Test public void unauthorizedResponseHandling() throws IOException {
    MockResponse response = new MockResponse().addHeader("WWW-Authenticate: challenge")
        .setResponseCode(401) // UNAUTHORIZED
        .setBody("Unauthorized");
    server.enqueue(response);
    server.enqueue(response);
    server.enqueue(response);

    URL url = server.url("/").url();
    HttpURLConnection conn = urlFactory.open(url);

    assertEquals(401, conn.getResponseCode());
    assertEquals(401, conn.getResponseCode());
    assertEquals(401, conn.getResponseCode());
    assertEquals(1, server.getRequestCount());
    conn.getErrorStream().close();
  }

  @Test public void nonHexChunkSize() throws IOException {
    server.enqueue(new MockResponse().setBody("5\r\nABCDE\r\nG\r\nFGHIJKLMNOPQRSTU\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    try {
      readAscii(connection.getInputStream(), Integer.MAX_VALUE);
      fail();
    } catch (IOException e) {
    }
    connection.getInputStream().close();
  }

  @Test public void malformedChunkSize() throws IOException {
    server.enqueue(new MockResponse().setBody("5:x\r\nABCDE\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    try {
      readAscii(connection.getInputStream(), Integer.MAX_VALUE);
      fail();
    } catch (IOException e) {
    } finally {
      connection.getInputStream().close();
    }
  }

  @Test public void extensionAfterChunkSize() throws IOException {
    server.enqueue(new MockResponse().setBody("5;x\r\nABCDE\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked"));

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertContent("ABCDE", connection);
  }

  @Test public void missingChunkBody() throws IOException {
    server.enqueue(new MockResponse().setBody("5")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked")
        .setSocketPolicy(DISCONNECT_AT_END));

    URLConnection connection = urlFactory.open(server.url("/").url());
    try {
      readAscii(connection.getInputStream(), Integer.MAX_VALUE);
      fail();
    } catch (IOException e) {
    } finally {
      connection.getInputStream().close();
    }
  }

  /**
   * This test checks whether connections are gzipped by default. This behavior in not required by
   * the API, so a failure of this test does not imply a bug in the implementation.
   */
  @Test public void gzipEncodingEnabledByDefault() throws IOException, InterruptedException {
    server.enqueue(new MockResponse()
        .setBody(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("ABCABCABC", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    assertNull(connection.getContentEncoding());
    assertEquals(-1, connection.getContentLength());

    RecordedRequest request = server.takeRequest();
    assertEquals("gzip", request.getHeader("Accept-Encoding"));
  }

  @Test public void clientConfiguredGzipContentEncoding() throws Exception {
    Buffer bodyBytes = gzip("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    server.enqueue(new MockResponse()
        .setBody(bodyBytes)
        .addHeader("Content-Encoding: gzip"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Accept-Encoding", "gzip");
    InputStream gunzippedIn = new GZIPInputStream(connection.getInputStream());
    assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", readAscii(gunzippedIn, Integer.MAX_VALUE));
    assertEquals(bodyBytes.size(), connection.getContentLength());

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
    server.enqueue(new MockResponse().setBody("ABCDE").addHeader("Content-Encoding: custom"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Accept-Encoding", "custom");
    assertEquals("ABCDE", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    RecordedRequest request = server.takeRequest();
    assertEquals("custom", request.getHeader("Accept-Encoding"));
  }

  /**
   * Test a bug where gzip input streams weren't exhausting the input stream, which corrupted the
   * request that followed or prevented connection reuse.
   * http://code.google.com/p/android/issues/detail?id=7059
   * http://code.google.com/p/android/issues/detail?id=38817
   */
  private void testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind transferKind,
      boolean tls) throws Exception {
    if (tls) {
      SSLSocketFactory socketFactory = sslClient.socketFactory;
      RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
      server.useHttps(socketFactory, false);
      urlFactory.setClient(urlFactory.client().newBuilder()
          .sslSocketFactory(socketFactory, sslClient.trustManager)
          .hostnameVerifier(hostnameVerifier)
          .build());
    }

    MockResponse responseOne = new MockResponse();
    responseOne.addHeader("Content-Encoding: gzip");
    transferKind.setBody(responseOne, gzip("one (gzipped)"), 5);
    server.enqueue(responseOne);
    MockResponse responseTwo = new MockResponse();
    transferKind.setBody(responseTwo, "two (identity)", 5);
    server.enqueue(responseTwo);

    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    connection1.addRequestProperty("Accept-Encoding", "gzip");
    InputStream gunzippedIn = new GZIPInputStream(connection1.getInputStream());
    assertEquals("one (gzipped)", readAscii(gunzippedIn, Integer.MAX_VALUE));
    assertEquals(0, server.takeRequest().getSequenceNumber());

    HttpURLConnection connection2 = urlFactory.open(server.url("/").url());
    assertEquals("two (identity)", readAscii(connection2.getInputStream(), Integer.MAX_VALUE));
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
    assertContent("a", urlFactory.open(server.url("/").url()));

    // Give the server time to disconnect.
    Thread.sleep(500);

    // This connection will need to be recovered. When it is, transparent gzip should still work!
    assertContent("b", urlFactory.open(server.url("/").url()));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber()); // Connection is not pooled.
  }

  @Test public void endOfStreamResponseIsNotPooled() throws Exception {
    urlFactory.client().connectionPool().evictAll();
    server.enqueue(new MockResponse()
        .setBody("{}")
        .clearHeaders()
        .setSocketPolicy(DISCONNECT_AT_END));

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertContent("{}", connection);
    assertEquals(0, urlFactory.client().connectionPool().idleConnectionCount());
  }

  @Test public void earlyDisconnectDoesntHarmPoolingWithChunkedEncoding() throws Exception {
    testEarlyDisconnectDoesntHarmPooling(TransferKind.CHUNKED);
  }

  @Test public void earlyDisconnectDoesntHarmPoolingWithFixedLengthEncoding() throws Exception {
    testEarlyDisconnectDoesntHarmPooling(TransferKind.FIXED_LENGTH);
  }

  private void testEarlyDisconnectDoesntHarmPooling(TransferKind transferKind) throws Exception {
    MockResponse response1 = new MockResponse();
    transferKind.setBody(response1, "ABCDEFGHIJK", 1024);
    server.enqueue(response1);

    MockResponse response2 = new MockResponse();
    transferKind.setBody(response2, "LMNOPQRSTUV", 1024);
    server.enqueue(response2);

    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    InputStream in1 = connection1.getInputStream();
    assertEquals("ABCDE", readAscii(in1, 5));
    in1.close();
    connection1.disconnect();

    HttpURLConnection connection2 = urlFactory.open(server.url("/").url());
    InputStream in2 = connection2.getInputStream();
    assertEquals("LMNOP", readAscii(in2, 5));
    in2.close();
    connection2.disconnect();

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber()); // Connection is pooled!
  }

  @Test public void streamDiscardingIsTimely() throws Exception {
    // This response takes at least a full second to serve: 10,000 bytes served 100 bytes at a time.
    server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[10000]))
        .throttleBody(100, 10, MILLISECONDS));
    server.enqueue(new MockResponse().setBody("A"));

    long startNanos = System.nanoTime();
    URLConnection connection1 = urlFactory.open(server.url("/").url());
    InputStream in = connection1.getInputStream();
    in.close();
    long elapsedNanos = System.nanoTime() - startNanos;
    long elapsedMillis = NANOSECONDS.toMillis(elapsedNanos);

    // If we're working correctly, this should be greater than 100ms, but less than double that.
    // Previously we had a bug where we would download the entire response body as long as no
    // individual read took longer than 100ms.
    assertTrue(Util.format("Time to close: %sms", elapsedMillis), elapsedMillis < 500);

    // Do another request to confirm that the discarded connection was not pooled.
    assertContent("A", urlFactory.open(server.url("/").url()));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber()); // Connection is not pooled.
  }

  @Test public void setChunkedStreamingMode() throws IOException, InterruptedException {
    server.enqueue(new MockResponse());

    String body = "ABCDEFGHIJKLMNOPQ";
    connection = urlFactory.open(server.url("/").url());
    connection.setChunkedStreamingMode(0); // OkHttp does not honor specific chunk sizes.
    connection.setDoOutput(true);
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write(body.getBytes("US-ASCII"));
    assertEquals(200, connection.getResponseCode());
    connection.getInputStream().close();

    RecordedRequest request = server.takeRequest();
    assertEquals(body, request.getBody().readUtf8());
    assertEquals(Arrays.asList(body.length()), request.getChunkSizes());
  }

  @Test public void authenticateWithFixedLengthStreaming() throws Exception {
    testAuthenticateWithStreamingPost(StreamingMode.FIXED_LENGTH);
  }

  @Test public void authenticateWithChunkedStreaming() throws Exception {
    testAuthenticateWithStreamingPost(StreamingMode.CHUNKED);
  }

  private void testAuthenticateWithStreamingPost(StreamingMode streamingMode) throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse().setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    server.enqueue(pleaseAuthenticate);

    Authenticator.setDefault(new RecordingAuthenticator());
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build());
    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    byte[] requestBody = {'A', 'B', 'C', 'D'};
    if (streamingMode == StreamingMode.FIXED_LENGTH) {
      connection.setFixedLengthStreamingMode(requestBody.length);
    } else if (streamingMode == StreamingMode.CHUNKED) {
      connection.setChunkedStreamingMode(0);
    }
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write(requestBody);
    outputStream.close();
    try {
      connection.getInputStream();
      fail();
    } catch (HttpRetryException expected) {
    }

    // no authorization header for the request...
    RecordedRequest request = server.takeRequest();
    assertNull(request.getHeader("Authorization"));
    assertEquals("ABCD", request.getBody().readUtf8());
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
    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(new MockResponse());

    String credential = Credentials.basic("jesse", "secret");
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential))
        .build());

    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write(body.getBytes("UTF-8"));
    outputStream.close();
    assertEquals(200, connection.getResponseCode());
    connection.getInputStream().close();

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
    assertTrue(call, call.toLowerCase().contains("scheme=basic")); // lowercase for the RI.
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
    assertTrue(call, call.toLowerCase().contains("scheme=basic")); // lowercase for the RI.
  }

  private List<String> authCallsForHeader(String authHeader) throws IOException {
    boolean proxy = authHeader.startsWith("Proxy-");
    int responseCode = proxy ? 407 : 401;
    RecordingAuthenticator authenticator = new RecordingAuthenticator(null);
    Authenticator.setDefault(authenticator);
    MockResponse pleaseAuthenticate = new MockResponse()
        .setResponseCode(responseCode)
        .addHeader(authHeader)
        .setBody("Please authenticate.");
    server.enqueue(pleaseAuthenticate);

    if (proxy) {
      urlFactory.setClient(urlFactory.client().newBuilder()
          .proxy(server.toProxyAddress())
          .proxyAuthenticator(new JavaNetAuthenticator())
          .build());
      connection = urlFactory.open(new URL("http://android.com/"));
    } else {
      urlFactory.setClient(urlFactory.client().newBuilder()
          .authenticator(new JavaNetAuthenticator())
          .build());
      connection = urlFactory.open(server.url("/").url());
    }
    assertEquals(responseCode, connection.getResponseCode());
    connection.getErrorStream().close();
    return authenticator.calls;
  }

  @Test public void setValidRequestMethod() throws Exception {
    assertValidRequestMethod("GET");
    assertValidRequestMethod("DELETE");
    assertValidRequestMethod("HEAD");
    assertValidRequestMethod("OPTIONS");
    assertValidRequestMethod("POST");
    assertValidRequestMethod("PUT");
    assertValidRequestMethod("TRACE");
    assertValidRequestMethod("PATCH");
  }

  private void assertValidRequestMethod(String requestMethod) throws Exception {
    connection = urlFactory.open(server.url("/").url());
    connection.setRequestMethod(requestMethod);
    assertEquals(requestMethod, connection.getRequestMethod());
  }

  @Test public void setInvalidRequestMethodLowercase() throws Exception {
    assertInvalidRequestMethod("get");
  }

  @Test public void setInvalidRequestMethodConnect() throws Exception {
    assertInvalidRequestMethod("CONNECT");
  }

  private void assertInvalidRequestMethod(String requestMethod) throws Exception {
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.setRequestMethod(requestMethod);
      fail();
    } catch (ProtocolException expected) {
    }
  }

  @Test public void shoutcast() throws Exception {
    server.enqueue(new MockResponse().setStatus("ICY 200 OK")
        // .addHeader("HTTP/1.0 200 OK")
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
    connection = urlFactory.open(server.url("/").url());
    assertEquals(200, connection.getResponseCode());
    assertEquals("OK", connection.getResponseMessage());
    assertContent("mp3 data", connection);
  }

  @Test public void cannotSetNegativeFixedLengthStreamingMode() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.setFixedLengthStreamingMode(-2);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void canSetNegativeChunkedStreamingMode() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    connection.setChunkedStreamingMode(-2);
  }

  @Test public void cannotSetFixedLengthStreamingModeAfterConnect() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    try {
      connection.setFixedLengthStreamingMode(1);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void cannotSetChunkedStreamingModeAfterConnect() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    try {
      connection.setChunkedStreamingMode(1);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void cannotSetFixedLengthStreamingModeAfterChunkedStreamingMode() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    connection.setChunkedStreamingMode(1);
    try {
      connection.setFixedLengthStreamingMode(1);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void cannotSetChunkedStreamingModeAfterFixedLengthStreamingMode() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    connection.setFixedLengthStreamingMode(1);
    try {
      connection.setChunkedStreamingMode(1);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void secureFixedLengthStreaming() throws Exception {
    testSecureStreamingPost(StreamingMode.FIXED_LENGTH);
  }

  @Test public void secureChunkedStreaming() throws Exception {
    testSecureStreamingPost(StreamingMode.CHUNKED);
  }

  /**
   * Users have reported problems using HTTPS with streaming request bodies.
   * http://code.google.com/p/android/issues/detail?id=12860
   */
  private void testSecureStreamingPost(StreamingMode streamingMode) throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setBody("Success!"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());
    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    byte[] requestBody = {'A', 'B', 'C', 'D'};
    if (streamingMode == StreamingMode.FIXED_LENGTH) {
      connection.setFixedLengthStreamingMode(requestBody.length);
    } else if (streamingMode == StreamingMode.CHUNKED) {
      connection.setChunkedStreamingMode(0);
    }
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write(requestBody);
    outputStream.close();
    assertEquals("Success!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    RecordedRequest request = server.takeRequest();
    assertEquals("POST / HTTP/1.1", request.getRequestLine());
    if (streamingMode == StreamingMode.FIXED_LENGTH) {
      assertEquals(Collections.<Integer>emptyList(), request.getChunkSizes());
    } else if (streamingMode == StreamingMode.CHUNKED) {
      assertEquals(Arrays.asList(4), request.getChunkSizes());
    }
    assertEquals("ABCD", request.getBody().readUtf8());
  }

  enum StreamingMode {
    FIXED_LENGTH, CHUNKED
  }

  @Test public void authenticateWithPost() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse().setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    // fail auth three times...
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    // ...then succeed the fourth time
    server.enqueue(new MockResponse().setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build());
    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    byte[] requestBody = {'A', 'B', 'C', 'D'};
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write(requestBody);
    outputStream.close();
    assertEquals("Successful auth!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    // no authorization header for the first request...
    RecordedRequest request = server.takeRequest();
    assertNull(request.getHeader("Authorization"));

    // ...but the three requests that follow include an authorization header
    for (int i = 0; i < 3; i++) {
      request = server.takeRequest();
      assertEquals("POST / HTTP/1.1", request.getRequestLine());
      assertEquals("Basic " + RecordingAuthenticator.BASE_64_CREDENTIALS,
          request.getHeader("Authorization"));
      assertEquals("ABCD", request.getBody().readUtf8());
    }
  }

  @Test public void authenticateWithGet() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse().setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    // fail auth three times...
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    // ...then succeed the fourth time
    server.enqueue(new MockResponse().setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("Successful auth!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

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

  /** https://code.google.com/p/android/issues/detail?id=74026 */
  @Test public void authenticateWithGetAndTransparentGzip() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse().setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    // fail auth three times...
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    server.enqueue(pleaseAuthenticate);
    // ...then succeed the fourth time
    MockResponse successfulResponse = new MockResponse()
        .addHeader("Content-Encoding", "gzip")
        .setBody(gzip("Successful auth!"));
    server.enqueue(successfulResponse);

    Authenticator.setDefault(new RecordingAuthenticator());
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("Successful auth!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

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
    server.enqueue(new MockResponse().setResponseCode(401)
        .addHeader("wWw-aUtHeNtIcAtE: bAsIc rEaLm=\"pRoTeCtEd aReA\"")
        .setBody("Please authenticate."));
    server.enqueue(new MockResponse().setBody("Successful auth!"));

    Authenticator.setDefault(new RecordingAuthenticator());
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new JavaNetAuthenticator())
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("Successful auth!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
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
    MockResponse response = new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo");
    transferKind.setBody(response, "This page has moved!", 10);
    server.enqueue(response);
    server.enqueue(new MockResponse().setBody("This is the new location!"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("This is the new location!",
        readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    RecordedRequest first = server.takeRequest();
    assertEquals("GET / HTTP/1.1", first.getRequestLine());
    RecordedRequest retry = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", retry.getRequestLine());
    if (reuse) {
      assertEquals("Expected connection reuse", 1, retry.getSequenceNumber());
    }
  }

  @Test public void redirectedOnHttps() throws IOException, InterruptedException {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("This is the new location!"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("This is the new location!",
        readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    RecordedRequest first = server.takeRequest();
    assertEquals("GET / HTTP/1.1", first.getRequestLine());
    RecordedRequest retry = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", retry.getRequestLine());
    assertEquals("Expected connection reuse", 1, retry.getSequenceNumber());
  }

  @Test public void notRedirectedFromHttpsToHttp() throws IOException, InterruptedException {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: http://anyhost/foo")
        .setBody("This page has moved!"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .followSslRedirects(false)
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("This page has moved!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
  }

  @Test public void notRedirectedFromHttpToHttps() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: https://anyhost/foo")
        .setBody("This page has moved!"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .followSslRedirects(false)
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("This page has moved!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
  }

  @Test public void redirectedFromHttpsToHttpFollowingProtocolRedirects() throws Exception {
    server2.enqueue(new MockResponse().setBody("This is insecure HTTP!"));

    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/").url())
        .setBody("This page has moved!"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .followSslRedirects(true)
        .build());
    HttpsURLConnection connection = (HttpsURLConnection) urlFactory.open(server.url("/").url());
    assertContent("This is insecure HTTP!", connection);
    assertNull(connection.getCipherSuite());
    assertNull(connection.getLocalCertificates());
    assertNull(connection.getServerCertificates());
    assertNull(connection.getPeerPrincipal());
    assertNull(connection.getLocalPrincipal());
  }

  @Test public void redirectedFromHttpToHttpsFollowingProtocolRedirects() throws Exception {
    server2.useHttps(sslClient.socketFactory, false);
    server2.enqueue(new MockResponse().setBody("This is secure HTTPS!"));

    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/").url())
        .setBody("This page has moved!"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .followSslRedirects(true)
        .build());
    connection = urlFactory.open(server.url("/").url());
    assertContent("This is secure HTTPS!", connection);
    assertFalse(connection instanceof HttpsURLConnection);
  }

  @Test public void redirectToAnotherOriginServer() throws Exception {
    redirectToAnotherOriginServer(false);
  }

  @Test public void redirectToAnotherOriginServerWithHttps() throws Exception {
    redirectToAnotherOriginServer(true);
  }

  private void redirectToAnotherOriginServer(boolean https) throws Exception {
    if (https) {
      server.useHttps(sslClient.socketFactory, false);
      server2.useHttps(sslClient.socketFactory, false);
      server2.setProtocolNegotiationEnabled(false);
      urlFactory.setClient(urlFactory.client().newBuilder()
          .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
          .hostnameVerifier(new RecordingHostnameVerifier())
          .build());
    }

    server2.enqueue(new MockResponse().setBody("This is the 2nd server!"));
    server2.enqueue(new MockResponse().setBody("This is the 2nd server, again!"));

    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/").url().toString())
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("This is the first server again!"));

    connection = urlFactory.open(server.url("/").url());
    assertContent("This is the 2nd server!", connection);
    assertEquals(server2.url("/").url(), connection.getURL());

    // make sure the first server was careful to recycle the connection
    assertContent("This is the first server again!", urlFactory.open(server.url("/").url()));
    assertContent("This is the 2nd server, again!", urlFactory.open(server2.url("/").url()));

    String server1Host = server.getHostName() + ":" + server.getPort();
    String server2Host = server2.getHostName() + ":" + server2.getPort();
    assertEquals(server1Host, server.takeRequest().getHeader("Host"));
    assertEquals(server2Host, server2.takeRequest().getHeader("Host"));
    assertEquals("Expected connection reuse", 1, server.takeRequest().getSequenceNumber());
    assertEquals("Expected connection reuse", 1, server2.takeRequest().getSequenceNumber());
  }

  @Test public void redirectWithProxySelector() throws Exception {
    final List<URI> proxySelectionRequests = new ArrayList<>();
    urlFactory.setClient(urlFactory.client().newBuilder()
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
        .build());

    server2.enqueue(new MockResponse().setBody("This is the 2nd server!"));

    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + server2.url("/b").url().toString())
        .setBody("This page has moved!"));

    assertContent("This is the 2nd server!", urlFactory.open(server.url("/a").url()));

    assertEquals(Arrays.asList(server.url("/").url().toURI(), server2.url("/").url().toURI()),
        proxySelectionRequests);
  }

  @Test public void redirectWithAuthentication() throws Exception {
    server2.enqueue(new MockResponse().setBody("Page 2"));

    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(new MockResponse().setResponseCode(302)
        .addHeader("Location: " + server2.url("/b").url()));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new RecordingOkAuthenticator(Credentials.basic("jesse", "secret")))
        .build());
    assertContent("Page 2", urlFactory.open(server.url("/a").url()));

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
    server.enqueue(new MockResponse().setResponseCode(redirectCode)
        .addHeader("Location: /page2")
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    connection = urlFactory.open(server.url("/page1").url());
    connection.setDoOutput(true);
    transferKind.setForRequest(connection, 4);
    byte[] requestBody = {'A', 'B', 'C', 'D'};
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write(requestBody);
    outputStream.close();
    assertEquals("Page 2", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    assertTrue(connection.getDoOutput());

    RecordedRequest page1 = server.takeRequest();
    assertEquals("POST /page1 HTTP/1.1", page1.getRequestLine());
    assertEquals("ABCD", page1.getBody().readUtf8());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void redirectedPostStripsRequestBodyHeaders() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /page2"));
    server.enqueue(new MockResponse().setBody("Page 2"));

    connection = urlFactory.open(server.url("/page1").url());
    connection.setDoOutput(true);
    connection.addRequestProperty("Content-Length", "4");
    connection.addRequestProperty("Content-Type", "text/plain; charset=utf-8");
    connection.addRequestProperty("Transfer-Encoding", "identity");
    OutputStream outputStream = connection.getOutputStream();
    outputStream.write("ABCD".getBytes("UTF-8"));
    outputStream.close();
    assertEquals("Page 2", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    assertEquals("POST /page1 HTTP/1.1", server.takeRequest().getRequestLine());

    RecordedRequest page2 = server.takeRequest();
    assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
    assertNull(page2.getHeader("Content-Length"));
    assertNull(page2.getHeader("Content-Type"));
    assertNull(page2.getHeader("Transfer-Encoding"));
  }

  @Test public void response305UseProxy() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_USE_PROXY)
        .addHeader("Location: " + server.url("/").url())
        .setBody("This page has moved!"));
    server.enqueue(new MockResponse().setBody("Proxy Response"));

    connection = urlFactory.open(server.url("/foo").url());
    // Fails on the RI, which gets "Proxy Response"
    assertEquals("This page has moved!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

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
    server.enqueue(new MockResponse().setBody("Page 2"));

    connection = urlFactory.open(server.url("/page1").url());
    connection.setRequestMethod(method);
    byte[] requestBody = {'A', 'B', 'C', 'D'};
    if (method.equals("POST")) {
      connection.setDoOutput(true);
      OutputStream outputStream = connection.getOutputStream();
      outputStream.write(requestBody);
      outputStream.close();
    }

    String response = readAscii(connection.getInputStream(), Integer.MAX_VALUE);

    RecordedRequest page1 = server.takeRequest();
    assertEquals(method + " /page1 HTTP/1.1", page1.getRequestLine());

    if (method.equals("GET")) {
      assertEquals("Page 2", response);
    } else if (method.equals("HEAD")) {
      assertEquals("", response);
    } else {
      // Methods other than GET/HEAD shouldn't follow the redirect
      if (method.equals("POST")) {
        assertTrue(connection.getDoOutput());
        assertEquals("ABCD", page1.getBody().readUtf8());
      }
      assertEquals(1, server.getRequestCount());
      assertEquals("This page has moved!", response);
      return;
    }

    // GET/HEAD requests should have followed the redirect with the same method
    assertFalse(connection.getDoOutput());
    assertEquals(2, server.getRequestCount());
    RecordedRequest page2 = server.takeRequest();
    assertEquals(method + " /page2 HTTP/1.1", page2.getRequestLine());
  }

  @Test public void follow20Redirects() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    connection = urlFactory.open(server.url("/0").url());
    assertContent("Success!", connection);
    assertEquals(server.url("/20").url(), connection.getURL());
  }

  @Test public void doesNotFollow21Redirects() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
          .addHeader("Location: /" + (i + 1))
          .setBody("Redirecting to /" + (i + 1)));
    }

    connection = urlFactory.open(server.url("/0").url());
    try {
      connection.getInputStream();
      fail();
    } catch (ProtocolException expected) {
      assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, connection.getResponseCode());
      assertEquals("Too many follow-up requests: 21", expected.getMessage());
      assertEquals(server.url("/20").url(), connection.getURL());
    }
  }

  @Test public void httpsWithCustomTrustManager() throws Exception {
    RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
    RecordingTrustManager trustManager = new RecordingTrustManager(sslClient.trustManager);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, new TrustManager[] { trustManager }, null);

    urlFactory.setClient(urlFactory.client().newBuilder()
        .hostnameVerifier(hostnameVerifier)
        .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
        .build());
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));
    server.enqueue(new MockResponse().setBody("GHI"));

    URL url = server.url("/").url();
    assertContent("ABC", urlFactory.open(url));
    assertContent("DEF", urlFactory.open(url));
    assertContent("GHI", urlFactory.open(url));

    assertEquals(Arrays.asList("verify " + server.getHostName()), hostnameVerifier.calls);
    assertEquals(Arrays.asList("checkServerTrusted [CN=" + server.getHostName() + " 1]"),
        trustManager.calls);
  }

  @Test public void getClientRequestTimeout() throws Exception {
    enqueueClientRequestTimeoutResponses();

    HttpURLConnection connection = urlFactory.open(server.url("/").url());

    assertEquals(200, connection.getResponseCode());
    assertEquals("Body", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
  }

  private void enqueueClientRequestTimeoutResponses() {
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setResponseCode(HttpURLConnection.HTTP_CLIENT_TIMEOUT)
        .setHeader("Connection", "Close")
        .setBody("You took too long!"));
    server.enqueue(new MockResponse().setBody("Body"));
  }

  @Test public void bufferedBodyWithClientRequestTimeout() throws Exception {
    enqueueClientRequestTimeoutResponses();

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.setRequestMethod("POST");
    connection.getOutputStream().write("Hello".getBytes("UTF-8"));

    assertEquals(200, connection.getResponseCode());
    assertEquals("Body", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    RecordedRequest request1 = server.takeRequest();
    assertEquals("Hello", request1.getBody().readUtf8());

    RecordedRequest request2 = server.takeRequest();
    assertEquals("Hello", request2.getBody().readUtf8());
  }

  @Test public void streamedBodyWithClientRequestTimeout() throws Exception {
    enqueueClientRequestTimeoutResponses();

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.setRequestMethod("POST");
    connection.setChunkedStreamingMode(0);
    connection.getOutputStream().write("Hello".getBytes("UTF-8"));

    assertEquals(408, connection.getResponseCode());
    assertEquals(1, server.getRequestCount());
    connection.getErrorStream().close();
  }

  @Test public void readTimeouts() throws IOException {
    // This relies on the fact that MockWebServer doesn't close the
    // connection after a response has been sent. This causes the client to
    // try to read more bytes than are sent, which results in a timeout.
    MockResponse timeout =
        new MockResponse().setBody("ABC").clearHeaders().addHeader("Content-Length: 4");
    server.enqueue(timeout);
    server.enqueue(new MockResponse().setBody("unused")); // to keep the server alive

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.setReadTimeout(1000);
    InputStream in = connection.getInputStream();
    assertEquals('A', in.read());
    assertEquals('B', in.read());
    assertEquals('C', in.read());
    try {
      in.read(); // if Content-Length was accurate, this would return -1 immediately
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
    urlFactory.setClient(urlFactory.client().newBuilder()
        .socketFactory(new DelegatingSocketFactory(SocketFactory.getDefault()) {
          @Override protected Socket configureSocket(Socket socket) throws IOException {
            socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
            socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
            return socket;
          }
        })
        .writeTimeout(500, TimeUnit.MILLISECONDS)
        .build());

    server.start();
    server.enqueue(new MockResponse()
        .throttleBody(1, 1, TimeUnit.SECONDS)); // Prevent the server from reading!

    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    connection.setChunkedStreamingMode(0);
    OutputStream out = connection.getOutputStream();
    try {
      byte[] data = new byte[2 * 1024 * 1024]; // 2 MiB.
      out.write(data);
      fail();
    } catch (SocketTimeoutException expected) {
    }
  }

  @Test public void setChunkedEncodingAsRequestProperty() throws IOException, InterruptedException {
    server.enqueue(new MockResponse());

    connection = urlFactory.open(server.url("/").url());
    connection.setRequestProperty("Transfer-encoding", "chunked");
    connection.setDoOutput(true);
    connection.getOutputStream().write("ABC".getBytes("UTF-8"));
    assertEquals(200, connection.getResponseCode());

    RecordedRequest request = server.takeRequest();
    assertEquals("ABC", request.getBody().readUtf8());
  }

  @Test public void connectionCloseInRequest() throws IOException, InterruptedException {
    server.enqueue(new MockResponse()); // server doesn't honor the connection: close header!
    server.enqueue(new MockResponse());

    HttpURLConnection a = urlFactory.open(server.url("/").url());
    a.setRequestProperty("Connection", "close");
    assertEquals(200, a.getResponseCode());

    HttpURLConnection b = urlFactory.open(server.url("/").url());
    assertEquals(200, b.getResponseCode());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals("When connection: close is used, each request should get its own connection", 0,
        server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionCloseInResponse() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().addHeader("Connection: close"));
    server.enqueue(new MockResponse());

    HttpURLConnection a = urlFactory.open(server.url("/").url());
    assertEquals(200, a.getResponseCode());

    HttpURLConnection b = urlFactory.open(server.url("/").url());
    assertEquals(200, b.getResponseCode());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals("When connection: close is used, each request should get its own connection", 0,
        server.takeRequest().getSequenceNumber());
  }

  @Test public void connectionCloseWithRedirect() throws IOException, InterruptedException {
    MockResponse response = new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .addHeader("Connection: close");
    server.enqueue(response);
    server.enqueue(new MockResponse()
        .setBody("This is the new location!"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("This is the new location!",
        readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals("When connection: close is used, each request should get its own connection", 0,
        server.takeRequest().getSequenceNumber());
  }

  /**
   * Retry redirects if the socket is closed.
   * https://code.google.com/p/android/issues/detail?id=41576
   */
  @Test public void sameConnectionRedirectAndReuse() throws Exception {
    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new SingleInetAddressDns())
        .build());
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .setSocketPolicy(SHUTDOWN_INPUT_AT_END)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse()
        .setBody("This is the new page!"));

    assertContent("This is the new page!", urlFactory.open(server.url("/").url()));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(0, server.takeRequest().getSequenceNumber());
  }

  @Test public void responseCodeDisagreesWithHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NO_CONTENT)
        .setBody("This body is not allowed!"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    try {
      connection.getInputStream();
      fail();
    } catch (IOException expected) {
      assertEquals("HTTP 204 had non-zero Content-Length: 25", expected.getMessage());
    }
  }

  @Test public void singleByteReadIsSigned() throws IOException {
    server.enqueue(new MockResponse().setBody(new Buffer().writeByte(-2).writeByte(-1)));

    connection = urlFactory.open(server.url("/").url());
    InputStream in = connection.getInputStream();
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
    server.enqueue(new MockResponse().setBody("abc"));

    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    byte[] upload = "def".getBytes("UTF-8");

    if (transferKind == TransferKind.CHUNKED) {
      connection.setChunkedStreamingMode(0);
    } else if (transferKind == TransferKind.FIXED_LENGTH) {
      connection.setFixedLengthStreamingMode(upload.length);
    }

    OutputStream out = connection.getOutputStream();
    out.write(upload);
    assertEquals("abc", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

    out.flush(); // Dubious but permitted.
    try {
      out.write("ghi".getBytes("UTF-8"));
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void getHeadersThrows() throws IOException {
    server.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START));

    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getInputStream();
      fail();
    } catch (IOException expected) {
    }

    try {
      connection.getInputStream();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void dnsFailureThrowsIOException() throws IOException {
    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new FakeDns())
        .build());
    connection = urlFactory.open(new URL("http://host.unlikelytld"));
    try {
      connection.connect();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void malformedUrlThrowsUnknownHostException() throws IOException {
    connection = urlFactory.open(new URL("http://./foo.html"));
    try {
      connection.connect();
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void getKeepAlive() throws Exception {
    server.enqueue(new MockResponse().setBody("ABC"));

    // The request should work once and then fail
    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    connection1.setReadTimeout(100);
    InputStream input = connection1.getInputStream();
    assertEquals("ABC", readAscii(input, Integer.MAX_VALUE));
    server.shutdown();
    try {
      HttpURLConnection connection2 = urlFactory.open(server.url("").url());
      connection2.setReadTimeout(100);
      connection2.getInputStream();
      fail();
    } catch (ConnectException expected) {
    }
  }

  /** http://code.google.com/p/android/issues/detail?id=14562 */
  @Test public void readAfterLastByte() throws Exception {
    server.enqueue(new MockResponse().setBody("ABC")
        .clearHeaders()
        .addHeader("Connection: close")
        .setSocketPolicy(DISCONNECT_AT_END));

    connection = urlFactory.open(server.url("/").url());
    InputStream in = connection.getInputStream();
    assertEquals("ABC", readAscii(in, 3));
    assertEquals(-1, in.read());
    assertEquals(-1, in.read()); // throws IOException in Gingerbread
  }

  @Test public void getContent() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain").setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    InputStream in = (InputStream) connection.getContent();
    assertEquals("A", readAscii(in, Integer.MAX_VALUE));
  }

  @Test public void getContentOfType() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain").setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getContent(null);
      fail();
    } catch (NullPointerException expected) {
    }
    try {
      connection.getContent(new Class[] {null});
      fail();
    } catch (NullPointerException expected) {
    }
    assertNull(connection.getContent(new Class[] {getClass()}));
    connection.getInputStream().close();
  }

  @Test public void getOutputStreamOnGetFails() throws Exception {
    server.enqueue(new MockResponse());
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getOutputStream();
      fail();
    } catch (ProtocolException expected) {
    }
    connection.getInputStream().close();
  }

  @Test public void getOutputAfterGetInputStreamFails() throws Exception {
    server.enqueue(new MockResponse());
    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    try {
      connection.getInputStream();
      connection.getOutputStream();
      fail();
    } catch (ProtocolException expected) {
    }
  }

  @Test public void setDoOutputOrDoInputAfterConnectFails() throws Exception {
    server.enqueue(new MockResponse());
    connection = urlFactory.open(server.url("/").url());
    connection.connect();
    try {
      connection.setDoOutput(true);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      connection.setDoInput(true);
      fail();
    } catch (IllegalStateException expected) {
    }
    connection.getInputStream().close();
  }

  @Test public void clientSendsContentLength() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    OutputStream out = connection.getOutputStream();
    out.write(new byte[] {'A', 'B', 'C'});
    out.close();
    assertEquals("A", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    RecordedRequest request = server.takeRequest();
    assertEquals("3", request.getHeader("Content-Length"));
    connection.getInputStream().close();
  }

  @Test public void getContentLengthConnects() throws Exception {
    server.enqueue(new MockResponse().setBody("ABC"));
    connection = urlFactory.open(server.url("/").url());
    assertEquals(3, connection.getContentLength());
    connection.getInputStream().close();
  }

  @Test public void getContentTypeConnects() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Type: text/plain").setBody("ABC"));
    connection = urlFactory.open(server.url("/").url());
    assertEquals("text/plain", connection.getContentType());
    connection.getInputStream().close();
  }

  @Test public void getContentEncodingConnects() throws Exception {
    server.enqueue(new MockResponse().addHeader("Content-Encoding: identity").setBody("ABC"));
    connection = urlFactory.open(server.url("/").url());
    assertEquals("identity", connection.getContentEncoding());
    connection.getInputStream().close();
  }

  // http://b/4361656
  @Test public void urlContainsQueryButNoPath() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    URL url = new URL("http", server.getHostName(), server.getPort(), "?query");
    assertEquals("A", readAscii(urlFactory.open(url).getInputStream(), Integer.MAX_VALUE));
    RecordedRequest request = server.takeRequest();
    assertEquals("GET /?query HTTP/1.1", request.getRequestLine());
  }

  @Test public void doOutputForMethodThatDoesntSupportOutput() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    connection.setRequestMethod("HEAD");
    connection.setDoOutput(true);
    try {
      connection.connect();
      fail();
    } catch (IOException expected) {
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
    MockResponse response = new MockResponse();
    transferKind.setBody(response, body, 4);
    server.enqueue(response);
    connection = urlFactory.open(server.url("/").url());
    InputStream in = connection.getInputStream();
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
    server.enqueue(new MockResponse().setBody("A").setSocketPolicy(DISCONNECT_AT_END));
    server.enqueue(new MockResponse().setBody("B"));
    server.enqueue(new MockResponse().setBody("C"));

    assertContent("A", urlFactory.open(server.url("/a").url()));

    // Give the server time to disconnect.
    Thread.sleep(500);

    // If the request body is larger than OkHttp's replay buffer, the failure may still occur.
    byte[] requestBody = new byte[requestSize];
    new Random(0).nextBytes(requestBody);

    for (int j = 0; j < 2; j++) {
      try {
        connection = urlFactory.open(server.url("/b").url());
        connection.setRequestMethod("POST");
        transferKind.setForRequest(connection, requestBody.length);
        for (int i = 0; i < requestBody.length; i += 1024) {
          connection.getOutputStream().write(requestBody, i, 1024);
        }
        connection.getOutputStream().close();
        assertContent("B", connection);
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
    assertEquals(Arrays.toString(requestBody), Arrays.toString(requestB.getBody().readByteArray()));
  }

  @Test public void postBodyRetransmittedOnFailureRecovery() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));
    server.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setBody("def"));

    // Seed the connection pool so we have something that can fail.
    assertContent("abc", urlFactory.open(server.url("/").url()));

    HttpURLConnection post = urlFactory.open(server.url("/").url());
    post.setDoOutput(true);
    post.getOutputStream().write("body!".getBytes(UTF_8));
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

  @Test public void fullyBufferedPostIsTooShort() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    connection = urlFactory.open(server.url("/b").url());
    connection.setRequestProperty("Content-Length", "4");
    connection.setRequestMethod("POST");
    OutputStream out = connection.getOutputStream();
    out.write('a');
    out.write('b');
    out.write('c');
    try {
      out.close();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void fullyBufferedPostIsTooLong() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    connection = urlFactory.open(server.url("/b").url());
    connection.setRequestProperty("Content-Length", "3");
    connection.setRequestMethod("POST");
    OutputStream out = connection.getOutputStream();
    out.write('a');
    out.write('b');
    out.write('c');
    try {
      out.write('d');
      out.flush();
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
    server.enqueue(new MockResponse().setBody("body"));
    connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("B", "");
    assertContent("body", connection);
    assertEquals("", connection.getRequestProperty("B"));
  }

  @Test public void emptyResponseHeaderValueIsAllowed() throws Exception {
    server.enqueue(new MockResponse().addHeader("A:").setBody("body"));
    connection = urlFactory.open(server.url("/").url());
    assertContent("body", connection);
    assertEquals("", connection.getHeaderField("A"));
  }

  @Test public void emptyRequestHeaderNameIsStrict() throws Exception {
    server.enqueue(new MockResponse().setBody("body"));
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.setRequestProperty("", "A");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void emptyResponseHeaderNameIsLenient() throws Exception {
    Headers.Builder headers = new Headers.Builder();
    Internal.instance.addLenient(headers, ":A");
    server.enqueue(new MockResponse().setHeaders(headers.build()).setBody("body"));
    connection = urlFactory.open(server.url("/").url());
    connection.getResponseCode();
    assertEquals("A", connection.getHeaderField(""));
    connection.getInputStream().close();
  }

  @Test public void requestHeaderValidationIsStrict() throws Exception {
    connection = urlFactory.open(server.url("/").url());
    try {
      connection.addRequestProperty("a\tb", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      connection.addRequestProperty("Name", "c\u007fd");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      connection.addRequestProperty("", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      connection.addRequestProperty("\ud83c\udf69", "Value");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      connection.addRequestProperty("Name", "\u2615\ufe0f");
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
    server.enqueue(new MockResponse().setHeaders(headers));

    connection = urlFactory.open(server.url("/").url());
    connection.getResponseCode();
    assertEquals("c\u007fd", connection.getHeaderField("a\tb"));
    assertEquals("\u2615\ufe0f", connection.getHeaderField("\ud83c\udf69"));
    assertEquals("ef", connection.getHeaderField(""));
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
    MockResponse pleaseAuthenticate = new MockResponse().setResponseCode(401)
        .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
        .setBody("Please authenticate.");
    server.enqueue(pleaseAuthenticate);
    server.enqueue(new MockResponse().setBody("A"));

    String credential = Credentials.basic("jesse", "peanutbutter");
    RecordingOkAuthenticator authenticator = new RecordingOkAuthenticator(credential);
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(authenticator)
        .build());
    assertContent("A", urlFactory.open(server.url("/private").url()));

    assertNull(server.takeRequest().getHeader("Authorization"));
    assertEquals(credential, server.takeRequest().getHeader("Authorization"));

    assertEquals(Proxy.NO_PROXY, authenticator.onlyProxy());
    Response response = authenticator.onlyResponse();
    assertEquals("/private", response.request().url().url().getPath());
    assertEquals(Arrays.asList(new Challenge("Basic", "protected area")), response.challenges());
  }

  @Test public void customTokenAuthenticator() throws Exception {
    MockResponse pleaseAuthenticate = new MockResponse().setResponseCode(401)
        .addHeader("WWW-Authenticate: Bearer realm=\"oauthed\"")
        .setBody("Please authenticate.");
    server.enqueue(pleaseAuthenticate);
    server.enqueue(new MockResponse().setBody("A"));

    RecordingOkAuthenticator authenticator = new RecordingOkAuthenticator("oauthed abc123");
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(authenticator)
        .build());
    assertContent("A", urlFactory.open(server.url("/private").url()));

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
    server.enqueue(new MockResponse().setBody("c"));

    RecordingOkAuthenticator authenticator = new RecordingOkAuthenticator(
        Credentials.basic("jesse", "peanutbutter"));
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(authenticator)
        .build());
    assertContent("c", urlFactory.open(server.url("/a").url()));

    Response challengeResponse = authenticator.responses.get(0);
    assertEquals("/b", challengeResponse.request().url().url().getPath());

    Response redirectedBy = challengeResponse.priorResponse();
    assertEquals("/a", redirectedBy.request().url().url().getPath());
  }

  @Test public void attemptAuthorization20Times() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse().setResponseCode(401));
    }
    server.enqueue(new MockResponse().setBody("Success!"));

    String credential = Credentials.basic("jesse", "peanutbutter");
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential))
        .build());

    connection = urlFactory.open(server.url("/0").url());
    assertContent("Success!", connection);
  }

  @Test public void doesNotAttemptAuthorization21Times() throws Exception {
    for (int i = 0; i < 21; i++) {
      server.enqueue(new MockResponse().setResponseCode(401));
    }

    String credential = Credentials.basic("jesse", "peanutbutter");
    urlFactory.setClient(urlFactory.client().newBuilder()
        .authenticator(new RecordingOkAuthenticator(credential))
        .build());

    connection = urlFactory.open(server.url("/").url());
    try {
      connection.getInputStream();
      fail();
    } catch (ProtocolException expected) {
      assertEquals(401, connection.getResponseCode());
      assertEquals("Too many follow-up requests: 21", expected.getMessage());
    }
  }

  @Test public void setsNegotiatedProtocolHeader_HTTP_2() throws Exception {
    setsNegotiatedProtocolHeader(Protocol.HTTP_2);
  }

  private void setsNegotiatedProtocolHeader(Protocol protocol) throws IOException {
    enableProtocol(protocol);
    server.enqueue(new MockResponse().setBody("A"));
    urlFactory.setClient(urlFactory.client().newBuilder()
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build());
    connection = urlFactory.open(server.url("/").url());
    List<String> protocolValues = connection.getHeaderFields().get(SELECTED_PROTOCOL);
    assertEquals(Arrays.asList(protocol.toString()), protocolValues);
    assertContent("A", connection);
  }

  @Test public void http10SelectedProtocol() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.0 200 OK"));
    connection = urlFactory.open(server.url("/").url());
    List<String> protocolValues = connection.getHeaderFields().get(SELECTED_PROTOCOL);
    assertEquals(Arrays.asList("http/1.0"), protocolValues);
  }

  @Test public void http11SelectedProtocol() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 200 OK"));
    connection = urlFactory.open(server.url("/").url());
    List<String> protocolValues = connection.getHeaderFields().get(SELECTED_PROTOCOL);
    assertEquals(Arrays.asList("http/1.1"), protocolValues);
  }

  /** For example, empty Protobuf RPC messages end up as a zero-length POST. */
  @Test public void zeroLengthPost() throws IOException, InterruptedException {
    zeroLengthPayload("POST");
  }

  @Test public void zeroLengthPost_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    zeroLengthPost();
  }

  /** For example, creating an Amazon S3 bucket ends up as a zero-length POST. */
  @Test public void zeroLengthPut() throws IOException, InterruptedException {
    zeroLengthPayload("PUT");
  }

  @Test public void zeroLengthPut_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    zeroLengthPut();
  }

  private void zeroLengthPayload(String method)
      throws IOException, InterruptedException {
    server.enqueue(new MockResponse());
    connection = urlFactory.open(server.url("/").url());
    connection.setRequestProperty("Content-Length", "0");
    connection.setRequestMethod(method);
    connection.setFixedLengthStreamingMode(0);
    connection.setDoOutput(true);
    assertContent("", connection);
    RecordedRequest zeroLengthPayload = server.takeRequest();
    assertEquals(method, zeroLengthPayload.getMethod());
    assertEquals("0", zeroLengthPayload.getHeader("content-length"));
    assertEquals(0L, zeroLengthPayload.getBodySize());
  }

  @Test public void unspecifiedRequestBodyContentTypeGetsDefault() throws Exception {
    server.enqueue(new MockResponse());

    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    connection.getOutputStream().write("abc".getBytes(UTF_8));
    assertEquals(200, connection.getResponseCode());

    RecordedRequest request = server.takeRequest();
    assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"));
    assertEquals("3", request.getHeader("Content-Length"));
    assertEquals("abc", request.getBody().readUtf8());
  }

  @Test public void setProtocols() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    urlFactory.setClient(urlFactory.client().newBuilder()
        .protocols(Arrays.asList(Protocol.HTTP_1_1))
        .build());
    assertContent("A", urlFactory.open(server.url("/").url()));
  }

  @Test public void setProtocolsWithoutHttp11() throws Exception {
    try {
      new OkHttpClient.Builder().protocols(Arrays.asList(Protocol.HTTP_2));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void setProtocolsWithNull() throws Exception {
    try {
      new OkHttpClient.Builder().protocols(Arrays.asList(Protocol.HTTP_1_1, null));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void veryLargeFixedLengthRequest() throws Exception {
    server.setBodyLimit(0);
    server.enqueue(new MockResponse());

    connection = urlFactory.open(server.url("/").url());
    connection.setDoOutput(true);
    long contentLength = Integer.MAX_VALUE + 1L;
    connection.setFixedLengthStreamingMode(contentLength);
    OutputStream out = connection.getOutputStream();
    byte[] buffer = new byte[1024 * 1024];
    for (long bytesWritten = 0; bytesWritten < contentLength; ) {
      int byteCount = (int) Math.min(buffer.length, contentLength - bytesWritten);
      out.write(buffer, 0, byteCount);
      bytesWritten += byteCount;
    }
    assertContent("", connection);

    RecordedRequest request = server.takeRequest();
    assertEquals(Long.toString(contentLength), request.getHeader("Content-Length"));
  }

  @Test public void testNoSslFallback() throws Exception {
    server.useHttps(sslClient.socketFactory, false /* tunnelProxy */);
    server.enqueue(new MockResponse().setSocketPolicy(FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setBody("Response that would have needed fallbacks"));

    HttpsURLConnection connection = (HttpsURLConnection) server.url("/").url().openConnection();
    connection.setSSLSocketFactory(sslClient.socketFactory);
    try {
      connection.getInputStream();
      fail();
    } catch (SSLProtocolException expected) {
      // RI response to the FAIL_HANDSHAKE
    } catch (SSLHandshakeException expected) {
      // Android's response to the FAIL_HANDSHAKE
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
    server.enqueue(new MockResponse().setBody("b"));

    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection1.getResponseCode());
    assertContent("", connection1);

    HttpURLConnection connection2 = urlFactory.open(server.url("/").url());
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertContent("b", connection2);

    RecordedRequest requestA = server.takeRequest();
    assertEquals(0, requestA.getSequenceNumber());

    RecordedRequest requestB = server.takeRequest();
    assertEquals(1, requestB.getSequenceNumber());
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
    server.enqueue(new MockResponse().setBody("This is the new page!"));

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertContent("This is the new page!", connection);

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

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.setRequestMethod("DELETE");
    connection.setDoOutput(true);
    connection.getOutputStream().write("BODY".getBytes(UTF_8));
    assertEquals(200, connection.getResponseCode());

    RecordedRequest request = server.takeRequest();
    assertEquals("DELETE", request.getMethod());
    assertEquals("BODY", request.getBody().readUtf8());
  }

  @Test public void userAgentPicksUpHttpAgentSystemProperty() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    System.setProperty("http.agent", "foo");
    assertContent("abc", urlFactory.open(server.url("/").url()));

    RecordedRequest request = server.takeRequest();
    assertEquals("foo", request.getHeader("User-Agent"));
  }

  /** https://github.com/square/okhttp/issues/891 */
  @Test public void userAgentSystemPropertyIsNotAscii() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    System.setProperty("http.agent", "a\nb\ud83c\udf69c\ud83c\udf68d\u007fe");
    assertContent("abc", urlFactory.open(server.url("/").url()));

    RecordedRequest request = server.takeRequest();
    assertEquals("a?b?c?d?e", request.getHeader("User-Agent"));
  }

  @Test public void userAgentDefaultsToOkHttpVersion() throws Exception {
    server.enqueue(new MockResponse().setBody("abc"));

    assertContent("abc", urlFactory.open(server.url("/").url()));

    RecordedRequest request = server.takeRequest();
    assertEquals(Version.userAgent(), request.getHeader("User-Agent"));
  }

  @Test public void interceptorsNotInvoked() throws Exception {
    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        throw new AssertionError();
      }
    };
    urlFactory.setClient(urlFactory.client().newBuilder()
        .addInterceptor(interceptor)
        .addNetworkInterceptor(interceptor)
        .build());

    server.enqueue(new MockResponse().setBody("abc"));
    assertContent("abc", urlFactory.open(server.url("/").url()));
  }

  @Test public void urlWithSpaceInHost() throws Exception {
    URLConnection urlConnection = urlFactory.open(new URL("http://and roid.com/"));
    try {
      urlConnection.getInputStream();
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void urlWithSpaceInHostViaHttpProxy() throws Exception {
    server.enqueue(new MockResponse());
    URLConnection urlConnection =
        urlFactory.open(new URL("http://and roid.com/"), server.toProxyAddress());

    try {
      // This test is to check that a NullPointerException is not thrown.
      urlConnection.getInputStream();
      fail(); // the RI makes a bogus proxy request for "GET http://and roid.com/ HTTP/1.1"
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void urlHostWithNul() throws Exception {
    URLConnection urlConnection = urlFactory.open(new URL("http://host\u0000/"));
    try {
      urlConnection.getInputStream();
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void urlRedirectToHostWithNul() throws Exception {
    String redirectUrl = "http://host\u0000/";
    server.enqueue(new MockResponse().setResponseCode(302)
        .addHeaderLenient("Location", redirectUrl));

    HttpURLConnection urlConnection = urlFactory.open(server.url("/").url());
    assertEquals(302, urlConnection.getResponseCode());
    assertEquals(redirectUrl, urlConnection.getHeaderField("Location"));
  }

  @Test public void urlWithBadAsciiHost() throws Exception {
    URLConnection urlConnection = urlFactory.open(new URL("http://host\u0001/"));
    try {
      urlConnection.getInputStream();
      fail();
    } catch (UnknownHostException expected) {
    }
  }

  @Test public void instanceFollowsRedirects() throws Exception {
    testInstanceFollowsRedirects("http://www.google.com/");
    testInstanceFollowsRedirects("https://www.google.com/");
  }

  @Test public void setSslSocketFactoryFailsOnJdk9() throws Exception {
    assumeTrue(getPlatform().equals("jdk9"));

    enableProtocol(Protocol.HTTP_2);
    URL url = server.url("/").url();
    HttpsURLConnection connection = (HttpsURLConnection) urlFactory.open(url);
    try {
      connection.setSSLSocketFactory(sslClient.socketFactory);
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  /** Confirm that runtime exceptions thrown inside of OkHttp propagate to the caller. */
  @Test public void unexpectedExceptionSync() throws Exception {
    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new Dns() {
          @Override public List<InetAddress> lookup(String hostname) {
            throw new RuntimeException("boom!");
          }
        })
        .build());

    server.enqueue(new MockResponse());

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    try {
      connection.getResponseCode(); // Use the synchronous implementation.
      fail();
    } catch (RuntimeException expected) {
      assertEquals("boom!", expected.getMessage());
    }
  }

  /** Confirm that runtime exceptions thrown inside of OkHttp propagate to the caller. */
  @Test public void unexpectedExceptionAsync() throws Exception {
    urlFactory.setClient(urlFactory.client().newBuilder()
        .dns(new Dns() {
          @Override public List<InetAddress> lookup(String hostname) {
            throw new RuntimeException("boom!");
          }
        })
        .build());

    server.enqueue(new MockResponse());

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    try {
      connection.connect(); // Force the async implementation.
      fail();
    } catch (RuntimeException expected) {
      assertEquals("boom!", expected.getMessage());
    }
  }

  @Test public void callsNotManagedByDispatcher() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("abc"));

    Dispatcher dispatcher = urlFactory.client().dispatcher();
    assertEquals(0, dispatcher.runningCallsCount());

    connection = urlFactory.open(server.url("/").url());
    assertEquals(0, dispatcher.runningCallsCount());

    connection.connect();
    assertEquals(0, dispatcher.runningCallsCount());

    assertContent("abc", connection);
    assertEquals(0, dispatcher.runningCallsCount());
  }

  @Test public void streamedBodyIsRetriedOnHttp2Shutdown() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    server.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody("abc"));
    server.enqueue(new MockResponse()
        .setBody("def"));

    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    connection1.setChunkedStreamingMode(4096);
    connection1.setRequestMethod("POST");
    connection1.connect(); // Establish healthy HTTP/2 connection, but don't write yet.

    // Send a separate request which will trigger a GOAWAY frame on the healthy connection.
    HttpURLConnection connection2 = urlFactory.open(server.url("/").url());
    assertContent("abc", connection2);

    // Ensure the GOAWAY frame has time to be read and processed.
    Thread.sleep(500);

    OutputStream os = connection1.getOutputStream();
    os.write(new byte[] { '1', '2', '3' });
    os.close();
    assertContent("def", connection1);

    RecordedRequest request1 = server.takeRequest();
    assertEquals(0, request1.getSequenceNumber());

    RecordedRequest request2 = server.takeRequest();
    assertEquals("123", request2.getBody().readUtf8());
    assertEquals(0, request2.getSequenceNumber());
  }

  private void testInstanceFollowsRedirects(String spec) throws Exception {
    URL url = new URL(spec);
    HttpURLConnection urlConnection = urlFactory.open(url);
    urlConnection.setInstanceFollowRedirects(true);
    assertTrue(urlConnection.getInstanceFollowRedirects());
    urlConnection.setInstanceFollowRedirects(false);
    assertFalse(urlConnection.getInstanceFollowRedirects());
  }

  /** Returns a gzipped copy of {@code bytes}. */
  public Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink gzipSink = Okio.buffer(new GzipSink(result));
    gzipSink.writeUtf8(data);
    gzipSink.close();
    return result;
  }

  /**
   * Reads at most {@code limit} characters from {@code in} and asserts that content equals {@code
   * expected}.
   */
  private void assertContent(String expected, HttpURLConnection connection, int limit)
      throws IOException {
    connection.connect();
    assertEquals(expected, readAscii(connection.getInputStream(), limit));
  }

  private void assertContent(String expected, HttpURLConnection connection) throws IOException {
    assertContent(expected, connection, Integer.MAX_VALUE);
  }

  private Set<String> newSet(String... elements) {
    return new LinkedHashSet<>(Arrays.asList(elements));
  }

  enum TransferKind {
    CHUNKED() {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize)
          throws IOException {
        response.setChunkedBody(content, chunkSize);
      }

      @Override void setForRequest(HttpURLConnection connection, int contentLength) {
        connection.setChunkedStreamingMode(5);
      }
    },
    FIXED_LENGTH() {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
      }

      @Override void setForRequest(HttpURLConnection connection, int contentLength) {
        connection.setFixedLengthStreamingMode(contentLength);
      }
    },
    END_OF_STREAM() {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
        response.setSocketPolicy(DISCONNECT_AT_END);
        response.removeHeader("Content-Length");
      }

      @Override void setForRequest(HttpURLConnection connection, int contentLength) {
      }
    };

    abstract void setBody(MockResponse response, Buffer content, int chunkSize) throws IOException;

    abstract void setForRequest(HttpURLConnection connection, int contentLength);

    void setBody(MockResponse response, String content, int chunkSize) throws IOException {
      setBody(response, new Buffer().writeUtf8(content), chunkSize);
    }
  }

  enum ProxyConfig {
    NO_PROXY() {
      @Override public HttpURLConnection connect(
          MockWebServer server, OkUrlFactory streamHandlerFactory, URL url)
          throws IOException {
        streamHandlerFactory.setClient(streamHandlerFactory.client().newBuilder()
            .proxy(Proxy.NO_PROXY)
            .build());
        return streamHandlerFactory.open(url);
      }
    },

    CREATE_ARG() {
      @Override public HttpURLConnection connect(
          MockWebServer server, OkUrlFactory streamHandlerFactory, URL url)
          throws IOException {
        streamHandlerFactory.setClient(streamHandlerFactory.client().newBuilder()
            .proxy(server.toProxyAddress())
            .build());
        return streamHandlerFactory.open(url);
      }
    },

    PROXY_SYSTEM_PROPERTY() {
      @Override public HttpURLConnection connect(
          MockWebServer server, OkUrlFactory streamHandlerFactory, URL url)
          throws IOException {
        System.setProperty("proxyHost", server.getHostName());
        System.setProperty("proxyPort", Integer.toString(server.getPort()));
        return streamHandlerFactory.open(url);
      }
    },

    HTTP_PROXY_SYSTEM_PROPERTY() {
      @Override public HttpURLConnection connect(
          MockWebServer server, OkUrlFactory streamHandlerFactory, URL url)
          throws IOException {
        System.setProperty("http.proxyHost", server.getHostName());
        System.setProperty("http.proxyPort", Integer.toString(server.getPort()));
        return streamHandlerFactory.open(url);
      }
    },

    HTTPS_PROXY_SYSTEM_PROPERTY() {
      @Override public HttpURLConnection connect(
          MockWebServer server, OkUrlFactory streamHandlerFactory, URL url)
          throws IOException {
        System.setProperty("https.proxyHost", server.getHostName());
        System.setProperty("https.proxyPort", Integer.toString(server.getPort()));
        return streamHandlerFactory.open(url);
      }
    };

    public abstract HttpURLConnection connect(
        MockWebServer server, OkUrlFactory streamHandlerFactory, URL url)
        throws IOException;
  }

  private static class RecordingTrustManager implements X509TrustManager {
    private final List<String> calls = new ArrayList<>();
    private final X509TrustManager delegate;

    public RecordingTrustManager(X509TrustManager delegate) {
      this.delegate = delegate;
    }

    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }

    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      calls.add("checkClientTrusted " + certificatesToString(chain));
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
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
    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build());
    server.useHttps(sslClient.socketFactory, false);
    server.setProtocolNegotiationEnabled(true);
    server.setProtocols(urlFactory.client().protocols());
  }

  /**
   * Used during tests that involve TLS connection fallback attempts. OkHttp includes the
   * TLS_FALLBACK_SCSV cipher on fallback connections. See {@link FallbackTestClientSocketFactory}
   * for details.
   */
  private FallbackTestClientSocketFactory suppressTlsFallbackClientSocketFactory() {
    return new FallbackTestClientSocketFactory(sslClient.socketFactory);
  }

  private String getPlatform() {
    return System.getProperty("okhttp.platform", "platform");
  }
}
