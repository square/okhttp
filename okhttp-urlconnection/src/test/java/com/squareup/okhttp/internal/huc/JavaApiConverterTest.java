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
package com.squareup.okhttp.internal.huc;

import com.squareup.okhttp.Handshake;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.SecureCacheResponse;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JavaApiConverterTest {

  // $ openssl req -x509 -nodes -days 36500 -subj '/CN=localhost' -config ./cert.cnf \
  //     -newkey rsa:512 -out cert.pem
  private static final X509Certificate LOCAL_CERT = certificate(""
      + "-----BEGIN CERTIFICATE-----\n"
      + "MIIBWDCCAQKgAwIBAgIJANS1EtICX2AZMA0GCSqGSIb3DQEBBQUAMBQxEjAQBgNV\n"
      + "BAMTCWxvY2FsaG9zdDAgFw0xMjAxMDIxOTA4NThaGA8yMTExMTIwOTE5MDg1OFow\n"
      + "FDESMBAGA1UEAxMJbG9jYWxob3N0MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAPpt\n"
      + "atK8r4/hf4hSIs0os/BSlQLbRBaK9AfBReM4QdAklcQqe6CHsStKfI8pp0zs7Ptg\n"
      + "PmMdpbttL0O7mUboBC8CAwEAAaM1MDMwMQYDVR0RBCowKIIVbG9jYWxob3N0Lmxv\n"
      + "Y2FsZG9tYWlugglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcNAQEFBQADQQD0ntfL\n"
      + "DCzOCv9Ma6Lv5o5jcYWVxvBSTsnt22hsJpWD1K7iY9lbkLwl0ivn73pG2evsAn9G\n"
      + "X8YKH52fnHsCrhSD\n"
      + "-----END CERTIFICATE-----");

  // openssl req -x509 -nodes -days 36500 -subj '/CN=*.0.0.1' -newkey rsa:512 -out cert.pem
  private static final X509Certificate SERVER_CERT = certificate(""
      + "-----BEGIN CERTIFICATE-----\n"
      + "MIIBkjCCATygAwIBAgIJAMdemqOwd/BEMA0GCSqGSIb3DQEBBQUAMBIxEDAOBgNV\n"
      + "BAMUByouMC4wLjEwIBcNMTAxMjIwMTY0NDI1WhgPMjExMDExMjYxNjQ0MjVaMBIx\n"
      + "EDAOBgNVBAMUByouMC4wLjEwXDANBgkqhkiG9w0BAQEFAANLADBIAkEAqY8c9Qrt\n"
      + "YPWCvb7lclI+aDHM6fgbJcHsS9Zg8nUOh5dWrS7AgeA25wyaokFl4plBbbHQe2j+\n"
      + "cCjsRiJIcQo9HwIDAQABo3MwcTAdBgNVHQ4EFgQUJ436TZPJvwCBKklZZqIvt1Yt\n"
      + "JjEwQgYDVR0jBDswOYAUJ436TZPJvwCBKklZZqIvt1YtJjGhFqQUMBIxEDAOBgNV\n"
      + "BAMUByouMC4wLjGCCQDHXpqjsHfwRDAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEB\n"
      + "BQUAA0EAk9i88xdjWoewqvE+iMC9tD2obMchgFDaHH0ogxxiRaIKeEly3g0uGxIt\n"
      + "fl2WRY8hb4x+zRrwsFaLEpdEvqcjOQ==\n"
      + "-----END CERTIFICATE-----");

  private static final SSLContext sslContext = SslContextBuilder.localhost();
  private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
    public boolean verify(String hostname, SSLSession session) {
      return true;
    }
  };

  @Rule public MockWebServer server = new MockWebServer();

  private OkHttpClient client;

  private HttpURLConnection connection;

  @Before public void setUp() throws Exception {
    client = new OkHttpClient();
  }

  @After public void tearDown() throws Exception {
    if (connection != null) {
      connection.disconnect();
    }
  }

  @Test public void createOkResponse_fromOkHttpUrlConnection() throws Exception {
    testCreateOkResponseInternal(new OkHttpURLConnectionFactory(client), false /* isSecure */);
  }

  @Test public void createOkResponse_fromJavaHttpUrlConnection() throws Exception {
    testCreateOkResponseInternal(new JavaHttpURLConnectionFactory(), false /* isSecure */);
  }

  @Test public void createOkResponse_fromOkHttpsUrlConnection() throws Exception {
    testCreateOkResponseInternal(new OkHttpURLConnectionFactory(client), true /* isSecure */);
  }

  @Test public void createOkResponse_fromJavaHttpsUrlConnection() throws Exception {
    testCreateOkResponseInternal(new JavaHttpURLConnectionFactory(), true /* isSecure */);
  }

  private void testCreateOkResponseInternal(HttpURLConnectionFactory httpUrlConnectionFactory,
      boolean isSecure) throws Exception {
    String statusLine = "HTTP/1.1 200 Fantastic";
    String body = "Nothing happens";
    final URL serverUrl;
    MockResponse mockResponse = new MockResponse()
        .setStatus(statusLine)
        .addHeader("xyzzy", "baz")
        .setBody(body);
    if (isSecure) {
      serverUrl = configureHttpsServer(
          mockResponse);

      assertEquals("https", serverUrl.getProtocol());
    } else {
      serverUrl = configureServer(
          mockResponse);
      assertEquals("http", serverUrl.getProtocol());
    }

    connection = httpUrlConnectionFactory.open(serverUrl);
    if (isSecure) {
      HttpsURLConnection httpsUrlConnection = (HttpsURLConnection) connection;
      httpsUrlConnection.setSSLSocketFactory(sslContext.getSocketFactory());
      httpsUrlConnection.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    }
    connection.setRequestProperty("snake", "bird");
    connection.connect();
    Response response = JavaApiConverter.createOkResponse(serverUrl.toURI(), connection);

    // Check the response.request()
    Request request = response.request();
    assertEquals(isSecure, request.isHttps());
    assertEquals(serverUrl.toURI(), request.uri());
    assertNull(request.body());
    Headers okRequestHeaders = request.headers();
    // In Java the request headers are unavailable for a connected HttpURLConnection.
    assertEquals(0, okRequestHeaders.size());
    assertEquals("GET", request.method());

    // Check the response
    assertEquals(Protocol.HTTP_1_1, response.protocol());
    assertEquals(200, response.code());
    assertEquals("Fantastic", response.message());
    Headers okResponseHeaders = response.headers();
    assertEquals("baz", okResponseHeaders.get("xyzzy"));
    if (isSecure) {
      Handshake handshake = response.handshake();
      assertNotNull(handshake);
      HttpsURLConnection httpsURLConnection = (HttpsURLConnection) connection;
      assertNotNullAndEquals(httpsURLConnection.getCipherSuite(), handshake.cipherSuite());
      assertEquals(httpsURLConnection.getLocalPrincipal(), handshake.localPrincipal());
      assertNotNullAndEquals(httpsURLConnection.getPeerPrincipal(), handshake.peerPrincipal());
      assertNotNull(httpsURLConnection.getServerCertificates());
      assertEquals(Arrays.asList(httpsURLConnection.getServerCertificates()),
          handshake.peerCertificates());
      assertNull(httpsURLConnection.getLocalCertificates());
    } else {
      assertNull(response.handshake());
    }
    assertEquals(body, response.body().string());
  }

  @Test public void createOkResponse_fromCacheResponse() throws Exception {
    final String statusLine = "HTTP/1.1 200 Fantastic";
    URI uri = new URI("http://foo/bar");
    Request request = new Request.Builder().url(uri.toURL()).build();
    CacheResponse cacheResponse = new CacheResponse() {
      @Override public Map<String, List<String>> getHeaders() throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put(null, Collections.singletonList(statusLine));
        headers.put("xyzzy", Arrays.asList("bar", "baz"));
        return headers;
      }

      @Override public InputStream getBody() throws IOException {
        return new ByteArrayInputStream("HelloWorld".getBytes(StandardCharsets.UTF_8));
      }
    };

    Response response = JavaApiConverter.createOkResponse(request, cacheResponse);
    assertSame(request, response.request());

    assertEquals(Protocol.HTTP_1_1, response.protocol());
    assertEquals(200, response.code());
    assertEquals("Fantastic", response.message());
    Headers okResponseHeaders = response.headers();
    assertEquals("baz", okResponseHeaders.get("xyzzy"));
    assertEquals("HelloWorld", response.body().string());
    assertNull(response.handshake());
  }

  @Test public void createOkResponse_fromSecureCacheResponse() throws Exception {
    final String statusLine = "HTTP/1.1 200 Fantastic";
    final Principal localPrincipal = LOCAL_CERT.getSubjectX500Principal();
    final List<Certificate> localCertificates = Arrays.<Certificate>asList(LOCAL_CERT);
    final Principal serverPrincipal = SERVER_CERT.getSubjectX500Principal();
    final List<Certificate> serverCertificates = Arrays.<Certificate>asList(SERVER_CERT);
    URI uri = new URI("https://foo/bar");
    Request request = new Request.Builder().url(uri.toURL()).build();
    SecureCacheResponse cacheResponse = new SecureCacheResponse() {
      @Override public Map<String, List<String>> getHeaders() throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put(null, Collections.singletonList(statusLine));
        headers.put("xyzzy", Arrays.asList("bar", "baz"));
        return headers;
      }

      @Override public InputStream getBody() throws IOException {
        return new ByteArrayInputStream("HelloWorld".getBytes(StandardCharsets.UTF_8));
      }

      @Override public String getCipherSuite() {
        return "SuperSecure";
      }

      @Override public List<Certificate> getLocalCertificateChain() {
        return localCertificates;
      }

      @Override public List<Certificate> getServerCertificateChain() throws SSLPeerUnverifiedException {
        return serverCertificates;
      }

      @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        return serverPrincipal;
      }

      @Override public Principal getLocalPrincipal() {
        return localPrincipal;
      }
    };

    Response response = JavaApiConverter.createOkResponse(request, cacheResponse);
    assertSame(request, response.request());

    assertEquals(Protocol.HTTP_1_1, response.protocol());
    assertEquals(200, response.code());
    assertEquals("Fantastic", response.message());
    Headers okResponseHeaders = response.headers();
    assertEquals("baz", okResponseHeaders.get("xyzzy"));
    assertEquals("HelloWorld", response.body().string());

    Handshake handshake = response.handshake();
    assertNotNull(handshake);
    assertNotNullAndEquals("SuperSecure", handshake.cipherSuite());
    assertEquals(localPrincipal, handshake.localPrincipal());
    assertEquals(serverPrincipal, handshake.peerPrincipal());
    assertEquals(serverCertificates, handshake.peerCertificates());
    assertEquals(localCertificates, handshake.localCertificates());
  }

  @Test public void createOkRequest_nullRequestHeaders() throws Exception {
    URI uri = new URI("http://foo/bar");

    Map<String,List<String>> javaRequestHeaders = null;
    Request request = JavaApiConverter.createOkRequest(uri, "POST", javaRequestHeaders);
    assertFalse(request.isHttps());
    assertEquals(uri, request.uri());
    Headers okRequestHeaders = request.headers();
    assertEquals(0, okRequestHeaders.size());
    assertEquals("POST", request.method());
  }

  @Test public void createOkRequest_nonNullRequestHeaders() throws Exception {
    URI uri = new URI("https://foo/bar");

    Map<String,List<String>> javaRequestHeaders = new HashMap<>();
    javaRequestHeaders.put("Foo", Arrays.asList("Bar"));
    Request request = JavaApiConverter.createOkRequest(uri, "POST", javaRequestHeaders);
    assertTrue(request.isHttps());
    assertEquals(uri, request.uri());
    Headers okRequestHeaders = request.headers();
    assertEquals(1, okRequestHeaders.size());
    assertEquals("Bar", okRequestHeaders.get("Foo"));
    assertEquals("POST", request.method());
  }

  // Older versions of OkHttp would store the "request line" as a header with a
  // null key. To support the Android usecase where an old version of OkHttp uses
  // a newer, Android-bundled, version of HttpResponseCache the null key must be
  // explicitly ignored.
  @Test public void createOkRequest_nullRequestHeaderKey() throws Exception {
    URI uri = new URI("https://foo/bar");

    Map<String,List<String>> javaRequestHeaders = new HashMap<>();
    javaRequestHeaders.put(null, Arrays.asList("GET / HTTP 1.1"));
    javaRequestHeaders.put("Foo", Arrays.asList("Bar"));
    Request request = JavaApiConverter.createOkRequest(uri, "POST", javaRequestHeaders);
    assertTrue(request.isHttps());
    assertEquals(uri, request.uri());
    Headers okRequestHeaders = request.headers();
    assertEquals(1, okRequestHeaders.size());
    assertEquals("Bar", okRequestHeaders.get("Foo"));
    assertEquals("POST", request.method());
  }

  @Test public void createJavaUrlConnection_requestChangesForbidden() throws Exception {
    Response okResponse = createArbitraryOkResponse();
    HttpURLConnection httpUrlConnection = JavaApiConverter.createJavaUrlConnection(okResponse);
    // Check an arbitrary (not complete) set of methods that can be used to modify the
    // request.
    try {
      httpUrlConnection.setRequestProperty("key", "value");
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.setFixedLengthStreamingMode(1234);
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.setRequestMethod("PUT");
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.getHeaderFields().put("key", Collections.singletonList("value"));
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.getOutputStream();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  @Test public void createJavaUrlConnection_connectionChangesForbidden() throws Exception {
    Response okResponse = createArbitraryOkResponse();
    HttpURLConnection httpUrlConnection = JavaApiConverter.createJavaUrlConnection(okResponse);
    try {
      httpUrlConnection.connect();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.disconnect();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  @Test public void createJavaUrlConnection_responseChangesForbidden() throws Exception {
    Response okResponse = createArbitraryOkResponse();
    HttpURLConnection httpUrlConnection = JavaApiConverter.createJavaUrlConnection(okResponse);
    // Check an arbitrary (not complete) set of methods that can be used to access the response
    // body.
    try {
      httpUrlConnection.getInputStream();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.getContent();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.setFixedLengthStreamingMode(1234);
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.setRequestMethod("PUT");
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpUrlConnection.getHeaderFields().put("key", Collections.singletonList("value"));
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  @Test public void createJavaUrlConnection_responseHeadersOk() throws Exception {
    ResponseBody responseBody = createResponseBody("BodyText");
    Response okResponse = new Response.Builder()
        .request(createArbitraryOkRequest())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Fantastic")
        .addHeader("A", "c")
        .addHeader("B", "d")
        .addHeader("A", "e")
        .addHeader("Content-Length", Long.toString(responseBody.contentLength()))
        .body(responseBody)
        .build();

    HttpURLConnection httpUrlConnection = JavaApiConverter.createJavaUrlConnection(okResponse);
    assertEquals(200, httpUrlConnection.getResponseCode());
    assertEquals("Fantastic", httpUrlConnection.getResponseMessage());
    assertEquals(responseBody.contentLength(), httpUrlConnection.getContentLength());

    // Check retrieval by string key.
    assertEquals("HTTP/1.1 200 Fantastic", httpUrlConnection.getHeaderField(null));
    assertEquals("e", httpUrlConnection.getHeaderField("A"));
    // The RI and OkHttp supports case-insensitive matching for this method.
    assertEquals("e", httpUrlConnection.getHeaderField("a"));

    // Check retrieval using a Map.
    Map<String, List<String>> responseHeaders = httpUrlConnection.getHeaderFields();
    assertEquals(Arrays.asList("HTTP/1.1 200 Fantastic"), responseHeaders.get(null));
    assertEquals(newSet("c", "e"), newSet(responseHeaders.get("A")));
    // OkHttp supports case-insensitive matching here. The RI does not.
    assertEquals(newSet("c", "e"), newSet(responseHeaders.get("a")));

    // Check the Map iterator contains the expected mappings.
    assertHeadersContainsMapping(responseHeaders, null, "HTTP/1.1 200 Fantastic");
    assertHeadersContainsMapping(responseHeaders, "A", "c", "e");
    assertHeadersContainsMapping(responseHeaders, "B", "d");

    // Check immutability of the headers Map.
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

    // Check retrieval of headers by index.
    assertEquals(null, httpUrlConnection.getHeaderFieldKey(0));
    assertEquals("HTTP/1.1 200 Fantastic", httpUrlConnection.getHeaderField(0));
    // After header zero there may be additional entries provided at the beginning or end by the
    // implementation. It's probably important that the relative ordering of the headers is
    // preserved, particularly if there are multiple value for the same key.
    int i = 1;
    while (!httpUrlConnection.getHeaderFieldKey(i).equals("A")) {
      i++;
    }
    // Check the ordering of the headers set by app code.
    assertResponseHeaderAtIndex(httpUrlConnection, i++, "A", "c");
    assertResponseHeaderAtIndex(httpUrlConnection, i++, "B", "d");
    assertResponseHeaderAtIndex(httpUrlConnection, i++, "A", "e");
    // There may be some additional headers provided by the implementation.
    while (httpUrlConnection.getHeaderField(i) != null) {
      assertNotNull(httpUrlConnection.getHeaderFieldKey(i));
      i++;
    }
    // Confirm the correct behavior when the index is out-of-range.
    assertNull(httpUrlConnection.getHeaderFieldKey(i));
  }

  private static void assertResponseHeaderAtIndex(HttpURLConnection httpUrlConnection,
      int headerIndex, String expectedKey, String expectedValue) {
    assertEquals(expectedKey, httpUrlConnection.getHeaderFieldKey(headerIndex));
    assertEquals(expectedValue, httpUrlConnection.getHeaderField(headerIndex));
  }

  private void assertHeadersContainsMapping(Map<String, List<String>> headers, String expectedKey,
      String... expectedValues) {
    assertTrue(headers.containsKey(expectedKey));
    assertEquals(newSet(expectedValues), newSet(headers.get(expectedKey)));
  }

  @Test public void createJavaUrlConnection_accessibleRequestInfo_GET() throws Exception {
    Request okRequest = createArbitraryOkRequest().newBuilder()
        .get()
        .build();
    Response okResponse = createArbitraryOkResponse(okRequest);
    HttpURLConnection httpUrlConnection = JavaApiConverter.createJavaUrlConnection(okResponse);

    assertEquals("GET", httpUrlConnection.getRequestMethod());
    assertTrue(httpUrlConnection.getDoInput());
    assertFalse(httpUrlConnection.getDoOutput());
  }

  @Test public void createJavaUrlConnection_accessibleRequestInfo_POST() throws Exception {
    Request okRequest = createArbitraryOkRequest().newBuilder()
        .post(createRequestBody("PostBody"))
        .build();
    Response okResponse = createArbitraryOkResponse(okRequest);
    HttpURLConnection httpUrlConnection = JavaApiConverter.createJavaUrlConnection(okResponse);

    assertEquals("POST", httpUrlConnection.getRequestMethod());
    assertTrue(httpUrlConnection.getDoInput());
    assertTrue(httpUrlConnection.getDoOutput());
  }

  @Test public void createJavaUrlConnection_https_extraHttpsMethods() throws Exception {
    Request okRequest = createArbitraryOkRequest().newBuilder()
        .get()
        .url("https://secure/request")
        .build();
    Handshake handshake = Handshake.get("SecureCipher", Arrays.<Certificate>asList(SERVER_CERT),
        Arrays.<Certificate>asList(LOCAL_CERT));
    Response okResponse = createArbitraryOkResponse(okRequest).newBuilder()
        .handshake(handshake)
        .build();
    HttpsURLConnection httpsUrlConnection =
        (HttpsURLConnection) JavaApiConverter.createJavaUrlConnection(okResponse);

    assertEquals("SecureCipher", httpsUrlConnection.getCipherSuite());
    assertEquals(SERVER_CERT.getSubjectX500Principal(), httpsUrlConnection.getPeerPrincipal());
    assertArrayEquals(new Certificate[] { LOCAL_CERT }, httpsUrlConnection.getLocalCertificates());
    assertArrayEquals(new Certificate[] { SERVER_CERT },
        httpsUrlConnection.getServerCertificates());
    assertEquals(LOCAL_CERT.getSubjectX500Principal(), httpsUrlConnection.getLocalPrincipal());
  }

  @Test public void createJavaUrlConnection_https_forbiddenFields() throws Exception {
    Request okRequest = createArbitraryOkRequest().newBuilder()
        .url("https://secure/request")
        .build();
    Response okResponse = createArbitraryOkResponse(okRequest);
    HttpsURLConnection httpsUrlConnection =
        (HttpsURLConnection) JavaApiConverter.createJavaUrlConnection(okResponse);

    try {
      httpsUrlConnection.getHostnameVerifier();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
    try {
      httpsUrlConnection.getSSLSocketFactory();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  @Test public void createJavaCacheResponse_httpGet() throws Exception {
    Request okRequest =
        createArbitraryOkRequest().newBuilder()
            .url("http://insecure/request")
            .get()
            .build();
    Response okResponse = createArbitraryOkResponse(okRequest).newBuilder()
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Fantastic")
        .addHeader("key1", "value1_1")
        .addHeader("key2", "value2")
        .addHeader("key1", "value1_2")
        .body(null)
        .build();
    CacheResponse javaCacheResponse = JavaApiConverter.createJavaCacheResponse(okResponse);
    assertFalse(javaCacheResponse instanceof SecureCacheResponse);
    Map<String, List<String>> javaHeaders = javaCacheResponse.getHeaders();
    assertEquals(Arrays.asList("value1_1", "value1_2"), javaHeaders.get("key1"));
    assertEquals(Arrays.asList("HTTP/1.1 200 Fantastic"), javaHeaders.get(null));
    assertNull(javaCacheResponse.getBody());
  }

  @Test public void createJavaCacheResponse_httpPost() throws Exception {
    Request okRequest =
        createArbitraryOkRequest().newBuilder()
            .url("http://insecure/request")
            .post(createRequestBody("RequestBody"))
            .build();
    ResponseBody responseBody = createResponseBody("ResponseBody");
    Response okResponse = createArbitraryOkResponse(okRequest).newBuilder()
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Fantastic")
        .addHeader("key1", "value1_1")
        .addHeader("key2", "value2")
        .addHeader("key1", "value1_2")
        .body(responseBody)
        .build();
    CacheResponse javaCacheResponse = JavaApiConverter.createJavaCacheResponse(okResponse);
    assertFalse(javaCacheResponse instanceof SecureCacheResponse);
    Map<String, List<String>> javaHeaders = javaCacheResponse.getHeaders();
    assertEquals(Arrays.asList("value1_1", "value1_2"), javaHeaders.get("key1"));
    assertEquals(Arrays.asList("HTTP/1.1 200 Fantastic"), javaHeaders.get(null));
    assertEquals("ResponseBody", readAll(javaCacheResponse.getBody()));
  }

  @Test public void createJavaCacheResponse_httpsPost() throws Exception {
    Request okRequest =
        createArbitraryOkRequest().newBuilder()
            .url("https://secure/request")
            .post(createRequestBody("RequestBody") )
            .build();
    ResponseBody responseBody = createResponseBody("ResponseBody");
    Handshake handshake = Handshake.get("SecureCipher", Arrays.<Certificate>asList(SERVER_CERT),
        Arrays.<Certificate>asList(LOCAL_CERT));
    Response okResponse = createArbitraryOkResponse(okRequest).newBuilder()
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Fantastic")
        .addHeader("key1", "value1_1")
        .addHeader("key2", "value2")
        .addHeader("key1", "value1_2")
        .body(responseBody)
        .handshake(handshake)
        .build();
    SecureCacheResponse javaCacheResponse =
        (SecureCacheResponse) JavaApiConverter.createJavaCacheResponse(okResponse);
    Map<String, List<String>> javaHeaders = javaCacheResponse.getHeaders();
    assertEquals(Arrays.asList("value1_1", "value1_2"), javaHeaders.get("key1"));
    assertEquals(Arrays.asList("HTTP/1.1 200 Fantastic"), javaHeaders.get(null));
    assertEquals("ResponseBody", readAll(javaCacheResponse.getBody()));
    assertEquals(handshake.cipherSuite(), javaCacheResponse.getCipherSuite());
    assertEquals(handshake.localCertificates(), javaCacheResponse.getLocalCertificateChain());
    assertEquals(handshake.peerCertificates(), javaCacheResponse.getServerCertificateChain());
    assertEquals(handshake.localPrincipal(), javaCacheResponse.getLocalPrincipal());
    assertEquals(handshake.peerPrincipal(), javaCacheResponse.getPeerPrincipal());
  }

  @Test public void extractJavaHeaders() throws Exception {
    Request okRequest = createArbitraryOkRequest().newBuilder()
        .addHeader("key1", "value1_1")
        .addHeader("key2", "value2")
        .addHeader("key1", "value1_2")
        .build();
    Map<String, List<String>> javaHeaders = JavaApiConverter.extractJavaHeaders(okRequest);

    assertEquals(Arrays.asList("value1_1", "value1_2"), javaHeaders.get("key1"));
    assertEquals(Arrays.asList("value2"), javaHeaders.get("key2"));
  }

  @Test public void extractOkHeaders() {
    Map<String, List<String>> javaResponseHeaders = new HashMap<>();
    javaResponseHeaders.put(null, Arrays.asList("StatusLine"));
    javaResponseHeaders.put("key1", Arrays.asList("value1_1", "value1_2"));
    javaResponseHeaders.put("key2", Arrays.asList("value2"));

    Headers okHeaders = JavaApiConverter.extractOkHeaders(javaResponseHeaders);
    assertEquals(3, okHeaders.size()); // null entry should be stripped out
    assertEquals(Arrays.asList("value1_1", "value1_2"), okHeaders.values("key1"));
    assertEquals(Arrays.asList("value2"), okHeaders.values("key2"));
  }

  @Test public void extractStatusLine() {
    Map<String, List<String>> javaResponseHeaders = new HashMap<>();
    javaResponseHeaders.put(null, Arrays.asList("StatusLine"));
    javaResponseHeaders.put("key1", Arrays.asList("value1_1", "value1_2"));
    javaResponseHeaders.put("key2", Arrays.asList("value2"));
    assertEquals("StatusLine", JavaApiConverter.extractStatusLine(javaResponseHeaders));

    assertNull(JavaApiConverter.extractStatusLine(Collections.<String, List<String>>emptyMap()));
  }

  private URL configureServer(MockResponse mockResponse) throws Exception {
    server.enqueue(mockResponse);
    return server.getUrl("/");
  }

  private URL configureHttpsServer(MockResponse mockResponse) throws Exception {
    server.useHttps(sslContext.getSocketFactory(), false /* tunnelProxy */);
    server.enqueue(mockResponse);
    return server.getUrl("/");
  }

  private static <T> void assertNotNullAndEquals(T expected, T actual) {
    assertNotNull(actual);
    assertEquals(expected, actual);
  }

  private interface HttpURLConnectionFactory {
    public HttpURLConnection open(URL serverUrl) throws IOException;
  }

  private static class OkHttpURLConnectionFactory implements HttpURLConnectionFactory {
    protected final OkHttpClient client;

    private OkHttpURLConnectionFactory(OkHttpClient client) {
      this.client = client;
    }

    @Override public HttpURLConnection open(URL serverUrl) {
      return new OkUrlFactory(client).open(serverUrl);
    }
  }

  private static class JavaHttpURLConnectionFactory implements HttpURLConnectionFactory {
    @Override public HttpURLConnection open(URL serverUrl) throws IOException {
      return (HttpURLConnection) serverUrl.openConnection();
    }
  }

  private static X509Certificate certificate(String certificate) {
    try {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
          new ByteArrayInputStream(certificate.getBytes(Util.UTF_8)));
    } catch (CertificateException e) {
      fail();
      return null;
    }
  }

  private static <T> Set<T> newSet(T... elements) {
    return newSet(Arrays.asList(elements));
  }

  private static <T> Set<T> newSet(List<T> elements) {
    return new LinkedHashSet<>(elements);
  }

  private static Request createArbitraryOkRequest() {
    return new Request.Builder().url("http://arbitrary/url").build();
  }

  private static Response createArbitraryOkResponse(Request request) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Arbitrary")
        .build();
  }

  private static Response createArbitraryOkResponse() {
    return createArbitraryOkResponse(createArbitraryOkRequest());
  }

  private static RequestBody createRequestBody(String bodyText) {
    return RequestBody.create(MediaType.parse("text/plain"), bodyText);
  }

  private static ResponseBody createResponseBody(String bodyText) {
    final Buffer source = new Buffer().writeUtf8(bodyText);
    final long contentLength = source.size();
    return new ResponseBody() {
      @Override public MediaType contentType() {
        return MediaType.parse("text/plain; charset=utf-8");
      }

      @Override public long contentLength() {
        return contentLength;
      }

      @Override public BufferedSource source() {
        return source;
      }
    };
  }

  private String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int value;
    while ((value = in.read()) != -1) {
      buffer.write(value);
    }
    in.close();
    return buffer.toString("UTF-8");
  }
}
