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

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import org.junit.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.*;
import java.util.*;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.util.Arrays.asList;
import static okhttp3.mockwebserver.SocketPolicy.*;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;

public final class URLConnectionProxyTest {

  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final MockWebServer server2 = new MockWebServer();
  private OkHttpClient client = clientTestRule.newClient();
  private HandshakeCertificates handshakeCertificates = localhost();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @After public void tearDown() throws Exception {
    Authenticator.setDefault(null);
    System.clearProperty("proxyHost");
    System.clearProperty("proxyPort");
    System.clearProperty("http.proxyHost");
    System.clearProperty("http.proxyPort");
    System.clearProperty("https.proxyHost");
    System.clearProperty("https.proxyPort");
  }

  enum ProxyConfig {
    NO_PROXY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        return client.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .build();
      }
    },

    CREATE_ARG() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        return client.newBuilder()
                .proxy(server.toProxyAddress())
                .build();
      }
    },

    PROXY_SYSTEM_PROPERTY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        System.setProperty("proxyHost", server.getHostName());
        System.setProperty("proxyPort", Integer.toString(server.getPort()));
        return client;
      }
    },

    HTTP_PROXY_SYSTEM_PROPERTY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        System.setProperty("http.proxyHost", server.getHostName());
        System.setProperty("http.proxyPort", Integer.toString(server.getPort()));
        return client;
      }
    },

    HTTPS_PROXY_SYSTEM_PROPERTY() {
      @Override public Call.Factory connect(MockWebServer server, OkHttpClient client) {
        System.setProperty("https.proxyHost", server.getHostName());
        System.setProperty("https.proxyPort", Integer.toString(server.getPort()));
        return client;
      }
    };

    public abstract Call.Factory connect(MockWebServer server, OkHttpClient client)
            throws IOException;

    public Call connect(
            MockWebServer server, OkHttpClient client, HttpUrl url) throws IOException {
      Request request = new Request.Builder()
              .url(url)
              .build();
      return connect(server, client).newCall(request);
    }
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
    MockResponse mockResponse = new MockResponse()
            .setBody("this response comes via a proxy");
    server.enqueue(mockResponse);

    HttpUrl url = HttpUrl.parse("http://android.com/foo");
    Response response = proxyConfig.connect(server, client, url).execute();
    assertContent("this response comes via a proxy", response);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo(
            "GET http://android.com/foo HTTP/1.1");
    assertThat(request.getHeader("Host")).isEqualTo("android.com");
  }

  @Test public void connectViaHttpProxyToHttpsUsingProxyArgWithNoProxy() throws Exception {
    testConnectViaDirectProxyToHttps(ProxyConfig.NO_PROXY);
  }

  @Test public void connectViaHttpProxyToHttpsUsingHttpProxySystemProperty() throws Exception {
    // https should not use http proxy
    testConnectViaDirectProxyToHttps(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY);
  }

  private void testConnectViaDirectProxyToHttps(ProxyConfig proxyConfig) throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.enqueue(new MockResponse()
            .setBody("this response comes via HTTPS"));

    HttpUrl url = server.url("/foo");
    client = client.newBuilder()
            .sslSocketFactory(
                    handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
            .hostnameVerifier(new RecordingHostnameVerifier())
            .build();
    Call call = proxyConfig.connect(server, client, url);

    assertContent("this response comes via HTTPS", call.execute());

    RecordedRequest request = server.takeRequest();
    assertThat(request.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
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

    server.useHttps(handshakeCertificates.sslSocketFactory(), true);
    server.enqueue(new MockResponse()
            .setSocketPolicy(UPGRADE_TO_SSL_AT_END)
            .clearHeaders());
    server.enqueue(new MockResponse()
            .setBody("this response comes via a secure proxy"));

    HttpUrl url = HttpUrl.parse("https://android.com/foo");
    client = client.newBuilder()
            .sslSocketFactory(
                    handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
            .hostnameVerifier(hostnameVerifier)
            .build();
    Call call = proxyConfig.connect(server, client, url);

    assertContent("this response comes via a secure proxy", call.execute());

    RecordedRequest connect = server.takeRequest();
    assertThat(connect.getRequestLine()).overridingErrorMessage(
            "Connect line failure on proxy").isEqualTo("CONNECT android.com:443 HTTP/1.1");
    assertThat(connect.getHeader("Host")).isEqualTo("android.com:443");

    RecordedRequest get = server.takeRequest();
    assertThat(get.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(get.getHeader("Host")).isEqualTo("android.com");
    assertThat(hostnameVerifier.calls).isEqualTo(
            asList("verify android.com"));
  }

  private void assertContent(String expected, Response response, int limit)
          throws IOException {
    assertThat(readAscii(response.body().byteStream(), limit)).isEqualTo(
            expected);
  }

  private void assertContent(String expected, Response response) throws IOException {
    assertContent(expected, response, Integer.MAX_VALUE);
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

  @Test public void redirectWithProxySelector() throws Exception {
    final List<URI> proxySelectionRequests = new ArrayList<>();
    client = client.newBuilder()
            .proxySelector(new ProxySelector() {
              @Override public List<Proxy> select(URI uri) {
                proxySelectionRequests.add(uri);
                MockWebServer proxyServer = (uri.getPort() == server.getPort())
                        ? server
                        : server2;
                return asList(proxyServer.toProxyAddress());
              }

              @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                throw new AssertionError();
              }
            })
            .build();

    server2.enqueue(new MockResponse()
            .setBody("This is the 2nd server!"));

    server.enqueue(new MockResponse()
            .setResponseCode(HTTP_MOVED_TEMP)
            .addHeader("Location: " + server2.url("/b").toString())
            .setBody("This page has moved!"));

    assertContent("This is the 2nd server!", getResponse(newRequest("/a")));

    assertThat(proxySelectionRequests).isEqualTo(
            asList(server.url("/").url().toURI(), server2.url("/").url().toURI()));
  }

  @Test public void response305UseProxy() throws Exception {
    server.enqueue(new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_USE_PROXY)
            .addHeader("Location: " + server.url("/").url())
            .setBody("This page has moved!"));
    server.enqueue(new MockResponse()
            .setBody("Proxy Response"));

    Response response = getResponse(newRequest("/foo"));
    // Fails on the RI, which gets "Proxy Response".
    assertThat(readAscii(response.body().byteStream(), Integer.MAX_VALUE)).isEqualTo(
            "This page has moved!");

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo("GET /foo HTTP/1.1");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

 /* @Test @Ignore public void pooledConnectionProblemsNotReportedToProxySelector() {
    fail("TODO");
  } */

  private Request newRequest(String s) {
    return newRequest(server.url(s));
  }

  private Request newRequest(HttpUrl url) {
    return new Request.Builder()
            .url(url)
            .build();
  }

  private Response getResponse(Request request) throws IOException {
    return client.newCall(request).execute();
  }
}
