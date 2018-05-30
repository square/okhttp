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

import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.security.Principal;
import java.security.cert.Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import okhttp3.internal.Internal;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CacheTest {
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
    @Override public boolean verify(String s, SSLSession sslSession) {
      return true;
    }
  };

  @Rule public MockWebServer server = new MockWebServer();
  @Rule public MockWebServer server2 = new MockWebServer();
  @Rule public InMemoryFileSystem fileSystem = new InMemoryFileSystem();

  private final SslClient sslClient = SslClient.localhost();
  private OkHttpClient client;
  private Cache cache;
  private final CookieManager cookieManager = new CookieManager();

  @Before public void setUp() throws Exception {
    server.setProtocolNegotiationEnabled(false);
    cache = new Cache(new File("/cache/"), Integer.MAX_VALUE, fileSystem);
    client = defaultClient().newBuilder()
        .cache(cache)
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build();
  }

  @After public void tearDown() throws Exception {
    ResponseCache.setDefault(null);
    cache.delete();
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
    } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT
        || responseCode == HttpURLConnection.HTTP_RESET) {
      mockResponse.setBody(""); // We forbid bodies for 204 and 205.
    }
    server.enqueue(mockResponse);

    if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
      // 408's are a bit of an outlier because we may repeat the request if we encounter this
      // response code. In this scenario, there are 2 responses: the initial 408 and then the 200
      // because of the retry. We just want to ensure the initial 408 isn't cached.
      expectedResponseCode = 200;
      server.enqueue(new MockResponse()
          .setHeader("Cache-Control", "no-store")
          .setBody("FGHIJ"));
    }

    server.start();

    Request request = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response = client.newCall(request).execute();
    assertEquals(expectedResponseCode, response.code());

    // Exhaust the content stream.
    response.body().string();

    Response cached = cache.get(request);
    if (shouldPut) {
      assertNotNull(Integer.toString(responseCode), cached);
      cached.body().close();
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
   * Skipping bytes in the input stream caused ResponseCache corruption.
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
    Request request = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request).execute();

    BufferedSource in1 = response1.body().source();
    assertEquals("I love ", in1.readUtf8("I love ".length()));
    in1.skip("puppies but hate ".length());
    assertEquals("spiders", in1.readUtf8("spiders".length()));
    assertTrue(in1.exhausted());
    in1.close();
    assertEquals(1, cache.writeSuccessCount());
    assertEquals(0, cache.writeAbortCount());

    Response response2 = client.newCall(request).execute();
    BufferedSource in2 = response2.body().source();
    assertEquals("I love puppies but hate spiders",
        in2.readUtf8("I love puppies but hate spiders".length()));
    assertEquals(200, response2.code());
    assertEquals("Fantastic", response2.message());

    assertTrue(in2.exhausted());
    in2.close();
    assertEquals(1, cache.writeSuccessCount());
    assertEquals(0, cache.writeAbortCount());
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.hitCount());
  }

  @Test public void secureResponseCaching() throws IOException {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request).execute();
    BufferedSource in = response1.body().source();
    assertEquals("ABC", in.readUtf8());

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    CipherSuite cipherSuite = response1.handshake().cipherSuite();
    List<Certificate> localCerts = response1.handshake().localCertificates();
    List<Certificate> serverCerts = response1.handshake().peerCertificates();
    Principal peerPrincipal = response1.handshake().peerPrincipal();
    Principal localPrincipal = response1.handshake().localPrincipal();

    Response response2 = client.newCall(request).execute(); // Cached!
    assertEquals("ABC", response2.body().string());

    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(1, cache.hitCount());

    assertEquals(cipherSuite, response2.handshake().cipherSuite());
    assertEquals(localCerts, response2.handshake().localCertificates());
    assertEquals(serverCerts, response2.handshake().peerCertificates());
    assertEquals(peerPrincipal, response2.handshake().peerPrincipal());
    assertEquals(localPrincipal, response2.handshake().localPrincipal());
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

    Request request = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request).execute();
    assertEquals("ABC", response1.body().string());

    Response response2 = client.newCall(request).execute(); // Cached!
    assertEquals("ABC", response2.body().string());

    assertEquals(4, cache.requestCount()); // 2 requests + 2 redirects
    assertEquals(2, cache.networkCount());
    assertEquals(2, cache.hitCount());
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

    Request request1 = new Request.Builder().url(server.url("/foo")).build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("ABC", response1.body().string());
    RecordedRequest recordedRequest1 = server.takeRequest();
    assertEquals("GET /foo HTTP/1.1", recordedRequest1.getRequestLine());
    assertEquals(0, recordedRequest1.getSequenceNumber());

    Request request2 = new Request.Builder().url(server.url("/bar")).build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("ABC", response2.body().string());
    RecordedRequest recordedRequest2 = server.takeRequest();
    assertEquals("GET /bar HTTP/1.1", recordedRequest2.getRequestLine());
    assertEquals(1, recordedRequest2.getSequenceNumber());

    // an unrelated request should reuse the pooled connection
    Request request3 = new Request.Builder().url(server.url("/baz")).build();
    Response response3 = client.newCall(request3).execute();
    assertEquals("DEF", response3.body().string());
    RecordedRequest recordedRequest3 = server.takeRequest();
    assertEquals("GET /baz HTTP/1.1", recordedRequest3.getRequestLine());
    assertEquals(2, recordedRequest3.getSequenceNumber());
  }

  @Test public void secureResponseCachingAndRedirects() throws IOException {
    server.useHttps(sslClient.socketFactory, false);
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

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    Response response1 = get(server.url("/"));
    assertEquals("ABC", response1.body().string());
    assertNotNull(response1.handshake().cipherSuite());

    // Cached!
    Response response2 = get(server.url("/"));
    assertEquals("ABC", response2.body().string());
    assertNotNull(response2.handshake().cipherSuite());

    assertEquals(4, cache.requestCount()); // 2 direct + 2 redirect = 4
    assertEquals(2, cache.hitCount());
    assertEquals(response1.handshake().cipherSuite(), response2.handshake().cipherSuite());
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
        .addHeader("Location: " + server2.url("/")));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    Response response1 = get(server.url("/"));
    assertEquals("ABC", response1.body().string());

    // Cached!
    Response response2 = get(server.url("/"));
    assertEquals("ABC", response2.body().string());

    assertEquals(4, cache.requestCount()); // 2 direct + 2 redirect = 4
    assertEquals(2, cache.hitCount());
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

    HttpUrl url = server.url("/");
    assertEquals("a", get(url).body().string());
    assertEquals("a", get(url).body().string());
  }

  private void temporaryRedirectNotCachedWithoutCachingHeader(int responseCode) throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(responseCode)
        .addHeader("Location", "/a"));
    server.enqueue(new MockResponse()
        .setBody("a"));
    server.enqueue(new MockResponse()
        .setBody("b"));

    HttpUrl url = server.url("/");
    assertEquals("a", get(url).body().string());
    assertEquals("b", get(url).body().string());
  }

  /** https://github.com/square/okhttp/issues/2198 */
  @Test public void cachedRedirect() throws IOException {
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Location: /bar"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));
    server.enqueue(new MockResponse()
        .setBody("ABC"));

    Request request1 = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("ABC", response1.body().string());

    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("ABC", response2.body().string());
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
    MockResponse mockResponse = new MockResponse();
    transferKind.setBody(mockResponse, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 16);
    server.enqueue(truncateViolently(mockResponse, 16));
    server.enqueue(new MockResponse()
        .setBody("Request #2"));

    BufferedSource bodySource = get(server.url("/")).body().source();
    assertEquals("ABCDE", bodySource.readUtf8Line());
    try {
      bodySource.readUtf8Line();
      fail("This implementation silently ignored a truncated HTTP body.");
    } catch (IOException expected) {
    } finally {
      bodySource.close();
    }

    assertEquals(1, cache.writeAbortCount());
    assertEquals(0, cache.writeSuccessCount());
    Response response = get(server.url("/"));
    assertEquals("Request #2", response.body().string());
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
    MockResponse mockResponse = new MockResponse()
        .throttleBody(6, 1, TimeUnit.SECONDS);
    transferKind.setBody(mockResponse, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 1024);
    server.enqueue(mockResponse);
    server.enqueue(new MockResponse()
        .setBody("Request #2"));

    Response response1 = get(server.url("/"));
    BufferedSource in = response1.body().source();
    assertEquals("ABCDE", in.readUtf8(5));
    in.close();
    try {
      in.readByte();
      fail("Expected an IllegalStateException because the source is closed.");
    } catch (IllegalStateException expected) {
    }

    assertEquals(1, cache.writeAbortCount());
    assertEquals(0, cache.writeSuccessCount());
    Response response2 = get(server.url("/"));
    assertEquals("Request #2", response2.body().string());
    assertEquals(1, cache.writeAbortCount());
    assertEquals(1, cache.writeSuccessCount());
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

    HttpUrl url = server.url("/");
    Response response1 = get(url);
    assertEquals("A", response1.body().string());

    Response response2 = get(url);
    assertEquals("A", response2.body().string());
    assertNull(response2.header("Warning"));
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

    assertEquals("A", get(server.url("/")).body().string());
    Response response = get(server.url("/"));
    assertEquals("A", response.body().string());
    assertEquals("113 HttpURLConnection \"Heuristic expiration\"", response.header("Warning"));
  }

  @Test public void noDefaultExpirationForUrlsWithQueryString() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/").newBuilder().addQueryParameter("foo", "bar").build();
    assertEquals("A", get(url).body().string());
    assertEquals("B", get(url).body().string());
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
    // 1. Seed the cache (potentially).
    // 2. Expect a cache hit or miss.
    server.enqueue(new MockResponse()
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("X-Response-ID: 1"));
    server.enqueue(new MockResponse()
        .addHeader("X-Response-ID: 2"));

    HttpUrl url = server.url("/");

    Request request = new Request.Builder()
        .url(url)
        .method(requestMethod, requestBodyOrNull(requestMethod))
        .build();
    Response response1 = client.newCall(request).execute();
    response1.body().close();
    assertEquals("1", response1.header("X-Response-ID"));

    Response response2 = get(url);
    response2.body().close();
    if (expectCached) {
      assertEquals("1", response2.header("X-Response-ID"));
    } else {
      assertEquals("2", response2.header("X-Response-ID"));
    }
  }

  private RequestBody requestBodyOrNull(String requestMethod) {
    return (requestMethod.equals("POST") || requestMethod.equals("PUT"))
        ? RequestBody.create(MediaType.parse("text/plain"), "foo")
        : null;
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
    // 1. Seed the cache.
    // 2. Invalidate it.
    // 3. Expect a cache miss.
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setBody("C"));

    HttpUrl url = server.url("/");

    assertEquals("A", get(url).body().string());

    Request request = new Request.Builder()
        .url(url)
        .method(requestMethod, requestBodyOrNull(requestMethod))
        .build();
    Response invalidate = client.newCall(request).execute();
    assertEquals("B", invalidate.body().string());

    assertEquals("C", get(url).body().string());
  }

  @Test public void postInvalidatesCacheWithUncacheableResponse() throws Exception {
    // 1. Seed the cache.
    // 2. Invalidate it with an uncacheable response.
    // 3. Expect a cache miss.
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B")
        .setResponseCode(500));
    server.enqueue(new MockResponse()
        .setBody("C"));

    HttpUrl url = server.url("/");

    assertEquals("A", get(url).body().string());

    Request request = new Request.Builder()
        .url(url)
        .method("POST", requestBodyOrNull("POST"))
        .build();
    Response invalidate = client.newCall(request).execute();
    assertEquals("B", invalidate.body().string());

    assertEquals("C", get(url).body().string());
  }

  @Test public void putInvalidatesWithNoContentResponse() throws Exception {
    // 1. Seed the cache.
    // 2. Invalidate it.
    // 3. Expect a cache miss.
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NO_CONTENT));
    server.enqueue(new MockResponse()
        .setBody("C"));

    HttpUrl url = server.url("/");

    assertEquals("A", get(url).body().string());

    Request request = new Request.Builder()
        .url(url)
        .put(RequestBody.create(MediaType.parse("text/plain"), "foo"))
        .build();
    Response invalidate = client.newCall(request).execute();
    assertEquals("", invalidate.body().string());

    assertEquals("C", get(url).body().string());
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
    // 1. Request a range.
    // 2. Request a full document, expecting a cache miss.
    server.enqueue(new MockResponse()
        .setBody("AA")
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 1000-1001/2000"));
    server.enqueue(new MockResponse()
        .setBody("BB"));

    HttpUrl url = server.url("/");

    Request request = new Request.Builder()
        .url(url)
        .header("Range", "bytes=1000-1001")
        .build();
    Response range = client.newCall(request).execute();
    assertEquals("AA", range.body().string());

    assertEquals("BB", get(url).body().string());
  }

  /**
   * When the server returns a full response body we will store it and return it regardless of what
   * its Last-Modified date is. This behavior was different prior to OkHttp 3.5 when we would prefer
   * the response with the later Last-Modified date.
   *
   * https://github.com/square/okhttp/issues/2886
   */
  @Test public void serverReturnsDocumentOlderThanCache() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setBody("B")
        .addHeader("Last-Modified: " + formatDate(-4, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    HttpUrl url = server.url("/");

    assertEquals("A", get(url).body().string());
    assertEquals("B", get(url).body().string());
    assertEquals("B", get(url).body().string());
  }

  @Test public void clientSideNoStore() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("B"));

    Request request1 = new Request.Builder()
        .url(server.url("/"))
        .cacheControl(new CacheControl.Builder().noStore().build())
        .build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("A", response1.body().string());

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("B", response2.body().string());
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
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
  }

  @Test public void previouslyNotGzippedContentIsNotModifiedAndSpecifiesGzipEncoding() throws Exception {
    server.enqueue(new MockResponse()
            .setBody("ABCABCABC")
            .addHeader("Content-Type: text/plain")
            .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    server.enqueue(new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED)
            .addHeader("Content-Type: text/plain")
            .addHeader("Content-Encoding: gzip"));
    server.enqueue(new MockResponse()
            .setBody("DEFDEFDEF"));

    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("DEFDEFDEF", get(server.url("/")).body().string());
  }

  @Test public void changedGzippedContentIsNotModifiedAndSpecifiesNewEncoding() throws Exception {
    server.enqueue(new MockResponse()
            .setBody(gzip("ABCABCABC"))
            .addHeader("Content-Type: text/plain")
            .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
            .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
            .addHeader("Content-Encoding: gzip"));
    server.enqueue(new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED)
            .addHeader("Content-Type: text/plain")
            .addHeader("Content-Encoding: identity"));
    server.enqueue(new MockResponse()
            .setBody("DEFDEFDEF"));

    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("DEFDEFDEF", get(server.url("/")).body().string());
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

    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("DEFDEFDEF", get(server.url("/")).body().string());
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

    assertEquals("ABCABCABC", get(server.url("/")).body().string());
    assertEquals("ABCABCABC", get(server.url("/")).body().string());
  }

  @Test public void conditionalCacheHitIsNotDoublePooled() throws Exception {
    // Ensure that the (shared) connection pool is in a consistent state.
    client.connectionPool().evictAll();
    assertEquals(0, client.connectionPool().idleConnectionCount());

    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals("A", get(server.url("/")).body().string());
    assertEquals(1, client.connectionPool().idleConnectionCount());
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

    assertEquals("A", get(server.url("/")).body().string());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-age=30")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("B", response.body().string());
  }

  @Test public void requestMinFresh() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "min-fresh=120")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("B", response.body().string());
  }

  @Test public void requestMaxStale() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale=180")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("A", response.body().string());
    assertEquals("110 HttpURLConnection \"Response is stale\"", response.header("Warning"));
  }

  @Test public void requestMaxStaleDirectiveWithNoValue() throws IOException {
    // Add a stale response to the cache.
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());

    // With max-stale, we'll return that stale response.
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("A", response.body().string());
    assertEquals("110 HttpURLConnection \"Response is stale\"", response.header("Warning"));
  }

  @Test public void requestMaxStaleNotHonoredWithMustRevalidate() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120, must-revalidate")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale=180")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("B", response.body().string());
  }

  @Test public void requestOnlyIfCachedWithNoResponseCached() throws IOException {
    // (no responses enqueued)

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertTrue(response.body().source().exhausted());
    assertEquals(504, response.code());
    assertEquals(1, cache.requestCount());
    assertEquals(0, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void requestOnlyIfCachedWithFullResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", get(server.url("/")).body().string());
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("A", response.body().string());
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(1, cache.hitCount());
  }

  @Test public void requestOnlyIfCachedWithConditionalResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES)));

    assertEquals("A", get(server.url("/")).body().string());
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertTrue(response.body().source().exhausted());
    assertEquals(504, response.code());
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void requestOnlyIfCachedWithUnhelpfulResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A"));

    assertEquals("A", get(server.url("/")).body().string());
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertTrue(response.body().source().exhausted());
    assertEquals(504, response.code());
    assertEquals(2, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void requestCacheControlNoCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    Request request = new Request.Builder()
        .url(url)
        .header("Cache-Control", "no-cache")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("B", response.body().string());
  }

  @Test public void requestPragmaNoCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    Request request = new Request.Builder()
        .url(url)
        .header("Pragma", "no-cache")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("B", response.body().string());
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

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());

    Request request = new Request.Builder()
        .url(url)
        .header(conditionName, conditionValue)
        .build();
    Response response = client.newCall(request).execute();
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, response.code());
    assertEquals("", response.body().string());

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
    dateFormat.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    String lastModifiedString = dateFormat.format(lastModifiedDate);
    String servedString = dateFormat.format(servedDate);

    // This response should be conditionally cached.
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedString)
        .addHeader("Expires: " + servedString)
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals("A", get(server.url("/")).body().string());

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

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("If-Modified-Since", formatDate(-24, TimeUnit.HOURS))
        .build();
    Response response = client.newCall(request).execute();
    assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, response.code());
    assertEquals("", response.body().string());
  }

  @Test public void authorizationRequestFullyCached() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request request = new Request.Builder()
        .url(url)
        .header("Authorization", "password")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("A", response.body().string());
    assertEquals("A", get(url).body().string());
  }

  @Test public void contentLocationDoesNotPopulateCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Content-Location: /bar")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/foo")).body().string());
    assertEquals("B", get(server.url("/bar")).body().string());
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

    assertEquals("A", get(server.url("/a")).body().string());
    assertEquals("A", get(server.url("/a")).body().string());
    assertEquals("B", get(server.url("/b")).body().string());

    assertEquals(0, server.takeRequest().getSequenceNumber());
    assertEquals(1, server.takeRequest().getSequenceNumber());
    assertEquals(2, server.takeRequest().getSequenceNumber());
  }

  @Test public void statisticsConditionalCacheMiss() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setBody("C"));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
    assertEquals("B", get(server.url("/")).body().string());
    assertEquals("C", get(server.url("/")).body().string());
    assertEquals(3, cache.requestCount());
    assertEquals(3, cache.networkCount());
    assertEquals(0, cache.hitCount());
  }

  @Test public void statisticsConditionalCacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
    assertEquals("A", get(server.url("/")).body().string());
    assertEquals("A", get(server.url("/")).body().string());
    assertEquals(3, cache.requestCount());
    assertEquals(3, cache.networkCount());
    assertEquals(2, cache.hitCount());
  }

  @Test public void statisticsFullCacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals(1, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(0, cache.hitCount());
    assertEquals("A", get(server.url("/")).body().string());
    assertEquals("A", get(server.url("/")).body().string());
    assertEquals(3, cache.requestCount());
    assertEquals(1, cache.networkCount());
    assertEquals(2, cache.hitCount());
  }

  @Test public void varyMatchesChangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request frRequest = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build();
    Response frResponse = client.newCall(frRequest).execute();
    assertEquals("A", frResponse.body().string());

    Request enRequest = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build();
    Response enResponse = client.newCall(enRequest).execute();
    assertEquals("B", enResponse.body().string());
  }

  @Test public void varyMatchesUnchangedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request request = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build();
    Response response1 = client.newCall(request).execute();
    assertEquals("A", response1.body().string());
    Request request1 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build();
    Response response2 = client.newCall(request1).execute();
    assertEquals("A", response2.body().string());
  }

  @Test public void varyMatchesAbsentRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals("A", get(server.url("/")).body().string());
  }

  @Test public void varyMatchesAddedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());
    Request request = new Request.Builder()
        .url(server.url("/")).header("Foo", "bar")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("B", response.body().string());
  }

  @Test public void varyMatchesRemovedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    Request request = new Request.Builder()
        .url(server.url("/")).header("Foo", "bar")
        .build();
    Response fooresponse = client.newCall(request).execute();
    assertEquals("A", fooresponse.body().string());
    assertEquals("B", get(server.url("/")).body().string());
  }

  @Test public void varyFieldsAreCaseInsensitive() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: ACCEPT-LANGUAGE")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request request = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build();
    Response response1 = client.newCall(request).execute();
    assertEquals("A", response1.body().string());
    Request request1 = new Request.Builder()
        .url(url)
        .header("accept-language", "fr-CA")
        .build();
    Response response2 = client.newCall(request1).execute();
    assertEquals("A", response2.body().string());
  }

  @Test public void varyMultipleFieldsWithMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request request = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build();
    Response response1 = client.newCall(request).execute();
    assertEquals("A", response1.body().string());
    Request request1 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build();
    Response response2 = client.newCall(request1).execute();
    assertEquals("A", response2.body().string());
  }

  @Test public void varyMultipleFieldsWithNoMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request frRequest = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build();
    Response frResponse = client.newCall(frRequest).execute();
    assertEquals("A", frResponse.body().string());
    Request enRequest = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build();
    Response enResponse = client.newCall(enRequest).execute();
    assertEquals("B", enResponse.body().string());
  }

  @Test public void varyMultipleFieldValuesWithMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request request1 = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("A", response1.body().string());

    Request request2 = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("A", response2.body().string());
  }

  @Test public void varyMultipleFieldValuesWithNoMatch() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    Request request1 = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("A", response1.body().string());

    Request request2 = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA")
        .addHeader("Accept-Language", "en-US")
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("B", response2.body().string());
  }

  @Test public void varyAsterisk() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertEquals("A", get(server.url("/")).body().string());
    assertEquals("B", get(server.url("/")).body().string());
  }

  @Test public void varyAndHttps() throws Exception {
    server.useHttps(sslClient.socketFactory, false);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    HttpUrl url = server.url("/");
    Request request1 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build();
    Response response1 = client.newCall(request1).execute();
    assertEquals("A", response1.body().string());

    Request request2 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build();
    Response response2 = client.newCall(request2).execute();
    assertEquals("A", response2.body().string());
  }

  @Test public void cachePlusCookies() throws Exception {
    RecordingCookieJar cookieJar = new RecordingCookieJar();
    client = client.newBuilder()
        .cookieJar(cookieJar)
        .build();

    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie: a=FIRST")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Set-Cookie: a=SECOND")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    cookieJar.assertResponseCookies("a=FIRST; path=/");
    assertEquals("A", get(url).body().string());
    cookieJar.assertResponseCookies("a=SECOND; path=/");
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

    Response response1 = get(server.url("/"));
    assertEquals("A", response1.body().string());
    assertEquals("GET, HEAD", response1.header("Allow"));

    Response response2 = get(server.url("/"));
    assertEquals("A", response2.body().string());
    assertEquals("GET, HEAD, PUT", response2.header("Allow"));
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

    Response response1 = get(server.url("/"));
    assertEquals("A", response1.body().string());
    assertEquals("identity", response1.header("Transfer-Encoding"));

    Response response2 = get(server.url("/"));
    assertEquals("A", response2.body().string());
    assertEquals("identity", response2.header("Transfer-Encoding"));
  }

  @Test public void getHeadersDeletesCached100LevelWarnings() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Warning: 199 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Response response1 = get(server.url("/"));
    assertEquals("A", response1.body().string());
    assertEquals("199 test danger", response1.header("Warning"));

    Response response2 = get(server.url("/"));
    assertEquals("A", response2.body().string());
    assertEquals(null, response2.header("Warning"));
  }

  @Test public void getHeadersRetainsCached200LevelWarnings() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Warning: 299 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Response response1 = get(server.url("/"));
    assertEquals("A", response1.body().string());
    assertEquals("299 test danger", response1.header("Warning"));

    Response response2 = get(server.url("/"));
    assertEquals("A", response2.body().string());
    assertEquals("299 test danger", response2.header("Warning"));
  }

  @Test public void doNotCachePartialResponse() throws Exception {
    assertNotCached(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 100-100/200")
        .addHeader("Cache-Control: max-age=60"));
  }

  @Test public void conditionalHitUpdatesCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Allow: GET, HEAD")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse()
        .setBody("B"));

    // A cache miss writes the cache.
    long t0 = System.currentTimeMillis();
    Response response1 = get(server.url("/a"));
    assertEquals("A", response1.body().string());
    assertEquals(null, response1.header("Allow"));
    assertEquals(0, response1.receivedResponseAtMillis() - t0, 250.0);

    // A conditional cache hit updates the cache.
    Thread.sleep(500); // Make sure t0 and t1 are distinct.
    long t1 = System.currentTimeMillis();
    Response response2 = get(server.url("/a"));
    assertEquals(HttpURLConnection.HTTP_OK, response2.code());
    assertEquals("A", response2.body().string());
    assertEquals("GET, HEAD", response2.header("Allow"));
    assertEquals(0, response2.receivedResponseAtMillis() - t1, 250.0);

    // A full cache hit reads the cache.
    Thread.sleep(500); // Make sure t1 and t2 are distinct.
    long t2 = System.currentTimeMillis();
    Response response3 = get(server.url("/a"));
    assertEquals("A", response3.body().string());
    assertEquals("GET, HEAD", response3.header("Allow"));
    assertEquals(0, response3.receivedResponseAtMillis() - t1, 250.0);

    assertEquals(2, server.getRequestCount());
  }

  @Test public void responseSourceHeaderCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertEquals("A", get(server.url("/")).body().string());
    Request request = new Request.Builder()
        .url(server.url("/")).header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertEquals("A", response.body().string());
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

    assertEquals("A", get(server.url("/")).body().string());
    Response response = get(server.url("/"));
    assertEquals("B", response.body().string());
  }

  @Test public void responseSourceHeaderConditionalCacheNotFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setResponseCode(304));

    assertEquals("A", get(server.url("/")).body().string());
    Response response = get(server.url("/"));
    assertEquals("A", response.body().string());
  }

  @Test public void responseSourceHeaderFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A"));

    Response response = get(server.url("/"));
    assertEquals("A", response.body().string());
  }

  @Test public void emptyResponseHeaderNameFromCacheIsLenient() throws Exception {
    Headers.Builder headers = new Headers.Builder()
        .add("Cache-Control: max-age=120");
    Internal.instance.addLenient(headers, ": A");
    server.enqueue(new MockResponse()
        .setHeaders(headers.build())
        .setBody("body"));

    Response response = get(server.url("/"));
    assertEquals("A", response.header(""));
    assertEquals("body", response.body().string());
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

    HttpUrl url = server.url("/");
    String urlKey = Cache.key(url);
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
    client = client.newBuilder()
        .cache(cache)
        .build();

    Response response = get(url);
    assertEquals(entryBody, response.body().string());
    assertEquals("3", response.header("Content-Length"));
    assertEquals("foo", response.header("etag"));
  }

  /** Exercise the cache format in OkHttp 2.7 and all earlier releases. */
  @Test public void testGoldenCacheHttpsResponseOkHttp27() throws Exception {
    HttpUrl url = server.url("/");
    String urlKey = Cache.key(url);
    String prefix = Platform.get().getPrefix();
    String entryMetadata = ""
        + "" + url + "\n"
        + "GET\n"
        + "0\n"
        + "HTTP/1.1 200 OK\n"
        + "4\n"
        + "Content-Length: 3\n"
        + prefix + "-Received-Millis: " + System.currentTimeMillis() + "\n"
        + prefix + "-Sent-Millis: " + System.currentTimeMillis() + "\n"
        + "Cache-Control: max-age=60\n"
        + "\n"
        + "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256\n"
        + "1\n"
        + "MIIBnDCCAQWgAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwHhcNMTUxMjIyMDEx"
        + "MTQwWhcNMTUxMjIzMDExMTQwWjAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJ"
        + "AoGBAJTn2Dh8xYmegvpOSmsKb2Os6Cxf1L4fYbnHr/turInUD5r1P7ZAuxurY880q3GT5bUDoirS3IfucddrT1Ac"
        + "AmUzEmk/FDjggiP8DlxFkY/XwXBlhRDVIp/mRuASPMGInckc0ZaixOkRFyrxADj+r1eaSmXCIvV5yTY6IaIokLj1"
        + "AgMBAAEwDQYJKoZIhvcNAQELBQADgYEAFblnedqtfRqI9j2WDyPPoG0NTZf9xwjeUu+ju+Ktty8u9k7Lgrrd/DH2"
        + "mQEtBD1Ctvp91MJfAClNg3faZzwClUyu5pd0QXRZEUwSwZQNen2QWDHRlVsItclBJ4t+AJLqTbwofWi4m4K8REOl"
        + "593hD55E4+lY22JZiVQyjsQhe6I=\n"
        + "0\n";
    String entryBody = "abc";
    String journalBody = ""
        + "libcore.io.DiskLruCache\n"
        + "1\n"
        + "201105\n"
        + "2\n"
        + "\n"
        + "DIRTY " + urlKey + "\n"
        + "CLEAN " + urlKey + " " + entryMetadata.length() + " " + entryBody.length() + "\n";
    writeFile(cache.directory(), urlKey + ".0", entryMetadata);
    writeFile(cache.directory(), urlKey + ".1", entryBody);
    writeFile(cache.directory(), "journal", journalBody);
    cache.close();
    cache = new Cache(cache.directory(), Integer.MAX_VALUE, fileSystem);
    client = client.newBuilder()
        .cache(cache)
        .build();

    Response response = get(url);
    assertEquals(entryBody, response.body().string());
    assertEquals("3", response.header("Content-Length"));
  }

  /** The TLS version is present in OkHttp 3.0 and beyond. */
  @Test public void testGoldenCacheHttpsResponseOkHttp30() throws Exception {
    HttpUrl url = server.url("/");
    String urlKey = Cache.key(url);
    String prefix = Platform.get().getPrefix();
    String entryMetadata = ""
        + "" + url + "\n"
        + "GET\n"
        + "0\n"
        + "HTTP/1.1 200 OK\n"
        + "4\n"
        + "Content-Length: 3\n"
        + prefix + "-Received-Millis: " + System.currentTimeMillis() + "\n"
        + prefix + "-Sent-Millis: " + System.currentTimeMillis() + "\n"
        + "Cache-Control: max-age=60\n"
        + "\n"
        + "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256\n"
        + "1\n"
        + "MIIBnDCCAQWgAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwHhcNMTUxMjIyMDEx"
        + "MTQwWhcNMTUxMjIzMDExMTQwWjAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJ"
        + "AoGBAJTn2Dh8xYmegvpOSmsKb2Os6Cxf1L4fYbnHr/turInUD5r1P7ZAuxurY880q3GT5bUDoirS3IfucddrT1Ac"
        + "AmUzEmk/FDjggiP8DlxFkY/XwXBlhRDVIp/mRuASPMGInckc0ZaixOkRFyrxADj+r1eaSmXCIvV5yTY6IaIokLj1"
        + "AgMBAAEwDQYJKoZIhvcNAQELBQADgYEAFblnedqtfRqI9j2WDyPPoG0NTZf9xwjeUu+ju+Ktty8u9k7Lgrrd/DH2"
        + "mQEtBD1Ctvp91MJfAClNg3faZzwClUyu5pd0QXRZEUwSwZQNen2QWDHRlVsItclBJ4t+AJLqTbwofWi4m4K8REOl"
        + "593hD55E4+lY22JZiVQyjsQhe6I=\n"
        + "0\n"
        + "TLSv1.2\n";
    String entryBody = "abc";
    String journalBody = ""
        + "libcore.io.DiskLruCache\n"
        + "1\n"
        + "201105\n"
        + "2\n"
        + "\n"
        + "DIRTY " + urlKey + "\n"
        + "CLEAN " + urlKey + " " + entryMetadata.length() + " " + entryBody.length() + "\n";
    writeFile(cache.directory(), urlKey + ".0", entryMetadata);
    writeFile(cache.directory(), urlKey + ".1", entryBody);
    writeFile(cache.directory(), "journal", journalBody);
    cache.close();
    cache = new Cache(cache.directory(), Integer.MAX_VALUE, fileSystem);
    client = client.newBuilder()
        .cache(cache)
        .build();

    Response response = get(url);
    assertEquals(entryBody, response.body().string());
    assertEquals("3", response.header("Content-Length"));
  }

  @Test public void testGoldenCacheHttpResponseOkHttp30() throws Exception {
    HttpUrl url = server.url("/");
    String urlKey = Cache.key(url);
    String prefix = Platform.get().getPrefix();
    String entryMetadata = ""
        + "" + url + "\n"
        + "GET\n"
        + "0\n"
        + "HTTP/1.1 200 OK\n"
        + "4\n"
        + "Cache-Control: max-age=60\n"
        + "Content-Length: 3\n"
        + prefix + "-Received-Millis: " + System.currentTimeMillis() + "\n"
        + prefix + "-Sent-Millis: " + System.currentTimeMillis() + "\n";
    String entryBody = "abc";
    String journalBody = ""
        + "libcore.io.DiskLruCache\n"
        + "1\n"
        + "201105\n"
        + "2\n"
        + "\n"
        + "DIRTY " + urlKey + "\n"
        + "CLEAN " + urlKey + " " + entryMetadata.length() + " " + entryBody.length() + "\n";
    writeFile(cache.directory(), urlKey + ".0", entryMetadata);
    writeFile(cache.directory(), urlKey + ".1", entryBody);
    writeFile(cache.directory(), "journal", journalBody);
    cache.close();
    cache = new Cache(cache.directory(), Integer.MAX_VALUE, fileSystem);
    client = client.newBuilder()
        .cache(cache)
        .build();

    Response response = get(url);
    assertEquals(entryBody, response.body().string());
    assertEquals("3", response.header("Content-Length"));
  }

  @Test public void evictAll() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    client.cache().evictAll();
    assertEquals(0, client.cache().size());
    assertEquals("B", get(url).body().string());
  }

  @Test public void networkInterceptorInvokedForConditionalGet() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    // Seed the cache.
    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());

    final AtomicReference<String> ifNoneMatch = new AtomicReference<>();
    client = client.newBuilder()
        .addNetworkInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            ifNoneMatch.compareAndSet(null, chain.request().header("If-None-Match"));
            return chain.proceed(chain.request());
          }
        }).build();

    // Confirm the value is cached and intercepted.
    assertEquals("A", get(url).body().string());
    assertEquals("v1", ifNoneMatch.get());
  }

  @Test public void networkInterceptorNotInvokedForFullyCached() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));

    // Seed the cache.
    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());

    // Confirm the interceptor isn't exercised.
    client = client.newBuilder()
        .addNetworkInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            throw new AssertionError();
          }
        }).build();
    assertEquals("A", get(url).body().string());
  }

  @Test public void iterateCache() throws Exception {
    // Put some responses in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl urlA = server.url("/a");
    assertEquals("a", get(urlA).body().string());

    server.enqueue(new MockResponse()
        .setBody("b"));
    HttpUrl urlB = server.url("/b");
    assertEquals("b", get(urlB).body().string());

    server.enqueue(new MockResponse()
        .setBody("c"));
    HttpUrl urlC = server.url("/c");
    assertEquals("c", get(urlC).body().string());

    // Confirm the iterator returns those responses...
    Iterator<String> i = cache.urls();
    assertTrue(i.hasNext());
    assertEquals(urlA.toString(), i.next());
    assertTrue(i.hasNext());
    assertEquals(urlB.toString(), i.next());
    assertTrue(i.hasNext());
    assertEquals(urlC.toString(), i.next());

    // ... and nothing else.
    assertFalse(i.hasNext());
    try {
      i.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void iteratorRemoveFromCache() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertEquals("a", get(url).body().string());

    // Remove it with iteration.
    Iterator<String> i = cache.urls();
    assertEquals(url.toString(), i.next());
    i.remove();

    // Confirm that subsequent requests suffer a cache miss.
    server.enqueue(new MockResponse()
        .setBody("b"));
    assertEquals("b", get(url).body().string());
  }

  @Test public void iteratorRemoveWithoutNextThrows() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertEquals("a", get(url).body().string());

    Iterator<String> i = cache.urls();
    assertTrue(i.hasNext());
    try {
      i.remove();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void iteratorRemoveOncePerCallToNext() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertEquals("a", get(url).body().string());

    Iterator<String> i = cache.urls();
    assertEquals(url.toString(), i.next());
    i.remove();

    // Too many calls to remove().
    try {
      i.remove();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void elementEvictedBetweenHasNextAndNext() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertEquals("a", get(url).body().string());

    // The URL will remain available if hasNext() returned true...
    Iterator<String> i = cache.urls();
    assertTrue(i.hasNext());

    // ...so even when we evict the element, we still get something back.
    cache.evictAll();
    assertEquals(url.toString(), i.next());

    // Remove does nothing. But most importantly, it doesn't throw!
    i.remove();
  }

  @Test public void elementEvictedBeforeHasNextIsOmitted() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertEquals("a", get(url).body().string());

    Iterator<String> i = cache.urls();
    cache.evictAll();

    // The URL was evicted before hasNext() made any promises.
    assertFalse(i.hasNext());
    try {
      i.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  /** Test https://github.com/square/okhttp/issues/1712. */
  @Test public void conditionalMissUpdatesCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse()
        .addHeader("ETag: v2")
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    assertEquals("A", get(url).body().string());
    assertEquals("B", get(url).body().string());
    assertEquals("B", get(url).body().string());

    assertEquals(null, server.takeRequest().getHeader("If-None-Match"));
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
    assertEquals("v2", server.takeRequest().getHeader("If-None-Match"));
  }

  @Test public void combinedCacheHeadersCanBeNonAscii() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .addHeaderLenient("Alpha", "α")
        .addHeaderLenient("β", "Beta")
        .setBody("abcd"));
    server.enqueue(new MockResponse()
        .addHeader("Transfer-Encoding: none")
        .addHeaderLenient("Gamma", "Γ")
        .addHeaderLenient("Δ", "Delta")
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Response response1 = get(server.url("/"));
    assertEquals("α", response1.header("Alpha"));
    assertEquals("Beta", response1.header("β"));
    assertEquals("abcd", response1.body().string());

    Response response2 = get(server.url("/"));
    assertEquals("α", response2.header("Alpha"));
    assertEquals("Beta", response2.header("β"));
    assertEquals("Γ", response2.header("Gamma"));
    assertEquals("Delta", response2.header("Δ"));
    assertEquals("abcd", response2.body().string());
  }

  @Test public void etagConditionCanBeNonAscii() throws Exception {
    server.enqueue(new MockResponse()
        .addHeaderLenient("Etag", "α")
        .addHeader("Cache-Control: max-age=0")
        .setBody("abcd"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Response response1 = get(server.url("/"));
    assertEquals("abcd", response1.body().string());

    Response response2 = get(server.url("/"));
    assertEquals("abcd", response2.body().string());

    assertEquals(null, server.takeRequest().getHeader("If-None-Match"));
    assertEquals("α", server.takeRequest().getHeader("If-None-Match"));
  }

  private Response get(HttpUrl url) throws IOException {
    Request request = new Request.Builder()
        .url(url)
        .build();
    return client.newCall(request).execute();
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

  private void assertNotCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    assertEquals("B", get(url).body().string());
  }

  /** @return the request with the conditional get headers. */
  private RecordedRequest assertConditionallyCached(MockResponse response) throws Exception {
    // scenario 1: condition succeeds
    server.enqueue(response.setBody("A").setStatus("HTTP/1.1 200 A-OK"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    // scenario 2: condition fails
    server.enqueue(response.setBody("B")
        .setStatus("HTTP/1.1 200 B-OK"));
    server.enqueue(new MockResponse()
        .setStatus("HTTP/1.1 200 C-OK")
        .setBody("C"));

    HttpUrl valid = server.url("/valid");
    Response response1 = get(valid);
    assertEquals("A", response1.body().string());
    assertEquals(HttpURLConnection.HTTP_OK, response1.code());
    assertEquals("A-OK", response1.message());
    Response response2 = get(valid);
    assertEquals("A", response2.body().string());
    assertEquals(HttpURLConnection.HTTP_OK, response2.code());
    assertEquals("A-OK", response2.message());

    HttpUrl invalid = server.url("/invalid");
    Response response3 = get(invalid);
    assertEquals("B", response3.body().string());
    assertEquals(HttpURLConnection.HTTP_OK, response3.code());
    assertEquals("B-OK", response3.message());
    Response response4 = get(invalid);
    assertEquals("C", response4.body().string());
    assertEquals(HttpURLConnection.HTTP_OK, response4.code());
    assertEquals("C-OK", response4.message());

    server.takeRequest(); // regular get
    return server.takeRequest(); // conditional get
  }

  @Test public void immutableIsCached() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "immutable")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    assertEquals("A", get(url).body().string());
  }

  @Test public void immutableIsCachedAfterMultipleCalls() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "immutable")
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setBody("C"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    assertEquals("B", get(url).body().string());
    assertEquals("B", get(url).body().string());
  }

  private void assertFullyCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(response.setBody("B"));

    HttpUrl url = server.url("/");
    assertEquals("A", get(url).body().string());
    assertEquals("A", get(url).body().string());
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

  /** Returns a gzipped copy of {@code bytes}. */
  public Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }
}
