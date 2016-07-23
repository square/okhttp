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

package okhttp3;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URL;
import java.net.URLConnection;
import java.security.Principal;
import java.security.cert.Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

/** Test caching with {@link OkUrlFactory}. */
public final class UrlConnectionCacheTest {
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
    @Override public boolean verify(String s, SSLSession sslSession) {
      return true;
    }
  };

  @Rule public MockWebServer server = new MockWebServer();
  @Rule public MockWebServer server2 = new MockWebServer();
  @Rule public InMemoryFileSystem fileSystem = new InMemoryFileSystem();

  private final SslClient sslClient = SslClient.localhost();
  private OkUrlFactory urlFactory = new OkUrlFactory(new OkHttpClient());
  private Cache cache;
  private final CookieManager cookieManager = new CookieManager();

  @Before public void setUp() throws Exception {
    server.setProtocolNegotiationEnabled(false);
    cache = new Cache(new File("/cache/"), Integer.MAX_VALUE, fileSystem);
    urlFactory = new OkUrlFactory(new OkHttpClient.Builder()
        .cache(cache)
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build());
  }

  @After public void tearDown() throws Exception {
    ResponseCache.setDefault(null);
    cache.delete();
  }

  @Test public void responseCacheAccessWithOkHttpMember() throws IOException {
    assertSame(cache, urlFactory.client().cache());
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
    assertCached(true, 204);
    assertCached(false, 205);
    assertCached(false, 206); //Electing to not cache partial responses
    assertCached(false, 207);
    assertCached(true, 300);
    assertCached(true, 301);
    assertCached(true, 302);
    assertCached(false, 303);
    assertCached(false, 304);
    assertCached(false, 305);
    assertCached(false, 306);
    assertCached(true, 307);
    assertCached(true, 308);
    assertCached(false, 400);
    assertCached(false, 401);
    assertCached(false, 402);
    assertCached(false, 403);
    assertCached(true, 404);
    assertCached(true, 405);
    assertCached(false, 406);
    assertCached(false, 408);
    assertCached(false, 409);
    // the HTTP spec permits caching 410s, but the RI doesn't.
    assertCached(true, 410);
    assertCached(false, 411);
    assertCached(false, 412);
    assertCached(false, 413);
    assertCached(true, 414);
    assertCached(false, 415);
    assertCached(false, 416);
    assertCached(false, 417);
    assertCached(false, 418);

    assertCached(false, 500);
    assertCached(true, 501);
    assertCached(false, 502);
    assertCached(false, 503);
    assertCached(false, 504);
    assertCached(false, 505);
    assertCached(false, 506);
  }

  private void assertCached(boolean shouldPut, int responseCode) throws Exception {
    int expectedResponseCode = responseCode;

    server = new MockWebServer();
    MockResponse response = new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(responseCode)
        .setBody("ABCDE")
        .addHeader("WWW-Authenticate: challenge");
    if (responseCode == HttpURLConnection.HTTP_PROXY_AUTH) {
      response.addHeader("Proxy-Authenticate: Basic realm=\"protected area\"");
    } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
      response.addHeader("WWW-Authenticate: Basic realm=\"protected area\"");
    } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT
        || responseCode == HttpURLConnection.HTTP_RESET) {
      response.setBody(""); // We forbid bodies for 204 and 205.
    }
    server.enqueue(response);

    if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
      // 408's are a bit of an outlier because we may repeat the request if we encounter this
      // response code. In this scenario, there are 2 responses: the initial 408 and then the 200
      // because of the retry. We just want to ensure the initial 408 isn't cached.
      expectedResponseCode = 200;
      server.enqueue(new MockResponse()
          .addHeader("Cache-Control", "no-store")
          .setBody("FGHIJ"));
    }

    server.start();

    URL url = server.url("/").url();
    HttpURLConnection conn = urlFactory.open(url);
    assertEquals(expectedResponseCode, conn.getResponseCode());

    // exhaust the content stream
    readAscii(conn);

    Response cached = cache.get(new Request.Builder().url(url).build());
    if (shouldPut) {
      assertNotNull(Integer.toString(responseCode), cached);
      cached.body().close();
    } else {
      assertNull(Integer.toString(responseCode), cached);
    }

    // tearDown() isn't sufficient; this test starts multiple servers
    assertTrue("Server failed to shutdown.", server.shutdown());
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

    // Make sure that calling skip() doesn't omit bytes from the cache.
    HttpURLConnection urlConnection = urlFactory.open(server.url("/").url());
    InputStream in = urlConnection.getInputStream();
    assertEquals("I love ", readAscii(urlConnection, "I love ".length()));
    reliableSkip(in, "puppies but hate ".length());
    assertEquals("spiders", readAscii(urlConnection, "spiders".length()));
    assertEquals(-1, in.read());
    in.close();
    assertEquals(1, cache.writeSuccessCount());
    assertEquals(0, cache.writeAbortCount());

    urlConnection = urlFactory.open(server.url("/").url()); // cached!
    in = urlConnection.getInputStream();
    assertEquals("I love puppies but hate spiders",
        readAscii(urlConnection, "I love puppies but hate spiders".length()));
    assertEquals(200, urlConnection.getResponseCode());
    assertEquals("Fantastic", urlConnection.getResponseMessage());

    assertEquals(-1, in.read());
    in.close();
    assertEquals(1, cache.writeSuccessCount());
    assertEquals(0, cache.writeAbortCount());
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.hitCount());
  }

  @Test public void secureResponseCaching() throws IOException {
    assumeFalse(getPlatform().equals("jdk9"));

    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));

    HttpsURLConnection c1 = (HttpsURLConnection) urlFactory.open(server.url("/").url());
    c1.setSSLSocketFactory(sslClient.socketFactory);
    c1.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(c1));

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    String suite = c1.getCipherSuite();
    List<Certificate> localCerts = toListOrNull(c1.getLocalCertificates());
    List<Certificate> serverCerts = toListOrNull(c1.getServerCertificates());
    Principal peerPrincipal = c1.getPeerPrincipal();
    Principal localPrincipal = c1.getLocalPrincipal();

    HttpsURLConnection c2 = (HttpsURLConnection) urlFactory.open(server.url("/").url()); // cached!
    c2.setSSLSocketFactory(sslClient.socketFactory);
    c2.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    assertEquals("ABC", readAscii(c2));

    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(1, cache.hitCount());

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

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("ABC", readAscii(connection));

    connection = urlFactory.open(server.url("/").url()); // cached!
    assertEquals("ABC", readAscii(connection));

    assertEquals(4, cache.requestCount()); // 2 requests + 2 redirects
    assertEquals(2, cache.networkCount());
    assertEquals(2, cache.hitCount());
  }

  @Test public void redirectToCachedResult() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60").setBody("ABC"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse().setBody("DEF"));

    assertEquals("ABC", readAscii(urlFactory.open(server.url("/foo").url())));
    RecordedRequest request1 = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request1.getRequestLine());
    assertEquals(0, request1.getSequenceNumber());

    assertEquals("ABC", readAscii(urlFactory.open(server.url("/bar").url())));
    RecordedRequest request2 = server.takeRequest();
    assertEquals("GET /bar HTTP/1.1", request2.getRequestLine());
    assertEquals(1, request2.getSequenceNumber());

    // an unrelated request should reuse the pooled connection
    assertEquals("DEF", readAscii(urlFactory.open(server.url("/baz").url())));
    RecordedRequest request3 = server.takeRequest();
    assertEquals("GET /baz HTTP/1.1", request3.getRequestLine());
    assertEquals(2, request3.getSequenceNumber());
  }

  @Test public void secureResponseCachingAndRedirects() throws IOException {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build());

    HttpsURLConnection connection1 = (HttpsURLConnection) urlFactory.open(server.url("/").url());
    assertEquals("ABC", readAscii(connection1));
    assertNotNull(connection1.getCipherSuite());

    // Cached!
    HttpsURLConnection connection2 = (HttpsURLConnection) urlFactory.open(server.url("/").url());
    assertEquals("ABC", readAscii(connection2));
    assertNotNull(connection2.getCipherSuite());

    assertEquals(4, cache.requestCount()); // 2 direct + 2 redirect = 4
    assertEquals(2, cache.hitCount());
    assertEquals(connection1.getCipherSuite(), connection2.getCipherSuite());
  }

  /**
   * We've had bugs where caching and cross-protocol redirects yield class cast exceptions internal
   * to the cache because we incorrectly assumed that HttpsURLConnection was always HTTPS and
   * HttpURLConnection was always HTTP; in practice redirects mean that each can do either.
   *
   * https://github.com/square/okhttp/issues/214
   */
  @Test public void secureResponseCachingAndProtocolRedirects() throws IOException {
    server2.useHttps(sslClient.socketFactory, false);
    server2.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server2.enqueue(new MockResponse().setBody("DEF"));

    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: " + server2.url("/").url()));

    urlFactory.setClient(urlFactory.client().newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build());

    HttpURLConnection connection1 = urlFactory.open(server.url("/").url());
    assertEquals("ABC", readAscii(connection1));

    // Cached!
    HttpURLConnection connection2 = urlFactory.open(server.url("/").url());
    assertEquals("ABC", readAscii(connection2));

    assertEquals(4, cache.requestCount()); // 2 direct + 2 redirect = 4
    assertEquals(2, cache.hitCount());
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

    BufferedReader reader = new BufferedReader(
        new InputStreamReader(urlFactory.open(server.url("/").url()).getInputStream()));
    assertEquals("ABCDE", reader.readLine());
    try {
      reader.readLine();
      fail("This implementation silently ignored a truncated HTTP body.");
    } catch (IOException expected) {
    } finally {
      reader.close();
    }

    assertEquals(1, cache.writeAbortCount());
    assertEquals(0, cache.writeSuccessCount());
    URLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("Request #2", readAscii(connection));
    assertEquals(1, cache.writeAbortCount());
    assertEquals(1, cache.writeSuccessCount());
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

    URLConnection connection = urlFactory.open(server.url("/").url());
    InputStream in = connection.getInputStream();
    assertEquals("ABCDE", readAscii(connection, 5));
    in.close();
    try {
      in.read();
      fail("Expected an IOException because the stream is closed.");
    } catch (IOException expected) {
    }

    assertEquals(1, cache.writeAbortCount());
    assertEquals(0, cache.writeSuccessCount());
    connection = urlFactory.open(server.url("/").url());
    assertEquals("Request #2", readAscii(connection));
    assertEquals(1, cache.writeAbortCount());
    assertEquals(1, cache.writeSuccessCount());
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

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    URLConnection connection = urlFactory.open(url);
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
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void defaultExpirationDateFullyCachedForMoreThan24Hours() throws Exception {
    //      last modified: 105 days ago
    //             served:   5 days ago
    //   default lifetime: (105 - 5) / 10 = 10 days
    //            expires:  10 days from served date = 5 days from now
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-105, TimeUnit.DAYS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.DAYS))
        .setBody("A"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    URLConnection connection = urlFactory.open(server.url("/").url());
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

    URL url = server.url("/?foo=bar").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    assertEquals("B", readAscii(urlFactory.open(url)));
  }

  @Test public void expirationDateInThePastWithLastModifiedHeader() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
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
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
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

    URL url = server.url("/").url();

    HttpURLConnection request1 = urlFactory.open(url);
    request1.setRequestMethod(requestMethod);
    addRequestBodyIfNecessary(requestMethod, request1);
    request1.getInputStream().close();
    assertEquals("1", request1.getHeaderField("X-Response-ID"));

    URLConnection request2 = urlFactory.open(url);
    request2.getInputStream().close();
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

    URL url = server.url("/").url();

    assertEquals("A", readAscii(urlFactory.open(url)));

    HttpURLConnection invalidate = urlFactory.open(url);
    invalidate.setRequestMethod(requestMethod);
    addRequestBodyIfNecessary(requestMethod, invalidate);
    assertEquals("B", readAscii(invalidate));

    assertEquals("C", readAscii(urlFactory.open(url)));
  }

  @Test public void postInvalidatesCacheWithUncacheableResponse() throws Exception {
    // 1. seed the cache
    // 2. invalidate it with uncacheable response
    // 3. expect a cache miss
    server.enqueue(
        new MockResponse().setBody("A").addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B").setResponseCode(500));
    server.enqueue(new MockResponse().setBody("C"));

    URL url = server.url("/").url();

    assertEquals("A", readAscii(urlFactory.open(url)));

    HttpURLConnection invalidate = urlFactory.open(url);
    invalidate.setRequestMethod("POST");
    addRequestBodyIfNecessary("POST", invalidate);
    assertEquals("B", readAscii(invalidate));

    assertEquals("C", readAscii(urlFactory.open(url)));
  }

  @Test public void etag() throws Exception {
    RecordedRequest conditionalRequest =
        assertConditionallyCached(new MockResponse().addHeader("ETag: v1"));
    assertEquals("v1", conditionalRequest.getHeader("If-None-Match"));
  }

  @Test public void etagAndExpirationDateInThePast() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(
        new MockResponse().addHeader("ETag: v1")
            .addHeader("Last-Modified: " + lastModifiedDate)
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    assertEquals("v1", conditionalRequest.getHeader("If-None-Match"));
    assertNull(conditionalRequest.getHeader("If-Modified-Since"));
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
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
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
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
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

    URL url = server.url("/").url();

    URLConnection range = urlFactory.open(url);
    range.addRequestProperty("Range", "bytes=1000-1001");
    assertEquals("AA", readAscii(range));

    assertEquals("BB", readAscii(urlFactory.open(url)));
  }

  @Test public void serverReturnsDocumentOlderThanCache() throws Exception {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse().setBody("B")
        .addHeader("Last-Modified: " + formatDate(-4, TimeUnit.HOURS)));

    URL url = server.url("/").url();

    assertEquals("A", readAscii(urlFactory.open(url)));
    assertEquals("A", readAscii(urlFactory.open(url)));
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

    // At least three request/response pairs are required because after the first request is cached
    // a different execution path might be taken. Thus modifications to the cache applied during
    // the second request might not be visible until another request is performed.
    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
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

    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("DEFDEFDEF", readAscii(urlFactory.open(server.url("/").url())));
  }

  /** https://github.com/square/okhttp/issues/947 */
  @Test public void gzipAndVaryOnAcceptEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .addHeader("Vary: Accept-Encoding")
        .addHeader("Cache-Control: max-age=60"));
    server.enqueue(new MockResponse().setBody("FAIL"));

    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(urlFactory.open(server.url("/").url())));
  }

  @Test public void conditionalCacheHitIsNotDoublePooled() throws Exception {
    server.enqueue(new MockResponse().addHeader("ETag: v1").setBody("A"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(1, urlFactory.client().connectionPool().idleConnectionCount());
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

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "max-age=30");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestMinFresh() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "min-fresh=120");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestMaxStale() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));

    URLConnection connection = urlFactory.open(server.url("/").url());
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

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "max-stale=180");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestOnlyIfCachedWithNoResponseCached() throws IOException {
    // (no responses enqueued)

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
    assertEquals(1, cache.requestCount());
    assertEquals(0, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void requestOnlyIfCachedWithFullResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertEquals("A", readAscii(connection));
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(1, cache.hitCount());
  }

  @Test public void requestOnlyIfCachedWithConditionalResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void requestOnlyIfCachedWithUnhelpfulResponseCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void requestCacheControlNoCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
            .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
            .addHeader("Cache-Control: max-age=60")
            .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    URLConnection connection = urlFactory.open(url);
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

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    URLConnection connection = urlFactory.open(url);
    connection.setRequestProperty("Pragma", "no-cache");
    assertEquals("B", readAscii(connection));
  }

  @Test public void clientSuppliedIfModifiedSinceWithCachedResult() throws Exception {
    MockResponse response =
        new MockResponse().addHeader("ETag: v3").addHeader("Cache-Control: max-age=0");
    String ifModifiedSinceDate = formatDate(-24, TimeUnit.HOURS);
    RecordedRequest request =
        assertClientSuppliedCondition(response, "If-Modified-Since", ifModifiedSinceDate);
    assertEquals(ifModifiedSinceDate, request.getHeader("If-Modified-Since"));
    assertNull(request.getHeader("If-None-Match"));
  }

  @Test public void clientSuppliedIfNoneMatchSinceWithCachedResult() throws Exception {
    String lastModifiedDate = formatDate(-3, TimeUnit.MINUTES);
    MockResponse response = new MockResponse().addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: max-age=0");
    RecordedRequest request = assertClientSuppliedCondition(response, "If-None-Match", "v1");
    assertEquals("v1", request.getHeader("If-None-Match"));
    assertNull(request.getHeader("If-Modified-Since"));
  }

  private RecordedRequest assertClientSuppliedCondition(MockResponse seed, String conditionName,
      String conditionValue) throws Exception {
    server.enqueue(seed.setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));

    HttpURLConnection connection = urlFactory.open(url);
    connection.addRequestProperty(conditionName, conditionValue);
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection.getResponseCode());
    assertEquals("", readAscii(connection));

    server.takeRequest(); // seed
    return server.takeRequest();
  }

  /**
   * Confirm that {@link URLConnection#setIfModifiedSince} causes an If-Modified-Since header with a
   * GMT timestamp.
   *
   * https://code.google.com/p/android/issues/detail?id=66135
   */
  @Test public void setIfModifiedSince() throws Exception {
    server.enqueue(new MockResponse().setBody("A"));

    URL url = server.url("/").url();
    URLConnection connection = urlFactory.open(url);
    connection.setIfModifiedSince(1393666200000L);
    assertEquals("A", readAscii(connection));
    RecordedRequest request = server.takeRequest();
    String ifModifiedSinceHeader = request.getHeader("If-Modified-Since");
    assertEquals("Sat, 01 Mar 2014 09:30:00 GMT", ifModifiedSinceHeader);
  }

  /**
   * For Last-Modified and Date headers, we should echo the date back in the exact format we were
   * served.
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

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));

    // The first request has no conditions.
    RecordedRequest request1 = server.takeRequest();
    assertNull(request1.getHeader("If-Modified-Since"));

    // The 2nd request uses the server's date format.
    RecordedRequest request2 = server.takeRequest();
    assertEquals(lastModifiedString, request2.getHeader("If-Modified-Since"));
  }

  @Test public void clientSuppliedConditionWithoutCachedResult() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    String clientIfModifiedSince = formatDate(-24, TimeUnit.HOURS);
    connection.addRequestProperty("If-Modified-Since", clientIfModifiedSince);
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection.getResponseCode());
    assertEquals("", readAscii(connection));
  }

  @Test public void authorizationRequestFullyCached() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    URLConnection connection = urlFactory.open(url);
    connection.addRequestProperty("Authorization", "password");
    assertEquals("A", readAscii(connection));
    assertEquals("A", readAscii(urlFactory.open(url)));
  }

  @Test public void contentLocationDoesNotPopulateCache() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Content-Location: /bar")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/foo").url())));
    assertEquals("B", readAscii(urlFactory.open(server.url("/bar").url())));
  }

  @Test public void useCachesFalseDoesNotWriteToCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.setUseCaches(false);
    assertEquals("A", readAscii(connection));
    assertEquals("B", readAscii(urlFactory.open(server.url("/").url())));
  }

  @Test public void useCachesFalseDoesNotReadFromCache() throws Exception {
    server.enqueue(
        new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A").setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.setUseCaches(false);
    assertEquals("B", readAscii(connection));
  }

  @Test public void defaultUseCachesSetsInitialValueOnly() throws Exception {
    URL url = new URL("http://localhost/");
    URLConnection c1 = urlFactory.open(url);
    URLConnection c2 = urlFactory.open(url);
    assertTrue(c1.getDefaultUseCaches());
    c1.setDefaultUseCaches(false);
    try {
      assertTrue(c1.getUseCaches());
      assertTrue(c2.getUseCaches());
      URLConnection c3 = urlFactory.open(url);
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

    assertEquals("A", readAscii(urlFactory.open(server.url("/a").url())));
    assertEquals("A", readAscii(urlFactory.open(server.url("/a").url())));
    assertEquals("B", readAscii(urlFactory.open(server.url("/b").url())));

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

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
    assertEquals("B", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("C", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(3, cache.requestCount());
    assertEquals(3, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void statisticsConditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(3, cache.requestCount());
    assertEquals(3, cache.networkCount());
    assertEquals(2, cache.hitCount());
  }

  @Test public void statisticsFullCacheHit() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60").setBody("A"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals(3, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(2, cache.hitCount());
  }

  @Test public void varyMatchesChangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection frConnection = urlFactory.open(url);
    frConnection.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(frConnection));

    HttpURLConnection enConnection = urlFactory.open(url);
    enConnection.addRequestProperty("Accept-Language", "en-US");
    assertEquals("B", readAscii(enConnection));
  }

  @Test public void varyMatchesUnchangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    URLConnection connection1 = urlFactory.open(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = urlFactory.open(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMatchesAbsentRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
  }

  @Test public void varyMatchesAddedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    URLConnection fooConnection = urlFactory.open(server.url("/").url());
    fooConnection.addRequestProperty("Foo", "bar");
    assertEquals("B", readAscii(fooConnection));
  }

  @Test public void varyMatchesRemovedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URLConnection fooConnection = urlFactory.open(server.url("/").url());
    fooConnection.addRequestProperty("Foo", "bar");
    assertEquals("A", readAscii(fooConnection));
    assertEquals("B", readAscii(urlFactory.open(server.url("/").url())));
  }

  @Test public void varyFieldsAreCaseInsensitive() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: ACCEPT-LANGUAGE")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    URLConnection connection1 = urlFactory.open(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = urlFactory.open(url);
    connection2.addRequestProperty("accept-language", "fr-CA");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMultipleFieldsWithMatch() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    URLConnection connection1 = urlFactory.open(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA");
    connection1.addRequestProperty("Accept-Charset", "UTF-8");
    connection1.addRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(connection1));
    URLConnection connection2 = urlFactory.open(url);
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

    URL url = server.url("/").url();
    URLConnection frConnection = urlFactory.open(url);
    frConnection.addRequestProperty("Accept-Language", "fr-CA");
    frConnection.addRequestProperty("Accept-Charset", "UTF-8");
    frConnection.addRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(frConnection));
    URLConnection enConnection = urlFactory.open(url);
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

    URL url = server.url("/").url();
    URLConnection connection1 = urlFactory.open(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA, fr-FR");
    connection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    URLConnection connection2 = urlFactory.open(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA, fr-FR");
    connection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void varyMultipleFieldValuesWithNoMatch() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    URLConnection connection1 = urlFactory.open(url);
    connection1.addRequestProperty("Accept-Language", "fr-CA, fr-FR");
    connection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    URLConnection connection2 = urlFactory.open(url);
    connection2.addRequestProperty("Accept-Language", "fr-CA");
    connection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("B", readAscii(connection2));
  }

  @Test public void varyAsterisk() throws Exception {
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    assertEquals("B", readAscii(urlFactory.open(server.url("/").url())));
  }

  @Test public void varyAndHttps() throws Exception {
    assumeFalse(getPlatform().equals("jdk9"));

    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse().addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    HttpsURLConnection connection1 = (HttpsURLConnection) urlFactory.open(url);
    connection1.setSSLSocketFactory(sslClient.socketFactory);
    connection1.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    connection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    HttpsURLConnection connection2 = (HttpsURLConnection) urlFactory.open(url);
    connection2.setSSLSocketFactory(sslClient.socketFactory);
    connection2.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    connection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void cachePlusCookies() throws Exception {
    RecordingCookieJar cookieJar = new RecordingCookieJar();
    urlFactory.setClient(urlFactory.client().newBuilder()
        .cookieJar(cookieJar)
        .build());

    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie: a=FIRST")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie: a=SECOND")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    cookieJar.assertResponseCookies("a=FIRST; path=/");

    assertEquals("A", readAscii(urlFactory.open(url)));
    cookieJar.assertResponseCookies("a=SECOND; path=/");
  }

  @Test public void getHeadersReturnsNetworkEndToEndHeaders() throws Exception {
    server.enqueue(new MockResponse().addHeader("Allow: GET, HEAD")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().addHeader("Allow: GET, HEAD, PUT")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("GET, HEAD", connection1.getHeaderField("Allow"));

    URLConnection connection2 = urlFactory.open(server.url("/").url());
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

    URLConnection connection1 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("identity", connection1.getHeaderField("Transfer-Encoding"));

    URLConnection connection2 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals("identity", connection2.getHeaderField("Transfer-Encoding"));
  }

  @Test public void getHeadersDeletesCached100LevelWarnings() throws Exception {
    server.enqueue(new MockResponse().addHeader("Warning: 199 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("199 test danger", connection1.getHeaderField("Warning"));

    URLConnection connection2 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals(null, connection2.getHeaderField("Warning"));
  }

  @Test public void getHeadersRetainsCached200LevelWarnings() throws Exception {
    server.enqueue(new MockResponse().addHeader("Warning: 299 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("299 test danger", connection1.getHeaderField("Warning"));

    URLConnection connection2 = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals("299 test danger", connection2.getHeaderField("Warning"));
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

    // cache miss; seed the cache
    HttpURLConnection connection1 = urlFactory.open(server.url("/a").url());
    assertEquals("A", readAscii(connection1));
    assertEquals(null, connection1.getHeaderField("Allow"));

    // conditional cache hit; update the cache
    HttpURLConnection connection2 = urlFactory.open(server.url("/a").url());
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A", readAscii(connection2));
    assertEquals("GET, HEAD", connection2.getHeaderField("Allow"));

    // full cache hit
    HttpURLConnection connection3 = urlFactory.open(server.url("/a").url());
    assertEquals("A", readAscii(connection3));
    assertEquals("GET, HEAD", connection3.getHeaderField("Allow"));

    assertEquals(2, server.getRequestCount());
  }

  @Test public void responseSourceHeaderCached() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    URLConnection connection = urlFactory.open(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertEquals("A", readAscii(connection));
  }

  @Test public void responseSourceHeaderConditionalCacheFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-31, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setBody("B")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("B", readAscii(connection));
  }

  @Test public void responseSourceHeaderConditionalCacheNotFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse().setResponseCode(304));

    assertEquals("A", readAscii(urlFactory.open(server.url("/").url())));
    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection));
  }

  @Test public void responseSourceHeaderFetched() throws IOException {
    server.enqueue(new MockResponse().setBody("A"));

    URLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("A", readAscii(connection));
  }

  @Test public void emptyResponseHeaderNameFromCacheIsLenient() throws Exception {
    Headers.Builder headers = new Headers.Builder()
        .add("Cache-Control: max-age=120");
    Internal.instance.addLenient(headers, ": A");
    server.enqueue(new MockResponse().setHeaders(headers.build()).setBody("body"));

    HttpURLConnection connection = urlFactory.open(server.url("/").url());
    assertEquals("A", connection.getHeaderField(""));
    assertEquals("body", readAscii(connection));
  }

  /**
   * Old implementations of OkHttp's response cache wrote header fields like ":status: 200 OK". This
   * broke our cached response parser because it split on the first colon. This regression test
   * exists to help us read these old bad cache entries.
   *
   * https://github.com/square/okhttp/issues/227
   */
  @Test public void testGoldenCacheResponse() throws Exception {
    cache.close();
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URL url = server.url("/").url();
    String urlKey = Util.md5Hex(url.toString());
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
    writeFile(cache.directory(), urlKey + ".0", entryMetadata);
    writeFile(cache.directory(), urlKey + ".1", entryBody);
    writeFile(cache.directory(), "journal", journalBody);
    cache = new Cache(cache.directory(), Integer.MAX_VALUE, fileSystem);
    urlFactory.setClient(urlFactory.client().newBuilder()
        .cache(cache)
        .build());

    HttpURLConnection connection = urlFactory.open(url);
    assertEquals(entryBody, readAscii(connection));
    assertEquals("3", connection.getHeaderField("Content-Length"));
    assertEquals("foo", connection.getHeaderField("etag"));
  }

  private void writeFile(File directory, String file, String content) throws IOException {
    BufferedSink sink = Okio.buffer(fileSystem.sink(new File(directory, file)));
    sink.writeUtf8(content);
    sink.close();
  }

  /**
   * @param delta the offset from the current date to use. Negative values yield dates in the past;
   * positive values yield dates in the future.
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

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    assertEquals("B", readAscii(urlFactory.open(url)));
  }

  /** @return the request with the conditional get headers. */
  private RecordedRequest assertConditionallyCached(MockResponse response) throws Exception {
    // scenario 1: condition succeeds
    server.enqueue(response.setBody("A").setStatus("HTTP/1.1 200 A-OK"));
    server.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    // scenario 2: condition fails
    server.enqueue(response.setBody("B").setStatus("HTTP/1.1 200 B-OK"));
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 200 C-OK").setBody("C"));

    URL valid = server.url("/valid").url();
    HttpURLConnection connection1 = urlFactory.open(valid);
    assertEquals("A", readAscii(connection1));
    assertEquals(HttpURLConnection.HTTP_OK, connection1.getResponseCode());
    assertEquals("A-OK", connection1.getResponseMessage());
    HttpURLConnection connection2 = urlFactory.open(valid);
    assertEquals("A", readAscii(connection2));
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A-OK", connection2.getResponseMessage());

    URL invalid = server.url("/invalid").url();
    HttpURLConnection connection3 = urlFactory.open(invalid);
    assertEquals("B", readAscii(connection3));
    assertEquals(HttpURLConnection.HTTP_OK, connection3.getResponseCode());
    assertEquals("B-OK", connection3.getResponseMessage());
    HttpURLConnection connection4 = urlFactory.open(invalid);
    assertEquals("C", readAscii(connection4));
    assertEquals(HttpURLConnection.HTTP_OK, connection4.getResponseCode());
    assertEquals("C-OK", connection4.getResponseMessage());

    server.takeRequest(); // regular get
    return server.takeRequest(); // conditional get
  }

  private void assertFullyCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(response.setBody("B"));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(urlFactory.open(url)));
    assertEquals("A", readAscii(urlFactory.open(url)));
  }

  /**
   * Shortens the body of {@code response} but not the corresponding headers. Only useful to test
   * how clients respond to the premature conclusion of the HTTP body.
   */
  private MockResponse truncateViolently(MockResponse response, int numBytesToKeep) {
    response.setSocketPolicy(DISCONNECT_AT_END);
    Headers headers = response.getHeaders();
    Buffer truncatedBody = new Buffer();
    truncatedBody.write(response.getBody(), numBytesToKeep);
    response.setBody(truncatedBody);
    response.setHeaders(headers);
    return response;
  }

  /**
   * Reads {@code count} characters from the stream. If the stream is exhausted before {@code count}
   * characters can be read, the remaining characters are returned and the stream is closed.
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
        response.removeHeader("Content-Length");
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

  private String getPlatform() {
    return System.getProperty("okhttp.platform", "platform");
  }
}
