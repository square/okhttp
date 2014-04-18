/*
 * Copyright (C) 2014 Square, Inc.
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

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.SecureCacheResponse;
import java.net.URI;
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
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
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
 * Tests for interaction between OkHttp and the ResponseCache. This test is based on
 * {@link com.squareup.okhttp.internal.http.HttpResponseCacheTest}. Some tests for the
 * {@link com.squareup.okhttp.OkResponseCache} found in HttpResponseCacheTest provide
 * coverage for ResponseCache as well.
 */
public final class ResponseCacheTest {
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
    @Override public boolean verify(String s, SSLSession sslSession) {
      return true;
    }
  };

  private static final SSLContext sslContext = SslContextBuilder.localhost();

  private OkHttpClient client;
  private MockWebServer server;
  private MockWebServer server2;
  private ResponseCache cache;

  @Before public void setUp() throws Exception {
    server =  new MockWebServer();
    server.setNpnEnabled(false);
    server2 =  new MockWebServer();

    client = new OkHttpClient();
    cache = new InMemoryResponseCache();
    ResponseCache.setDefault(cache);
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    server2.shutdown();
    CookieManager.setDefault(null);
  }

  private HttpURLConnection openConnection(URL url) {
    return client.open(url);
  }

  @Test public void responseCacheAccessWithOkHttpMember() throws IOException {
    ResponseCache.setDefault(null);
    client.setResponseCache(cache);
    assertSame(cache, client.getResponseCache());
    assertTrue(client.getOkResponseCache() instanceof ResponseCacheAdapter);
  }

  @Test public void responseCacheAccessWithGlobalDefault() throws IOException {
    ResponseCache.setDefault(cache);
    client.setResponseCache(null);
    assertNull(client.getOkResponseCache());
    assertNull(client.getResponseCache());
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

    urlConnection = openConnection(server.getUrl("/")); // cached!
    in = urlConnection.getInputStream();
    assertEquals("I love puppies but hate spiders",
        readAscii(urlConnection, "I love puppies but hate spiders".length()));
    assertEquals(200, urlConnection.getResponseCode());
    assertEquals("Fantastic", urlConnection.getResponseMessage());

    assertEquals(-1, in.read());
    in.close();
  }

  @Test public void secureResponseCaching() throws IOException {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.play();

    HttpsURLConnection c1 = (HttpsURLConnection) openConnection(server.getUrl("/"));
    c1.setSSLSocketFactory(sslContext.getSocketFactory());
    c1.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(c1));

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    String suite = c1.getCipherSuite();
    List<Certificate> localCerts = toListOrNull(c1.getLocalCertificates());
    List<Certificate> serverCerts = toListOrNull(c1.getServerCertificates());
    Principal peerPrincipal = c1.getPeerPrincipal();
    Principal localPrincipal = c1.getLocalPrincipal();

    HttpsURLConnection c2 = (HttpsURLConnection) openConnection(server.getUrl("/")); // cached!
    c2.setSSLSocketFactory(sslContext.getSocketFactory());
    c2.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(c2));

    assertEquals(suite, c2.getCipherSuite());
    assertEquals(localCerts, toListOrNull(c2.getLocalCertificates()));
    assertEquals(serverCerts, toListOrNull(c2.getServerCertificates()));
    assertEquals(peerPrincipal, c2.getPeerPrincipal());
    assertEquals(localPrincipal, c2.getLocalPrincipal());
  }

  @Test public void cacheReturnsInsecureResponseForSecureRequest() throws IOException {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));
    server.play();

    client.setResponseCache(new InsecureResponseCache(new InMemoryResponseCache()));

    HttpsURLConnection connection1 = (HttpsURLConnection) openConnection(server.getUrl("/"));
    connection1.setSSLSocketFactory(sslContext.getSocketFactory());
    connection1.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(connection1));

    // Not cached!
    HttpsURLConnection connection2 = (HttpsURLConnection) openConnection(server.getUrl("/"));
    connection2.setSSLSocketFactory(sslContext.getSocketFactory());
    connection2.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("DEF", readAscii(connection2));
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

    HttpsURLConnection connection1 = (HttpsURLConnection) openConnection(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection1));
    assertNotNull(connection1.getCipherSuite());

    // Cached!
    HttpsURLConnection connection2 = (HttpsURLConnection) openConnection(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection2));
    assertNotNull(connection2.getCipherSuite());

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

    HttpURLConnection connection1 = openConnection(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection1));

    // Cached!
    HttpURLConnection connection2 = openConnection(server.getUrl("/"));
    assertEquals("ABC", readAscii(connection2));
  }

  @Test public void responseCacheRequestHeaders() throws IOException, URISyntaxException {
    server.enqueue(new MockResponse().setBody("ABC"));
    server.play();

    final AtomicReference<Map<String, List<String>>> requestHeadersRef =
        new AtomicReference<Map<String, List<String>>>();
    client.setResponseCache(new ResponseCache() {
      @Override
      public CacheResponse get(URI uri, String requestMethod,
          Map<String, List<String>> requestHeaders) throws IOException {
        requestHeadersRef.set(requestHeaders);
        return null;
      }

      @Override
      public CacheRequest put(URI uri, URLConnection conn) throws IOException {
        return null;
      }
    });

    URL url = server.getUrl("/");
    URLConnection urlConnection = openConnection(url);
    urlConnection.addRequestProperty("A", "android");
    readAscii(urlConnection);
    assertEquals(Arrays.asList("android"), requestHeadersRef.get().get("A"));
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
        expected.printStackTrace();
    } finally {
      reader.close();
    }

    URLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("Request #2", readAscii(connection));
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

    connection = openConnection(server.getUrl("/"));
    assertEquals("Request #2", readAscii(connection));
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

  /**
   * Tests that the ResponseCache can cache something. The InMemoryResponseCache only caches GET
   * requests.
   */
  @Test public void responseCacheCanCache() throws Exception {
    testRequestMethod("GET", true);
  }

  /**
   * Confirm the ResponseCache can elect to not cache something. The InMemoryResponseCache only
   * caches GET requests.
   */
  @Test public void responseCacheCanIgnore() throws Exception {
    testRequestMethod("HEAD", false);
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

  /**
   * Equivalent to {@link HttpResponseCacheTest#postInvalidatesCacheWithUncacheableResponse()} but
   * demonstrating that {@link ResponseCache} provides no mechanism for cache invalidation as the
   * result of locally-made requests. In reality invalidation could take place from other clients at
   * any time.
   */
  @Test public void postInvalidatesCacheWithUncacheableResponse() throws Exception {
    // 1. seed the cache
    // 2. invalidate it with uncacheable response
    // 3. the cache to return the original value
    server.enqueue(
        new MockResponse().setBody("A").addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B").setResponseCode(500));
    server.play();

    URL url = server.getUrl("/");

    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection invalidate = openConnection(url);
    invalidate.setRequestMethod("POST");
    addRequestBodyIfNecessary("POST", invalidate);
    assertEquals("B", readAscii(invalidate));

    assertEquals("A", readAscii(openConnection(url)));
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
  }

  @Test public void requestOnlyIfCachedWithUnhelpfulResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    assertEquals("A", readAscii(openConnection(server.getUrl("/"))));
    HttpURLConnection connection = openConnection(server.getUrl("/"));
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
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

  @Test public void setIfModifiedSince() throws Exception {
    Date since = new Date();
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection = openConnection(url);
    connection.setIfModifiedSince(since.getTime());
    assertEquals("A", readAscii(connection));
    RecordedRequest request = server.takeRequest();
    assertTrue(request.getHeaders().contains("If-Modified-Since: " + formatDate(since)));
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

  /**
   * Confirms the cache implementation may determine the criteria for caching. In real caches
   * this would be the "Vary" headers.
   */
  @Test public void cacheCanUseCriteriaBesidesVariantObeyed() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Cache-Control: max-age=60")
            .addHeader(InMemoryResponseCache.CACHE_VARIANT_HEADER, "A").setBody("A"));
    server.enqueue(
        new MockResponse().addHeader("Cache-Control: max-age=60")
            .addHeader(InMemoryResponseCache.CACHE_VARIANT_HEADER, "B").setBody("B"));
    server.play();

    URL url = server.getUrl("/");
    URLConnection connection1 = openConnection(url);
    connection1.addRequestProperty(InMemoryResponseCache.CACHE_VARIANT_HEADER, "A");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = openConnection(url);
    connection2.addRequestProperty(InMemoryResponseCache.CACHE_VARIANT_HEADER, "A");
    assertEquals("A", readAscii(connection2));
    assertEquals(1, server.getRequestCount());

    URLConnection connection3 = openConnection(url);
    connection3.addRequestProperty(InMemoryResponseCache.CACHE_VARIANT_HEADER, "B");
    assertEquals("B", readAscii(connection3));
    assertEquals(2, server.getRequestCount());

    URLConnection connection4 = openConnection(url);
    connection4.addRequestProperty(InMemoryResponseCache.CACHE_VARIANT_HEADER, "A");
    assertEquals("A", readAscii(connection4));
    assertEquals(2, server.getRequestCount());
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

    CookieManager cookieManager = new CookieManager();
    CookieManager.setDefault(cookieManager);

    URL url = server.getUrl("/");
    assertEquals("A", readAscii(openConnection(url)));
    assertCookies(cookieManager, url, "a=FIRST");
    assertEquals("A", readAscii(openConnection(url)));
    assertCookies(cookieManager, url, "a=SECOND");
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

  public void assertCookies(CookieManager cookieManager, URL url, String... expectedCookies)
      throws Exception {
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

  /**
   * Equivalent to {@link HttpResponseCacheTest#conditionalHitUpdatesCache()}, except a Java
   * standard cache has no means to update the headers for an existing entry so the behavior is
   * different.
   */
  @Test public void conditionalHitDoesNotUpdateCache() throws Exception {
    // A response that is cacheable, but with a short life.
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    // A response that refers to the previous response, but is cacheable with a long life.
    // Contains a header we can recognize as having come from the server.
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=30")
        .addHeader("Allow: GET, HEAD")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    // A response that is cacheable with a long life.
    server.enqueue(new MockResponse().setBody("B").addHeader("Cache-Control: max-age=30"));
    // A response that should never be requested.
    server.enqueue(new MockResponse().setBody("C"));
    server.play();

    // cache miss; seed the cache with an entry that will require a network hit to be sure it is
    // still valid
    HttpURLConnection connection1 = openConnection(server.getUrl("/a"));
    assertEquals("A", readAscii(connection1));
    assertEquals(null, connection1.getHeaderField("Allow"));

    // conditional cache hit; The cached data should be returned, but the cache is not updated.
    HttpURLConnection connection2 = openConnection(server.getUrl("/a"));
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A", readAscii(connection2));
    assertEquals("GET, HEAD", connection2.getHeaderField("Allow"));

    // conditional cache hit; The server responds with new data. The cache is updated.
    HttpURLConnection connection3 = openConnection(server.getUrl("/a"));
    assertEquals("B", readAscii(connection3));

    // full cache hit; The data from connection3 has now replaced that from connection 1.
    HttpURLConnection connection4 = openConnection(server.getUrl("/a"));
    assertEquals("B", readAscii(connection4));

    assertEquals(3, server.getRequestCount());
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

    String source = connection.getHeaderField(OkHeaders.RESPONSE_SOURCE);
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

    String source = connection.getHeaderField(OkHeaders.RESPONSE_SOURCE);
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

    String source = connection.getHeaderField(OkHeaders.RESPONSE_SOURCE);
    assertEquals(ResponseSource.CONDITIONAL_CACHE + " 304", source);
  }

  @Test public void responseSourceHeaderFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));
    server.play();

    URLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("A", readAscii(connection));

    String source = connection.getHeaderField(OkHeaders.RESPONSE_SOURCE);
    assertEquals(ResponseSource.NETWORK + " 200", source);
  }

  @Test public void emptyResponseHeaderNameFromCacheIsLenient() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=120")
        .addHeader(": A")
        .setBody("body"));
    server.play();
    HttpURLConnection connection = openConnection(server.getUrl("/"));
    assertEquals("A", connection.getHeaderField(""));
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
        ? connection.getInputStream() : httpConnection.getErrorStream();
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
        connection.getHeaderField(OkHeaders.RESPONSE_SOURCE));
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

  private static class InsecureResponseCache extends ResponseCache {

    private final ResponseCache delegate;

    private InsecureResponseCache(ResponseCache delegate) {
      this.delegate = delegate;
    }

    @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
      return delegate.put(uri, connection);
    }

    @Override public CacheResponse get(URI uri, String requestMethod,
        Map<String, List<String>> requestHeaders) throws IOException {
      final CacheResponse response = delegate.get(uri, requestMethod, requestHeaders);
      if (response instanceof SecureCacheResponse) {
        return new CacheResponse() {
          @Override public InputStream getBody() throws IOException {
            return response.getBody();
          }
          @Override public Map<String, List<String>> getHeaders() throws IOException {
            return response.getHeaders();
          }
        };
      }
      return response;
    }
  }

  /**
   * A trivial and non-thread-safe implementation of ResponseCache that uses an in-memory map to
   * cache GETs.
   */
  private static class InMemoryResponseCache extends ResponseCache {

    /** A request / response header that acts a bit like Vary but without the complexity. */
    public static final String CACHE_VARIANT_HEADER = "CacheVariant";

    private static class Key {
      private final URI uri;
      private final String cacheVariant;

      private Key(URI uri, String cacheVariant) {
        this.uri = uri;
        this.cacheVariant = cacheVariant;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }

        Key key = (Key) o;

        if (cacheVariant != null ? !cacheVariant.equals(key.cacheVariant)
            : key.cacheVariant != null) {
          return false;
        }
        if (!uri.equals(key.uri)) {
          return false;
        }

        return true;
      }

      @Override
      public int hashCode() {
        int result = uri.hashCode();
        result = 31 * result + (cacheVariant != null ? cacheVariant.hashCode() : 0);
        return result;
      }
    }

    private class Entry {

      private final URI uri;
      private final String cacheVariant;
      private final String method;
      private final Map<String, List<String>> responseHeaders;
      private final String cipherSuite;
      private final Certificate[] serverCertificates;
      private final Certificate[] localCertificates;
      private byte[] body;

      public Entry(URI uri, URLConnection urlConnection) {
        this.uri = uri;
        HttpURLConnection httpUrlConnection = (HttpURLConnection) urlConnection;
        method = httpUrlConnection.getRequestMethod();
        cacheVariant = urlConnection.getHeaderField(CACHE_VARIANT_HEADER);
        responseHeaders = urlConnection.getHeaderFields();
        if (urlConnection instanceof HttpsURLConnection) {
          HttpsURLConnection httpsURLConnection = (HttpsURLConnection) urlConnection;
          cipherSuite = httpsURLConnection.getCipherSuite();
          Certificate[] serverCertificates;
          try {
            serverCertificates = httpsURLConnection.getServerCertificates();
          } catch (SSLPeerUnverifiedException e) {
            serverCertificates = null;
          }
          this.serverCertificates = serverCertificates;
          localCertificates = httpsURLConnection.getLocalCertificates();
        } else {
          cipherSuite = null;
          serverCertificates = null;
          localCertificates = null;
        }
      }

      public CacheResponse asCacheResponse() {
        if (!method.equals(this.method)) {
          return null;
        }

        // Handle SSL
        if (cipherSuite != null) {
          return new SecureCacheResponse() {
            @Override
            public Map<String, List<String>> getHeaders() throws IOException {
              return responseHeaders;
            }

            @Override
            public InputStream getBody() throws IOException {
              return new ByteArrayInputStream(body);
            }

            @Override
            public String getCipherSuite() {
              return cipherSuite;
            }

            @Override
            public List<Certificate> getLocalCertificateChain() {
              return localCertificates == null ? null : Arrays.asList(localCertificates);
            }

            @Override
            public List<Certificate> getServerCertificateChain() throws SSLPeerUnverifiedException {
              if (serverCertificates == null) {
                throw new SSLPeerUnverifiedException("Test implementation");
              }
              return Arrays.asList(serverCertificates);
            }

            @Override
            public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
              throw new UnsupportedOperationException();
            }

            @Override
            public Principal getLocalPrincipal() {
              throw new UnsupportedOperationException();
            }
          };
        } else {
          return new CacheResponse() {
            @Override
            public Map<String, List<String>> getHeaders() throws IOException {
              return responseHeaders;
            }

            @Override
            public InputStream getBody() throws IOException {
              return new ByteArrayInputStream(body);
            }
          };
        }
      }

      public CacheRequest asCacheRequest() {
        return new CacheRequest() {
          @Override
          public OutputStream getBody() throws IOException {
            return new ByteArrayOutputStream() {
              @Override
              public void close() throws IOException {
                super.close();
                body = toByteArray();
                cache.put(Entry.this.key(), Entry.this);
              }
            };
          }

          @Override
          public void abort() {
            // No-op: close() puts the item in the cache, abort need not do anything.
          }
        };
      }

      private Key key() {
        return new Key(uri, cacheVariant);
      }
    }

    private Map<Key, Entry> cache = new HashMap<Key, Entry>();

    @Override
    public CacheResponse get(URI uri, String method, Map<String, List<String>> requestHeaders)
        throws IOException {

      if (!"GET".equals(method)) {
        return null;
      }

      String cacheVariant =
          requestHeaders.containsKey(CACHE_VARIANT_HEADER)
              ? requestHeaders.get(CACHE_VARIANT_HEADER).get(0) : null;
      Key key = new Key(uri, cacheVariant);
      Entry entry = cache.get(key);
      if (entry == null) {
        return null;
      }
      return entry.asCacheResponse();
    }

    @Override
    public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
      if (!"GET".equals(((HttpURLConnection) urlConnection).getRequestMethod())) {
        return null;
      }

      Entry entry = new Entry(uri, urlConnection);
      return entry.asCacheRequest();
    }
  }
}
