/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.squareup.okhttp.internal.huc;

import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.HttpResponseCache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkResponseCache;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.security.Principal;
import java.security.cert.Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.okhttp.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Android's HttpResponseCacheTest. This tests both the {@link HttpResponseCache} implementation and
 * the behavior of {@link com.squareup.okhttp.OkResponseCache} classes generally.
 */
public final class HttpResponseCacheTest {
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
    @Override public boolean verify(String s, SSLSession sslSession) {
      return true;
    }
  };

  private static final SSLContext sslContext = SslContextBuilder.localhost();

  private final OkHttpClient client = new OkHttpClient();
  private MockWebServer server = new MockWebServer();
  private MockWebServer server2 = new MockWebServer();
  private HttpResponseCache cache;
  private final CookieManager cookieManager = new CookieManager();

  @Before public void setUp() throws Exception {
    String tmp = System.getProperty("java.io.tmpdir");
    File cacheDir = new File(tmp, "HttpCache-" + UUID.randomUUID());
    cache = new HttpResponseCache(cacheDir, Integer.MAX_VALUE);
    ResponseCache.setDefault(cache);
    CookieHandler.setDefault(cookieManager);
    server.setNpnEnabled(false);
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    server2.shutdown();
    ResponseCache.setDefault(null);
    cache.delete();
    CookieHandler.setDefault(null);
  }

  private HttpURLConnection openConnection(URL url) {
    return client.open(url);
  }

  @Test public void responseCacheAccessWithOkHttpMember() throws IOException {
    ResponseCache.setDefault(null);
    client.setResponseCache(cache);
    assertSame(cache, client.getOkResponseCache());
    assertNull(client.getResponseCache());
  }

  @Test public void responseCacheAccessWithGlobalDefault() throws IOException {
    ResponseCache.setDefault(cache);
    client.setResponseCache(null);
    assertNull(client.getOkResponseCache());
    assertNull(client.getResponseCache());
  }

  /**
   * Test that response caching is consistent with the RI and the spec.
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.4
   */
  @Test public void responseCachingByResponseCode() throws Exception {
    // Test each documented HTTP/1.1 code, plus the first unused value in each range.
    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html

    // We can't test 100 because it's not really a response.
    // assertCached(false, 100);
    assertCached(false, 101);
    assertCached(false, 102);
    assertCached(true, 200);
    assertCached(false, 201);
    assertCached(false, 202);
    assertCached(true, 203);
    assertCached(false, 204);
    assertCached(false, 205);
    assertCached(false, 206); // we don't cache partial responses
    assertCached(false, 207);
    assertCached(true, 300);
    assertCached(true, 301);
    for (int i = 302; i <= 308; ++i) {
      assertCached(false, i);
    }
    for (int i = 400; i <= 406; ++i) {
      assertCached(false, i);
    }
    // (See test_responseCaching_407.)
    assertCached(false, 408);
    assertCached(false, 409);
    // (See test_responseCaching_410.)
    for (int i = 411; i <= 418; ++i) {
      assertCached(false, i);
    }
    for (int i = 500; i <= 506; ++i) {
      assertCached(false, i);
    }
  }

  /**
   * Response code 407 should only come from proxy servers. Android's client
   * throws if it is sent by an origin server.
   */
  @Test public void originServerSends407() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(407));
    server.play();

    URL url = server.getUrl("/");
    HttpURLConnection conn = openConnection(url);
    try {
      conn.getResponseCode();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test public void responseCaching_410() throws Exception {
    // the HTTP spec permits caching 410s, but the RI doesn't.
    assertCached(true, 410);
  }

  private void assertCached(boolean shouldPut, int responseCode) throws Exception {
    server = new MockWebServer();
    MockResponse response =
        new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
            .setResponseCode(responseCode)
            .setBody("ABCDE")
            .addHeader("WWW-Authenticate: challenge");
    if (responseCode == HttpURLConnection.HTTP_PROXY_AUTH) {
      response.addHeader("Proxy-Authenticate: Basic realm=\"protected area\"");
    } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
      response.addHeader("WWW-Authenticate: Basic realm=\"protected area\"");
    }
    server.enqueue(response);
    server.play();

    URL url = server.getUrl("/");
    HttpURLConnection conn = openConnection(url);
    assertEquals(responseCode, conn.getResponseCode());

    // exhaust the content stream
    readAscii(conn);

    Response cached = cache.get(new Request.Builder().url(url).build());
    if (shouldPut) {
      assertNotNull(Integer.toString(responseCode), cached);
      cached.body().close();
    } else {
      assertNull(Integer.toString(responseCode), cached);
    }
    server.shutdown(); // tearDown() isn't sufficient; this test starts multiple servers
  }

  /**
   * Test that we can interrogate the response when the cache is being
   * populated. http://code.google.com/p/android/issues/detail?id=7787
   */
  @Test public void responseCacheCallbackApis() throws Exception {
    final String body = "ABCDE";
    final AtomicInteger cacheCount = new AtomicInteger();

    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 200 Fantastic")
        .addHeader("Content-Type: text/plain")
        .addHeader("fgh: ijk")
        .setBody(body));
    server.play();

    client.setOkResponseCache(new AbstractOkResponseCache() {
      @Override public CacheRequest put(Response response) throws IOException {
        assertEquals(server.getUrl("/"), response.request().url());
        assertEquals(200, response.code());
        assertNull(response.body());
        assertEquals("5", response.header("Content-Length"));
        assertEquals("text/plain", response.header("Content-Type"));
        assertEquals("ijk", response.header("fgh"));
        cacheCount.incrementAndGet();
        return null;
      }
    });

    URL url = server.getUrl("/");
    HttpURLConnection connection = openConnection(url);
    assertEquals(body, readAscii(connection));
    assertEquals(1, cacheCount.get());
  }

  /** Don't explode if the cache returns a null body. http://b/3373699 */
  @Test public void responseCacheReturnsNullOutputStream() throws Exception {
    final AtomicBoolean aborted = new AtomicBoolean();
    client.setOkResponseCache(new AbstractOkResponseCache() {
      @Override public CacheRequest put(Response response) throws IOException {
        return new CacheRequest() {
          @Override public void abort() {
            aborted.set(true);
          }

          @Override public OutputStream getBody() throws IOException {
            return null;
          }
        };
      }
    });

    server.enqueue(new MockResponse().setBody("abcdef"));
    server.play();

    HttpURLConnection connection = client.open(server.getUrl("/"));
    assertEquals("abc", readAscii(connection, 3));
    connection.getInputStream().close();
    assertFalse(aborted.get()); // The best behavior is ambiguous, but RI 6 doesn't abort here
  }

  @Test public void responseCachingAndInputStreamSkipWithFixedLength() throws IOException {
    testResponseCaching(TransferKind.FIXED_LENGTH);
  }

  @Test public void responseCachingAndInputStreamSkipWithChunkedEncoding() throws IOException {
    testResponseCaching(TransferKind.CHUNKED);
  }

  @Test public void responseCachingAndInputStreamSkipWithNoLengthHeaders() throws IOException {
    testResponseCaching(TransferKind.END_OF_STREAM);
  }

  /**
   * HttpURLConnection.getInputStream().skip(long) causes ResponseCache corruption
   * http://code.google.com/p/android/issues/detail?id=8175
   */
  private void testResponseCaching(TransferKind transferKind) throws IOException {
    MockResponse response =
        new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
            .setStatus("HTTP/1.1 200 Fantastic");
    transferKind.setBody(response, "I love puppies but hate spiders", 1);
    server.enqueue(response);
    server.play();

    // Make sure that calling skip() doesn't omit bytes from the cache.
    HttpURLConnection urlConnection = openConnection(server.getUrl("/"));
    InputStream in = urlConnection.getInputStream();
    assertEquals("I love ", readAscii(urlConnection, "I love ".length()));
    reliableSkip(in, "puppies but hate ".length());
    assertEquals("spiders", readAscii(urlConnection, "spiders".length()));
    assertEquals(-1, in.read());
    in.close();
    assertEquals(1, cache.getWriteSuccessCount());
    assertEquals(0, cache.getWriteAbortCount());

    urlConnection = openConnection(server.getUrl("/")); // cached!
    in = urlConnection.getInputStream();
    assertEquals("I love puppies but hate spiders",
        readAscii(urlConnection, "I love puppies but hate spiders".length()));
    assertEquals(200, urlConnection.getResponseCode());
    assertEquals("Fantastic", urlConnection.getResponseMessage());

    assertEquals(-1, in.read());
    in.close();
    assertEquals(1, cache.getWriteSuccessCount());
    assertEquals(0, cache.getWriteAbortCount());
    assertEquals(2, cache.getRequestCount());
    assertEquals(1, cache.getHitCount());
  }

  @Test public void secureResponseCaching() throws IOException {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.play();

    HttpsURLConnection c1 = (HttpsURLConnection) client.open(server.getUrl("/"));
    c1.setSSLSocketFactory(sslContext.getSocketFactory());
    c1.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(c1));

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    String suite = c1.getCipherSuite();
    List<Certificate> localCerts = toListOrNull(c1.getLocalCertificates());
    List<Certificate> serverCerts = toListOrNull(c1.getServerCertificates());
    Principal peerPrincipal = c1.getPeerPrincipal();
    Principal localPrincipal = c1.getLocalPrincipal();

    HttpsURLConnection c2 = (HttpsURLConnection) client.open(server.getUrl("/")); // cached!
    c2.setSSLSocketFactory(sslContext.getSocketFactory());
    c2.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(c2));

    assertEquals(2, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(1, cache.getHitCount());

    assertEquals(suite, c2.getCipherSuite());
    assertEquals(localCerts, toListOrNull(c2.getLocalCertificates()));
    assertEquals(serverCerts, toListOrNull(c2.getServerCertificates()));
    assertEquals(peerPrincipal, c2.getPeerPrincipal());
    assertEquals(localPrincipal, c2.getLocalPrincipal());
  }

  @Test public void responseCachingAndRedirects() throws Exception {
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));
    server.play();

    HttpURLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection));

    connection = openConnection(server.getUrl("/")); // cached!
    assertEquals("ABC", readAscii(connection));

    assertEquals(4, cache.getRequestCount()); // 2 requests + 2 redirects
    assertEquals(2, cache.getNetworkCount());
    assertEquals(2, cache.getHitCount());
  }

  @Test public void redirectToCachedResult() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60").setBody("ABC"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse().setBody("DEF"));
    server.play();

    assertEquals("ABC", readAscii(openConnection(server.getUrl("/foo"))));
    RecordedRequest request1 = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request1.getRequestLine());
    assertEquals(0, request1.getSequenceNumber());

    assertEquals("ABC", readAscii(openConnection(server.getUrl("/bar"))));
    RecordedRequest request2 = server.takeRequest();
    assertEquals("GET /bar HTTP/1.1", request2.getRequestLine());
    assertEquals(1, request2.getSequenceNumber());

    // an unrelated request should reuse the pooled connection
    assertEquals("DEF", readAscii(openConnection(server.getUrl("/baz"))));
    RecordedRequest request3 = server.takeRequest();
    assertEquals("GET /baz HTTP/1.1", request3.getRequestLine());
    assertEquals(2, request3.getSequenceNumber());
  }

  @Test public void secureResponseCachingAndRedirects() throws IOException {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);

    HttpsURLConnection connection1 = (HttpsURLConnection) client.open(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection1));
    assertNotNull(connection1.getCipherSuite());

    // Cached!
    HttpsURLConnection connection2 = (HttpsURLConnection) client.open(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection2));
    assertNotNull(connection2.getCipherSuite());

    assertEquals(4, cache.getRequestCount()); // 2 direct + 2 redirect = 4
    assertEquals(2, cache.getHitCount());
    assertEquals(connection1.getCipherSuite(), connection2.getCipherSuite());
  }

  /**
   * We've had bugs where caching and cross-protocol redirects yield class
   * cast exceptions internal to the cache because we incorrectly assumed that
   * HttpsURLConnection was always HTTPS and HttpURLConnection was always HTTP;
   * in practice redirects mean that each can do either.
   *
   * https://github.com/square/okhttp/issues/214
   */
  @Test public void secureResponseCachingAndProtocolRedirects() throws IOException {
    server2.useHttps(sslContext.getSocketFactory(), false);
    server2.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server2.enqueue(new MockResponse().setBody("DEF"));
    server2.play();

    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: " + server2.getUrl("/")));
    server.play();

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);

    HttpURLConnection connection1 = client.open(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection1));

    // Cached!
    HttpURLConnection connection2 = client.open(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection2));

    assertEquals(4, cache.getRequestCount()); // 2 direct + 2 redirect = 4
    assertEquals(2, cache.getHitCount());
  }

  @Test public void responseCacheRequestHeaders() throws IOException, URISyntaxException {
    server.enqueue(new MockResponse().setBody("ABC"));
    server.play();

    final AtomicReference<Request> requestRef = new AtomicReference<Request>();
    client.setOkResponseCache(new AbstractOkResponseCache() {
      @Override public Response get(Request request) throws IOException {
        requestRef.set(request);
        return null;
      }
    });

    URL url = server.getUrl("/");
    URLConnection urlConnection = openConnection(url);
    urlConnection.addRequestProperty("A", "android");
    readAscii(urlConnection);
    assertEquals(Arrays.asList("android"), requestRef.get().headers("A"));
  }

  @Test public void serverDisconnectsPrematurelyWithContentLengthHeader() throws IOException {
    testServerPrematureDisconnect(TransferKind.FIXED_LENGTH);
  }

  @Test public void serverDisconnectsPrematurelyWithChunkedEncoding() throws IOException {
    testServerPrematureDisconnect(TransferKind.CHUNKED);
  }

  @Test public void serverDisconnectsPrematurelyWithNoLengthHeaders() throws IOException {
    // Intentionally empty. This case doesn't make sense because there's no
    // such thing as a premature disconnect when the disconnect itself
    // indicates the end of the data stream.
  }

  private void testServerPrematureDisconnect(TransferKind transferKind) throws IOException {
    MockResponse response = new MockResponse();
    transferKind.setBody(response, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 16);
    server.enqueue(truncateViolently(response, 16));
    server.enqueue(new MockResponse().setBody("Request #2"));
    server.play();

    BufferedReader reader = new BufferedReader(
        new InputStreamReader(openConnection(server.getUrl("/")).getInputStream()));
    assertEquals("ABCDE", reader.readLine());
    try {
      reader.readLine();
      fail("This implementation silently ignored a truncated HTTP body.");
    } catch (IOException expected) {
    } finally {
      reader.close();
    }

    assertEquals(1, cache.getWriteAbortCount());
    assertEquals(0, cache.getWriteSuccessCount());
    URLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("Request #2", readAscii(connection));
    assertEquals(1, cache.getWriteAbortCount());
    assertEquals(1, cache.getWriteSuccessCount());
  }

  @Test public void clientPrematureDisconnectWithContentLengthHeader() throws IOException {
    testClientPrematureDisconnect(TransferKind.FIXED_LENGTH);
  }

  @Test public void clientPrematureDisconnectWithChunkedEncoding() throws IOException {
    testClientPrematureDisconnect(TransferKind.CHUNKED);
  }

  @Test public void clientPrematureDisconnectWithNoLengthHeaders() throws IOException {
    testClientPrematureDisconnect(TransferKind.END_OF_STREAM);
  }

  private void testClientPrematureDisconnect(TransferKind transferKind) throws IOException {
    // Setting a low transfer speed ensures that stream discarding will time out.
    MockResponse response = new MockResponse().throttleBody(6, 1, TimeUnit.SECONDS);
    transferKind.setBody(response, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 1024);
    server.enqueue(response);
    server.enqueue(new MockResponse().setBody("Request #2"));
    server.play();

    URLConnection connection = openConnection(server.getUrl("/"));
    InputStream in = connection.getInputStream();
    assertEquals("ABCDE", readAscii(connection, 5));
    in.close();
    try {
      in.read();
      fail("Expected an IOException because the stream is closed.");
    } catch (IOException expected) {
    }

    assertEquals(1, cache.getWriteAbortCount());
    assertEquals(0, cache.getWriteSuccessCount());
    connection = openConnection(server.getUrl("/"));
    assertEquals("Request #2", readAscii(connection));
    assertEquals(1, cache.getWriteAbortCount());
    assertEquals(1, cache.getWriteSuccessCount());
  }

  @Test public void defaultExpirationDateFullyCachedForLessThan24Hours() throws Exception {
    //      last modified: 105 seconds ago
    //             served:   5 seconds ago
    //   default lifetime: (105 - 5) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds from now
    server.enqueue(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
            .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
            .setBody("A"));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    URLConnection connection = openConnection(url);
    assertEquals("A", readAscii(connection));
    assertNull(connection.getHeaderField("Warning"));
  }

  @Test public void defaultExpirationDateConditionallyCached() throws Exception {
    //      last modified: 115 seconds ago
    //             served:  15 seconds ago
    //   default lifetime: (115 - 15) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds ago
    String lastModifiedDate = formatDate(-115, TimeUnit.SECONDS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Date: " + formatDate(-15, TimeUnit.SECONDS)));
    List<String> headers = conditionalRequest.getHeaders();
    assertTrue(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  @Test public void defaultExpirationDateFullyCachedForMoreThan24Hours() throws Exception {
    //      last modified: 105 days ago
    //             served:   5 days ago
    //   default lifetime: (105 - 5) / 10 = 10 days
    //            expires:  10 days from served date = 5 days from now
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-105, TimeUnit.DAYS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.DAYS))
        .setBody("A"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    URLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection));
    assertEquals("113 HttpURLConnection \"Heuristic expiration\"",
        connection.getHeaderField("Warning"));
  }

  @Test public void noDefaultExpirationForUrlsWithQueryString() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
            .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
            .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/?foo=bar");
    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("B", readAscii(openConnection(url)));
  }

  @Test public void expirationDateInThePastWithLastModifiedHeader() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    List<String> headers = conditionalRequest.getHeaders();
    assertTrue(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  @Test public void expirationDateInThePastWithNoLastModifiedHeader() throws Exception {
    assertNotCached(new MockResponse().addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
  }

  @Test public void expirationDateInTheFuture() throws Exception {
    assertFullyCached(new MockResponse().addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
  }

  @Test public void maxAgePreferredWithMaxAgeAndExpires() throws Exception {
    assertFullyCached(new MockResponse().addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInThePastWithDateAndLastModifiedHeaders() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("Date: " + formatDate(-120, TimeUnit.SECONDS))
            .addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Cache-Control: max-age=60"));
    List<String> headers = conditionalRequest.getHeaders();
    assertTrue(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  @Test public void maxAgeInThePastWithDateHeaderButNoLastModifiedHeader() throws Exception {
    // Chrome interprets max-age relative to the local clock. Both our cache
    // and Firefox both use the earlier of the local and server's clock.
    assertNotCached(new MockResponse().addHeader("Date: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInTheFutureWithDateHeader() throws Exception {
    assertFullyCached(new MockResponse().addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInTheFutureWithNoDateHeader() throws Exception {
    assertFullyCached(new MockResponse().addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeWithLastModifiedButNoServedDate() throws Exception {
    assertFullyCached(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
            .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInTheFutureWithDateAndLastModifiedHeaders() throws Exception {
    assertFullyCached(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
            .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
            .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgePreferredOverLowerSharedMaxAge() throws Exception {
    assertFullyCached(new MockResponse().addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: s-maxage=60")
        .addHeader("Cache-Control: max-age=180"));
  }

  @Test public void maxAgePreferredOverHigherMaxAge() throws Exception {
    assertNotCached(new MockResponse().addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: s-maxage=180")
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void requestMethodOptionsIsNotCached() throws Exception {
    testRequestMethod("OPTIONS", false);
  }

  @Test public void requestMethodGetIsCached() throws Exception {
    testRequestMethod("GET", true);
  }

  @Test public void requestMethodHeadIsNotCached() throws Exception {
    // We could support this but choose not to for implementation simplicity
    testRequestMethod("HEAD", false);
  }

  @Test public void requestMethodPostIsNotCached() throws Exception {
    // We could support this but choose not to for implementation simplicity
    testRequestMethod("POST", false);
  }

  @Test public void requestMethodPutIsNotCached() throws Exception {
    testRequestMethod("PUT", false);
  }

  @Test public void requestMethodDeleteIsNotCached() throws Exception {
    testRequestMethod("DELETE", false);
  }

  @Test public void requestMethodTraceIsNotCached() throws Exception {
    testRequestMethod("TRACE", false);
  }

  private void testRequestMethod(String requestMethod, boolean expectCached) throws Exception {
    // 1. seed the cache (potentially)
    // 2. expect a cache hit or miss
    server.enqueue(new MockResponse().addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("X-Response-ID: 1"));
    server.enqueue(new MockResponse().addHeader("X-Response-ID: 2"));
    server.play();

    URL url = server.getUrl("/");

    HttpURLConnection request1 = openConnection(url);
    request1.setRequestMethod(requestMethod);
    addRequestBodyIfNecessary(requestMethod, request1);
    assertEquals("1", request1.getHeaderField("X-Response-ID"));

    URLConnection request2 = openConnection(url);
    if (expectCached) {
      assertEquals("1", request2.getHeaderField("X-Response-ID"));
    } else {
      assertEquals("2", request2.getHeaderField("X-Response-ID"));
    }
  }

  @Test public void postInvalidatesCache() throws Exception {
    testMethodInvalidates("POST");
  }

  @Test public void putInvalidatesCache() throws Exception {
    testMethodInvalidates("PUT");
  }

  @Test public void deleteMethodInvalidatesCache() throws Exception {
    testMethodInvalidates("DELETE");
  }

  private void testMethodInvalidates(String requestMethod) throws Exception {
    // 1. seed the cache
    // 2. invalidate it
    // 3. expect a cache miss
    server.enqueue(
        new MockResponse().setBody("A").addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B"));
    server.enqueue(new MockResponse().setBody("C"));
    server.play();

    URL url = server.getUrl("/");

    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection invalidate = openConnection(url);
    invalidate.setRequestMethod(requestMethod);
    addRequestBodyIfNecessary(requestMethod, invalidate);
    assertEquals("B", readAscii(invalidate));

    assertEquals("C", readAscii(openConnection(url)));
  }

  @Test public void postInvalidatesCacheWithUncacheableResponse() throws Exception {
    // 1. seed the cache
    // 2. invalidate it with uncacheable response
    // 3. expect a cache miss
    server.enqueue(
        new MockResponse().setBody("A").addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B").setResponseCode(500));
    server.enqueue(new MockResponse().setBody("C"));
    server.play();

    URL url = server.getUrl("/");

    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection invalidate = openConnection(url);
    invalidate.setRequestMethod("POST");
    addRequestBodyIfNecessary("POST", invalidate);
    assertEquals("B", readAscii(invalidate));

    assertEquals("C", readAscii(openConnection(url)));
  }

  @Test public void etag() throws Exception {
    RecordedRequest conditionalRequest =
        assertConditionallyCached(new MockResponse().addHeader("ETag: v1"));
    assertTrue(conditionalRequest.getHeaders().contains("If-None-Match: v1"));
  }

  @Test public void etagAndExpirationDateInThePast() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("ETag: v1")
            .addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    List<String> headers = conditionalRequest.getHeaders();
    assertTrue(headers.contains("If-None-Match: v1"));
    assertTrue(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  @Test public void etagAndExpirationDateInTheFuture() throws Exception {
    assertFullyCached(new MockResponse().addHeader("ETag: v1")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
  }

  @Test public void cacheControlNoCache() throws Exception {
    assertNotCached(new MockResponse().addHeader("Cache-Control: no-cache"));
  }

  @Test public void cacheControlNoCacheAndExpirationDateInTheFuture() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
            .addHeader("Cache-Control: no-cache"));
    List<String> headers = conditionalRequest.getHeaders();
    assertTrue(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  @Test public void pragmaNoCache() throws Exception {
    assertNotCached(new MockResponse().addHeader("Pragma: no-cache"));
  }

  @Test public void pragmaNoCacheAndExpirationDateInTheFuture() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
            .addHeader("Pragma: no-cache"));
    List<String> headers = conditionalRequest.getHeaders();
    assertTrue(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  @Test public void cacheControlNoStore() throws Exception {
    assertNotCached(new MockResponse().addHeader("Cache-Control: no-store"));
  }

  @Test public void cacheControlNoStoreAndExpirationDateInTheFuture() throws Exception {
    assertNotCached(new MockResponse().addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Cache-Control: no-store"));
  }

  @Test public void partialRangeResponsesDoNotCorruptCache() throws Exception {
    // 1. request a range
    // 2. request a full document, expecting a cache miss
    server.enqueue(new MockResponse().setBody("AA")
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 1000-1001/2000"));
    server.enqueue(new MockResponse().setBody("BB"));
    server.play();

    URL url = server.getUrl("/");

    URLConnection range = openConnection(url);
    range.addRequestProperty("Range", "bytes=1000-1001");
    assertEquals("AA", readAscii(range));

    assertEquals("BB", readAscii(openConnection(url)));
  }

  @Test public void serverReturnsDocumentOlderThanCache() throws Exception {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B")
        .addHeader("Last-Modified: " + formatDate(-4, TimeUnit.HOURS)));
    server.play();

    URL url = server.getUrl("/");

    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("A", readAscii(openConnection(url)));
  }

  @Test public void nonIdentityEncodingAndConditionalCache() throws Exception {
    assertNonIdentityEncodingCached(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
  }

  @Test public void nonIdentityEncodingAndFullCache() throws Exception {
    assertNonIdentityEncodingCached(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
  }

  private void assertNonIdentityEncodingCached(MockResponse response) throws Exception {
    server.enqueue(
        response.setBody(gzip("ABCABCABC")).addHeader("Content-Encoding: gzip"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    server.play();

    // At least three request/response pairs are required because after the first request is cached
    // a different execution path might be taken. Thus modifications to the cache applied during
    // the second request might not be visible until another request is performed.
    assertEquals("ABCABCABC", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("ABCABCABC", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("ABCABCABC", readAscii(openConnection(server.getUrl("/"))));
  }

  @Test public void notModifiedSpecifiesEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("Content-Encoding: gzip"));
    server.enqueue(new MockResponse()
        .setBody("DEFDEFDEF"));

    server.play();
    assertEquals("ABCABCABC", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("ABCABCABC", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("DEFDEFDEF", readAscii(openConnection(server.getUrl("/"))));
  }

  @Test public void conditionalCacheHitIsNotDoublePooled() throws Exception {
    server.enqueue(new MockResponse().addHeader("ETag: v1").setBody("A"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    ConnectionPool pool = ConnectionPool.getDefault();
    pool.evictAll();
    client.setConnectionPool(pool);

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(1, client.getConnectionPool().getConnectionCount());
  }

  @Test public void expiresDateBeforeModifiedDate() throws Exception {
    assertConditionallyCached(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(-2, TimeUnit.HOURS)));
  }

  @Test public void requestMaxAge() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B"));

    server.play();
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));

    URLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "max-age=30");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestMinFresh() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B"));

    server.play();
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));

    URLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "min-fresh=120");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestMaxStale() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B"));

    server.play();
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));

    URLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "max-stale=180");
    assertEquals("A", readAscii(connection));
    assertEquals("110 HttpURLConnection \"Response is stale\"",
        connection.getHeaderField("Warning"));
  }

  @Test public void requestMaxStaleNotHonoredWithMustRevalidate() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=120, must-revalidate")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B"));

    server.play();
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));

    URLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "max-stale=180");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestOnlyIfCachedWithNoResponseCached() throws IOException {
    // (no responses enqueued)
    server.play();

    HttpURLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
    assertEquals(1, cache.getRequestCount());
    assertEquals(0, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
  }

  @Test public void requestOnlyIfCachedWithFullResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    URLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertEquals("A", readAscii(connection));
    assertEquals(2, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(1, cache.getHitCount());
  }

  @Test public void requestOnlyIfCachedWithConditionalResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES)));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    HttpURLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
    assertEquals(2, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
  }

  @Test public void requestOnlyIfCachedWithUnhelpfulResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    HttpURLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
    assertEquals(2, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
  }

  @Test public void requestCacheControlNoCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
            .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
            .addHeader("Cache-Control: max-age=60")
            .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    URLConnection connection = openConnection(url);
    connection.setRequestProperty("Cache-Control", "no-cache");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestPragmaNoCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
            .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
            .addHeader("Cache-Control: max-age=60")
            .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    URLConnection connection = openConnection(url);
    connection.setRequestProperty("Pragma", "no-cache");
    assertEquals("B", readAscii(connection));
  }

  @Test public void clientSuppliedIfModifiedSinceWithCachedResult() throws Exception {
    MockResponse response =
        new MockResponse().addHeader("ETag: v3").addHeader("Cache-Control: max-age=0");
    String ifModifiedSinceDate = formatDate(-24, TimeUnit.HOURS);
    RecordedRequest request =
        assertClientSuppliedCondition(response, "If-Modified-Since", ifModifiedSinceDate);
    List<String> headers = request.getHeaders();
    assertTrue(headers.contains("If-Modified-Since: " + ifModifiedSinceDate));
    assertFalse(headers.contains("If-None-Match: v3"));
  }

  @Test public void clientSuppliedIfNoneMatchSinceWithCachedResult() throws Exception {
    String lastModifiedDate = formatDate(-3, TimeUnit.MINUTES);
    MockResponse response = new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: max-age=0");
    RecordedRequest request = assertClientSuppliedCondition(response, "If-None-Match", "v1");
    List<String> headers = request.getHeaders();
    assertTrue(headers.contains("If-None-Match: v1"));
    assertFalse(headers.contains("If-Modified-Since: " + lastModifiedDate));
  }

  private RecordedRequest assertClientSuppliedCondition(MockResponse seed, String conditionName,
      String conditionValue) throws Exception {
    server.enqueue(seed.setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection connection = openConnection(url);
    connection.addRequestProperty(conditionName, conditionValue);
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection.getResponseCode());
    assertEquals("", readAscii(connection));

    server.takeRequest(); // seed
    return server.takeRequest();
  }

  /**
   * Confirm that {@link URLConnection#setIfModifiedSince} causes an
   * If-Modified-Since header with a GMT timestamp.
   *
   * https://code.google.com/p/android/issues/detail?id=66135
   */
  @Test public void setIfModifiedSince() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection = openConnection(url);
    connection.setIfModifiedSince(1393666200000L);
    assertEquals("A", readAscii(connection));
    RecordedRequest request = server.takeRequest();
    String ifModifiedSinceHeader = request.getHeader("If-Modified-Since");
    assertEquals("Sat, 01 Mar 2014 09:30:00 GMT", ifModifiedSinceHeader);
  }

  /**
   * For Last-Modified and Date headers, we should echo the date back in the
   * exact format we were served.
   */
  @Test public void retainServedDateFormat() throws Exception {
    // Serve a response with a non-standard date format that OkHttp supports.
    Date lastModifiedDate = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(-1));
    Date servedDate = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(-2));
    DateFormat dateFormat = new SimpleDateFormat("EEE dd-MMM-yyyy HH:mm:ss z", Locale.US);
    dateFormat.setTimeZone(TimeZone.getTimeZone("EDT"));
    String lastModifiedString = dateFormat.format(lastModifiedDate);
    String servedString = dateFormat.format(servedDate);

    // This response should be conditionally cached.
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedString)
        .addHeader("Expires: " + servedString)
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));

    // The first request has no conditions.
    RecordedRequest request1 = server.takeRequest();
    assertNull(request1.getHeader("If-Modified-Since"));

    // The 2nd request uses the server's date format.
    RecordedRequest request2 = server.takeRequest();
    assertEquals(lastModifiedString, request2.getHeader("If-Modified-Since"));
  }

  @Test public void clientSuppliedConditionWithoutCachedResult() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    HttpURLConnection connection = openConnection(server.getUrl("/"));
    String clientIfModifiedSince = formatDate(-24, TimeUnit.HOURS);
    connection.addRequestProperty("If-Modified-Since", clientIfModifiedSince);
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection.getResponseCode());
    assertEquals("", readAscii(connection));
  }

  @Test public void authorizationRequestHeaderPreventsCaching() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-2, TimeUnit.MINUTES))
            .addHeader("Cache-Control: max-age=60")
            .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection = openConnection(url);
    connection.addRequestProperty("Authorization", "password");
    assertEquals("A", readAscii(connection));
    assertEquals("B", readAscii(openConnection(url)));
  }

  @Test public void authorizationResponseCachedWithSMaxAge() throws Exception {
    assertAuthorizationRequestFullyCached(
        new MockResponse().addHeader("Cache-Control: s-maxage=60"));
  }

  @Test public void authorizationResponseCachedWithPublic() throws Exception {
    assertAuthorizationRequestFullyCached(new MockResponse().addHeader("Cache-Control: public"));
  }

  @Test public void authorizationResponseCachedWithMustRevalidate() throws Exception {
    assertAuthorizationRequestFullyCached(
        new MockResponse().addHeader("Cache-Control: must-revalidate"));
  }

  public void assertAuthorizationRequestFullyCached(MockResponse response) throws Exception {
    server.enqueue(response.addHeader("Cache-Control: max-age=60").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection = openConnection(url);
    connection.addRequestProperty("Authorization", "password");
    assertEquals("A", readAscii(connection));
    assertEquals("A", readAscii(openConnection(url)));
  }

  @Test public void contentLocationDoesNotPopulateCache() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Content-Location: /bar")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/foo"))));
    assertEquals("B", readAscii(openConnection(server.getUrl("/bar"))));
  }

  @Test public void useCachesFalseDoesNotWriteToCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URLConnection connection = openConnection(server.getUrl("/"));
    connection.setUseCaches(false);
    assertEquals("A", readAscii(connection));
    assertEquals("B", readAscii(openConnection(server.getUrl("/"))));
  }

  @Test public void useCachesFalseDoesNotReadFromCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    URLConnection connection = openConnection(server.getUrl("/"));
    connection.setUseCaches(false);
    assertEquals("B", readAscii(connection));
  }

  @Test public void defaultUseCachesSetsInitialValueOnly() throws Exception {
    URL url = new URL("http://localhost/");
    URLConnection c1 = openConnection(url);
    URLConnection c2 = openConnection(url);
    assertTrue(c1.getDefaultUseCaches());
    c1.setDefaultUseCaches(false);
    try {
      assertTrue(c1.getUseCaches());
      assertTrue(c2.getUseCaches());
      URLConnection c3 = openConnection(url);
      assertFalse(c3.getUseCaches());
    } finally {
      c1.setDefaultUseCaches(true);
    }
  }

  @Test public void connectionIsReturnedToPoolAfterConditionalSuccess() throws Exception {
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/a"))));
    assertEquals("A", readAscii(openConnection(server.getUrl("/a"))));
    assertEquals("B", readAscii(openConnection(server.getUrl("/b"))));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void statisticsConditionalCacheMiss() throws Exception {
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.enqueue(new MockResponse().setBody("C"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(1, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
    assertEquals("B", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("C", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(3, cache.getRequestCount());
    assertEquals(3, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
  }

  @Test public void statisticsConditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(1, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(3, cache.getRequestCount());
    assertEquals(3, cache.getNetworkCount());
    assertEquals(2, cache.getHitCount());
  }

  @Test public void statisticsFullCacheHit() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(1, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(0, cache.getHitCount());
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals(3, cache.getRequestCount());
    assertEquals(1, cache.getNetworkCount());
    assertEquals(2, cache.getHitCount());
  }

  @Test public void varyMatchesChangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    HttpURLConnection frConnection = openConnection(url);
    frConnection.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(frConnection));

    HttpURLConnection enConnection = openConnection(url);
    enConnection.addRequestProperty("Accept-Language", "en-US");
    assertEquals("B", readAscii(enConnection));
  }

  @Test public void varyMatchesUnchangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection1 = openConnection(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = openConnection(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMatchesAbsentRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
  }

  @Test public void varyMatchesAddedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    URLConnection fooConnection = openConnection(server.getUrl("/"));
    fooConnection.addRequestProperty("Foo", "bar");
    assertEquals("B", readAscii(fooConnection));
  }

  @Test public void varyMatchesRemovedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URLConnection fooConnection = openConnection(server.getUrl("/"));
    fooConnection.addRequestProperty("Foo", "bar");
    assertEquals("A", readAscii(fooConnection));
    assertEquals("B", readAscii(openConnection(server.getUrl("/"))));
  }

  @Test public void varyFieldsAreCaseInsensitive() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: ACCEPT-LANGUAGE")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection1 = openConnection(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = openConnection(url);
    connection2.addRequestProperty("accept-language", "fr-CA");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMultipleFieldsWithMatch() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection1 = openConnection(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA");
    connection1.addRequestProperty("Accept-Charset", "UTF-8");
    connection1.addRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = openConnection(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA");
    connection2.addRequestProperty("Accept-Charset", "UTF-8");
    connection2.addRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMultipleFieldsWithNoMatch() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection frConnection = openConnection(url);
    frConnection.addRequestProperty("Accept-Language", "fr-CA");
    frConnection.addRequestProperty("Accept-Charset", "UTF-8");
    frConnection.addRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(frConnection));
    URLConnection enConnection = openConnection(url);
    enConnection.addRequestProperty("Accept-Language", "en-CA");
    enConnection.addRequestProperty("Accept-Charset", "UTF-8");
    enConnection.addRequestProperty("Accept-Encoding", "identity");
    assertEquals("B", readAscii(enConnection));
  }

  @Test public void varyMultipleFieldValuesWithMatch() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection1 = openConnection(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA, fr-FR");
    connection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    URLConnection connection2 = openConnection(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA, fr-FR");
    connection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMultipleFieldValuesWithNoMatch() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection1 = openConnection(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA, fr-FR");
    connection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    URLConnection connection2 = openConnection(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA");
    connection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("B", readAscii(connection2));
  }

  @Test public void varyAsterisk() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    assertEquals("B", readAscii(openConnection(server.getUrl("/"))));
  }

  @Test public void varyAndHttps() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    HttpsURLConnection connection1 = (HttpsURLConnection) client.open(url);
    connection1.setSSLSocketFactory(sslContext.getSocketFactory());
    connection1.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    connection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    HttpsURLConnection connection2 = (HttpsURLConnection) client.open(url);
    connection2.setSSLSocketFactory(sslContext.getSocketFactory());
    connection2.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    connection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void cachePlusCookies() throws Exception {
    server.enqueue(new MockResponse().addHeader(
        "Set-Cookie: a=FIRST; domain=" + server.getCookieDomain() + ";")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().addHeader(
        "Set-Cookie: a=SECOND; domain=" + server.getCookieDomain() + ";")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    assertCookies(url, "a=FIRST");
    assertEquals("A", readAscii(openConnection(url)));
    assertCookies(url, "a=SECOND");
  }

  @Test public void getHeadersReturnsNetworkEndToEndHeaders() throws Exception {
    server.enqueue(new MockResponse().addHeader("Allow: GET, HEAD")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().addHeader("Allow: GET, HEAD, PUT")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URLConnection connection1 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection1));
    assertEquals("GET, HEAD", connection1.getHeaderField("Allow"));

    URLConnection connection2 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection2));
    assertEquals("GET, HEAD, PUT", connection2.getHeaderField("Allow"));
  }

  @Test public void getHeadersReturnsCachedHopByHopHeaders() throws Exception {
    server.enqueue(new MockResponse().addHeader("Transfer-Encoding: identity")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().addHeader("Transfer-Encoding: none")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URLConnection connection1 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection1));
    assertEquals("identity", connection1.getHeaderField("Transfer-Encoding"));

    URLConnection connection2 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection2));
    assertEquals("identity", connection2.getHeaderField("Transfer-Encoding"));
  }

  @Test public void getHeadersDeletesCached100LevelWarnings() throws Exception {
    server.enqueue(new MockResponse().addHeader("Warning: 199 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URLConnection connection1 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection1));
    assertEquals("199 test danger", connection1.getHeaderField("Warning"));

    URLConnection connection2 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection2));
    assertEquals(null, connection2.getHeaderField("Warning"));
  }

  @Test public void getHeadersRetainsCached200LevelWarnings() throws Exception {
    server.enqueue(new MockResponse().addHeader("Warning: 299 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URLConnection connection1 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection1));
    assertEquals("299 test danger", connection1.getHeaderField("Warning"));

    URLConnection connection2 = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection2));
    assertEquals("299 test danger", connection2.getHeaderField("Warning"));
  }

  public void assertCookies(URL url, String... expectedCookies) throws Exception {
    List<String> actualCookies = new ArrayList<String>();
    for (HttpCookie cookie : cookieManager.getCookieStore().get(url.toURI())) {
      actualCookies.add(cookie.toString());
    }
    assertEquals(Arrays.asList(expectedCookies), actualCookies);
  }

  @Test public void cachePlusRange() throws Exception {
    assertNotCached(new MockResponse().setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 100-100/200")
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void conditionalHitUpdatesCache() throws Exception {
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=30")
        .addHeader("Allow: GET, HEAD")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    // cache miss; seed the cache
    HttpURLConnection connection1 = openConnection(server.getUrl("/a"));
    assertEquals("A", readAscii(connection1));
    assertEquals(null, connection1.getHeaderField("Allow"));

    // conditional cache hit; update the cache
    HttpURLConnection connection2 = openConnection(server.getUrl("/a"));
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A", readAscii(connection2));
    assertEquals("GET, HEAD", connection2.getHeaderField("Allow"));

    // full cache hit
    HttpURLConnection connection3 = openConnection(server.getUrl("/a"));
    assertEquals("A", readAscii(connection3));
    assertEquals("GET, HEAD", connection3.getHeaderField("Allow"));

    assertEquals(2, server.getRequestCount());
  }

  @Test public void responseSourceHeaderCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    URLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertEquals("A", readAscii(connection));

    String source = connection.getHeaderField(HttpURLConnectionImpl.RESPONSE_SOURCE);
    assertEquals(ResponseSource.CACHE + " 200", source);
  }

  @Test public void responseSourceHeaderConditionalCacheFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-31, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    HttpURLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("B", readAscii(connection));

    String source = connection.getHeaderField(HttpURLConnectionImpl.RESPONSE_SOURCE);
    assertEquals(ResponseSource.CONDITIONAL_CACHE + " 200", source);
  }

  @Test public void responseSourceHeaderConditionalCacheNotFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setResponseCode(304));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    HttpURLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection));

    String source = connection.getHeaderField(HttpURLConnectionImpl.RESPONSE_SOURCE);
    assertEquals(ResponseSource.CONDITIONAL_CACHE + " 304", source);
  }

  @Test public void responseSourceHeaderFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    URLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection));

    String source = connection.getHeaderField(HttpURLConnectionImpl.RESPONSE_SOURCE);
    assertEquals(ResponseSource.NETWORK + " 200", source);
  }

  @Test public void emptyResponseHeaderNameFromCacheIsLenient() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=120")
        .addHeader(": A")
        .setBody("body"));
    server.play();
    HttpURLConnection connection = client.open(server.getUrl("/"));
    assertEquals("A", connection.getHeaderField(""));
  }

  /**
   * Old implementations of OkHttp's response cache wrote header fields like
   * ":status: 200 OK". This broke our cached response parser because it split
   * on the first colon. This regression test exists to help us read these old
   * bad cache entries.
   *
   * https://github.com/square/okhttp/issues/227
   */
  @Test public void testGoldenCacheResponse() throws Exception {
    cache.close();
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.play();

    URL url = server.getUrl("/");
    String urlKey = Util.hash(url.toString());
    String entryMetadata = ""
        + "" + url + "\n"
        + "GET\n"
        + "0\n"
        + "HTTP/1.1 200 OK\n"
        + "7\n"
        + ":status: 200 OK\n"
        + ":version: HTTP/1.1\n"
        + "etag: foo\n"
        + "content-length: 3\n"
        + "OkHttp-Received-Millis: " + System.currentTimeMillis() + "\n"
        + "X-Android-Response-Source: NETWORK 200\n"
        + "OkHttp-Sent-Millis: " + System.currentTimeMillis() + "\n"
        + "\n"
        + "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA\n"
        + "1\n"
        + "MIIBpDCCAQ2gAwIBAgIBATANBgkqhkiG9w0BAQsFADAYMRYwFAYDVQQDEw1qd2lsc29uLmxvY2FsMB4XDTEzMDgy"
        + "OTA1MDE1OVoXDTEzMDgzMDA1MDE1OVowGDEWMBQGA1UEAxMNandpbHNvbi5sb2NhbDCBnzANBgkqhkiG9w0BAQEF"
        + "AAOBjQAwgYkCgYEAlFW+rGo/YikCcRghOyKkJanmVmJSce/p2/jH1QvNIFKizZdh8AKNwojt3ywRWaDULA/RlCUc"
        + "ltF3HGNsCyjQI/+Lf40x7JpxXF8oim1E6EtDoYtGWAseelawus3IQ13nmo6nWzfyCA55KhAWf4VipelEy8DjcuFK"
        + "v6L0xwXnI0ECAwEAATANBgkqhkiG9w0BAQsFAAOBgQAuluNyPo1HksU3+Mr/PyRQIQS4BI7pRXN8mcejXmqyscdP"
        + "7S6J21FBFeRR8/XNjVOp4HT9uSc2hrRtTEHEZCmpyoxixbnM706ikTmC7SN/GgM+SmcoJ1ipJcNcl8N0X6zym4dm"
        + "yFfXKHu2PkTo7QFdpOJFvP3lIigcSZXozfmEDg==\n"
        + "-1\n";
    String entryBody = "abc";
    String journalBody = ""
        + "libcore.io.DiskLruCache\n"
        + "1\n"
        + "201105\n"
        + "2\n"
        + "\n"
        + "CLEAN " + urlKey + " " + entryMetadata.length() + " " + entryBody.length() + "\n";
    writeFile(cache.getDirectory(), urlKey + ".0", entryMetadata);
    writeFile(cache.getDirectory(), urlKey + ".1", entryBody);
    writeFile(cache.getDirectory(), "journal", journalBody);
    cache = new HttpResponseCache(cache.getDirectory(), Integer.MAX_VALUE);
    client.setOkResponseCache(cache);

    HttpURLConnection connection = client.open(url);
    assertEquals(entryBody, readAscii(connection));
    assertEquals("3", connection.getHeaderField("Content-Length"));
    assertEquals("foo", connection.getHeaderField("etag"));
  }

  // Older versions of OkHttp use ResponseCache.get() and ResponseCache.put(). For compatibility
  // with Android apps when the Android-bundled and and an older app-bundled OkHttp library are in
  // use at the same time the HttpResponseCache must behave as it always used to. That's not the
  // same as a fully API-compliant {@link ResponseCache}: That means that the cache
  // doesn't throw an exception from get() or put() and also does not cache requests/responses from
  // anything other than the variant of OkHttp that it comes with. It does still return values from
  // get() and it is not expected to implement any cache-control logic.
  @Test public void testHttpResponseCacheBackwardsCompatible() throws Exception {
    assertSame(cache, ResponseCache.getDefault());
    assertEquals(0, cache.getRequestCount());

    String body = "Body";
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody(body));
    server.play();

    URL url = server.getUrl("/");

    // Here we use a HttpURLConnection from URL to represent a non-OkHttp HttpURLConnection. In
    // Android this would be com.android.okhttp.internal.http.HttpURLConnectionImpl. In tests this
    // is some other implementation.
    HttpURLConnection javaConnection = (HttpURLConnection) url.openConnection();
    assertFalse("This test relies on url.openConnection() not returning an OkHttp connection",
        javaConnection instanceof HttpURLConnectionImpl);
    javaConnection.disconnect();

    // This should simply be discarded. It doesn't matter the connection is not useful.
    cache.put(url.toURI(), javaConnection);

    // Confirm the initial cache state.
    assertNull(cache.get(url.toURI(), "GET", new HashMap<String, List<String>>()));

    // Now cache a response
    HttpURLConnection okHttpConnection = openConnection(url);
    assertEquals(body, readAscii(okHttpConnection));
    okHttpConnection.disconnect();

    assertEquals(1, server.getRequestCount());
    assertEquals(0, cache.getHitCount());

    // OkHttp should now find the result cached.
    HttpURLConnection okHttpConnection2 = openConnection(url);
    assertEquals(body, readAscii(okHttpConnection2));
    okHttpConnection2.disconnect();

    assertEquals(1, server.getRequestCount());
    assertEquals(1, cache.getHitCount());

    // Confirm the unfortunate get() behavior.
    assertNotNull(cache.get(url.toURI(), "GET", new HashMap<String, List<String>>()));
    // Only OkHttp makes the necessary callbacks to increment the cache stats.
    assertEquals(1, cache.getHitCount());
  }

  private void writeFile(File directory, String file, String content) throws IOException {
    BufferedSink sink = Okio.buffer(Okio.sink(new File(directory, file)));
    sink.writeUtf8(content);
    sink.close();
  }

  /**
   * @param delta the offset from the current date to use. Negative
   * values yield dates in the past; positive values yield dates in the
   * future.
   */
  private String formatDate(long delta, TimeUnit timeUnit) {
    return formatDate(new Date(System.currentTimeMillis() + timeUnit.toMillis(delta)));
  }

  private String formatDate(Date date) {
    DateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    rfc1123.setTimeZone(TimeZone.getTimeZone("GMT"));
    return rfc1123.format(date);
  }

  private void addRequestBodyIfNecessary(String requestMethod, HttpURLConnection invalidate)
      throws IOException {
    if (requestMethod.equals("POST") || requestMethod.equals("PUT")) {
      invalidate.setDoOutput(true);
      OutputStream requestBody = invalidate.getOutputStream();
      requestBody.write('x');
      requestBody.close();
    }
  }

  private void assertNotCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("B", readAscii(openConnection(url)));
  }

  /** @return the request with the conditional get headers. */
  private RecordedRequest assertConditionallyCached(MockResponse response) throws Exception {
    // scenario 1: condition succeeds
    server.enqueue(response.setBody("A").setStatus("HTTP/1.1 200 A-OK"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    // scenario 2: condition fails
    server.enqueue(response.setBody("B").setStatus("HTTP/1.1 200 B-OK"));
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 200 C-OK").setBody("C"));

    server.play();

    URL valid = server.getUrl("/valid");
    HttpURLConnection connection1 = openConnection(valid);
    assertEquals("A", readAscii(connection1));
    assertEquals(HttpURLConnection.HTTP_OK, connection1.getResponseCode());
    assertEquals("A-OK", connection1.getResponseMessage());
    HttpURLConnection connection2 = openConnection(valid);
    assertEquals("A", readAscii(connection2));
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A-OK", connection2.getResponseMessage());

    URL invalid = server.getUrl("/invalid");
    HttpURLConnection connection3 = openConnection(invalid);
    assertEquals("B", readAscii(connection3));
    assertEquals(HttpURLConnection.HTTP_OK, connection3.getResponseCode());
    assertEquals("B-OK", connection3.getResponseMessage());
    HttpURLConnection connection4 = openConnection(invalid);
    assertEquals("C", readAscii(connection4));
    assertEquals(HttpURLConnection.HTTP_OK, connection4.getResponseCode());
    assertEquals("C-OK", connection4.getResponseMessage());

    server.takeRequest(); // regular get
    return server.takeRequest(); // conditional get
  }

  private void assertFullyCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(response.setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("A", readAscii(openConnection(url)));
  }

  /**
   * Shortens the body of {@code response} but not the corresponding headers.
   * Only useful to test how clients respond to the premature conclusion of
   * the HTTP body.
   */
  private MockResponse truncateViolently(MockResponse response, int numBytesToKeep) {
    response.setSocketPolicy(DISCONNECT_AT_END);
    List<String> headers = new ArrayList<String>(response.getHeaders());
    Buffer truncatedBody = new Buffer();
    truncatedBody.write(response.getBody(), numBytesToKeep);
    response.setBody(truncatedBody);
    response.getHeaders().clear();
    response.getHeaders().addAll(headers);
    return response;
  }

  /**
   * Reads {@code count} characters from the stream. If the stream is
   * exhausted before {@code count} characters can be read, the remaining
   * characters are returned and the stream is closed.
   */
  private String readAscii(URLConnection connection, int count) throws IOException {
    HttpURLConnection httpConnection = (HttpURLConnection) connection;
    InputStream in = httpConnection.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST
        ? connection.getInputStream()
        : httpConnection.getErrorStream();
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

  private String readAscii(URLConnection connection) throws IOException {
    return readAscii(connection, Integer.MAX_VALUE);
  }

  private void reliableSkip(InputStream in, int length) throws IOException {
    while (length > 0) {
      length -= in.skip(length);
    }
  }

  private void assertGatewayTimeout(HttpURLConnection connection) throws IOException {
    try {
      connection.getInputStream();
      fail();
    } catch (FileNotFoundException expected) {
    }
    assertEquals(504, connection.getResponseCode());
    assertEquals(-1, connection.getErrorStream().read());
    assertEquals(ResponseSource.NONE + " 504",
        connection.getHeaderField(HttpURLConnectionImpl.RESPONSE_SOURCE));
  }

  enum TransferKind {
    CHUNKED() {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize)
          throws IOException {
        response.setChunkedBody(content, chunkSize);
      }
    },
    FIXED_LENGTH() {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
      }
    },
    END_OF_STREAM() {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
        response.setSocketPolicy(DISCONNECT_AT_END);
        for (Iterator<String> h = response.getHeaders().iterator(); h.hasNext(); ) {
          if (h.next().startsWith("Content-Length:")) {
            h.remove();
            break;
          }
        }
      }
    };

    abstract void setBody(MockResponse response, Buffer content, int chunkSize) throws IOException;

    void setBody(MockResponse response, String content, int chunkSize) throws IOException {
      setBody(response, new Buffer().writeUtf8(content), chunkSize);
    }
  }

  private <T> List<T> toListOrNull(T[] arrayOrNull) {
    return arrayOrNull != null ? Arrays.asList(arrayOrNull) : null;
  }

  /** Returns a gzipped copy of {@code bytes}. */
  public Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  static abstract class AbstractOkResponseCache implements OkResponseCache {
    @Override public Response get(Request request) throws IOException {
      return null;
    }

    @Override public CacheRequest put(Response response) throws IOException {
      return null;
    }

    @Override public void remove(Request request) throws IOException {
    }

    @Override public void update(Response cached, Response network) throws IOException {
    }

    @Override public void trackConditionalCacheHit() {
    }

    @Override public void trackResponse(ResponseSource source) {
    }
  }
}
