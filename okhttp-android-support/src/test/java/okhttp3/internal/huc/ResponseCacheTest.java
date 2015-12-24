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

package okhttp3.internal.huc;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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
import java.net.ProtocolException;
import java.net.ResponseCache;
import java.net.SecureCacheResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import okhttp3.AbstractResponseCache;
import okhttp3.AndroidInternal;
import okhttp3.AndroidShimResponseCache;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.internal.Internal;
import okhttp3.internal.JavaNetCookieJar;
import okhttp3.internal.SslContextBuilder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.RecordingHostnameVerifier;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the interaction between OkHttp and {@link ResponseCache}. Based on okhttp3.CacheTest with
 * changes for ResponseCache and HttpURLConnection.
 */
public final class ResponseCacheTest {
  @Rule public TemporaryFolder cacheRule = new TemporaryFolder();
  @Rule public MockWebServer server = new MockWebServer();
  @Rule public MockWebServer server2 = new MockWebServer();

  private HostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
  private SSLContext sslContext = SslContextBuilder.localhost();
  private OkHttpClient client;
  private ResponseCache cache;
  private CookieManager cookieManager;

  @Before public void setUp() throws Exception {
    server.setProtocolNegotiationEnabled(false);

    client = new OkHttpClient();

    cache = AndroidShimResponseCache.create(cacheRule.getRoot(), 10 * 1024 * 1024);
    AndroidInternal.setResponseCache(new OkUrlFactory(client), cache);

    cookieManager = new CookieManager();
  }

  @After public void tearDown() throws Exception {
    ResponseCache.setDefault(null);
  }

  private HttpURLConnection openConnection(URL url) {
    return new OkUrlFactory(client).open(url);
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
    server = new MockWebServer();
    MockResponse mockResponse = new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(responseCode)
        .setBody("ABCDE")
        .addHeader("WWW-Authenticate: challenge");
    if (responseCode == HttpURLConnection.HTTP_PROXY_AUTH) {
      mockResponse.addHeader("Proxy-Authenticate: Basic realm=\"protected area\"");
    } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
      mockResponse.addHeader("WWW-Authenticate: Basic realm=\"protected area\"");
    }
    server.enqueue(mockResponse);
    server.start();

    URL url = server.url("/").url();
    HttpURLConnection connection = openConnection(url);
    assertEquals(responseCode, connection.getResponseCode());

    // Exhaust the content stream.
    readAscii(connection);

    CacheResponse cached = cache.get(url.toURI(), "GET", null);
    if (shouldPut) {
      assertNotNull(Integer.toString(responseCode), cached);
    } else {
      assertNull(Integer.toString(responseCode), cached);
    }
    server.shutdown(); // tearDown() isn't sufficient; this test starts multiple servers
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
    MockResponse mockResponse = new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setStatus("HTTP/1.1 200 Fantastic");
    transferKind.setBody(mockResponse, "I love puppies but hate spiders", 1);
    server.enqueue(mockResponse);

    // Make sure that calling skip() doesn't omit bytes from the cache.
    HttpURLConnection urlConnection = openConnection(server.url("/").url());
    InputStream in = urlConnection.getInputStream();
    assertEquals("I love ", readAscii(urlConnection, "I love ".length()));
    reliableSkip(in, "puppies but hate ".length());
    assertEquals("spiders", readAscii(urlConnection, "spiders".length()));
    assertEquals(-1, in.read());
    in.close();

    urlConnection = openConnection(server.url("/").url()); // cached!
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
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));

    HttpsURLConnection c1 = (HttpsURLConnection) openConnection(server.url("/").url());
    c1.setSSLSocketFactory(sslContext.getSocketFactory());
    c1.setHostnameVerifier(hostnameVerifier);
    assertEquals("ABC", readAscii(c1));

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    String suite = c1.getCipherSuite();
    List<Certificate> localCerts = toListOrNull(c1.getLocalCertificates());
    List<Certificate> serverCerts = toListOrNull(c1.getServerCertificates());
    Principal peerPrincipal = c1.getPeerPrincipal();
    Principal localPrincipal = c1.getLocalPrincipal();

    HttpsURLConnection c2 = (HttpsURLConnection) openConnection(server.url("/").url()); // cached!
    c2.setSSLSocketFactory(sslContext.getSocketFactory());
    c2.setHostnameVerifier(hostnameVerifier);
    assertEquals("ABC", readAscii(c2));

    assertEquals(suite, c2.getCipherSuite());
    assertEquals(localCerts, toListOrNull(c2.getLocalCertificates()));
    assertEquals(serverCerts, toListOrNull(c2.getServerCertificates()));
    assertEquals(peerPrincipal, c2.getPeerPrincipal());
    assertEquals(localPrincipal, c2.getLocalPrincipal());
  }

  @Test public void responseCachingAndRedirects() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));

    HttpURLConnection connection = openConnection(server.url("/").url());
    assertEquals("ABC", readAscii(connection));

    connection = openConnection(server.url("/").url()); // cached!
    assertEquals("ABC", readAscii(connection));
  }

  @Test public void redirectToCachedResult() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));

    assertEquals("ABC", readAscii(openConnection(server.url("/foo").url())));
    RecordedRequest request1 = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", request1.getRequestLine());
    assertEquals(0, request1.getSequenceNumber());

    assertEquals("ABC", readAscii(openConnection(server.url("/bar").url())));
    RecordedRequest request2 = server.takeRequest();
    assertEquals("GET /bar HTTP/1.1", request2.getRequestLine());
    assertEquals(1, request2.getSequenceNumber());

    // an unrelated request should reuse the pooled connection
    assertEquals("DEF", readAscii(openConnection(server.url("/baz").url())));
    RecordedRequest request3 = server.takeRequest();
    assertEquals("GET /baz HTTP/1.1", request3.getRequestLine());
    assertEquals(2, request3.getSequenceNumber());
  }

  @Test public void secureResponseCachingAndRedirects() throws IOException {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo"));
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("DEF"));

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(hostnameVerifier);

    HttpsURLConnection connection1 = (HttpsURLConnection) openConnection(server.url("/").url());
    assertEquals("ABC", readAscii(connection1));
    assertNotNull(connection1.getCipherSuite());

    // Cached!
    HttpsURLConnection connection2 = (HttpsURLConnection) openConnection(server.url("/").url());
    assertEquals("ABC", readAscii(connection2));
    assertNotNull(connection2.getCipherSuite());

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
    server2.useHttps(sslContext.getSocketFactory(), false);
    server2.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));
    server2.enqueue(new MockResponse()
        .setBody("DEF"));

    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setResponseCode(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: " + server2.url("/").url()));

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(hostnameVerifier);

    HttpURLConnection connection1 = openConnection(server.url("/").url());
    assertEquals("ABC", readAscii(connection1));

    // Cached!
    HttpURLConnection connection2 = openConnection(server.url("/").url());
    assertEquals("ABC", readAscii(connection2));
  }

  @Test public void foundCachedWithExpiresHeader() throws Exception {
    temporaryRedirectCachedWithCachingHeader(302, "Expires", formatDate(1, TimeUnit.HOURS));
  }

  @Test public void foundCachedWithCacheControlHeader() throws Exception {
    temporaryRedirectCachedWithCachingHeader(302, "Cache-Control", "max-age=60");
  }

  @Test public void temporaryRedirectCachedWithExpiresHeader() throws Exception {
    temporaryRedirectCachedWithCachingHeader(307, "Expires", formatDate(1, TimeUnit.HOURS));
  }

  @Test public void temporaryRedirectCachedWithCacheControlHeader() throws Exception {
    temporaryRedirectCachedWithCachingHeader(307, "Cache-Control", "max-age=60");
  }

  @Test public void foundNotCachedWithoutCacheHeader() throws Exception {
    temporaryRedirectNotCachedWithoutCachingHeader(302);
  }

  @Test public void temporaryRedirectNotCachedWithoutCacheHeader() throws Exception {
    temporaryRedirectNotCachedWithoutCachingHeader(307);
  }

  private void temporaryRedirectCachedWithCachingHeader(
      int responseCode, String headerName, String headerValue) throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(responseCode)
        .addHeader(headerName, headerValue)
        .addHeader("Location", "/a"));
    server.enqueue(new MockResponse()
        .addHeader(headerName, headerValue)
        .setBody("a"));
    server.enqueue(new MockResponse()
        .setBody("b"));
    server.enqueue(new MockResponse()
        .setBody("c"));

    URL url = server.url("/").url();
    assertEquals("a", readAscii(openConnection(url)));
    assertEquals("a", readAscii(openConnection(url)));
  }

  private void temporaryRedirectNotCachedWithoutCachingHeader(int responseCode) throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(responseCode)
        .addHeader("Location", "/a"));
    server.enqueue(new MockResponse()
        .setBody("a"));
    server.enqueue(new MockResponse()
        .setBody("b"));

    URL url = server.url("/").url();
    assertEquals("a", readAscii(openConnection(url)));
    assertEquals("b", readAscii(openConnection(url)));
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
    server.enqueue(new MockResponse()
        .setBody("Request #2"));

    BufferedReader reader = new BufferedReader(
        new InputStreamReader(openConnection(server.url("/").url()).getInputStream()));
    assertEquals("ABCDE", reader.readLine());
    try {
      reader.readLine();
      fail("This implementation silently ignored a truncated HTTP body.");
    } catch (IOException expected) {
    } finally {
      reader.close();
    }

    URLConnection connection = openConnection(server.url("/").url());
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
    MockResponse response = new MockResponse()
        .throttleBody(6, 1, TimeUnit.SECONDS);
    transferKind.setBody(response, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 1024);
    server.enqueue(response);
    server.enqueue(new MockResponse()
        .setBody("Request #2"));

    URLConnection connection = openConnection(server.url("/").url());
    InputStream in = connection.getInputStream();
    assertEquals("ABCDE", readAscii(connection, 5));
    in.close();
    try {
      in.read();
      fail("Expected an IOException because the stream is closed.");
    } catch (IOException expected) {
    }

    connection = openConnection(server.url("/").url());
    assertEquals("Request #2", readAscii(connection));
  }

  @Test public void defaultExpirationDateFullyCachedForLessThan24Hours() throws Exception {
    //      last modified: 105 seconds ago
    //             served:   5 seconds ago
    //   default lifetime: (105 - 5) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds from now
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
        .setBody("A"));

    URL url = server.url("/").url();
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
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Date: " + formatDate(-15, TimeUnit.SECONDS)));
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void defaultExpirationDateFullyCachedForMoreThan24Hours() throws Exception {
    //      last modified: 105 days ago
    //             served:   5 days ago
    //   default lifetime: (105 - 5) / 10 = 10 days
    //            expires:  10 days from served date = 5 days from now
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.DAYS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.DAYS))
        .setBody("A"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    URLConnection connection = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection));
    assertEquals("113 HttpURLConnection \"Heuristic expiration\"",
        connection.getHeaderField("Warning"));
  }

  @Test public void noDefaultExpirationForUrlsWithQueryString() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/?foo=bar").url();
    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("B", readAscii(openConnection(url)));
  }

  @Test public void expirationDateInThePastWithLastModifiedHeader() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void expirationDateInThePastWithNoLastModifiedHeader() throws Exception {
    assertNotCached(new MockResponse()
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
  }

  @Test public void expirationDateInTheFuture() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
  }

  @Test public void maxAgePreferredWithMaxAgeAndExpires() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInThePastWithDateAndLastModifiedHeaders() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Date: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Cache-Control: max-age=60"));
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void maxAgeInThePastWithDateHeaderButNoLastModifiedHeader() throws Exception {
    // Chrome interprets max-age relative to the local clock. Both our cache
    // and Firefox both use the earlier of the local and server's clock.
    assertNotCached(new MockResponse()
        .addHeader("Date: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInTheFutureWithDateHeader() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInTheFutureWithNoDateHeader() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeWithLastModifiedButNoServedDate() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgeInTheFutureWithDateAndLastModifiedHeaders() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void maxAgePreferredOverLowerSharedMaxAge() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: s-maxage=60")
        .addHeader("Cache-Control: max-age=180"));
  }

  @Test public void maxAgePreferredOverHigherMaxAge() throws Exception {
    assertNotCached(new MockResponse()
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
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
    server.enqueue(new MockResponse()
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("X-Response-ID: 1"));
    server.enqueue(new MockResponse()
        .addHeader("X-Response-ID: 2"));

    URL url = server.url("/").url();

    HttpURLConnection request1 = openConnection(url);
    request1.setRequestMethod(requestMethod);
    addRequestBodyIfNecessary(requestMethod, request1);
    request1.getInputStream().close();
    assertEquals("1", request1.getHeaderField("X-Response-ID"));

    URLConnection request2 = openConnection(url);
    request2.getInputStream().close();
    if (expectCached) {
      assertEquals("1", request2.getHeaderField("X-Response-ID"));
    } else {
      assertEquals("2", request2.getHeaderField("X-Response-ID"));
    }
  }

  private void addRequestBodyIfNecessary(String requestMethod, HttpURLConnection connection)
      throws IOException {
    if (requestMethod.equals("POST") || requestMethod.equals("PUT")) {
      connection.setDoOutput(true);
      OutputStream requestBody = connection.getOutputStream();
      requestBody.write('x');
      requestBody.close();
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
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setBody("C"));

    URL url = server.url("/").url();

    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection invalidateConnection = openConnection(url);
    invalidateConnection.setRequestMethod(requestMethod);
    assertEquals("B", readAscii(invalidateConnection));

    assertEquals("C", readAscii(openConnection(url)));
  }

  /**
   * Equivalent to {@code CacheTest.postInvalidatesCacheWithUncacheableResponse()} but demonstrating
   * that {@link ResponseCache} provides no mechanism for cache invalidation as the result of
   * locally-made requests. In reality invalidation could take place from other clients at any
   * time.
   */
  @Test public void postInvalidatesCacheWithUncacheableResponse() throws Exception {
    // 1. seed the cache
    // 2. invalidate it with uncacheable response
    // 3. the cache to return the original value
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B")
        .setResponseCode(500));

    URL url = server.url("/").url();

    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection invalidate = openConnection(url);
    invalidate.setRequestMethod("POST");
    addRequestBodyIfNecessary("POST", invalidate);
    assertEquals("B", readAscii(invalidate));

    assertEquals("A", readAscii(openConnection(url)));
  }

  @Test public void etag() throws Exception {
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("ETag: v1"));
    assertEquals("v1", conditionalRequest.getHeader("If-None-Match"));
  }

  /** If both If-Modified-Since and If-None-Match conditions apply, send only If-None-Match. */
  @Test public void etagAndExpirationDateInThePast() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    assertEquals("v1", conditionalRequest.getHeader("If-None-Match"));
    assertNull(conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void etagAndExpirationDateInTheFuture() throws Exception {
    assertFullyCached(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
  }

  @Test public void cacheControlNoCache() throws Exception {
    assertNotCached(new MockResponse()
        .addHeader("Cache-Control: no-cache"));
  }

  @Test public void cacheControlNoCacheAndExpirationDateInTheFuture() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Cache-Control: no-cache"));
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void pragmaNoCache() throws Exception {
    assertNotCached(new MockResponse()
        .addHeader("Pragma: no-cache"));
  }

  @Test public void pragmaNoCacheAndExpirationDateInTheFuture() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Pragma: no-cache"));
    assertEquals(lastModifiedDate, conditionalRequest.getHeader("If-Modified-Since"));
  }

  @Test public void cacheControlNoStore() throws Exception {
    assertNotCached(new MockResponse()
        .addHeader("Cache-Control: no-store"));
  }

  @Test public void cacheControlNoStoreAndExpirationDateInTheFuture() throws Exception {
    assertNotCached(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Cache-Control: no-store"));
  }

  @Test public void partialRangeResponsesDoNotCorruptCache() throws Exception {
    // 1. request a range
    // 2. request a full document, expecting a cache miss
    server.enqueue(new MockResponse()
        .setBody("AA")
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 1000-1001/2000"));
    server.enqueue(new MockResponse()
        .setBody("BB"));

    URL url = server.url("/").url();

    HttpURLConnection range = openConnection(url);
    range.addRequestProperty("Range", "bytes=1000-1001");
    assertEquals("AA", readAscii(range));

    assertEquals("BB", readAscii(openConnection(url)));
  }

  @Test public void serverReturnsDocumentOlderThanCache() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B")
        .addHeader("Last-Modified: " + formatDate(-4, TimeUnit.HOURS)));

    URL url = server.url("/").url();

    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("A", readAscii(openConnection(url)));
  }

  @Test public void clientSideNoStore() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("B"));

    HttpURLConnection connection1 = openConnection(server.url("/").url());
    connection1.setRequestProperty("Cache-Control", "no-store");
    assertEquals("A", readAscii(connection1));

    HttpURLConnection connection2 = openConnection(server.url("/").url());
    assertEquals("B", readAscii(connection2));
  }

  @Test public void nonIdentityEncodingAndConditionalCache() throws Exception {
    assertNonIdentityEncodingCached(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
  }

  @Test public void nonIdentityEncodingAndFullCache() throws Exception {
    assertNonIdentityEncodingCached(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
  }

  private void assertNonIdentityEncodingCached(MockResponse response) throws Exception {
    server.enqueue(response
        .setBody(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    // At least three request/response pairs are required because after the first request is cached
    // a different execution path might be taken. Thus modifications to the cache applied during
    // the second request might not be visible until another request is performed.
    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
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

    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
    assertEquals("DEFDEFDEF", readAscii(openConnection(server.url("/").url())));
  }

  /** https://github.com/square/okhttp/issues/947 */
  @Test public void gzipAndVaryOnAcceptEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .addHeader("Vary: Accept-Encoding")
        .addHeader("Cache-Control: max-age=60"));
    server.enqueue(new MockResponse()
        .setBody("FAIL"));

    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
    assertEquals("ABCABCABC", readAscii(openConnection(server.url("/").url())));
  }

  @Test public void expiresDateBeforeModifiedDate() throws Exception {
    assertConditionallyCached(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-2, TimeUnit.HOURS)));
  }

  @Test public void requestMaxAge() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));

    URLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "max-age=30");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestMinFresh() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));

    URLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "min-fresh=120");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestMaxStale() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));

    URLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "max-stale=180");
    assertEquals("A", readAscii(connection));
    assertEquals("110 HttpURLConnection \"Response is stale\"",
        connection.getHeaderField("Warning"));
  }

  @Test public void requestMaxStaleDirectiveWithNoValue() throws IOException {
    // Add a stale response to the cache.
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));

    // With max-stale, we'll return that stale response.
    URLConnection maxStaleConnection = openConnection(server.url("/").url());
    maxStaleConnection.setRequestProperty("Cache-Control", "max-stale");
    assertEquals("A", readAscii(maxStaleConnection));
    assertEquals("110 HttpURLConnection \"Response is stale\"",
        maxStaleConnection.getHeaderField("Warning"));
  }

  @Test public void requestMaxStaleNotHonoredWithMustRevalidate() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120, must-revalidate")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));

    URLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "max-stale=180");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestOnlyIfCachedWithNoResponseCached() throws IOException {
    // (no responses enqueued)

    HttpURLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
  }

  @Test public void requestOnlyIfCachedWithFullResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    URLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertEquals("A", readAscii(connection));
  }

  @Test public void requestOnlyIfCachedWithConditionalResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    HttpURLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
  }

  @Test public void requestOnlyIfCachedWithUnhelpfulResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    HttpURLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertGatewayTimeout(connection);
  }

  @Test public void requestCacheControlNoCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(openConnection(url)));
    URLConnection connection = openConnection(url);
    connection.setRequestProperty("Cache-Control", "no-cache");
    assertEquals("B", readAscii(connection));
  }

  @Test public void requestPragmaNoCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse().setBody("B"));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(openConnection(url)));
    URLConnection connection = openConnection(url);
    connection.setRequestProperty("Pragma", "no-cache");
    assertEquals("B", readAscii(connection));
  }

  @Test public void clientSuppliedIfModifiedSinceWithCachedResult() throws Exception {
    MockResponse response = new MockResponse()
        .addHeader("ETag: v3")
        .addHeader("Cache-Control: max-age=0");
    String ifModifiedSinceDate = formatDate(-24, TimeUnit.HOURS);
    RecordedRequest request =
        assertClientSuppliedCondition(response, "If-Modified-Since", ifModifiedSinceDate);
    assertEquals(ifModifiedSinceDate, request.getHeader("If-Modified-Since"));
    assertNull(request.getHeader("If-None-Match"));
  }

  @Test public void clientSuppliedIfNoneMatchSinceWithCachedResult() throws Exception {
    String lastModifiedDate = formatDate(-3, TimeUnit.MINUTES);
    MockResponse response = new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: max-age=0");
    RecordedRequest request = assertClientSuppliedCondition(response, "If-None-Match", "v1");
    assertEquals("v1", request.getHeader("If-None-Match"));
    assertNull(request.getHeader("If-Modified-Since"));
  }

  private RecordedRequest assertClientSuppliedCondition(MockResponse seed, String conditionName,
      String conditionValue) throws Exception {
    server.enqueue(seed.setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(openConnection(url)));

    HttpURLConnection connection = openConnection(url);
    connection.addRequestProperty(conditionName, conditionValue);
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection.getResponseCode());
    assertEquals("", readAscii(connection));

    server.takeRequest(); // seed
    return server.takeRequest();
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

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    assertEquals("A", readAscii(openConnection(server.url("/").url())));

    // The first request has no conditions.
    RecordedRequest request1 = server.takeRequest();
    assertNull(request1.getHeader("If-Modified-Since"));

    // The 2nd request uses the server's date format.
    RecordedRequest request2 = server.takeRequest();
    assertEquals(lastModifiedString, request2.getHeader("If-Modified-Since"));
  }

  @Test public void clientSuppliedConditionWithoutCachedResult() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    HttpURLConnection connection = openConnection(server.url("/").url());
    String clientIfModifiedSince = formatDate(-24, TimeUnit.HOURS);
    connection.addRequestProperty("If-Modified-Since", clientIfModifiedSince);
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, connection.getResponseCode());
    assertEquals("", readAscii(connection));
  }

  @Test public void authorizationRequestFullyCached() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    URLConnection connection = openConnection(url);
    connection.addRequestProperty("Authorization", "password");
    assertEquals("A", readAscii(connection));
    assertEquals("A", readAscii(openConnection(url)));
  }

  @Test public void contentLocationDoesNotPopulateCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Content-Location: /bar")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/foo").url())));
    assertEquals("B", readAscii(openConnection(server.url("/bar").url())));
  }

  @Test public void connectionIsReturnedToPoolAfterConditionalSuccess() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/a").url())));
    assertEquals("A", readAscii(openConnection(server.url("/a").url())));
    assertEquals("B", readAscii(openConnection(server.url("/b").url())));

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void varyMatchesChangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection frenchConnection = openConnection(url);
    frenchConnection.setRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(frenchConnection));

    HttpURLConnection englishConnection = openConnection(url);
    englishConnection.setRequestProperty("Accept-Language", "en-US");
    assertEquals("B", readAscii(englishConnection));
  }

  @Test public void varyMatchesUnchangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection frenchConnection1 = openConnection(url);
    frenchConnection1.setRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(frenchConnection1));

    HttpURLConnection frenchConnection2 = openConnection(url);
    frenchConnection2.setRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(frenchConnection2));
  }

  @Test public void varyMatchesAbsentRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    assertEquals("A", readAscii(openConnection(server.url("/").url())));
  }

  @Test public void varyMatchesAddedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    HttpURLConnection connection2 = openConnection(server.url("/").url());
    connection2.setRequestProperty("Foo", "bar");
    assertEquals("B", readAscii(connection2));
  }

  @Test public void varyMatchesRemovedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpURLConnection connection1 = openConnection(server.url("/").url());
    connection1.setRequestProperty("Foo", "bar");
    assertEquals("A", readAscii(connection1));
    assertEquals("B", readAscii(openConnection(server.url("/").url())));
  }

  @Test public void varyFieldsAreCaseInsensitive() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: ACCEPT-LANGUAGE")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection frenchConnection1 = openConnection(url);
    frenchConnection1.setRequestProperty("Accept-Language", "fr-CA");
    assertEquals("A", readAscii(frenchConnection1));
    HttpURLConnection frenchConnection2 = openConnection(url);
    frenchConnection2.setRequestProperty("accept-language", "fr-CA");
    assertEquals("A", readAscii(frenchConnection2));
  }

  @Test public void varyMultipleFieldsWithMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection frenchConnection1 = openConnection(url);
    frenchConnection1.setRequestProperty("Accept-Language", "fr-CA");
    frenchConnection1.setRequestProperty("Accept-Charset", "UTF-8");
    frenchConnection1.setRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(frenchConnection1));
    HttpURLConnection frenchConnection2 = openConnection(url);
    frenchConnection2.setRequestProperty("Accept-Language", "fr-CA");
    frenchConnection2.setRequestProperty("Accept-Charset", "UTF-8");
    frenchConnection2.setRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(frenchConnection2));
  }

  @Test public void varyMultipleFieldsWithNoMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection frenchConnection = openConnection(url);
    frenchConnection.setRequestProperty("Accept-Language", "fr-CA");
    frenchConnection.setRequestProperty("Accept-Charset", "UTF-8");
    frenchConnection.setRequestProperty("Accept-Encoding", "identity");
    assertEquals("A", readAscii(frenchConnection));
    HttpURLConnection englishConnection = openConnection(url);
    englishConnection.setRequestProperty("Accept-Language", "en-CA");
    englishConnection.setRequestProperty("Accept-Charset", "UTF-8");
    englishConnection.setRequestProperty("Accept-Encoding", "identity");
    assertEquals("B", readAscii(englishConnection));
  }

  @Test public void varyMultipleFieldValuesWithMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection multiConnection1 = openConnection(url);
    multiConnection1.setRequestProperty("Accept-Language", "fr-CA, fr-FR");
    multiConnection1.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(multiConnection1));

    HttpURLConnection multiConnection2 = openConnection(url);
    multiConnection2.setRequestProperty("Accept-Language", "fr-CA, fr-FR");
    multiConnection2.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(multiConnection2));
  }

  @Test public void varyMultipleFieldValuesWithNoMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
    HttpURLConnection multiConnection = openConnection(url);
    multiConnection.setRequestProperty("Accept-Language", "fr-CA, fr-FR");
    multiConnection.addRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(multiConnection));

    HttpURLConnection notFrenchConnection = openConnection(url);
    notFrenchConnection.setRequestProperty("Accept-Language", "fr-CA");
    notFrenchConnection.addRequestProperty("Accept-Language", "en-US");
    assertEquals("B", readAscii(notFrenchConnection));
  }

  @Test public void varyAsterisk() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    assertEquals("B", readAscii(openConnection(server.url("/").url())));
  }

  @Test public void varyAndHttps() throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(hostnameVerifier);

    URL url = server.url("/").url();
    HttpURLConnection connection1 = openConnection(url);
    connection1.setRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection1));

    HttpURLConnection connection2 = openConnection(url);
    connection2.setRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection2));
  }

  @Test public void cachePlusCookies() throws Exception {
    client.setCookieJar(new JavaNetCookieJar(cookieManager));
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie: a=FIRST; domain=" + server.getCookieDomain() + ";")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie: a=SECOND; domain=" + server.getCookieDomain() + ";")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URL url = server.url("/").url();
    assertEquals("A", readAscii(openConnection(url)));
    assertCookies(url, "a=FIRST");
    assertEquals("A", readAscii(openConnection(url)));
    assertCookies(url, "a=SECOND");
  }

  @Test public void getHeadersReturnsNetworkEndToEndHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Allow: GET, HEAD")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Allow: GET, HEAD, PUT")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("GET, HEAD", connection1.getHeaderField("Allow"));

    URLConnection connection2 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals("GET, HEAD, PUT", connection2.getHeaderField("Allow"));
  }

  @Test public void getHeadersReturnsCachedHopByHopHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Transfer-Encoding: identity")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Transfer-Encoding: none")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("identity", connection1.getHeaderField("Transfer-Encoding"));

    URLConnection connection2 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals("identity", connection2.getHeaderField("Transfer-Encoding"));
  }

  @Test public void getHeadersDeletesCached100LevelWarnings() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Warning: 199 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("199 test danger", connection1.getHeaderField("Warning"));

    URLConnection connection2 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals(null, connection2.getHeaderField("Warning"));
  }

  @Test public void getHeadersRetainsCached200LevelWarnings() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Warning: 299 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    URLConnection connection1 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection1));
    assertEquals("299 test danger", connection1.getHeaderField("Warning"));

    URLConnection connection2 = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection2));
    assertEquals("299 test danger", connection2.getHeaderField("Warning"));
  }

  public void assertCookies(URL url, String... expectedCookies) throws Exception {
    List<String> actualCookies = new ArrayList<>();
    for (HttpCookie cookie : cookieManager.getCookieStore().get(url.toURI())) {
      actualCookies.add(cookie.toString());
    }
    assertEquals(Arrays.asList(expectedCookies), actualCookies);
  }

  @Test public void doNotCachePartialResponse() throws Exception {
    assertNotCached(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 100-100/200")
        .addHeader("Cache-Control: max-age=60"));
  }

  /**
   * Equivalent to {@code CacheTest.conditionalHitUpdatesCache()}, except a Java standard cache has
   * no means to update the headers for an existing entry so the behavior is different.
   */
  @Test public void conditionalHitDoesNotUpdateCache() throws Exception {
    // A response that is cacheable, but with a short life.
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    // A response that refers to the previous response, but is cacheable with a long life.
    // Contains a header we can recognize as having come from the server.
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Allow: GET, HEAD")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    // A response that is cacheable with a long life.
    server.enqueue(new MockResponse()
        .setBody("B")
        .addHeader("Cache-Control: max-age=30"));
    // A response that should never be requested.
    server.enqueue(new MockResponse()
        .setBody("C"));

    // cache miss; seed the cache with an entry that will require a network hit to be sure it is
    // still valid
    HttpURLConnection connection1 = openConnection(server.url("/a").url());
    assertEquals("A", readAscii(connection1));
    assertEquals(null, connection1.getHeaderField("Allow"));

    // conditional cache hit; The cached data should be returned, but the cache is not updated.
    HttpURLConnection connection2 = openConnection(server.url("/a").url());
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A", readAscii(connection2));
    assertEquals("GET, HEAD", connection2.getHeaderField("Allow"));

    // conditional cache hit; The server responds with new data. The cache is updated.
    HttpURLConnection connection3 = openConnection(server.url("/a").url());
    assertEquals("B", readAscii(connection3));

    // full cache hit; The data from connection3 has now replaced that from connection 1.
    HttpURLConnection connection4 = openConnection(server.url("/a").url());
    assertEquals("B", readAscii(connection4));

    assertEquals(3, server.getRequestCount());
  }

  @Test public void responseSourceHeaderCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    URLConnection connection = openConnection(server.url("/").url());
    connection.addRequestProperty("Cache-Control", "only-if-cached");
    assertEquals("A", readAscii(connection));
  }

  @Test public void responseSourceHeaderConditionalCacheFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-31, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    HttpURLConnection connection = openConnection(server.url("/").url());
    assertEquals("B", readAscii(connection));
  }

  @Test public void responseSourceHeaderConditionalCacheNotFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setResponseCode(304));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    HttpURLConnection connection = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection));
  }

  @Test public void responseSourceHeaderFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A"));

    URLConnection connection = openConnection(server.url("/").url());
    assertEquals("A", readAscii(connection));
  }

  @Test public void emptyResponseHeaderNameFromCacheIsLenient() throws Exception {
    Headers.Builder headers = new Headers.Builder()
        .add("Cache-Control: max-age=120");
    Internal.instance.addLenient(headers, ": A");
    server.enqueue(new MockResponse()
        .setHeaders(headers.build())
        .setBody("body"));

    HttpURLConnection connection = openConnection(server.url("/").url());
    assertEquals("A", connection.getHeaderField(""));
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

  private void assertNotCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URL url = server.url("/").url();
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

    URL valid = server.url("/valid").url();
    HttpURLConnection connection1 = openConnection(valid);
    assertEquals("A", readAscii(connection1));
    assertEquals(HttpURLConnection.HTTP_OK, connection1.getResponseCode());
    assertEquals("A-OK", connection1.getResponseMessage());
    HttpURLConnection connection2 = openConnection(valid);
    assertEquals("A", readAscii(connection2));
    assertEquals(HttpURLConnection.HTTP_OK, connection2.getResponseCode());
    assertEquals("A-OK", connection2.getResponseMessage());

    URL invalid = server.url("/invalid").url();
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

    URL url = server.url("/").url();
    assertEquals("A", readAscii(openConnection(url)));
    assertEquals("A", readAscii(openConnection(url)));
  }

  /**
   * Shortens the body of {@code response} but not the corresponding headers. Only useful to test
   * how clients respond to the premature conclusion of the HTTP body.
   */
  private MockResponse truncateViolently(MockResponse response, int numBytesToKeep) {
    response.setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
    Headers headers = response.getHeaders();
    Buffer truncatedBody = new Buffer();
    truncatedBody.write(response.getBody(), numBytesToKeep);
    response.setBody(truncatedBody);
    response.setHeaders(headers);
    return response;
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
        response.setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
        response.removeHeader("Content-Length");
      }
    };

    abstract void setBody(MockResponse response, Buffer content, int chunkSize) throws IOException;

    void setBody(MockResponse response, String content, int chunkSize) throws IOException {
      setBody(response, new Buffer().writeUtf8(content), chunkSize);
    }
  }

  /** Returns a gzipped copy of {@code bytes}. */
  public Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  /**
   * Reads {@code count} characters from the stream. If the stream is exhausted before {@code count}
   * characters can be read, the remaining characters are returned and the stream is closed.
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
  }

  private static <T> List<T> toListOrNull(T[] arrayOrNull) {
    return arrayOrNull != null ? Arrays.asList(arrayOrNull) : null;
  }

  // Android-added tests.

  /**
   * Test that we can interrogate the response when the cache is being populated.
   * http://code.google.com/p/android/issues/detail?id=7787
   */
  @Test public void responseCacheCallbackApis() throws Exception {
    final String body = "ABCDE";
    final AtomicInteger cacheCount = new AtomicInteger();

    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 200 Fantastic")
        .addHeader("Content-Type: text/plain")
        .addHeader("fgh: ijk")
        .setBody(body));

    Internal.instance.setCache(client, new CacheAdapter(new AbstractResponseCache() {
      @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
        HttpURLConnection httpURLConnection = (HttpURLConnection) connection;
        assertEquals(server.url("/").url(), uri.toURL());
        assertEquals(200, httpURLConnection.getResponseCode());
        InputStream is = httpURLConnection.getInputStream();
        try {
          is.read();
          fail();
        } catch (UnsupportedOperationException expected) {
        }
        assertEquals("5", connection.getHeaderField("Content-Length"));
        assertEquals("text/plain", connection.getHeaderField("Content-Type"));
        assertEquals("ijk", connection.getHeaderField("fgh"));
        cacheCount.incrementAndGet();
        return null;
      }
    }));

    URL url = server.url("/").url();
    HttpURLConnection connection = openConnection(url);
    assertEquals(body, readAscii(connection));
    assertEquals(1, cacheCount.get());
  }

  /** Don't explode if the cache returns a null body. http://b/3373699 */
  @Test public void responseCacheReturnsNullOutputStream() throws Exception {
    final AtomicBoolean aborted = new AtomicBoolean();
    Internal.instance.setCache(client, new CacheAdapter(new AbstractResponseCache() {
      @Override public CacheRequest put(URI uri, URLConnection connection) {
        return new CacheRequest() {
          @Override public void abort() {
            aborted.set(true);
          }

          @Override public OutputStream getBody() throws IOException {
            return null;
          }
        };
      }
    }));

    server.enqueue(new MockResponse().setBody("abcdef"));

    HttpURLConnection connection = openConnection(server.url("/").url());
    assertEquals("abc", readAscii(connection, 3));
    connection.getInputStream().close();
    assertFalse(aborted.get()); // The best behavior is ambiguous, but RI 6 doesn't abort here
  }

  /**
   * Fail if a badly-behaved cache returns a null status line header.
   * https://code.google.com/p/android/issues/detail?id=160522
   */
  @Test public void responseCacheReturnsNullStatusLine() throws Exception {
    String cachedContentString = "Hello";
    final byte[] cachedContent = cachedContentString.getBytes(StandardCharsets.US_ASCII);

    Internal.instance.setCache(client, new CacheAdapter(new AbstractResponseCache() {
      @Override
      public CacheResponse get(URI uri, String requestMethod,
          Map<String, List<String>> requestHeaders)
          throws IOException {
        return new CacheResponse() {
          @Override public Map<String, List<String>> getHeaders() throws IOException {
            String contentType = "text/plain";
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Content-Length", Arrays.asList(Integer.toString(cachedContent.length)));
            headers.put("Content-Type", Arrays.asList(contentType));
            headers.put("Expires", Arrays.asList(formatDate(-1, TimeUnit.HOURS)));
            headers.put("Cache-Control", Arrays.asList("max-age=60"));
            // Crucially, the header with a null key is missing, which renders the cache response
            // unusable because OkHttp only caches responses with cacheable response codes.
            return headers;
          }

          @Override public InputStream getBody() throws IOException {
            return new ByteArrayInputStream(cachedContent);
          }
        };
      }
    }));
    HttpURLConnection connection = openConnection(server.url("/").url());
    // If there was no status line from the cache an exception will be thrown. No network request
    // should be made.
    try {
      connection.getResponseCode();
      fail();
    } catch (ProtocolException expected) {
    }
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

  @Test public void cacheReturnsInsecureResponseForSecureRequest() throws IOException {
    server.useHttps(sslContext.getSocketFactory(), false);
    server.enqueue(new MockResponse().setBody("ABC"));
    server.enqueue(new MockResponse().setBody("DEF"));

    AndroidInternal.setResponseCache(new OkUrlFactory(client), new InsecureResponseCache(cache));

    HttpsURLConnection connection1 = (HttpsURLConnection) openConnection(server.url("/").url());
    connection1.setSSLSocketFactory(sslContext.getSocketFactory());
    connection1.setHostnameVerifier(hostnameVerifier);
    assertEquals("ABC", readAscii(connection1));

    // Not cached!
    HttpsURLConnection connection2 = (HttpsURLConnection) openConnection(server.url("/").url());
    connection2.setSSLSocketFactory(sslContext.getSocketFactory());
    connection2.setHostnameVerifier(hostnameVerifier);
    assertEquals("DEF", readAscii(connection2));
  }

  @Test public void responseCacheRequestHeaders() throws IOException, URISyntaxException {
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    final AtomicReference<Map<String, List<String>>> requestHeadersRef = new AtomicReference<>();
    Internal.instance.setCache(client, new CacheAdapter(new AbstractResponseCache() {
      @Override public CacheResponse get(URI uri, String requestMethod,
          Map<String, List<String>> requestHeaders) throws IOException {
        requestHeadersRef.set(requestHeaders);
        return null;
      }
    }));

    URL url = server.url("/").url();
    URLConnection urlConnection = openConnection(url);
    urlConnection.addRequestProperty("A", "android");
    readAscii(urlConnection);
    assertEquals(Arrays.asList("android"), requestHeadersRef.get().get("A"));
  }

  @Test public void responseCachingWithoutBody() throws IOException {
    MockResponse response =
        new MockResponse().addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
            .setStatus("HTTP/1.1 200 Fantastic");
    server.enqueue(response);

    HttpURLConnection urlConnection = openConnection(server.url("/").url());
    assertEquals(200, urlConnection.getResponseCode());
    assertEquals("Fantastic", urlConnection.getResponseMessage());
    assertTrue(urlConnection.getDoInput());
    InputStream is = urlConnection.getInputStream();
    assertEquals(-1, is.read());
    is.close();

    urlConnection = openConnection(server.url("/").url()); // cached!
    assertTrue(urlConnection.getDoInput());
    InputStream cachedIs = urlConnection.getInputStream();
    assertEquals(-1, cachedIs.read());
    cachedIs.close();
    assertEquals(200, urlConnection.getResponseCode());
    assertEquals("Fantastic", urlConnection.getResponseMessage());
  }

  @Test public void useCachesFalseDoesNotWriteToCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    URLConnection connection = openConnection(server.url("/").url());
    connection.setUseCaches(false);
    assertEquals("A", readAscii(connection));
    assertEquals("B", readAscii(openConnection(server.url("/").url())));
  }

  @Test public void useCachesFalseDoesNotReadFromCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", readAscii(openConnection(server.url("/").url())));
    URLConnection connection = openConnection(server.url("/").url());
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

  // Other stacks (e.g. older versions of OkHttp bundled inside Android apps) can interact with the
  // default ResponseCache. We try to keep this case working as much as possible because apps break
  // if we don't.
  @Test public void otherStacks_cacheHitWithoutVary() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("FAIL"));

    // Set the cache as the shared cache.
    ResponseCache.setDefault(cache);

    // Use the platform's HTTP stack.
    URLConnection connection = server.url("/").url().openConnection();
    assertFalse(connection instanceof HttpURLConnectionImpl);
    assertEquals("A", readAscii(connection));

    URLConnection connection2 = server.url("/").url().openConnection();
    assertFalse(connection2 instanceof HttpURLConnectionImpl);
    assertEquals("A", readAscii(connection2));
  }

  // Other stacks (e.g. older versions of OkHttp bundled inside Android apps) can interact with the
  // default ResponseCache. We can't keep the Vary case working because we can't get to the Vary
  // request headers after connect(). Accept-Encoding has special behavior so we test it explicitly.
  @Test public void otherStacks_cacheMissWithVaryAcceptEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    // Set the cache as the shared cache.
    ResponseCache.setDefault(cache);

    // Use the platform's HTTP stack.
    URLConnection connection = server.url("/").url().openConnection();
    assertFalse(connection instanceof HttpURLConnectionImpl);
    assertEquals("A", readAscii(connection));

    URLConnection connection2 = server.url("/").url().openConnection();
    assertFalse(connection2 instanceof HttpURLConnectionImpl);
    assertEquals("B", readAscii(connection2));
  }

  // Other stacks (e.g. older versions of OkHttp bundled inside Android apps) can interact with the
  // default ResponseCache. We can't keep the Vary case working because we can't get to the Vary
  // request headers after connect().
  @Test public void otherStacks_cacheMissWithVary() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    // Set the cache as the shared cache.
    ResponseCache.setDefault(cache);

    // Use the platform's HTTP stack.
    URLConnection connection = server.url("/").url().openConnection();
    assertFalse(connection instanceof HttpURLConnectionImpl);
    connection.setRequestProperty("Accept-Language", "en-US");
    assertEquals("A", readAscii(connection));

    URLConnection connection2 = server.url("/").url().openConnection();
    assertFalse(connection2 instanceof HttpURLConnectionImpl);
    assertEquals("B", readAscii(connection2));
  }

  // Other stacks (e.g. older versions of OkHttp bundled inside Android apps) can interact with the
  // default ResponseCache. We can't keep the Vary case working, because we can't get to the Vary
  // request headers after connect().
  @Test public void otherStacks_cacheMissWithVaryAsterisk() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    // Set the cache as the shared cache.
    ResponseCache.setDefault(cache);

    // Use the platform's HTTP stack.
    URLConnection connection = server.url("/").url().openConnection();
    assertFalse(connection instanceof HttpURLConnectionImpl);
    assertEquals("A", readAscii(connection));

    URLConnection connection2 = server.url("/").url().openConnection();
    assertFalse(connection2 instanceof HttpURLConnectionImpl);
    assertEquals("B", readAscii(connection2));
  }
}
