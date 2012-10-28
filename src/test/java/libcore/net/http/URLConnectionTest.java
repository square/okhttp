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

package libcore.net.http;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.RecordedRequest;
import com.google.mockwebserver.SocketPolicy;
import com.squareup.okhttp.OkHttpConnection;
import com.squareup.okhttp.OkHttpsConnection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ResponseCache;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import junit.framework.TestCase;
import libcore.net.ssl.SslContextBuilder;

import static com.google.mockwebserver.SocketPolicy.DISCONNECT_AT_END;
import static com.google.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static com.google.mockwebserver.SocketPolicy.SHUTDOWN_INPUT_AT_END;
import static com.google.mockwebserver.SocketPolicy.SHUTDOWN_OUTPUT_AT_END;

/**
 * Android's URLConnectionTest.
 */
public final class URLConnectionTest extends TestCase {
    /** base64("username:password") */
    private static final String BASE_64_CREDENTIALS = "dXNlcm5hbWU6cGFzc3dvcmQ=";

    private MockWebServer server = new MockWebServer();
    private HttpResponseCache cache;
    private String hostName;

    private static final SSLContext sslContext;

    static {
        try {
            sslContext = new SslContextBuilder(InetAddress.getLocalHost().getHostName())
                    .build();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        hostName = server.getHostName();
    }

    @Override protected void tearDown() throws Exception {
        ResponseCache.setDefault(null);
        Authenticator.setDefault(null);
        System.clearProperty("proxyHost");
        System.clearProperty("proxyPort");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        server.shutdown();
        if (cache != null) {
            cache.getCache().delete();
        }
        super.tearDown();
    }
    
    private static OkHttpConnection openConnection(URL url) {
        return OkHttpConnection.open(url);
    }

    private static OkHttpConnection openConnection(URL url, Proxy proxy) {
        return OkHttpConnection.open(url, proxy);
    }

    public void testRequestHeaders() throws IOException, InterruptedException {
        server.enqueue(new MockResponse());
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        urlConnection.addRequestProperty("D", "e");
        urlConnection.addRequestProperty("D", "f");
        assertEquals("f", urlConnection.getRequestProperty("D"));
        assertEquals("f", urlConnection.getRequestProperty("d"));
        Map<String, List<String>> requestHeaders = urlConnection.getRequestProperties();
        assertEquals(newSet("e", "f"), new HashSet<String>(requestHeaders.get("D")));
        assertEquals(newSet("e", "f"), new HashSet<String>(requestHeaders.get("d")));
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
            urlConnection.setRequestProperty(null, "j");
            fail();
        } catch (NullPointerException expected) {
        }
        try {
            urlConnection.addRequestProperty(null, "k");
            fail();
        } catch (NullPointerException expected) {
        }
        urlConnection.setRequestProperty("NullValue", null); // should fail silently!
        assertNull(urlConnection.getRequestProperty("NullValue"));
        urlConnection.addRequestProperty("AnotherNullValue", null);  // should fail silently!
        assertNull(urlConnection.getRequestProperty("AnotherNullValue"));

        urlConnection.getResponseCode();
        RecordedRequest request = server.takeRequest();
        assertContains(request.getHeaders(), "D: e");
        assertContains(request.getHeaders(), "D: f");
        assertContainsNoneMatching(request.getHeaders(), "NullValue.*");
        assertContainsNoneMatching(request.getHeaders(), "AnotherNullValue.*");
        assertContainsNoneMatching(request.getHeaders(), "G:.*");
        assertContainsNoneMatching(request.getHeaders(), "null:.*");

        try {
            urlConnection.addRequestProperty("N", "o");
            fail("Set header after connect");
        } catch (IllegalStateException expected) {
        }
        try {
            urlConnection.setRequestProperty("P", "q");
            fail("Set header after connect");
        } catch (IllegalStateException expected) {
        }
        try {
            urlConnection.getRequestProperties();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testGetRequestPropertyReturnsLastValue() throws Exception {
        server.play();
        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        urlConnection.addRequestProperty("A", "value1");
        urlConnection.addRequestProperty("A", "value2");
        assertEquals("value2", urlConnection.getRequestProperty("A"));
    }

    public void testResponseHeaders() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setStatus("HTTP/1.0 200 Fantastic")
                .addHeader("A: c")
                .addHeader("B: d")
                .addHeader("A: e")
                .setChunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8));
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        assertEquals(200, urlConnection.getResponseCode());
        assertEquals("Fantastic", urlConnection.getResponseMessage());
        assertEquals("HTTP/1.0 200 Fantastic", urlConnection.getHeaderField(null));
        Map<String, List<String>> responseHeaders = urlConnection.getHeaderFields();
        assertEquals(Arrays.asList("HTTP/1.0 200 Fantastic"), responseHeaders.get(null));
        assertEquals(newSet("c", "e"), new HashSet<String>(responseHeaders.get("A")));
        assertEquals(newSet("c", "e"), new HashSet<String>(responseHeaders.get("a")));
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
        assertEquals("A", urlConnection.getHeaderFieldKey(0));
        assertEquals("c", urlConnection.getHeaderField(0));
        assertEquals("B", urlConnection.getHeaderFieldKey(1));
        assertEquals("d", urlConnection.getHeaderField(1));
        assertEquals("A", urlConnection.getHeaderFieldKey(2));
        assertEquals("e", urlConnection.getHeaderField(2));
    }

    public void testServerSendsInvalidResponseHeaders() throws Exception {
        server.enqueue(new MockResponse().setStatus("HTP/1.1 200 OK"));
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        try {
            urlConnection.getResponseCode();
            fail();
        } catch (IOException expected) {
        }
    }

    public void testServerSendsInvalidCodeTooLarge() throws Exception {
        server.enqueue(new MockResponse().setStatus("HTTP/1.1 2147483648 OK"));
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        try {
            urlConnection.getResponseCode();
            fail();
        } catch (IOException expected) {
        }
    }

    public void testServerSendsInvalidCodeNotANumber() throws Exception {
        server.enqueue(new MockResponse().setStatus("HTTP/1.1 00a OK"));
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        try {
            urlConnection.getResponseCode();
            fail();
        } catch (IOException expected) {
        }
    }

    public void testServerSendsUnnecessaryWhitespace() throws Exception {
        server.enqueue(new MockResponse().setStatus(" HTTP/1.1 2147483648 OK"));
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        try {
            urlConnection.getResponseCode();
            fail();
        } catch (IOException expected) {
        }
    }

    public void testGetErrorStreamOnSuccessfulRequest() throws Exception {
        server.enqueue(new MockResponse().setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertNull(connection.getErrorStream());
    }

    public void testGetErrorStreamOnUnsuccessfulRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("A", readAscii(connection.getErrorStream(), Integer.MAX_VALUE));
    }

    // Check that if we don't read to the end of a response, the next request on the
    // recycled connection doesn't get the unread tail of the first request's response.
    // http://code.google.com/p/android/issues/detail?id=2939
    public void test_2939() throws Exception {
        MockResponse response = new MockResponse().setChunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8);

        server.enqueue(response);
        server.enqueue(response);
        server.play();

        assertContent("ABCDE", openConnection(server.getUrl("/")), 5);
        assertContent("ABCDE", openConnection(server.getUrl("/")), 5);
    }

    // Check that we recognize a few basic mime types by extension.
    // http://code.google.com/p/android/issues/detail?id=10100
    public void test_10100() throws Exception {
        assertEquals("image/jpeg", URLConnection.guessContentTypeFromName("someFile.jpg"));
        assertEquals("application/pdf", URLConnection.guessContentTypeFromName("stuff.pdf"));
    }

    public void testConnectionsArePooled() throws Exception {
        MockResponse response = new MockResponse().setBody("ABCDEFGHIJKLMNOPQR");

        server.enqueue(response);
        server.enqueue(response);
        server.enqueue(response);
        server.play();

        assertContent("ABCDEFGHIJKLMNOPQR", openConnection(server.getUrl("/foo")));
        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertContent("ABCDEFGHIJKLMNOPQR", openConnection(server.getUrl("/bar?baz=quux")));
        assertEquals(1, server.takeRequest().getSequenceNumber());
        assertContent("ABCDEFGHIJKLMNOPQR", openConnection(server.getUrl("/z")));
        assertEquals(2, server.takeRequest().getSequenceNumber());
    }

    public void testChunkedConnectionsArePooled() throws Exception {
        MockResponse response = new MockResponse().setChunkedBody("ABCDEFGHIJKLMNOPQR", 5);

        server.enqueue(response);
        server.enqueue(response);
        server.enqueue(response);
        server.play();

        assertContent("ABCDEFGHIJKLMNOPQR", openConnection(server.getUrl("/foo")));
        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertContent("ABCDEFGHIJKLMNOPQR", openConnection(server.getUrl("/bar?baz=quux")));
        assertEquals(1, server.takeRequest().getSequenceNumber());
        assertContent("ABCDEFGHIJKLMNOPQR", openConnection(server.getUrl("/z")));
        assertEquals(2, server.takeRequest().getSequenceNumber());
    }

    public void testServerClosesSocket() throws Exception {
        testServerClosesOutput(DISCONNECT_AT_END);
    }

    public void testServerShutdownInput() throws Exception {
        testServerClosesOutput(SHUTDOWN_INPUT_AT_END);
    }

    public void SUPPRESSED_testServerShutdownOutput() throws Exception {
        testServerClosesOutput(SHUTDOWN_OUTPUT_AT_END);
    }

    private void testServerClosesOutput(SocketPolicy socketPolicy) throws Exception {
        server.enqueue(new MockResponse()
                .setBody("This connection won't pool properly")
                .setSocketPolicy(socketPolicy));
        server.enqueue(new MockResponse()
                .setBody("This comes after a busted connection"));
        server.play();

        assertContent("This connection won't pool properly", openConnection(server.getUrl("/a")));
        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertContent("This comes after a busted connection", openConnection(server.getUrl("/b")));
        // sequence number 0 means the HTTP socket connection was not reused
        assertEquals(0, server.takeRequest().getSequenceNumber());
    }

    enum WriteKind { BYTE_BY_BYTE, SMALL_BUFFERS, LARGE_BUFFERS }

    public void test_chunkedUpload_byteByByte() throws Exception {
        doUpload(TransferKind.CHUNKED, WriteKind.BYTE_BY_BYTE);
    }

    public void test_chunkedUpload_smallBuffers() throws Exception {
        doUpload(TransferKind.CHUNKED, WriteKind.SMALL_BUFFERS);
    }

    public void test_chunkedUpload_largeBuffers() throws Exception {
        doUpload(TransferKind.CHUNKED, WriteKind.LARGE_BUFFERS);
    }

    public void SUPPRESSED_test_fixedLengthUpload_byteByByte() throws Exception {
        doUpload(TransferKind.FIXED_LENGTH, WriteKind.BYTE_BY_BYTE);
    }

    public void test_fixedLengthUpload_smallBuffers() throws Exception {
        doUpload(TransferKind.FIXED_LENGTH, WriteKind.SMALL_BUFFERS);
    }

    public void test_fixedLengthUpload_largeBuffers() throws Exception {
        doUpload(TransferKind.FIXED_LENGTH, WriteKind.LARGE_BUFFERS);
    }

    private void doUpload(TransferKind uploadKind, WriteKind writeKind) throws Exception {
        int n = 512*1024;
        server.setBodyLimit(0);
        server.enqueue(new MockResponse());
        server.play();

        OkHttpConnection conn = openConnection(server.getUrl("/"));
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
            byte[] buf = new byte[writeKind == WriteKind.SMALL_BUFFERS ? 256 : 64*1024];
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

    public void testGetResponseCodeNoResponseBody() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("abc: def"));
        server.play();

        URL url = server.getUrl("/");
        OkHttpConnection conn = openConnection(url);
        conn.setDoInput(false);
        assertEquals("def", conn.getHeaderField("abc"));
        assertEquals(200, conn.getResponseCode());
        try {
            conn.getInputStream();
            fail();
        } catch (ProtocolException expected) {
        }
    }

    public void testConnectViaHttps() throws Exception {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));
        server.play();

        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/foo"));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());

        assertContent("this response comes via HTTPS", connection);

        RecordedRequest request = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    }

    public void testConnectViaHttpsReusingConnections() throws IOException, InterruptedException {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));
        server.enqueue(new MockResponse().setBody("another response via HTTPS"));
        server.play();

        // The pool will only reuse sockets if the SSL socket factories are the same.
        SSLSocketFactory clientSocketFactory = sslContext.getSocketFactory();

        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        connection.setSSLSocketFactory(clientSocketFactory);
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        assertContent("this response comes via HTTPS", connection);

        connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        connection.setSSLSocketFactory(clientSocketFactory);
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        assertContent("another response via HTTPS", connection);

        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertEquals(1, server.takeRequest().getSequenceNumber());
    }

    public void testConnectViaHttpsReusingConnectionsDifferentFactories()
            throws IOException, InterruptedException {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));
        server.enqueue(new MockResponse().setBody("another response via HTTPS"));
        server.play();

        // install a custom SSL socket factory so the server can be authorized
        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        assertContent("this response comes via HTTPS", connection);

        connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        try {
            readAscii(connection.getInputStream(), Integer.MAX_VALUE);
            fail("without an SSL socket factory, the connection should fail");
        } catch (SSLException expected) {
        }
    }

    public void testConnectViaHttpsWithSSLFallback() throws IOException, InterruptedException {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setBody("this response comes via SSL"));
        server.play();

        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/foo"));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());

        assertContent("this response comes via SSL", connection);

        RecordedRequest request = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    }

    /**
     * Verify that we don't retry connections on certificate verification errors.
     *
     * http://code.google.com/p/android/issues/detail?id=13178
     */
//    public void testConnectViaHttpsToUntrustedServer() throws IOException, InterruptedException {
//        TestSSLContext testSSLContext = TestSSLContext.create(TestKeyStore.getClientCA2(),
//                                                              TestKeyStore.getServer());
//
//        server.useHttps(testSSLContext.serverContext.getSocketFactory(), false);
//        server.enqueue(new MockResponse()); // unused
//        server.play();
//
//        HttpsURLConnection connection = (HttpsURLConnection) server.getUrl("/foo").openConnection();
//        connection.setSSLSocketFactory(testSSLContext.clientContext.getSocketFactory());
//        try {
//            connection.getInputStream();
//            fail();
//        } catch (SSLHandshakeException expected) {
//            assertTrue(expected.getCause() instanceof CertificateException);
//        }
//        assertEquals(0, server.getRequestCount());
//    }

    public void testConnectViaProxyUsingProxyArg() throws Exception {
        testConnectViaProxy(ProxyConfig.CREATE_ARG);
    }

    public void testConnectViaProxyUsingProxySystemProperty() throws Exception {
        testConnectViaProxy(ProxyConfig.PROXY_SYSTEM_PROPERTY);
    }

    public void testConnectViaProxyUsingHttpProxySystemProperty() throws Exception {
        testConnectViaProxy(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY);
    }

    private void testConnectViaProxy(ProxyConfig proxyConfig) throws Exception {
        MockResponse mockResponse = new MockResponse().setBody("this response comes via a proxy");
        server.enqueue(mockResponse);
        server.play();

        URL url = new URL("http://android.com/foo");
        OkHttpConnection connection = proxyConfig.connect(server, url);
        assertContent("this response comes via a proxy", connection);

        RecordedRequest request = server.takeRequest();
        assertEquals("GET http://android.com/foo HTTP/1.1", request.getRequestLine());
        assertContains(request.getHeaders(), "Host: android.com");
    }

    public void testContentDisagreesWithContentLengthHeader() throws IOException {
        server.enqueue(new MockResponse()
                .setBody("abc\r\nYOU SHOULD NOT SEE THIS")
                .clearHeaders()
                .addHeader("Content-Length: 3"));
        server.play();

        assertContent("abc", openConnection(server.getUrl("/")));
    }

    public void testContentDisagreesWithChunkedHeader() throws IOException {
        MockResponse mockResponse = new MockResponse();
        mockResponse.setChunkedBody("abc", 3);
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        bytesOut.write(mockResponse.getBody());
        bytesOut.write("\r\nYOU SHOULD NOT SEE THIS".getBytes("UTF-8"));
        mockResponse.setBody(bytesOut.toByteArray());
        mockResponse.clearHeaders();
        mockResponse.addHeader("Transfer-encoding: chunked");

        server.enqueue(mockResponse);
        server.play();

        assertContent("abc", openConnection(server.getUrl("/")));
    }

    public void testConnectViaHttpProxyToHttpsUsingProxyArgWithNoProxy() throws Exception {
        testConnectViaDirectProxyToHttps(ProxyConfig.NO_PROXY);
    }

    public void testConnectViaHttpProxyToHttpsUsingHttpProxySystemProperty() throws Exception {
        // https should not use http proxy
        testConnectViaDirectProxyToHttps(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY);
    }

    private void testConnectViaDirectProxyToHttps(ProxyConfig proxyConfig) throws Exception {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("this response comes via HTTPS"));
        server.play();

        URL url = server.getUrl("/foo");
        OkHttpsConnection connection = (OkHttpsConnection) proxyConfig.connect(server, url);
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());

        assertContent("this response comes via HTTPS", connection);

        RecordedRequest request = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
    }

    public void testConnectViaHttpProxyToHttpsUsingProxyArg() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.CREATE_ARG);
    }

    /**
     * We weren't honoring all of the appropriate proxy system properties when
     * connecting via HTTPS. http://b/3097518
     */
    public void testConnectViaHttpProxyToHttpsUsingProxySystemProperty() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.PROXY_SYSTEM_PROPERTY);
    }

    public void testConnectViaHttpProxyToHttpsUsingHttpsProxySystemProperty() throws Exception {
        testConnectViaHttpProxyToHttps(ProxyConfig.HTTPS_PROXY_SYSTEM_PROPERTY);
    }

    /**
     * We were verifying the wrong hostname when connecting to an HTTPS site
     * through a proxy. http://b/3097277
     */
    private void testConnectViaHttpProxyToHttps(ProxyConfig proxyConfig) throws Exception {
        RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

        server.useHttps(sslContext.getSocketFactory(), true);
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .clearHeaders());
        server.enqueue(new MockResponse().setBody("this response comes via a secure proxy"));
        server.play();

        URL url = new URL("https://android.com/foo");
        OkHttpsConnection connection = (OkHttpsConnection) proxyConfig.connect(server, url);
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(hostnameVerifier);

        assertContent("this response comes via a secure proxy", connection);

        RecordedRequest connect = server.takeRequest();
        assertEquals("Connect line failure on proxy",
                "CONNECT android.com:443 HTTP/1.1", connect.getRequestLine());
        assertContains(connect.getHeaders(), "Host: android.com");

        RecordedRequest get = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
        assertContains(get.getHeaders(), "Host: android.com");
        assertEquals(Arrays.asList("verify android.com"), hostnameVerifier.calls);
    }

    /**
     * Tolerate bad https proxy response when using HttpResponseCache. http://b/6754912
     */
    public void testConnectViaHttpProxyToHttpsUsingBadProxyAndHttpResponseCache() throws Exception {
        ProxyConfig proxyConfig = ProxyConfig.PROXY_SYSTEM_PROPERTY;

        initResponseCache();

        server.useHttps(sslContext.getSocketFactory(), true);
        MockResponse response = new MockResponse() // Key to reproducing b/6754912
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .setBody("bogus proxy connect response content");
        server.enqueue(response); // For the first TLS tolerant connection
        server.enqueue(response); // For the backwards-compatible SSLv3 retry
        server.play();

        URL url = new URL("https://android.com/foo");
        OkHttpsConnection connection = (OkHttpsConnection) proxyConfig.connect(server, url);
        connection.setSSLSocketFactory(sslContext.getSocketFactory());

        try {
            connection.connect();
            fail();
        } catch (IOException expected) {
            // Thrown when the connect causes SSLSocket.startHandshake() to throw
            // when it sees the "bogus proxy connect response content"
            // instead of a ServerHello handshake message.
        }

        RecordedRequest connect = server.takeRequest();
        assertEquals("Connect line failure on proxy",
                "CONNECT android.com:443 HTTP/1.1", connect.getRequestLine());
        assertContains(connect.getHeaders(), "Host: android.com");
    }

    private void initResponseCache() throws IOException {
        String tmp = System.getProperty("java.io.tmpdir");
        File cacheDir = new File(tmp, "HttpCache-" + UUID.randomUUID());
        cache = new HttpResponseCache(cacheDir, Integer.MAX_VALUE);
        ResponseCache.setDefault(cache);
    }

    /**
     * Test which headers are sent unencrypted to the HTTP proxy.
     */
    public void testProxyConnectIncludesProxyHeadersOnly()
            throws IOException, InterruptedException {
        RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

        server.useHttps(sslContext.getSocketFactory(), true);
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .clearHeaders());
        server.enqueue(new MockResponse().setBody("encrypted response from the origin server"));
        server.play();

        URL url = new URL("https://android.com/foo");
        OkHttpsConnection connection = (OkHttpsConnection) openConnection(
                url, server.toProxyAddress());
        connection.addRequestProperty("Private", "Secret");
        connection.addRequestProperty("Proxy-Authorization", "bar");
        connection.addRequestProperty("User-Agent", "baz");
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(hostnameVerifier);
        assertContent("encrypted response from the origin server", connection);

        RecordedRequest connect = server.takeRequest();
        assertContainsNoneMatching(connect.getHeaders(), "Private.*");
        assertContains(connect.getHeaders(), "Proxy-Authorization: bar");
        assertContains(connect.getHeaders(), "User-Agent: baz");
        assertContains(connect.getHeaders(), "Host: android.com");
        assertContains(connect.getHeaders(), "Proxy-Connection: Keep-Alive");

        RecordedRequest get = server.takeRequest();
        assertContains(get.getHeaders(), "Private: Secret");
        assertEquals(Arrays.asList("verify android.com"), hostnameVerifier.calls);
    }

    public void testProxyAuthenticateOnConnect() throws Exception {
        Authenticator.setDefault(new RecordingAuthenticator());
        server.useHttps(sslContext.getSocketFactory(), true);
        server.enqueue(new MockResponse()
                .setResponseCode(407)
                .addHeader("Proxy-Authenticate: Basic realm=\"localhost\""));
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .clearHeaders());
        server.enqueue(new MockResponse().setBody("A"));
        server.play();

        URL url = new URL("https://android.com/foo");
        OkHttpsConnection connection = (OkHttpsConnection) openConnection(
                url, server.toProxyAddress());
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        assertContent("A", connection);

        RecordedRequest connect1 = server.takeRequest();
        assertEquals("CONNECT android.com:443 HTTP/1.1", connect1.getRequestLine());
        assertContainsNoneMatching(connect1.getHeaders(), "Proxy\\-Authorization.*");

        RecordedRequest connect2 = server.takeRequest();
        assertEquals("CONNECT android.com:443 HTTP/1.1", connect2.getRequestLine());
        assertContains(connect2.getHeaders(), "Proxy-Authorization: Basic " + BASE_64_CREDENTIALS);

        RecordedRequest get = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", get.getRequestLine());
        assertContainsNoneMatching(get.getHeaders(), "Proxy\\-Authorization.*");
    }

    // Don't disconnect after building a tunnel with CONNECT
    // http://code.google.com/p/android/issues/detail?id=37221
    public void testProxyWithConnectionClose() throws IOException {
        server.useHttps(sslContext.getSocketFactory(), true);
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .clearHeaders());
        server.enqueue(new MockResponse().setBody("this response comes via a proxy"));
        server.play();

        URL url = new URL("https://android.com/foo");
        OkHttpsConnection connection = (OkHttpsConnection) openConnection(
                url, server.toProxyAddress());
        connection.setRequestProperty("Connection", "close");
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());

        assertContent("this response comes via a proxy", connection);
    }

    public void testProxyWithConnectionReuse() throws IOException {
        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();

        server.useHttps(socketFactory, true);
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
                .clearHeaders());
        server.enqueue(new MockResponse().setBody("response 1"));
        server.enqueue(new MockResponse().setBody("response 2"));
        server.play();

        URL url = new URL("https://android.com/foo");
        OkHttpsConnection connection1 = (OkHttpsConnection) openConnection(
                url, server.toProxyAddress());
        connection1.setSSLSocketFactory(socketFactory);
        connection1.setHostnameVerifier(hostnameVerifier);
        assertContent("response 1", connection1);

        OkHttpsConnection connection2 = (OkHttpsConnection) openConnection(
                url, server.toProxyAddress());
        connection2.setSSLSocketFactory(socketFactory);
        connection2.setHostnameVerifier(hostnameVerifier);
        assertContent("response 2", connection2);
    }

    public void testDisconnectedConnection() throws IOException {
        server.enqueue(new MockResponse().setBody("ABCDEFGHIJKLMNOPQR"));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/"));
        InputStream in = connection.getInputStream();
        assertEquals('A', (char) in.read());
        connection.disconnect();
        try {
            in.read();
            fail("Expected a connection closed exception");
        } catch (IOException expected) {
        }
    }

    public void testDisconnectBeforeConnect() throws IOException {
        server.enqueue(new MockResponse().setBody("A"));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.disconnect();

        assertContent("A", connection);
        assertEquals(200, connection.getResponseCode());
    }

//    public void testDisconnectAfterOnlyResponseCodeCausesNoCloseGuardWarning() throws IOException {
//        CloseGuardGuard guard = new CloseGuardGuard();
//        try {
//            server.enqueue(new MockResponse()
//                    .setBody(gzip("ABCABCABC".getBytes("UTF-8")))
//                    .addHeader("Content-Encoding: gzip"));
//            server.play();
//
//            HttpURLConnection connection = (HttpURLConnection) server.getUrl("/").openConnection();
//            assertEquals(200, connection.getResponseCode());
//            connection.disconnect();
//            connection = null;
//            assertFalse(guard.wasCloseGuardCalled());
//        } finally {
//            guard.close();
//        }
//    }
//
//    public static class CloseGuardGuard implements Closeable, CloseGuard.Reporter  {
//        private final CloseGuard.Reporter oldReporter = CloseGuard.getReporter();
//
//        private AtomicBoolean closeGuardCalled = new AtomicBoolean();
//
//        public CloseGuardGuard() {
//            CloseGuard.setReporter(this);
//        }
//
//        @Override public void report(String message, Throwable allocationSite) {
//            oldReporter.report(message, allocationSite);
//            closeGuardCalled.set(true);
//        }
//
//        public boolean wasCloseGuardCalled() {
//            // FinalizationTester.induceFinalization();
//            close();
//            return closeGuardCalled.get();
//        }
//
//        @Override public void close() {
//            CloseGuard.setReporter(oldReporter);
//        }
//
//    }

    public void testDefaultRequestProperty() throws Exception {
        URLConnection.setDefaultRequestProperty("X-testSetDefaultRequestProperty", "A");
        assertNull(URLConnection.getDefaultRequestProperty("X-setDefaultRequestProperty"));
    }

    /**
     * Reads {@code count} characters from the stream. If the stream is
     * exhausted before {@code count} characters can be read, the remaining
     * characters are returned and the stream is closed.
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

    public void testMarkAndResetWithContentLengthHeader() throws IOException {
        testMarkAndReset(TransferKind.FIXED_LENGTH);
    }

    public void testMarkAndResetWithChunkedEncoding() throws IOException {
        testMarkAndReset(TransferKind.CHUNKED);
    }

    public void testMarkAndResetWithNoLengthHeaders() throws IOException {
        testMarkAndReset(TransferKind.END_OF_STREAM);
    }

    private void testMarkAndReset(TransferKind transferKind) throws IOException {
        MockResponse response = new MockResponse();
        transferKind.setBody(response, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 1024);
        server.enqueue(response);
        server.enqueue(response);
        server.play();

        InputStream in = openConnection(server.getUrl("/")).getInputStream();
        assertFalse("This implementation claims to support mark().", in.markSupported());
        in.mark(5);
        assertEquals("ABCDE", readAscii(in, 5));
        try {
            in.reset();
            fail();
        } catch (IOException expected) {
        }
        assertEquals("FGHIJKLMNOPQRSTUVWXYZ", readAscii(in, Integer.MAX_VALUE));
        assertContent("ABCDEFGHIJKLMNOPQRSTUVWXYZ", openConnection(server.getUrl("/")));
    }

    /**
     * We've had a bug where we forget the HTTP response when we see response
     * code 401. This causes a new HTTP request to be issued for every call into
     * the URLConnection.
     */
    public void SUPPRESSED_testUnauthorizedResponseHandling() throws IOException {
        MockResponse response = new MockResponse()
                .addHeader("WWW-Authenticate: challenge")
                .setResponseCode(401) // UNAUTHORIZED
                .setBody("Unauthorized");
        server.enqueue(response);
        server.enqueue(response);
        server.enqueue(response);
        server.play();

        URL url = server.getUrl("/");
        OkHttpConnection conn = openConnection(url);

        assertEquals(401, conn.getResponseCode());
        assertEquals(401, conn.getResponseCode());
        assertEquals(401, conn.getResponseCode());
        assertEquals(1, server.getRequestCount());
    }

    public void testNonHexChunkSize() throws IOException {
        server.enqueue(new MockResponse()
                .setBody("5\r\nABCDE\r\nG\r\nFGHIJKLMNOPQRSTU\r\n0\r\n\r\n")
                .clearHeaders()
                .addHeader("Transfer-encoding: chunked"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        try {
            readAscii(connection.getInputStream(), Integer.MAX_VALUE);
            fail();
        } catch (IOException e) {
        }
    }

    public void testMissingChunkBody() throws IOException {
        server.enqueue(new MockResponse()
                .setBody("5")
                .clearHeaders()
                .addHeader("Transfer-encoding: chunked")
                .setSocketPolicy(DISCONNECT_AT_END));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        try {
            readAscii(connection.getInputStream(), Integer.MAX_VALUE);
            fail();
        } catch (IOException e) {
        }
    }

    /**
     * This test checks whether connections are gzipped by default. This
     * behavior in not required by the API, so a failure of this test does not
     * imply a bug in the implementation.
     */
    public void testGzipEncodingEnabledByDefault() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setBody(gzip("ABCABCABC".getBytes("UTF-8")))
                .addHeader("Content-Encoding: gzip"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        assertEquals("ABCABCABC", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        assertNull(connection.getContentEncoding());

        RecordedRequest request = server.takeRequest();
        assertContains(request.getHeaders(), "Accept-Encoding: gzip");
    }

    public void testClientConfiguredGzipContentEncoding() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(gzip("ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes("UTF-8")))
                .addHeader("Content-Encoding: gzip"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        connection.addRequestProperty("Accept-Encoding", "gzip");
        InputStream gunzippedIn = new GZIPInputStream(connection.getInputStream());
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", readAscii(gunzippedIn, Integer.MAX_VALUE));

        RecordedRequest request = server.takeRequest();
        assertContains(request.getHeaders(), "Accept-Encoding: gzip");
    }

    public void testGzipAndConnectionReuseWithFixedLength() throws Exception {
        testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, false);
    }

    public void testGzipAndConnectionReuseWithChunkedEncoding() throws Exception {
        testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, false);
    }

    public void testGzipAndConnectionReuseWithFixedLengthAndTls() throws Exception {
        testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, true);
    }

    public void testGzipAndConnectionReuseWithChunkedEncodingAndTls() throws Exception {
        testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, true);
    }

    public void testClientConfiguredCustomContentEncoding() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("ABCDE")
                .addHeader("Content-Encoding: custom"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        connection.addRequestProperty("Accept-Encoding", "custom");
        assertEquals("ABCDE", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

        RecordedRequest request = server.takeRequest();
        assertContains(request.getHeaders(), "Accept-Encoding: custom");
    }

    /**
     * Test a bug where gzip input streams weren't exhausting the input stream,
     * which corrupted the request that followed or prevented connection reuse.
     * http://code.google.com/p/android/issues/detail?id=7059
     * http://code.google.com/p/android/issues/detail?id=38817
     */
    private void testClientConfiguredGzipContentEncodingAndConnectionReuse(
            TransferKind transferKind, boolean tls) throws Exception {
        SSLSocketFactory socketFactory = null;
        RecordingHostnameVerifier hostnameVerifier = null;
        if (tls) {
            socketFactory = sslContext.getSocketFactory();
            hostnameVerifier = new RecordingHostnameVerifier();
            server.useHttps(socketFactory, false);
        }

        MockResponse responseOne = new MockResponse();
        responseOne.addHeader("Content-Encoding: gzip");
        transferKind.setBody(responseOne, gzip("one (gzipped)".getBytes("UTF-8")), 5);
        server.enqueue(responseOne);
        MockResponse responseTwo = new MockResponse();
        transferKind.setBody(responseTwo, "two (identity)", 5);
        server.enqueue(responseTwo);
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        if (tls) {
            ((OkHttpsConnection) connection).setSSLSocketFactory(socketFactory);
            ((OkHttpsConnection) connection).setHostnameVerifier(hostnameVerifier);
        }
        connection.addRequestProperty("Accept-Encoding", "gzip");
        InputStream gunzippedIn = new GZIPInputStream(connection.getInputStream());
        assertEquals("one (gzipped)", readAscii(gunzippedIn, Integer.MAX_VALUE));
        assertEquals(0, server.takeRequest().getSequenceNumber());

        connection = openConnection(server.getUrl("/"));
        if (tls) {
            ((OkHttpsConnection) connection).setSSLSocketFactory(socketFactory);
            ((OkHttpsConnection) connection).setHostnameVerifier(hostnameVerifier);
        }
        assertEquals("two (identity)", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        assertEquals(1, server.takeRequest().getSequenceNumber());
    }

    /**
     * Obnoxiously test that the chunk sizes transmitted exactly equal the
     * requested data+chunk header size. Although setChunkedStreamingMode()
     * isn't specific about whether the size applies to the data or the
     * complete chunk, the RI interprets it as a complete chunk.
     */
    public void testSetChunkedStreamingMode() throws IOException, InterruptedException {
        server.enqueue(new MockResponse());
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        urlConnection.setChunkedStreamingMode(8);
        urlConnection.setDoOutput(true);
        OutputStream outputStream = urlConnection.getOutputStream();
        outputStream.write("ABCDEFGHIJKLMNOPQ".getBytes("US-ASCII"));
        assertEquals(200, urlConnection.getResponseCode());

        RecordedRequest request = server.takeRequest();
        assertEquals("ABCDEFGHIJKLMNOPQ", new String(request.getBody(), "US-ASCII"));
        assertEquals(Arrays.asList(3, 3, 3, 3, 3, 2), request.getChunkSizes());
    }

    public void testAuthenticateWithFixedLengthStreaming() throws Exception {
        testAuthenticateWithStreamingPost(StreamingMode.FIXED_LENGTH);
    }

    public void testAuthenticateWithChunkedStreaming() throws Exception {
        testAuthenticateWithStreamingPost(StreamingMode.CHUNKED);
    }

    private void testAuthenticateWithStreamingPost(StreamingMode streamingMode) throws Exception {
        MockResponse pleaseAuthenticate = new MockResponse()
                .setResponseCode(401)
                .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
                .setBody("Please authenticate.");
        server.enqueue(pleaseAuthenticate);
        server.play();

        Authenticator.setDefault(new RecordingAuthenticator());
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setDoOutput(true);
        byte[] requestBody = { 'A', 'B', 'C', 'D' };
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
        assertContainsNoneMatching(request.getHeaders(), "Authorization: Basic .*");
        assertEquals(Arrays.toString(requestBody), Arrays.toString(request.getBody()));
    }

    public void testNonStandardAuthenticationScheme() throws Exception {
        List<String> calls = authCallsForHeader("WWW-Authenticate: Foo");
        assertEquals(Collections.<String>emptyList(), calls);
    }

    public void testNonStandardAuthenticationSchemeWithRealm() throws Exception {
        List<String> calls = authCallsForHeader("WWW-Authenticate: Foo realm=\"Bar\"");
        assertEquals(1, calls.size());
        String call = calls.get(0);
        assertTrue(call, call.contains("scheme=Foo"));
        assertTrue(call, call.contains("prompt=Bar"));
    }

    // Digest auth is currently unsupported. Test that digest requests should fail reasonably.
    // http://code.google.com/p/android/issues/detail?id=11140
    public void testDigestAuthentication() throws Exception {
        List<String> calls = authCallsForHeader("WWW-Authenticate: Digest "
                + "realm=\"testrealm@host.com\", qop=\"auth,auth-int\", "
                + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
                + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
        assertEquals(1, calls.size());
        String call = calls.get(0);
        assertTrue(call, call.contains("scheme=Digest"));
        assertTrue(call, call.contains("prompt=testrealm@host.com"));
    }

    public void testAllAttributesSetInServerAuthenticationCallbacks() throws Exception {
        List<String> calls = authCallsForHeader("WWW-Authenticate: Basic realm=\"Bar\"");
        assertEquals(1, calls.size());
        URL url = server.getUrl("/");
        String call = calls.get(0);
        assertTrue(call, call.contains("host=" + url.getHost()));
        assertTrue(call, call.contains("port=" + url.getPort()));
        assertTrue(call, call.contains("site=" + InetAddress.getAllByName(url.getHost())[0]));
        assertTrue(call, call.contains("url=" + url));
        assertTrue(call, call.contains("type=" + Authenticator.RequestorType.SERVER));
        assertTrue(call, call.contains("prompt=Bar"));
        assertTrue(call, call.contains("protocol=http"));
        assertTrue(call, call.toLowerCase().contains("scheme=basic")); // lowercase for the RI.
    }

    public void testAllAttributesSetInProxyAuthenticationCallbacks() throws Exception {
        List<String> calls = authCallsForHeader("Proxy-Authenticate: Basic realm=\"Bar\"");
        assertEquals(1, calls.size());
        URL url = server.getUrl("/");
        String call = calls.get(0);
        assertTrue(call, call.contains("host=" + url.getHost()));
        assertTrue(call, call.contains("port=" + url.getPort()));
        assertTrue(call, call.contains("site=" + InetAddress.getAllByName(url.getHost())[0]));
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
        server.play();

        OkHttpConnection connection = proxy
                ? openConnection(new URL("http://android.com"), server.toProxyAddress())
                : openConnection(server.getUrl("/"));
        assertEquals(responseCode, connection.getResponseCode());
        return authenticator.calls;
    }

    public void testSetValidRequestMethod() throws Exception {
        server.play();
        assertValidRequestMethod("GET");
        assertValidRequestMethod("DELETE");
        assertValidRequestMethod("HEAD");
        assertValidRequestMethod("OPTIONS");
        assertValidRequestMethod("POST");
        assertValidRequestMethod("PUT");
        assertValidRequestMethod("TRACE");
    }

    private void assertValidRequestMethod(String requestMethod) throws Exception {
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setRequestMethod(requestMethod);
        assertEquals(requestMethod, connection.getRequestMethod());
    }

    public void testSetInvalidRequestMethodLowercase() throws Exception {
        server.play();
        assertInvalidRequestMethod("get");
    }

    public void testSetInvalidRequestMethodConnect() throws Exception {
        server.play();
        assertInvalidRequestMethod("CONNECT");
    }

    private void assertInvalidRequestMethod(String requestMethod) throws Exception {
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        try {
            connection.setRequestMethod(requestMethod);
            fail();
        } catch (ProtocolException expected) {
        }
    }

    public void testCannotSetNegativeFixedLengthStreamingMode() throws Exception {
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        try {
            connection.setFixedLengthStreamingMode(-2);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testCanSetNegativeChunkedStreamingMode() throws Exception {
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setChunkedStreamingMode(-2);
    }

    public void testCannotSetFixedLengthStreamingModeAfterConnect() throws Exception {
        server.enqueue(new MockResponse().setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("A", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        try {
            connection.setFixedLengthStreamingMode(1);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testCannotSetChunkedStreamingModeAfterConnect() throws Exception {
        server.enqueue(new MockResponse().setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("A", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        try {
            connection.setChunkedStreamingMode(1);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testCannotSetFixedLengthStreamingModeAfterChunkedStreamingMode() throws Exception {
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setChunkedStreamingMode(1);
        try {
            connection.setFixedLengthStreamingMode(1);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testCannotSetChunkedStreamingModeAfterFixedLengthStreamingMode() throws Exception {
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setFixedLengthStreamingMode(1);
        try {
            connection.setChunkedStreamingMode(1);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testSecureFixedLengthStreaming() throws Exception {
        testSecureStreamingPost(StreamingMode.FIXED_LENGTH);
    }

    public void testSecureChunkedStreaming() throws Exception {
        testSecureStreamingPost(StreamingMode.CHUNKED);
    }

    /**
     * Users have reported problems using HTTPS with streaming request bodies.
     * http://code.google.com/p/android/issues/detail?id=12860
     */
    private void testSecureStreamingPost(StreamingMode streamingMode) throws Exception {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("Success!"));
        server.play();

        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        connection.setDoOutput(true);
        byte[] requestBody = { 'A', 'B', 'C', 'D' };
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
        assertEquals(Arrays.toString(requestBody), Arrays.toString(request.getBody()));
    }

    enum StreamingMode {
        FIXED_LENGTH, CHUNKED
    }

    public void testAuthenticateWithPost() throws Exception {
        MockResponse pleaseAuthenticate = new MockResponse()
                .setResponseCode(401)
                .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
                .setBody("Please authenticate.");
        // fail auth three times...
        server.enqueue(pleaseAuthenticate);
        server.enqueue(pleaseAuthenticate);
        server.enqueue(pleaseAuthenticate);
        // ...then succeed the fourth time
        server.enqueue(new MockResponse().setBody("Successful auth!"));
        server.play();

        Authenticator.setDefault(new RecordingAuthenticator());
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setDoOutput(true);
        byte[] requestBody = { 'A', 'B', 'C', 'D' };
        OutputStream outputStream = connection.getOutputStream();
        outputStream.write(requestBody);
        outputStream.close();
        assertEquals("Successful auth!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

        // no authorization header for the first request...
        RecordedRequest request = server.takeRequest();
        assertContainsNoneMatching(request.getHeaders(), "Authorization: Basic .*");

        // ...but the three requests that follow include an authorization header
        for (int i = 0; i < 3; i++) {
            request = server.takeRequest();
            assertEquals("POST / HTTP/1.1", request.getRequestLine());
            assertContains(request.getHeaders(), "Authorization: Basic " + BASE_64_CREDENTIALS);
            assertEquals(Arrays.toString(requestBody), Arrays.toString(request.getBody()));
        }
    }

    public void testAuthenticateWithGet() throws Exception {
        MockResponse pleaseAuthenticate = new MockResponse()
                .setResponseCode(401)
                .addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
                .setBody("Please authenticate.");
        // fail auth three times...
        server.enqueue(pleaseAuthenticate);
        server.enqueue(pleaseAuthenticate);
        server.enqueue(pleaseAuthenticate);
        // ...then succeed the fourth time
        server.enqueue(new MockResponse().setBody("Successful auth!"));
        server.play();

        Authenticator.setDefault(new RecordingAuthenticator());
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("Successful auth!", readAscii(connection.getInputStream(), Integer.MAX_VALUE));

        // no authorization header for the first request...
        RecordedRequest request = server.takeRequest();
        assertContainsNoneMatching(request.getHeaders(), "Authorization: Basic .*");

        // ...but the three requests that follow requests include an authorization header
        for (int i = 0; i < 3; i++) {
            request = server.takeRequest();
            assertEquals("GET / HTTP/1.1", request.getRequestLine());
            assertContains(request.getHeaders(), "Authorization: Basic " + BASE_64_CREDENTIALS);
        }
    }

    public void testRedirectedWithChunkedEncoding() throws Exception {
        testRedirected(TransferKind.CHUNKED, true);
    }

    public void testRedirectedWithContentLengthHeader() throws Exception {
        testRedirected(TransferKind.FIXED_LENGTH, true);
    }

    public void testRedirectedWithNoLengthHeaders() throws Exception {
        testRedirected(TransferKind.END_OF_STREAM, false);
    }

    private void testRedirected(TransferKind transferKind, boolean reuse) throws Exception {
        MockResponse response = new MockResponse()
                .setResponseCode(OkHttpConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: /foo");
        transferKind.setBody(response, "This page has moved!", 10);
        server.enqueue(response);
        server.enqueue(new MockResponse().setBody("This is the new location!"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
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

    public void testRedirectedOnHttps() throws IOException, InterruptedException {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: /foo")
                .setBody("This page has moved!"));
        server.enqueue(new MockResponse().setBody("This is the new location!"));
        server.play();

        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        assertEquals("This is the new location!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));

        RecordedRequest first = server.takeRequest();
        assertEquals("GET / HTTP/1.1", first.getRequestLine());
        RecordedRequest retry = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", retry.getRequestLine());
        assertEquals("Expected connection reuse", 1, retry.getSequenceNumber());
    }

    public void testNotRedirectedFromHttpsToHttp() throws IOException, InterruptedException {
        server.useHttps(sslContext.getSocketFactory(), false);
        server.enqueue(new MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: http://anyhost/foo")
                .setBody("This page has moved!"));
        server.play();

        OkHttpsConnection connection = (OkHttpsConnection) openConnection(server.getUrl("/"));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(new RecordingHostnameVerifier());
        assertEquals("This page has moved!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    }

    public void testNotRedirectedFromHttpToHttps() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(OkHttpConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: https://anyhost/foo")
                .setBody("This page has moved!"));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("This page has moved!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    }

    public void SUPPRESSED_testRedirectToAnotherOriginServer() throws Exception {
        MockWebServer server2 = new MockWebServer();
        server2.enqueue(new MockResponse().setBody("This is the 2nd server!"));
        server2.play();

        server.enqueue(new MockResponse()
                .setResponseCode(OkHttpConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: " + server2.getUrl("/").toString())
                .setBody("This page has moved!"));
        server.enqueue(new MockResponse().setBody("This is the first server again!"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        assertEquals("This is the 2nd server!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        assertEquals(server2.getUrl("/"), connection.getURL());

        // make sure the first server was careful to recycle the connection
        assertEquals("This is the first server again!",
                readAscii(server.getUrl("/").openStream(), Integer.MAX_VALUE));

        RecordedRequest first = server.takeRequest();
        assertContains(first.getHeaders(), "Host: " + hostName + ":" + server.getPort());
        RecordedRequest second = server2.takeRequest();
        assertContains(second.getHeaders(), "Host: " + hostName + ":" + server2.getPort());
        RecordedRequest third = server.takeRequest();
        assertEquals("Expected connection reuse", 1, third.getSequenceNumber());

        server2.shutdown();
    }

    public void testResponse300MultipleChoiceWithPost() throws Exception {
        // Chrome doesn't follow the redirect, but Firefox and the RI both do
        testResponseRedirectedWithPost(OkHttpConnection.HTTP_MULT_CHOICE);
    }

    public void testResponse301MovedPermanentlyWithPost() throws Exception {
        testResponseRedirectedWithPost(OkHttpConnection.HTTP_MOVED_PERM);
    }

    public void testResponse302MovedTemporarilyWithPost() throws Exception {
        testResponseRedirectedWithPost(OkHttpConnection.HTTP_MOVED_TEMP);
    }

    public void testResponse303SeeOtherWithPost() throws Exception {
        testResponseRedirectedWithPost(OkHttpConnection.HTTP_SEE_OTHER);
    }

    private void testResponseRedirectedWithPost(int redirectCode) throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(redirectCode)
                .addHeader("Location: /page2")
                .setBody("This page has moved!"));
        server.enqueue(new MockResponse().setBody("Page 2"));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/page1"));
        connection.setDoOutput(true);
        byte[] requestBody = { 'A', 'B', 'C', 'D' };
        OutputStream outputStream = connection.getOutputStream();
        outputStream.write(requestBody);
        outputStream.close();
        assertEquals("Page 2", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        assertTrue(connection.getDoOutput());

        RecordedRequest page1 = server.takeRequest();
        assertEquals("POST /page1 HTTP/1.1", page1.getRequestLine());
        assertEquals(Arrays.toString(requestBody), Arrays.toString(page1.getBody()));

        RecordedRequest page2 = server.takeRequest();
        assertEquals("GET /page2 HTTP/1.1", page2.getRequestLine());
    }

    public void testResponse305UseProxy() throws Exception {
        server.play();
        server.enqueue(new MockResponse()
                .setResponseCode(OkHttpConnection.HTTP_USE_PROXY)
                .addHeader("Location: " + server.getUrl("/"))
                .setBody("This page has moved!"));
        server.enqueue(new MockResponse().setBody("Proxy Response"));

        OkHttpConnection connection = openConnection(server.getUrl("/foo"));
        // Fails on the RI, which gets "Proxy Response"
        assertEquals("This page has moved!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));

        RecordedRequest page1 = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", page1.getRequestLine());
        assertEquals(1, server.getRequestCount());
    }

//    public void testHttpsWithCustomTrustManager() throws Exception {
//        RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
//        RecordingTrustManager trustManager = new RecordingTrustManager();
//        SSLContext sc = SSLContext.getInstance("TLS");
//        sc.init(null, new TrustManager[] { trustManager }, new java.security.SecureRandom());
//
//        HostnameVerifier defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
//        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier);
//        SSLSocketFactory defaultSSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
//        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
//        try {
//            TestSSLContext testSSLContext = TestSSLContext.create();
//            server.useHttps(testSSLContext.serverContext.getSocketFactory(), false);
//            server.enqueue(new MockResponse().setBody("ABC"));
//            server.enqueue(new MockResponse().setBody("DEF"));
//            server.enqueue(new MockResponse().setBody("GHI"));
//            server.play();
//
//            URL url = server.getUrl("/");
//            assertEquals("ABC", readAscii(url.openStream(), Integer.MAX_VALUE));
//            assertEquals("DEF", readAscii(url.openStream(), Integer.MAX_VALUE));
//            assertEquals("GHI", readAscii(url.openStream(), Integer.MAX_VALUE));
//
//            assertEquals(Arrays.asList("verify " + hostName), hostnameVerifier.calls);
//            assertEquals(Arrays.asList("checkServerTrusted ["
//                    + "CN=" + hostName + " 1, "
//                    + "CN=Test Intermediate Certificate Authority 1, "
//                    + "CN=Test Root Certificate Authority 1"
//                    + "] RSA"),
//                    trustManager.calls);
//        } finally {
//            HttpsURLConnection.setDefaultHostnameVerifier(defaultHostnameVerifier);
//            HttpsURLConnection.setDefaultSSLSocketFactory(defaultSSLSocketFactory);
//        }
//    }
//
//    public void testConnectTimeouts() throws IOException {
//        StuckServer ss = new StuckServer();
//        int serverPort = ss.getLocalPort();
//        URLConnection urlConnection = new URL("http://localhost:" + serverPort).openConnection();
//        int timeout = 1000;
//        urlConnection.setConnectTimeout(timeout);
//        long start = System.currentTimeMillis();
//        try {
//            urlConnection.getInputStream();
//            fail();
//        } catch (SocketTimeoutException expected) {
//            long actual = System.currentTimeMillis() - start;
//            assertTrue(Math.abs(timeout - actual) < 500);
//        } finally {
//            ss.close();
//        }
//    }

    public void testReadTimeouts() throws IOException {
        /*
         * This relies on the fact that MockWebServer doesn't close the
         * connection after a response has been sent. This causes the client to
         * try to read more bytes than are sent, which results in a timeout.
         */
        MockResponse timeout = new MockResponse()
                .setBody("ABC")
                .clearHeaders()
                .addHeader("Content-Length: 4");
        server.enqueue(timeout);
        server.enqueue(new MockResponse().setBody("unused")); // to keep the server alive
        server.play();

        URLConnection urlConnection = openConnection(server.getUrl("/"));
        urlConnection.setReadTimeout(1000);
        InputStream in = urlConnection.getInputStream();
        assertEquals('A', in.read());
        assertEquals('B', in.read());
        assertEquals('C', in.read());
        try {
            in.read(); // if Content-Length was accurate, this would return -1 immediately
            fail();
        } catch (SocketTimeoutException expected) {
        }
    }

    public void testSetChunkedEncodingAsRequestProperty() throws IOException, InterruptedException {
        server.enqueue(new MockResponse());
        server.play();

        OkHttpConnection urlConnection = openConnection(server.getUrl("/"));
        urlConnection.setRequestProperty("Transfer-encoding", "chunked");
        urlConnection.setDoOutput(true);
        urlConnection.getOutputStream().write("ABC".getBytes("UTF-8"));
        assertEquals(200, urlConnection.getResponseCode());

        RecordedRequest request = server.takeRequest();
        assertEquals("ABC", new String(request.getBody(), "UTF-8"));
    }

    public void testConnectionCloseInRequest() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()); // server doesn't honor the connection: close header!
        server.enqueue(new MockResponse());
        server.play();

        OkHttpConnection a = openConnection(server.getUrl("/"));
        a.setRequestProperty("Connection", "close");
        assertEquals(200, a.getResponseCode());

        OkHttpConnection b = openConnection(server.getUrl("/"));
        assertEquals(200, b.getResponseCode());

        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertEquals("When connection: close is used, each request should get its own connection",
                0, server.takeRequest().getSequenceNumber());
    }

    public void testConnectionCloseInResponse() throws IOException, InterruptedException {
        server.enqueue(new MockResponse().addHeader("Connection: close"));
        server.enqueue(new MockResponse());
        server.play();

        OkHttpConnection a = openConnection(server.getUrl("/"));
        assertEquals(200, a.getResponseCode());

        OkHttpConnection b = openConnection(server.getUrl("/"));
        assertEquals(200, b.getResponseCode());

        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertEquals("When connection: close is used, each request should get its own connection",
                0, server.takeRequest().getSequenceNumber());
    }

    public void testConnectionCloseWithRedirect() throws IOException, InterruptedException {
        MockResponse response = new MockResponse()
                .setResponseCode(OkHttpConnection.HTTP_MOVED_TEMP)
                .addHeader("Location: /foo")
                .addHeader("Connection: close");
        server.enqueue(response);
        server.enqueue(new MockResponse().setBody("This is the new location!"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        assertEquals("This is the new location!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));

        assertEquals(0, server.takeRequest().getSequenceNumber());
        assertEquals("When connection: close is used, each request should get its own connection",
                0, server.takeRequest().getSequenceNumber());
    }

    public void testResponseCodeDisagreesWithHeaders() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(OkHttpConnection.HTTP_NO_CONTENT)
                .setBody("This body is not allowed!"));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        assertEquals("This body is not allowed!",
                readAscii(connection.getInputStream(), Integer.MAX_VALUE));
    }

    public void testSingleByteReadIsSigned() throws IOException {
        server.enqueue(new MockResponse().setBody(new byte[] { -2, -1 }));
        server.play();

        URLConnection connection = openConnection(server.getUrl("/"));
        InputStream in = connection.getInputStream();
        assertEquals(254, in.read());
        assertEquals(255, in.read());
        assertEquals(-1, in.read());
    }

    public void testFlushAfterStreamTransmittedWithChunkedEncoding() throws IOException {
        testFlushAfterStreamTransmitted(TransferKind.CHUNKED);
    }

    public void testFlushAfterStreamTransmittedWithFixedLength() throws IOException {
        testFlushAfterStreamTransmitted(TransferKind.FIXED_LENGTH);
    }

    public void testFlushAfterStreamTransmittedWithNoLengthHeaders() throws IOException {
        testFlushAfterStreamTransmitted(TransferKind.END_OF_STREAM);
    }

    /**
     * We explicitly permit apps to close the upload stream even after it has
     * been transmitted.  We also permit flush so that buffered streams can
     * do a no-op flush when they are closed. http://b/3038470
     */
    private void testFlushAfterStreamTransmitted(TransferKind transferKind) throws IOException {
        server.enqueue(new MockResponse().setBody("abc"));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/"));
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

        out.flush(); // dubious but permitted
        try {
            out.write("ghi".getBytes("UTF-8"));
            fail();
        } catch (IOException expected) {
        }
    }

    public void testGetHeadersThrows() throws IOException {
        server.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/"));
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

    public void SUPPRESSED_testGetKeepAlive() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("ABC"));
        server.play();

        // The request should work once and then fail
        URLConnection connection = openConnection(server.getUrl(""));
        InputStream input = connection.getInputStream();
        assertEquals("ABC", readAscii(input, Integer.MAX_VALUE));
        input.close();
        try {
            openConnection(server.getUrl("")).getInputStream();
            fail();
        } catch (ConnectException expected) {
        }
    }

    /**
     * This test goes through the exhaustive set of interesting ASCII characters
     * because most of those characters are interesting in some way according to
     * RFC 2396 and RFC 2732. http://b/1158780
     */
    public void SUPPRESSED_testLenientUrlToUri() throws Exception {
        // alphanum
        testUrlToUriMapping("abzABZ09", "abzABZ09", "abzABZ09", "abzABZ09", "abzABZ09");

        // control characters
        testUrlToUriMapping("\u0001", "%01", "%01", "%01", "%01");
        testUrlToUriMapping("\u001f", "%1F", "%1F", "%1F", "%1F");

        // ascii characters
        testUrlToUriMapping("%20", "%20", "%20", "%20", "%20");
        testUrlToUriMapping("%20", "%20", "%20", "%20", "%20");
        testUrlToUriMapping(" ", "%20", "%20", "%20", "%20");
        testUrlToUriMapping("!", "!", "!", "!", "!");
        testUrlToUriMapping("\"", "%22", "%22", "%22", "%22");
        testUrlToUriMapping("#", null, null, null, "%23");
        testUrlToUriMapping("$", "$", "$", "$", "$");
        testUrlToUriMapping("&", "&", "&", "&", "&");
        testUrlToUriMapping("'", "'", "'", "'", "'");
        testUrlToUriMapping("(", "(", "(", "(", "(");
        testUrlToUriMapping(")", ")", ")", ")", ")");
        testUrlToUriMapping("*", "*", "*", "*", "*");
        testUrlToUriMapping("+", "+", "+", "+", "+");
        testUrlToUriMapping(",", ",", ",", ",", ",");
        testUrlToUriMapping("-", "-", "-", "-", "-");
        testUrlToUriMapping(".", ".", ".", ".", ".");
        testUrlToUriMapping("/", null, "/", "/", "/");
        testUrlToUriMapping(":", null, ":", ":", ":");
        testUrlToUriMapping(";", ";", ";", ";", ";");
        testUrlToUriMapping("<", "%3C", "%3C", "%3C", "%3C");
        testUrlToUriMapping("=", "=", "=", "=", "=");
        testUrlToUriMapping(">", "%3E", "%3E", "%3E", "%3E");
        testUrlToUriMapping("?", null, null, "?", "?");
        testUrlToUriMapping("@", "@", "@", "@", "@");
        testUrlToUriMapping("[", null, "%5B", null, "%5B");
        testUrlToUriMapping("\\", "%5C", "%5C", "%5C", "%5C");
        testUrlToUriMapping("]", null, "%5D", null, "%5D");
        testUrlToUriMapping("^", "%5E", "%5E", "%5E", "%5E");
        testUrlToUriMapping("_", "_", "_", "_", "_");
        testUrlToUriMapping("`", "%60", "%60", "%60", "%60");
        testUrlToUriMapping("{", "%7B", "%7B", "%7B", "%7B");
        testUrlToUriMapping("|", "%7C", "%7C", "%7C", "%7C");
        testUrlToUriMapping("}", "%7D", "%7D", "%7D", "%7D");
        testUrlToUriMapping("~", "~", "~", "~", "~");
        testUrlToUriMapping("~", "~", "~", "~", "~");
        testUrlToUriMapping("\u007f", "%7F", "%7F", "%7F", "%7F");

        // beyond ascii
        testUrlToUriMapping("\u0080", "%C2%80", "%C2%80", "%C2%80", "%C2%80");
        testUrlToUriMapping("\u20ac", "\u20ac", "\u20ac", "\u20ac", "\u20ac");
        testUrlToUriMapping("\ud842\udf9f",
                "\ud842\udf9f", "\ud842\udf9f", "\ud842\udf9f", "\ud842\udf9f");
    }

    public void SUPPRESSED_testLenientUrlToUriNul() throws Exception {
        testUrlToUriMapping("\u0000", "%00", "%00", "%00", "%00"); // RI fails this
    }

    private void testUrlToUriMapping(String string, String asAuthority, String asFile,
            String asQuery, String asFragment) throws Exception {
        if (asAuthority != null) {
            assertEquals("http://host" + asAuthority + ".tld/",
                    backdoorUrlToUri(new URL("http://host" + string + ".tld/")).toString());
        }
        if (asFile != null) {
            assertEquals("http://host.tld/file" + asFile + "/",
                    backdoorUrlToUri(new URL("http://host.tld/file" + string + "/")).toString());
        }
        if (asQuery != null) {
            assertEquals("http://host.tld/file?q" + asQuery + "=x",
                    backdoorUrlToUri(new URL("http://host.tld/file?q" + string + "=x")).toString());
        }
        assertEquals("http://host.tld/file#" + asFragment + "-x",
                backdoorUrlToUri(new URL("http://host.tld/file#" + asFragment + "-x")).toString());
    }

    /**
     * Exercises HttpURLConnection to convert URL to a URI. Unlike URL#toURI,
     * HttpURLConnection recovers from URLs with unescaped but unsupported URI
     * characters like '{' and '|' by escaping these characters.
     */
    private URI backdoorUrlToUri(URL url) throws Exception {
        final AtomicReference<URI> uriReference = new AtomicReference<URI>();

        ResponseCache.setDefault(new ResponseCache() {
            @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
                return null;
            }
            @Override public CacheResponse get(URI uri, String requestMethod,
                    Map<String, List<String>> requestHeaders) throws IOException {
                uriReference.set(uri);
                throw new UnsupportedOperationException();
            }
        });

        try {
            OkHttpConnection connection = openConnection(url);
            connection.getResponseCode();
        } catch (Exception expected) {
            if (expected.getCause() instanceof URISyntaxException) {
                expected.printStackTrace();
            }
        }

        return uriReference.get();
    }

    /**
     * Don't explode if the cache returns a null body. http://b/3373699
     */
    public void testResponseCacheReturnsNullOutputStream() throws Exception {
        final AtomicBoolean aborted = new AtomicBoolean();
        ResponseCache.setDefault(new ResponseCache() {
            @Override public CacheResponse get(URI uri, String requestMethod,
                    Map<String, List<String>> requestHeaders) throws IOException {
                return null;
            }
            @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
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

        OkHttpConnection connection = openConnection(server.getUrl("/"));
        InputStream in = connection.getInputStream();
        assertEquals("abc", readAscii(in, 3));
        in.close();
        assertFalse(aborted.get()); // The best behavior is ambiguous, but RI 6 doesn't abort here
    }


    /**
     * http://code.google.com/p/android/issues/detail?id=14562
     */
    public void testReadAfterLastByte() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("ABC")
                .clearHeaders()
                .addHeader("Connection: close")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
        server.play();

        OkHttpConnection connection = openConnection(server.getUrl("/"));
        InputStream in = connection.getInputStream();
        assertEquals("ABC", readAscii(in, 3));
        assertEquals(-1, in.read());
        assertEquals(-1, in.read()); // throws IOException in Gingerbread
    }

    public void testGetContent() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type: text/plain")
                .setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        InputStream in = (InputStream) connection.getContent();
        assertEquals("A", readAscii(in, Integer.MAX_VALUE));
    }

    public void testGetContentOfType() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type: text/plain")
                .setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        try {
            connection.getContent(null);
            fail();
        } catch (NullPointerException expected) {
        }
        try {
            connection.getContent(new Class[] { null });
            fail();
        } catch (NullPointerException expected) {
        }
        assertNull(connection.getContent(new Class[] { getClass() }));
        connection.disconnect();
    }

    public void testGetOutputStreamOnGetFails() throws Exception {
        server.enqueue(new MockResponse());
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        try {
            connection.getOutputStream();
            fail();
        } catch (ProtocolException expected) {
        }
    }

    public void testGetOutputAfterGetInputStreamFails() throws Exception {
        server.enqueue(new MockResponse());
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setDoOutput(true);
        try {
            connection.getInputStream();
            connection.getOutputStream();
            fail();
        } catch (ProtocolException expected) {
        }
    }

    public void testSetDoOutputOrDoInputAfterConnectFails() throws Exception {
        server.enqueue(new MockResponse());
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
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
        connection.disconnect();
    }

    public void testClientSendsContentLength() throws Exception {
        server.enqueue(new MockResponse().setBody("A"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        connection.setDoOutput(true);
        OutputStream out = connection.getOutputStream();
        out.write(new byte[] { 'A', 'B', 'C' });
        out.close();
        assertEquals("A", readAscii(connection.getInputStream(), Integer.MAX_VALUE));
        RecordedRequest request = server.takeRequest();
        assertContains(request.getHeaders(), "Content-Length: 3");
    }

    public void testGetContentLengthConnects() throws Exception {
        server.enqueue(new MockResponse().setBody("ABC"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals(3, connection.getContentLength());
        connection.disconnect();
    }

    public void testGetContentTypeConnects() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type: text/plain")
                .setBody("ABC"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("text/plain", connection.getContentType());
        connection.disconnect();
    }

    public void testGetContentEncodingConnects() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Encoding: identity")
                .setBody("ABC"));
        server.play();
        OkHttpConnection connection = openConnection(server.getUrl("/"));
        assertEquals("identity", connection.getContentEncoding());
        connection.disconnect();
    }

    // http://b/4361656
    public void testUrlContainsQueryButNoPath() throws Exception {
        server.enqueue(new MockResponse().setBody("A"));
        server.play();
        URL url = new URL("http", server.getHostName(), server.getPort(), "?query");
        assertEquals("A", readAscii(openConnection(url).getInputStream(), Integer.MAX_VALUE));
        RecordedRequest request = server.takeRequest();
        assertEquals("GET /?query HTTP/1.1", request.getRequestLine());
    }

    // http://code.google.com/p/android/issues/detail?id=20442
    public void testInputStreamAvailableWithChunkedEncoding() throws Exception {
        testInputStreamAvailable(TransferKind.CHUNKED);
    }

    public void testInputStreamAvailableWithContentLengthHeader() throws Exception {
        testInputStreamAvailable(TransferKind.FIXED_LENGTH);
    }

    public void testInputStreamAvailableWithNoLengthHeaders() throws Exception {
        testInputStreamAvailable(TransferKind.END_OF_STREAM);
    }

    private void testInputStreamAvailable(TransferKind transferKind) throws IOException {
        String body = "ABCDEFGH";
        MockResponse response = new MockResponse();
        transferKind.setBody(response, body, 4);
        server.enqueue(response);
        server.play();
        URLConnection connection = openConnection(server.getUrl("/"));
        InputStream in = connection.getInputStream();
        for (int i = 0; i < body.length(); i++) {
            assertTrue(in.available() >= 0);
            assertEquals(body.charAt(i), in.read());
        }
        assertEquals(0, in.available());
        assertEquals(-1, in.read());
    }

    /**
     * Returns a gzipped copy of {@code bytes}.
     */
    public byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        OutputStream gzippedOut = new GZIPOutputStream(bytesOut);
        gzippedOut.write(bytes);
        gzippedOut.close();
        return bytesOut.toByteArray();
    }

    /**
     * Reads at most {@code limit} characters from {@code in} and asserts that
     * content equals {@code expected}.
     */
    private void assertContent(String expected, URLConnection connection, int limit)
            throws IOException {
        connection.connect();
        assertEquals(expected, readAscii(connection.getInputStream(), limit));
        ((OkHttpConnection) connection).disconnect();
    }

    private void assertContent(String expected, URLConnection connection) throws IOException {
        assertContent(expected, connection, Integer.MAX_VALUE);
    }

    private void assertContains(List<String> headers, String header) {
        assertTrue(headers.toString(), headers.contains(header));
    }

    private void assertContainsNoneMatching(List<String> headers, String pattern) {
        for (String header : headers) {
            if (header.matches(pattern)) {
                fail("Header " + header + " matches " + pattern);
            }
        }
    }

    private Set<String> newSet(String... elements) {
        return new HashSet<String>(Arrays.asList(elements));
    }

    enum TransferKind {
        CHUNKED() {
            @Override void setBody(MockResponse response, byte[] content, int chunkSize)
                    throws IOException {
                response.setChunkedBody(content, chunkSize);
            }
        },
        FIXED_LENGTH() {
            @Override void setBody(MockResponse response, byte[] content, int chunkSize) {
                response.setBody(content);
            }
        },
        END_OF_STREAM() {
            @Override void setBody(MockResponse response, byte[] content, int chunkSize) {
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

        abstract void setBody(MockResponse response, byte[] content, int chunkSize)
                throws IOException;

        void setBody(MockResponse response, String content, int chunkSize) throws IOException {
            setBody(response, content.getBytes("UTF-8"), chunkSize);
        }
    }

    enum ProxyConfig {
        NO_PROXY() {
            @Override public OkHttpConnection connect(MockWebServer server, URL url)
                    throws IOException {
                return openConnection(url, Proxy.NO_PROXY);
            }
        },

        CREATE_ARG() {
            @Override public OkHttpConnection connect(MockWebServer server, URL url)
                    throws IOException {
                return openConnection(url, server.toProxyAddress());
            }
        },

        PROXY_SYSTEM_PROPERTY() {
            @Override public OkHttpConnection connect(MockWebServer server, URL url)
                    throws IOException {
                System.setProperty("proxyHost", "localhost");
                System.setProperty("proxyPort", Integer.toString(server.getPort()));
                return openConnection(url);
            }
        },

        HTTP_PROXY_SYSTEM_PROPERTY() {
            @Override public OkHttpConnection connect(MockWebServer server, URL url)
                    throws IOException {
                System.setProperty("http.proxyHost", "localhost");
                System.setProperty("http.proxyPort", Integer.toString(server.getPort()));
                return openConnection(url);
            }
        },

        HTTPS_PROXY_SYSTEM_PROPERTY() {
            @Override public OkHttpConnection connect(MockWebServer server, URL url)
                    throws IOException {
                System.setProperty("https.proxyHost", "localhost");
                System.setProperty("https.proxyPort", Integer.toString(server.getPort()));
                return openConnection(url);
            }
        };

        public abstract OkHttpConnection connect(MockWebServer server, URL url) throws IOException;
    }

    private static class RecordingTrustManager implements X509TrustManager {
        private final List<String> calls = new ArrayList<String>();

        public X509Certificate[] getAcceptedIssuers() {
            calls.add("getAcceptedIssuers");
            return new X509Certificate[] {};
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            calls.add("checkClientTrusted " + certificatesToString(chain) + " " + authType);
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            calls.add("checkServerTrusted " + certificatesToString(chain) + " " + authType);
        }

        private String certificatesToString(X509Certificate[] certificates) {
            List<String> result = new ArrayList<String>();
            for (X509Certificate certificate : certificates) {
                result.add(certificate.getSubjectDN() + " " + certificate.getSerialNumber());
            }
            return result.toString();
        }
    }

    private static class RecordingHostnameVerifier implements HostnameVerifier {
        private final List<String> calls = new ArrayList<String>();

        public boolean verify(String hostname, SSLSession session) {
            calls.add("verify " + hostname);
            return true;
        }
    }

    private static class RecordingAuthenticator extends Authenticator {
        private final List<String> calls = new ArrayList<String>();
        private final PasswordAuthentication authentication;

        public RecordingAuthenticator(PasswordAuthentication authentication) {
            this.authentication = authentication;
        }

        public RecordingAuthenticator() {
            this(new PasswordAuthentication("username", "password".toCharArray()));
        }

        @Override protected PasswordAuthentication getPasswordAuthentication() {
            this.calls.add("host=" + getRequestingHost()
                    + " port=" + getRequestingPort()
                    + " site=" + getRequestingSite()
                    + " url=" + getRequestingURL()
                    + " type=" + getRequestorType()
                    + " prompt=" + getRequestingPrompt()
                    + " protocol=" + getRequestingProtocol()
                    + " scheme=" + getRequestingScheme());
            return authentication;
        }
    }
}
