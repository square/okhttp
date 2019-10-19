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
import okhttp3.internal.Internal;
import okhttp3.internal.io.InMemoryFileSystem;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.internal.Internal.cacheGet;
import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.fail;

public final class CacheTest {
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = (name, session) -> true;

  @Rule public MockWebServer server = new MockWebServer();
  @Rule public MockWebServer server2 = new MockWebServer();
  @Rule public InMemoryFileSystem fileSystem = new InMemoryFileSystem();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
  @Rule public final PlatformRule platform = new PlatformRule();

  private final HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client;
  private Cache cache;
  private final CookieManager cookieManager = new CookieManager();

  @Before public void setUp() throws Exception {
    platform.assumeNotOpenJSSE();

    server.setProtocolNegotiationEnabled(false);
    cache = new Cache(new File("/cache/"), Integer.MAX_VALUE, fileSystem);
    client = clientTestRule.newClientBuilder()
        .cache(cache)
        .cookieJar(new JavaNetCookieJar(cookieManager))
        .build();
  }

  @After public void tearDown() throws Exception {
    ResponseCache.setDefault(null);

    if (cache != null) {
      cache.delete();
    }
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
    assertThat(response.code()).isEqualTo(expectedResponseCode);

    // Exhaust the content stream.
    response.body().string();

    Response cached = cacheGet(cache, request);
    if (shouldPut) {
      assertThat(cached).isNotNull();
      cached.body().close();
    } else {
      assertThat(cached).isNull();
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
    assertThat(in1.readUtf8("I love ".length())).isEqualTo("I love ");
    in1.skip("puppies but hate ".length());
    assertThat(in1.readUtf8("spiders".length())).isEqualTo("spiders");
    assertThat(in1.exhausted()).isTrue();
    in1.close();
    assertThat(cache.writeSuccessCount()).isEqualTo(1);
    assertThat(cache.writeAbortCount()).isEqualTo(0);

    Response response2 = client.newCall(request).execute();
    BufferedSource in2 = response2.body().source();
    assertThat(in2.readUtf8("I love puppies but hate spiders".length())).isEqualTo(
        "I love puppies but hate spiders");
    assertThat(response2.code()).isEqualTo(200);
    assertThat(response2.message()).isEqualTo("Fantastic");

    assertThat(in2.exhausted()).isTrue();
    in2.close();
    assertThat(cache.writeSuccessCount()).isEqualTo(1);
    assertThat(cache.writeAbortCount()).isEqualTo(0);
    assertThat(cache.requestCount()).isEqualTo(2);
    assertThat(cache.hitCount()).isEqualTo(1);
  }

  @Test public void secureResponseCaching() throws IOException {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .setBody("ABC"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    Request request = new Request.Builder().url(server.url("/")).build();
    Response response1 = client.newCall(request).execute();
    BufferedSource in = response1.body().source();
    assertThat(in.readUtf8()).isEqualTo("ABC");

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    CipherSuite cipherSuite = response1.handshake().cipherSuite();
    List<Certificate> localCerts = response1.handshake().localCertificates();
    List<Certificate> serverCerts = response1.handshake().peerCertificates();
    Principal peerPrincipal = response1.handshake().peerPrincipal();
    Principal localPrincipal = response1.handshake().localPrincipal();

    Response response2 = client.newCall(request).execute(); // Cached!
    assertThat(response2.body().string()).isEqualTo("ABC");

    assertThat(cache.requestCount()).isEqualTo(2);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(1);

    assertThat(response2.handshake().cipherSuite()).isEqualTo(cipherSuite);
    assertThat(response2.handshake().localCertificates()).isEqualTo(localCerts);
    assertThat(response2.handshake().peerCertificates()).isEqualTo(serverCerts);
    assertThat(response2.handshake().peerPrincipal()).isEqualTo(peerPrincipal);
    assertThat(response2.handshake().localPrincipal()).isEqualTo(localPrincipal);
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
    assertThat(response1.body().string()).isEqualTo("ABC");

    Response response2 = client.newCall(request).execute(); // Cached!
    assertThat(response2.body().string()).isEqualTo("ABC");

    // 2 requests + 2 redirects
    assertThat(cache.requestCount()).isEqualTo(4);
    assertThat(cache.networkCount()).isEqualTo(2);
    assertThat(cache.hitCount()).isEqualTo(2);
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
    assertThat(response1.body().string()).isEqualTo("ABC");
    RecordedRequest recordedRequest1 = server.takeRequest();
    assertThat(recordedRequest1.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(recordedRequest1.getSequenceNumber()).isEqualTo(0);

    Request request2 = new Request.Builder().url(server.url("/bar")).build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("ABC");
    RecordedRequest recordedRequest2 = server.takeRequest();
    assertThat(recordedRequest2.getRequestLine()).isEqualTo("GET /bar HTTP/1.1");
    assertThat(recordedRequest2.getSequenceNumber()).isEqualTo(1);

    // an unrelated request should reuse the pooled connection
    Request request3 = new Request.Builder().url(server.url("/baz")).build();
    Response response3 = client.newCall(request3).execute();
    assertThat(response3.body().string()).isEqualTo("DEF");
    RecordedRequest recordedRequest3 = server.takeRequest();
    assertThat(recordedRequest3.getRequestLine()).isEqualTo("GET /baz HTTP/1.1");
    assertThat(recordedRequest3.getSequenceNumber()).isEqualTo(2);
  }

  @Test public void secureResponseCachingAndRedirects() throws IOException {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
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
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    Response response1 = get(server.url("/"));
    assertThat(response1.body().string()).isEqualTo("ABC");
    assertThat(response1.handshake().cipherSuite()).isNotNull();

    // Cached!
    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("ABC");
    assertThat(response2.handshake().cipherSuite()).isNotNull();

    // 2 direct + 2 redirect = 4
    assertThat(cache.requestCount()).isEqualTo(4);
    assertThat(cache.hitCount()).isEqualTo(2);
    assertThat(response2.handshake().cipherSuite()).isEqualTo(
        response1.handshake().cipherSuite());
  }

  /**
   * We've had bugs where caching and cross-protocol redirects yield class cast exceptions internal
   * to the cache because we incorrectly assumed that HttpsURLConnection was always HTTPS and
   * HttpURLConnection was always HTTP; in practice redirects mean that each can do either.
   *
   * https://github.com/square/okhttp/issues/214
   */
  @Test public void secureResponseCachingAndProtocolRedirects() throws IOException {
    server2.useHttps(handshakeCertificates.sslSocketFactory(), false);
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
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    Response response1 = get(server.url("/"));
    assertThat(response1.body().string()).isEqualTo("ABC");

    // Cached!
    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("ABC");

    // 2 direct + 2 redirect = 4
    assertThat(cache.requestCount()).isEqualTo(4);
    assertThat(cache.hitCount()).isEqualTo(2);
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
    assertThat(get(url).body().string()).isEqualTo("a");
    assertThat(get(url).body().string()).isEqualTo("a");
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
    assertThat(get(url).body().string()).isEqualTo("a");
    assertThat(get(url).body().string()).isEqualTo("b");
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
    assertThat(response1.body().string()).isEqualTo("ABC");

    Request request2 = new Request.Builder().url(server.url("/")).build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("ABC");
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
    assertThat(bodySource.readUtf8Line()).isEqualTo("ABCDE");
    try {
      bodySource.readUtf8(21);
      fail("This implementation silently ignored a truncated HTTP body.");
    } catch (IOException expected) {
    } finally {
      bodySource.close();
    }

    assertThat(cache.writeAbortCount()).isEqualTo(1);
    assertThat(cache.writeSuccessCount()).isEqualTo(0);
    Response response = get(server.url("/"));
    assertThat(response.body().string()).isEqualTo("Request #2");
    assertThat(cache.writeAbortCount()).isEqualTo(1);
    assertThat(cache.writeSuccessCount()).isEqualTo(1);
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
    assertThat(in.readUtf8(5)).isEqualTo("ABCDE");
    in.close();
    try {
      in.readByte();
      fail("Expected an IllegalStateException because the source is closed.");
    } catch (IllegalStateException expected) {
    }

    assertThat(cache.writeAbortCount()).isEqualTo(1);
    assertThat(cache.writeSuccessCount()).isEqualTo(0);
    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("Request #2");
    assertThat(cache.writeAbortCount()).isEqualTo(1);
    assertThat(cache.writeSuccessCount()).isEqualTo(1);
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
    assertThat(response1.body().string()).isEqualTo("A");

    Response response2 = get(url);
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.header("Warning")).isNull();
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
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isEqualTo(
        lastModifiedDate);
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Response response = get(server.url("/"));
    assertThat(response.body().string()).isEqualTo("A");
    assertThat(response.header("Warning")).isEqualTo(
        "113 HttpURLConnection \"Heuristic expiration\"");
  }

  @Test public void noDefaultExpirationForUrlsWithQueryString() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/").newBuilder().addQueryParameter("foo", "bar").build();
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("B");
  }

  @Test public void expirationDateInThePastWithLastModifiedHeader() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isEqualTo(
        lastModifiedDate);
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
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isEqualTo(
        lastModifiedDate);
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
    assertThat(response1.header("X-Response-ID")).isEqualTo("1");

    Response response2 = get(url);
    response2.body().close();
    if (expectCached) {
      assertThat(response2.header("X-Response-ID")).isEqualTo("1");
    } else {
      assertThat(response2.header("X-Response-ID")).isEqualTo("2");
    }
  }

  private RequestBody requestBodyOrNull(String requestMethod) {
    return (requestMethod.equals("POST") || requestMethod.equals("PUT"))
        ? RequestBody.create("foo", MediaType.get("text/plain"))
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

    assertThat(get(url).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(url)
        .method(requestMethod, requestBodyOrNull(requestMethod))
        .build();
    Response invalidate = client.newCall(request).execute();
    assertThat(invalidate.body().string()).isEqualTo("B");

    assertThat(get(url).body().string()).isEqualTo("C");
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

    assertThat(get(url).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(url)
        .method("POST", requestBodyOrNull("POST"))
        .build();
    Response invalidate = client.newCall(request).execute();
    assertThat(invalidate.body().string()).isEqualTo("B");

    assertThat(get(url).body().string()).isEqualTo("C");
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

    assertThat(get(url).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(url)
        .put(RequestBody.create("foo", MediaType.get("text/plain")))
        .build();
    Response invalidate = client.newCall(request).execute();
    assertThat(invalidate.body().string()).isEqualTo("");

    assertThat(get(url).body().string()).isEqualTo("C");
  }

  @Test public void etag() throws Exception {
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("ETag: v1"));
    assertThat(conditionalRequest.getHeader("If-None-Match")).isEqualTo("v1");
  }

  /** If both If-Modified-Since and If-None-Match conditions apply, send only If-None-Match. */
  @Test public void etagAndExpirationDateInThePast() throws Exception {
    String lastModifiedDate = formatDate(-2, TimeUnit.HOURS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("ETag: v1")
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS)));
    assertThat(conditionalRequest.getHeader("If-None-Match")).isEqualTo("v1");
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isNull();
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
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isEqualTo(
        lastModifiedDate);
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
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isEqualTo(
        lastModifiedDate);
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
    assertThat(range.body().string()).isEqualTo("AA");

    assertThat(get(url).body().string()).isEqualTo("BB");
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

    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("B");
    assertThat(get(url).body().string()).isEqualTo("B");
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
    assertThat(response1.body().string()).isEqualTo("A");

    Request request2 = new Request.Builder()
        .url(server.url("/"))
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("B");
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
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("DEFDEFDEF");
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("DEFDEFDEF");
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("DEFDEFDEF");
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
    assertThat(get(server.url("/")).body().string()).isEqualTo("ABCABCABC");
  }

  @Test public void conditionalCacheHitIsNotDoublePooled() throws Exception {
    clientTestRule.ensureAllConnectionsReleased();

    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .clearHeaders()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(client.connectionPool().idleConnectionCount()).isEqualTo(1);
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-age=30")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("B");
  }

  @Test public void requestMinFresh() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "min-fresh=120")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("B");
  }

  @Test public void requestMaxStale() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale=180")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("A");
    assertThat(response.header("Warning")).isEqualTo(
        "110 HttpURLConnection \"Response is stale\"");
  }

  @Test public void requestMaxStaleDirectiveWithNoValue() throws IOException {
    // Add a stale response to the cache.
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");

    // With max-stale, we'll return that stale response.
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("A");
    assertThat(response.header("Warning")).isEqualTo(
        "110 HttpURLConnection \"Response is stale\"");
  }

  @Test public void requestMaxStaleNotHonoredWithMustRevalidate() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=120, must-revalidate")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale=180")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("B");
  }

  @Test public void requestOnlyIfCachedWithNoResponseCached() throws IOException {
    // (no responses enqueued)

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().source().exhausted()).isTrue();
    assertThat(response.code()).isEqualTo(504);
    assertThat(cache.requestCount()).isEqualTo(1);
    assertThat(cache.networkCount()).isEqualTo(0);
    assertThat(cache.hitCount()).isEqualTo(0);
  }

  @Test public void requestOnlyIfCachedWithFullResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(2);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(1);
  }

  @Test public void requestOnlyIfCachedWithConditionalResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES)));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().source().exhausted()).isTrue();
    assertThat(response.code()).isEqualTo(504);
    assertThat(cache.requestCount()).isEqualTo(2);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);
  }

  @Test public void requestOnlyIfCachedWithUnhelpfulResponseCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().source().exhausted()).isTrue();
    assertThat(response.code()).isEqualTo(504);
    assertThat(cache.requestCount()).isEqualTo(2);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);
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
    assertThat(get(url).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(url)
        .header("Cache-Control", "no-cache")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("B");
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
    assertThat(get(url).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(url)
        .header("Pragma", "no-cache")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("B");
  }

  @Test public void clientSuppliedIfModifiedSinceWithCachedResult() throws Exception {
    MockResponse response = new MockResponse()
        .addHeader("ETag: v3")
        .addHeader("Cache-Control: max-age=0");
    String ifModifiedSinceDate = formatDate(-24, TimeUnit.HOURS);
    RecordedRequest request =
        assertClientSuppliedCondition(response, "If-Modified-Since", ifModifiedSinceDate);
    assertThat(request.getHeader("If-Modified-Since")).isEqualTo(ifModifiedSinceDate);
    assertThat(request.getHeader("If-None-Match")).isNull();
  }

  @Test public void clientSuppliedIfNoneMatchSinceWithCachedResult() throws Exception {
    String lastModifiedDate = formatDate(-3, TimeUnit.MINUTES);
    MockResponse response = new MockResponse()
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: max-age=0");
    RecordedRequest request = assertClientSuppliedCondition(response, "If-None-Match", "v1");
    assertThat(request.getHeader("If-None-Match")).isEqualTo("v1");
    assertThat(request.getHeader("If-Modified-Since")).isNull();
  }

  private RecordedRequest assertClientSuppliedCondition(MockResponse seed, String conditionName,
      String conditionValue) throws Exception {
    server.enqueue(seed.setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");

    Request request = new Request.Builder()
        .url(url)
        .header(conditionName, conditionValue)
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.code()).isEqualTo(HttpURLConnection.HTTP_NOT_MODIFIED);
    assertThat(response.body().string()).isEqualTo("");

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

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");

    // The first request has no conditions.
    RecordedRequest request1 = server.takeRequest();
    assertThat(request1.getHeader("If-Modified-Since")).isNull();

    // The 2nd request uses the server's date format.
    RecordedRequest request2 = server.takeRequest();
    assertThat(request2.getHeader("If-Modified-Since")).isEqualTo(lastModifiedString);
  }

  @Test public void clientSuppliedConditionWithoutCachedResult() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Request request = new Request.Builder()
        .url(server.url("/"))
        .header("If-Modified-Since", formatDate(-24, TimeUnit.HOURS))
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.code()).isEqualTo(HttpURLConnection.HTTP_NOT_MODIFIED);
    assertThat(response.body().string()).isEqualTo("");
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
    assertThat(response.body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("A");
  }

  @Test public void contentLocationDoesNotPopulateCache() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Content-Location: /bar")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/foo")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/bar")).body().string()).isEqualTo("B");
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

    assertThat(get(server.url("/a")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/a")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/b")).body().string()).isEqualTo("B");

    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(2);
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(1);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);
    assertThat(get(server.url("/")).body().string()).isEqualTo("B");
    assertThat(get(server.url("/")).body().string()).isEqualTo("C");
    assertThat(cache.requestCount()).isEqualTo(3);
    assertThat(cache.networkCount()).isEqualTo(3);
    assertThat(cache.hitCount()).isEqualTo(0);
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(1);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(3);
    assertThat(cache.networkCount()).isEqualTo(3);
    assertThat(cache.hitCount()).isEqualTo(2);
  }

  @Test public void statisticsFullCacheHit() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(1);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(0);
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(cache.requestCount()).isEqualTo(3);
    assertThat(cache.networkCount()).isEqualTo(1);
    assertThat(cache.hitCount()).isEqualTo(2);
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
    assertThat(frResponse.body().string()).isEqualTo("A");

    Request enRequest = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build();
    Response enResponse = client.newCall(enRequest).execute();
    assertThat(enResponse.body().string()).isEqualTo("B");
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
    assertThat(response1.body().string()).isEqualTo("A");
    Request request1 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build();
    Response response2 = client.newCall(request1).execute();
    assertThat(response2.body().string()).isEqualTo("A");
  }

  @Test public void varyMatchesAbsentRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
  }

  @Test public void varyMatchesAddedRequestHeaderField() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(server.url("/")).header("Foo", "bar")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("B");
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
    assertThat(fooresponse.body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("B");
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
    assertThat(response1.body().string()).isEqualTo("A");
    Request request1 = new Request.Builder()
        .url(url)
        .header("accept-language", "fr-CA")
        .build();
    Response response2 = client.newCall(request1).execute();
    assertThat(response2.body().string()).isEqualTo("A");
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
    assertThat(response1.body().string()).isEqualTo("A");
    Request request1 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build();
    Response response2 = client.newCall(request1).execute();
    assertThat(response2.body().string()).isEqualTo("A");
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
    assertThat(frResponse.body().string()).isEqualTo("A");
    Request enRequest = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build();
    Response enResponse = client.newCall(enRequest).execute();
    assertThat(enResponse.body().string()).isEqualTo("B");
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
    assertThat(response1.body().string()).isEqualTo("A");

    Request request2 = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("A");
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
    assertThat(response1.body().string()).isEqualTo("A");

    Request request2 = new Request.Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA")
        .addHeader("Accept-Language", "en-US")
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("B");
  }

  @Test public void varyAsterisk() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    assertThat(get(server.url("/")).body().string()).isEqualTo("B");
  }

  @Test public void varyAndHttps() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build();

    HttpUrl url = server.url("/");
    Request request1 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build();
    Response response1 = client.newCall(request1).execute();
    assertThat(response1.body().string()).isEqualTo("A");

    Request request2 = new Request.Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build();
    Response response2 = client.newCall(request2).execute();
    assertThat(response2.body().string()).isEqualTo("A");
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
    assertThat(get(url).body().string()).isEqualTo("A");
    cookieJar.assertResponseCookies("a=FIRST; path=/");
    assertThat(get(url).body().string()).isEqualTo("A");
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
    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(response1.header("Allow")).isEqualTo("GET, HEAD");

    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.header("Allow")).isEqualTo("GET, HEAD, PUT");
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
    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(response1.header("Transfer-Encoding")).isEqualTo("identity");

    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.header("Transfer-Encoding")).isEqualTo("identity");
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
    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(response1.header("Warning")).isEqualTo("199 test danger");

    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.header("Warning")).isNull();
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
    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(response1.header("Warning")).isEqualTo("299 test danger");

    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.header("Warning")).isEqualTo("299 test danger");
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
    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(response1.header("Allow")).isNull();
    assertThat((double) (response1.receivedResponseAtMillis() - t0)).isCloseTo(
        (double) 0, offset(250.0));

    // A conditional cache hit updates the cache.
    Thread.sleep(500); // Make sure t0 and t1 are distinct.
    long t1 = System.currentTimeMillis();
    Response response2 = get(server.url("/a"));
    assertThat(response2.code()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.header("Allow")).isEqualTo("GET, HEAD");
    Long updatedTimestamp = response2.receivedResponseAtMillis();
    assertThat((double) (updatedTimestamp - t1)).isCloseTo(
        (double) 0, offset(250.0));

    // A full cache hit reads the cache.
    Thread.sleep(10);
    Response response3 = get(server.url("/a"));
    assertThat(response3.body().string()).isEqualTo("A");
    assertThat(response3.header("Allow")).isEqualTo("GET, HEAD");
    assertThat(response3.receivedResponseAtMillis()).isEqualTo(updatedTimestamp);

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test public void responseSourceHeaderCached() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Request request = new Request.Builder()
        .url(server.url("/")).header("Cache-Control", "only-if-cached")
        .build();
    Response response = client.newCall(request).execute();
    assertThat(response.body().string()).isEqualTo("A");
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

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Response response = get(server.url("/"));
    assertThat(response.body().string()).isEqualTo("B");
  }

  @Test public void responseSourceHeaderConditionalCacheNotFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES)));
    server.enqueue(new MockResponse()
        .setResponseCode(304));

    assertThat(get(server.url("/")).body().string()).isEqualTo("A");
    Response response = get(server.url("/"));
    assertThat(response.body().string()).isEqualTo("A");
  }

  @Test public void responseSourceHeaderFetched() throws IOException {
    server.enqueue(new MockResponse()
        .setBody("A"));

    Response response = get(server.url("/"));
    assertThat(response.body().string()).isEqualTo("A");
  }

  @Test public void emptyResponseHeaderNameFromCacheIsLenient() throws Exception {
    Headers.Builder headers = new Headers.Builder()
        .add("Cache-Control: max-age=120");
    Internal.addHeaderLenient(headers, ": A");
    server.enqueue(new MockResponse()
        .setHeaders(headers.build())
        .setBody("body"));

    Response response = get(server.url("/"));
    assertThat(response.header("")).isEqualTo("A");
    assertThat(response.body().string()).isEqualTo("body");
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
    assertThat(response.body().string()).isEqualTo(entryBody);
    assertThat(response.header("Content-Length")).isEqualTo("3");
    assertThat(response.header("etag")).isEqualTo("foo");
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
    assertThat(response.body().string()).isEqualTo(entryBody);
    assertThat(response.header("Content-Length")).isEqualTo("3");
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
    assertThat(response.body().string()).isEqualTo(entryBody);
    assertThat(response.header("Content-Length")).isEqualTo("3");
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
    assertThat(response.body().string()).isEqualTo(entryBody);
    assertThat(response.header("Content-Length")).isEqualTo("3");
  }

  @Test public void evictAll() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");
    client.cache().evictAll();
    assertThat(client.cache().size()).isEqualTo(0);
    assertThat(get(url).body().string()).isEqualTo("B");
  }

  @Test public void networkInterceptorInvokedForConditionalGet() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("ETag: v1")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    // Seed the cache.
    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");

    final AtomicReference<String> ifNoneMatch = new AtomicReference<>();
    client = client.newBuilder()
        .addNetworkInterceptor(chain -> {
          ifNoneMatch.compareAndSet(null, chain.request().header("If-None-Match"));
          return chain.proceed(chain.request());
        })
        .build();

    // Confirm the value is cached and intercepted.
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(ifNoneMatch.get()).isEqualTo("v1");
  }

  @Test public void networkInterceptorNotInvokedForFullyCached() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control: max-age=60")
        .setBody("A"));

    // Seed the cache.
    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");

    // Confirm the interceptor isn't exercised.
    client = client.newBuilder()
        .addNetworkInterceptor(chain -> { throw new AssertionError(); })
        .build();
    assertThat(get(url).body().string()).isEqualTo("A");
  }

  @Test public void iterateCache() throws Exception {
    // Put some responses in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl urlA = server.url("/a");
    assertThat(get(urlA).body().string()).isEqualTo("a");

    server.enqueue(new MockResponse()
        .setBody("b"));
    HttpUrl urlB = server.url("/b");
    assertThat(get(urlB).body().string()).isEqualTo("b");

    server.enqueue(new MockResponse()
        .setBody("c"));
    HttpUrl urlC = server.url("/c");
    assertThat(get(urlC).body().string()).isEqualTo("c");

    // Confirm the iterator returns those responses...
    Iterator<String> i = cache.urls();
    assertThat(i.hasNext()).isTrue();
    assertThat(i.next()).isEqualTo(urlA.toString());
    assertThat(i.hasNext()).isTrue();
    assertThat(i.next()).isEqualTo(urlB.toString());
    assertThat(i.hasNext()).isTrue();
    assertThat(i.next()).isEqualTo(urlC.toString());

    // ... and nothing else.
    assertThat(i.hasNext()).isFalse();
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
    assertThat(get(url).body().string()).isEqualTo("a");

    // Remove it with iteration.
    Iterator<String> i = cache.urls();
    assertThat(i.next()).isEqualTo(url.toString());
    i.remove();

    // Confirm that subsequent requests suffer a cache miss.
    server.enqueue(new MockResponse()
        .setBody("b"));
    assertThat(get(url).body().string()).isEqualTo("b");
  }

  @Test public void iteratorRemoveWithoutNextThrows() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertThat(get(url).body().string()).isEqualTo("a");

    Iterator<String> i = cache.urls();
    assertThat(i.hasNext()).isTrue();
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
    assertThat(get(url).body().string()).isEqualTo("a");

    Iterator<String> i = cache.urls();
    assertThat(i.next()).isEqualTo(url.toString());
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
    assertThat(get(url).body().string()).isEqualTo("a");

    // The URL will remain available if hasNext() returned true...
    Iterator<String> i = cache.urls();
    assertThat(i.hasNext()).isTrue();

    // ...so even when we evict the element, we still get something back.
    cache.evictAll();
    assertThat(i.next()).isEqualTo(url.toString());

    // Remove does nothing. But most importantly, it doesn't throw!
    i.remove();
  }

  @Test public void elementEvictedBeforeHasNextIsOmitted() throws Exception {
    // Put a response in the cache.
    server.enqueue(new MockResponse()
        .setBody("a"));
    HttpUrl url = server.url("/a");
    assertThat(get(url).body().string()).isEqualTo("a");

    Iterator<String> i = cache.urls();
    cache.evictAll();

    // The URL was evicted before hasNext() made any promises.
    assertThat(i.hasNext()).isFalse();
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
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("B");
    assertThat(get(url).body().string()).isEqualTo("B");

    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1");
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v1");
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("v2");
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
    assertThat(response1.header("Alpha")).isEqualTo("α");
    assertThat(response1.header("β")).isEqualTo("Beta");
    assertThat(response1.body().string()).isEqualTo("abcd");

    Response response2 = get(server.url("/"));
    assertThat(response2.header("Alpha")).isEqualTo("α");
    assertThat(response2.header("β")).isEqualTo("Beta");
    assertThat(response2.header("Gamma")).isEqualTo("Γ");
    assertThat(response2.header("Δ")).isEqualTo("Delta");
    assertThat(response2.body().string()).isEqualTo("abcd");
  }

  @Test public void etagConditionCanBeNonAscii() throws Exception {
    server.enqueue(new MockResponse()
        .addHeaderLenient("Etag", "α")
        .addHeader("Cache-Control: max-age=0")
        .setBody("abcd"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED));

    Response response1 = get(server.url("/"));
    assertThat(response1.body().string()).isEqualTo("abcd");

    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("abcd");

    assertThat(server.takeRequest().getHeader("If-None-Match")).isNull();
    assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("α");
  }

  @Test public void conditionalHitHeadersCombined() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Etag", "a")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("A: a1")
        .addHeader("B: b2")
        .addHeader("B: b3")
        .setBody("abcd"));
    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("B: b4")
        .addHeader("B: b5")
        .addHeader("C: c6"));

    Response response1 = get(server.url("/"));
    assertThat(response1.body().string()).isEqualTo("abcd");
    assertThat(response1.headers()).isEqualTo(Headers.of("Etag", "a", "Cache-Control", "max-age=0",
        "A", "a1", "B", "b2", "B", "b3", "Content-Length", "4"));

    // The original 'A' header is retained because the network response doesn't have one.
    // The original 'B' headers are replaced by the network response.
    // The network's 'C' header is added.
    Response response2 = get(server.url("/"));
    assertThat(response2.body().string()).isEqualTo("abcd");
    assertThat(response2.headers()).isEqualTo(Headers.of("Etag", "a", "Cache-Control", "max-age=0",
        "A", "a1", "Content-Length", "4", "B", "b4", "B", "b5", "C", "c6"));
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
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("B");
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
    assertThat(response1.body().string()).isEqualTo("A");
    assertThat(response1.code()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response1.message()).isEqualTo("A-OK");
    Response response2 = get(valid);
    assertThat(response2.body().string()).isEqualTo("A");
    assertThat(response2.code()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response2.message()).isEqualTo("A-OK");

    HttpUrl invalid = server.url("/invalid");
    Response response3 = get(invalid);
    assertThat(response3.body().string()).isEqualTo("B");
    assertThat(response3.code()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response3.message()).isEqualTo("B-OK");
    Response response4 = get(invalid);
    assertThat(response4.body().string()).isEqualTo("C");
    assertThat(response4.code()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(response4.message()).isEqualTo("C-OK");

    server.takeRequest(); // regular get
    return server.takeRequest(); // conditional get
  }

  @Test public void immutableIsCached() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "immutable, max-age=10")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("A");
  }

  @Test public void immutableIsCachedAfterMultipleCalls() throws Exception {
    server.enqueue(new MockResponse()
        .setBody("A"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "immutable, max-age=10")
        .setBody("B"));
    server.enqueue(new MockResponse()
        .setBody("C"));

    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("B");
    assertThat(get(url).body().string()).isEqualTo("B");
  }

  @Test public void immutableIsNotCachedBeyondFreshnessLifetime() throws Exception {
    //      last modified: 115 seconds ago
    //             served:  15 seconds ago
    //   default lifetime: (115 - 15) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds ago
    String lastModifiedDate = formatDate(-115, TimeUnit.SECONDS);
    RecordedRequest conditionalRequest = assertConditionallyCached(new MockResponse()
        .addHeader("Cache-Control: immutable")
        .addHeader("Last-Modified: " + lastModifiedDate)
        .addHeader("Date: " + formatDate(-15, TimeUnit.SECONDS)));
    assertThat(conditionalRequest.getHeader("If-Modified-Since")).isEqualTo(
        lastModifiedDate);
  }

  private void assertFullyCached(MockResponse response) throws Exception {
    server.enqueue(response.setBody("A"));
    server.enqueue(response.setBody("B"));

    HttpUrl url = server.url("/");
    assertThat(get(url).body().string()).isEqualTo("A");
    assertThat(get(url).body().string()).isEqualTo("A");
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
    CHUNKED {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setChunkedBody(content, chunkSize);
      }
    },
    FIXED_LENGTH {
      @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
        response.setBody(content);
      }
    },
    END_OF_STREAM {
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
