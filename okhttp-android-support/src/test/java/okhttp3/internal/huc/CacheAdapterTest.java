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

import java.io.IOException;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import okhttp3.AbstractResponseCache;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.internal.Internal;
import okhttp3.internal.InternalCache;
import okhttp3.internal.SslContextBuilder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.testing.RecordingHostnameVerifier;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A white-box test for {@link CacheAdapter}. See also:
 * <ul>
 *     <li>{@link ResponseCacheTest} for black-box tests that check that {@link ResponseCache}
 *         classes are called correctly by OkHttp.
 *     <li>{@link JavaApiConverterTest} for tests that check Java API classes / OkHttp conversion
 *         logic.
 * </ul>
 */
public class CacheAdapterTest {
  private SSLContext sslContext = SslContextBuilder.localhost();
  private HostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
  private MockWebServer server;
  private OkHttpClient client;
  private HttpURLConnection connection;

  @Before public void setUp() throws Exception {
    server = new MockWebServer();
    client = new OkHttpClient();
  }

  @After public void tearDown() throws Exception {
    if (connection != null) {
      connection.disconnect();
    }
    server.shutdown();
  }

  @Test public void get_httpGet() throws Exception {
    final URL serverUrl = configureServer(new MockResponse());
    assertEquals("http", serverUrl.getProtocol());

    ResponseCache responseCache = new AbstractResponseCache() {
      @Override public CacheResponse get(
          URI uri, String method, Map<String, List<String>> headers) throws IOException {
        assertEquals(toUri(serverUrl), uri);
        assertEquals("GET", method);
        assertTrue("Arbitrary standard header not present", headers.containsKey("User-Agent"));
        assertEquals(Collections.singletonList("value1"), headers.get("key1"));
        return null;
      }
    };
    setInternalCache(new CacheAdapter(responseCache));

    connection = new OkUrlFactory(client).open(serverUrl);
    connection.setRequestProperty("key1", "value1");

    executeGet(connection);
  }

  @Test public void get_httpsGet() throws Exception {
    final URL serverUrl = configureHttpsServer(new MockResponse());
    assertEquals("https", serverUrl.getProtocol());

    ResponseCache responseCache = new AbstractResponseCache() {
      @Override public CacheResponse get(URI uri, String method, Map<String, List<String>> headers)
          throws IOException {
        assertEquals("https", uri.getScheme());
        assertEquals(toUri(serverUrl), uri);
        assertEquals("GET", method);
        assertTrue("Arbitrary standard header not present", headers.containsKey("User-Agent"));
        assertEquals(Collections.singletonList("value1"), headers.get("key1"));
        return null;
      }
    };
    setInternalCache(new CacheAdapter(responseCache));
    client = client.newBuilder()
        .sslSocketFactory(sslContext.getSocketFactory())
        .hostnameVerifier(hostnameVerifier)
        .build();

    connection = new OkUrlFactory(client).open(serverUrl);
    connection.setRequestProperty("key1", "value1");

    executeGet(connection);
  }

  @Test public void put_httpGet() throws Exception {
    final String statusLine = "HTTP/1.1 200 Fantastic";
    final byte[] response = "ResponseString".getBytes(StandardCharsets.UTF_8);
    final URL serverUrl = configureServer(
        new MockResponse()
            .setStatus(statusLine)
            .addHeader("A", "c")
            .setBody(new Buffer().write(response)));

    ResponseCache responseCache = new AbstractResponseCache() {
      @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
        assertTrue(connection instanceof HttpURLConnection);
        assertFalse(connection instanceof HttpsURLConnection);

        assertEquals(response.length, connection.getContentLength());

        HttpURLConnection httpUrlConnection = (HttpURLConnection) connection;
        assertEquals("GET", httpUrlConnection.getRequestMethod());
        assertTrue(httpUrlConnection.getDoInput());
        assertFalse(httpUrlConnection.getDoOutput());

        assertEquals("Fantastic", httpUrlConnection.getResponseMessage());
        assertEquals(toUri(serverUrl), uri);
        assertEquals(serverUrl, connection.getURL());
        assertEquals("value", connection.getRequestProperty("key"));

        // Check retrieval by string key.
        assertEquals(statusLine, httpUrlConnection.getHeaderField(null));
        assertEquals("c", httpUrlConnection.getHeaderField("A"));
        // The RI and OkHttp supports case-insensitive matching for this method.
        assertEquals("c", httpUrlConnection.getHeaderField("a"));
        return null;
      }
    };
    setInternalCache(new CacheAdapter(responseCache));

    connection = new OkUrlFactory(client).open(serverUrl);
    connection.setRequestProperty("key", "value");
    executeGet(connection);
  }

  @Test public void put_httpPost() throws Exception {
    final String statusLine = "HTTP/1.1 200 Fantastic";
    final URL serverUrl = configureServer(
        new MockResponse()
            .setStatus(statusLine)
            .addHeader("A", "c"));

    ResponseCache responseCache = new AbstractResponseCache() {
      @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
        assertTrue(connection instanceof HttpURLConnection);
        assertFalse(connection instanceof HttpsURLConnection);

        assertEquals(0, connection.getContentLength());

        HttpURLConnection httpUrlConnection = (HttpURLConnection) connection;
        assertEquals("POST", httpUrlConnection.getRequestMethod());
        assertTrue(httpUrlConnection.getDoInput());
        assertTrue(httpUrlConnection.getDoOutput());

        assertEquals("Fantastic", httpUrlConnection.getResponseMessage());
        assertEquals(toUri(serverUrl), uri);
        assertEquals(serverUrl, connection.getURL());
        assertEquals("value", connection.getRequestProperty("key"));

        // Check retrieval by string key.
        assertEquals(statusLine, httpUrlConnection.getHeaderField(null));
        assertEquals("c", httpUrlConnection.getHeaderField("A"));
        // The RI and OkHttp supports case-insensitive matching for this method.
        assertEquals("c", httpUrlConnection.getHeaderField("a"));
        return null;
      }
    };
    setInternalCache(new CacheAdapter(responseCache));

    connection = new OkUrlFactory(client).open(serverUrl);

    executePost(connection);
  }

  @Test public void put_httpsGet() throws Exception {
    final URL serverUrl = configureHttpsServer(new MockResponse());
    assertEquals("https", serverUrl.getProtocol());

    ResponseCache responseCache = new AbstractResponseCache() {
      @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
        assertTrue(connection instanceof HttpsURLConnection);
        assertEquals(toUri(serverUrl), uri);
        assertEquals(serverUrl, connection.getURL());

        HttpsURLConnection cacheHttpsUrlConnection = (HttpsURLConnection) connection;
        HttpsURLConnection realHttpsUrlConnection =
            (HttpsURLConnection) CacheAdapterTest.this.connection;
        assertEquals(realHttpsUrlConnection.getCipherSuite(),
            cacheHttpsUrlConnection.getCipherSuite());
        assertEquals(realHttpsUrlConnection.getPeerPrincipal(),
            cacheHttpsUrlConnection.getPeerPrincipal());
        assertArrayEquals(realHttpsUrlConnection.getLocalCertificates(),
            cacheHttpsUrlConnection.getLocalCertificates());
        assertArrayEquals(realHttpsUrlConnection.getServerCertificates(),
            cacheHttpsUrlConnection.getServerCertificates());
        assertEquals(realHttpsUrlConnection.getLocalPrincipal(),
            cacheHttpsUrlConnection.getLocalPrincipal());
        return null;
      }
    };
    setInternalCache(new CacheAdapter(responseCache));
    client = client.newBuilder()
        .sslSocketFactory(sslContext.getSocketFactory())
        .hostnameVerifier(hostnameVerifier)
        .build();

    connection = new OkUrlFactory(client).open(serverUrl);
    executeGet(connection);
  }

  private void executeGet(HttpURLConnection connection) throws IOException {
    connection.connect();
    connection.getHeaderFields();
    connection.disconnect();
  }

  private void executePost(HttpURLConnection connection) throws IOException {
    connection.setDoOutput(true);
    connection.connect();
    connection.getOutputStream().write("Hello World".getBytes());
    connection.disconnect();
  }

  private URL configureServer(MockResponse mockResponse) throws Exception {
    server.enqueue(mockResponse);
    server.start();
    return server.url("/").url();
  }

  private URL configureHttpsServer(MockResponse mockResponse) throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false /* tunnelProxy */);
    server.enqueue(mockResponse);
    server.start();
    return server.url("/").url();
  }

  private void setInternalCache(InternalCache internalCache) {
    OkHttpClient.Builder builder = client.newBuilder();
    Internal.instance.setCache(builder, internalCache);
    client = builder.build();
  }
}
