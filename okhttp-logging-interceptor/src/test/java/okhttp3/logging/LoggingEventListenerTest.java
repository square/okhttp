/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.logging;

import java.io.IOException;
import java.net.UnknownHostException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.testing.PlatformRule;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.Protocol.HTTP_1_1;
import static okhttp3.Protocol.HTTP_2;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class LoggingEventListenerTest {
  private static final MediaType PLAIN = MediaType.get("text/plain");

  @Rule public final PlatformRule platform = new PlatformRule();
  @Rule public final MockWebServer server = new MockWebServer();

  private final HandshakeCertificates handshakeCertificates = localhost();
  private final LogRecorder logRecorder = new LogRecorder();
  private final LoggingEventListener.Factory loggingEventListenerFactory =
      new LoggingEventListener.Factory(logRecorder);
  private OkHttpClient client;
  private HttpUrl url;

  @Before
  public void setUp() {
    client =
        new OkHttpClient.Builder()
            .eventListenerFactory(loggingEventListenerFactory)
            .sslSocketFactory(
                handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
            .retryOnConnectionFailure(false)
            .build();

    url = server.url("/");
  }

  @Test
  public void get() throws Exception {
    server.enqueue(new MockResponse().setBody("Hello!").setHeader("Content-Type", PLAIN));
    Response response = client.newCall(request().build()).execute();
    assertThat(response.body()).isNotNull();
    response.body().bytes();

    logRecorder
        .assertLogMatch("callStart: Request\\{method=GET, url=" + url + "\\}")
        .assertLogMatch("proxySelectStart: " + url)
        .assertLogMatch("proxySelectEnd: \\[DIRECT\\]")
        .assertLogMatch("dnsStart: " + url.host())
        .assertLogMatch("dnsEnd: \\[.+\\]")
        .assertLogMatch("connectStart: " + url.host() + "/.+ DIRECT")
        .assertLogMatch("connectEnd: http/1.1")
        .assertLogMatch(
            "connectionAcquired: Connection\\{"
                + url.host()
                + ":\\d+, proxy=DIRECT hostAddress="
                + url.host()
                + "/.+ cipherSuite=none protocol=http/1\\.1\\}")
        .assertLogMatch("requestHeadersStart")
        .assertLogMatch("requestHeadersEnd")
        .assertLogMatch("responseHeadersStart")
        .assertLogMatch(
            "responseHeadersEnd: Response\\{protocol=http/1\\.1, code=200, message=OK, url="
                + url
                + "}")
        .assertLogMatch("responseBodyStart")
        .assertLogMatch("responseBodyEnd: byteCount=6")
        .assertLogMatch("connectionReleased")
        .assertLogMatch("callEnd")
        .assertNoMoreLogs();
  }

  @Test
  public void post() throws IOException {
    server.enqueue(new MockResponse());
    client.newCall(request().post(RequestBody.create("Hello!", PLAIN)).build()).execute();

    logRecorder
        .assertLogMatch("callStart: Request\\{method=POST, url=" + url + "\\}")
        .assertLogMatch("proxySelectStart: " + url)
        .assertLogMatch("proxySelectEnd: \\[DIRECT\\]")
        .assertLogMatch("dnsStart: " + url.host())
        .assertLogMatch("dnsEnd: \\[.+\\]")
        .assertLogMatch("connectStart: " + url.host() + "/.+ DIRECT")
        .assertLogMatch("connectEnd: http/1.1")
        .assertLogMatch(
            "connectionAcquired: Connection\\{"
                + url.host()
                + ":\\d+, proxy=DIRECT hostAddress="
                + url.host()
                + "/.+ cipherSuite=none protocol=http/1\\.1\\}")
        .assertLogMatch("requestHeadersStart")
        .assertLogMatch("requestHeadersEnd")
        .assertLogMatch("requestBodyStart")
        .assertLogMatch("requestBodyEnd: byteCount=6")
        .assertLogMatch("responseHeadersStart")
        .assertLogMatch(
            "responseHeadersEnd: Response\\{protocol=http/1\\.1, code=200, message=OK, url="
                + url
                + "}")
        .assertLogMatch("responseBodyStart")
        .assertLogMatch("responseBodyEnd: byteCount=0")
        .assertLogMatch("connectionReleased")
        .assertLogMatch("callEnd")
        .assertNoMoreLogs();
  }

  @Test
  public void secureGet() throws Exception {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    url = server.url("/");

    server.enqueue(new MockResponse());
    Response response = client.newCall(request().build()).execute();
    assertThat(response.body()).isNotNull();
    response.body().bytes();

    platform.assumeHttp2Support();

    logRecorder
        .assertLogMatch("callStart: Request\\{method=GET, url=" + url + "\\}")
        .assertLogMatch("proxySelectStart: " + url)
        .assertLogMatch("proxySelectEnd: \\[DIRECT\\]")
        .assertLogMatch("dnsStart: " + url.host())
        .assertLogMatch("dnsEnd: \\[.+\\]")
        .assertLogMatch("connectStart: " + url.host() + "/.+ DIRECT")
        .assertLogMatch("secureConnectStart")
        .assertLogMatch("secureConnectEnd: Handshake\\{"
            + "tlsVersion=TLS_1_[23] "
            + "cipherSuite=TLS_.* "
            + "peerCertificates=\\[CN=localhost\\] "
            + "localCertificates=\\[\\]}")
        .assertLogMatch("connectEnd: h2")
        .assertLogMatch(
            "connectionAcquired: Connection\\{"
                + url.host()
                + ":\\d+, proxy=DIRECT hostAddress="
                + url.host()
                + "/.+ cipherSuite=.+ protocol=h2}")
        .assertLogMatch("requestHeadersStart")
        .assertLogMatch("requestHeadersEnd")
        .assertLogMatch("responseHeadersStart")
        .assertLogMatch(
            "responseHeadersEnd: Response\\{protocol=h2, code=200, message=, url=" + url + "}")
        .assertLogMatch("responseBodyStart")
        .assertLogMatch("responseBodyEnd: byteCount=0")
        .assertLogMatch("connectionReleased")
        .assertLogMatch("callEnd")
        .assertNoMoreLogs();
  }

  @Test
  public void dnsFail() throws IOException {
    client = new OkHttpClient.Builder()
        .dns(hostname -> { throw new UnknownHostException("reason"); })
        .eventListenerFactory(loggingEventListenerFactory)
        .build();

    try {
      client.newCall(request().build()).execute();
      fail();
    } catch (UnknownHostException expected) {
    }

    logRecorder
        .assertLogMatch("callStart: Request\\{method=GET, url=" + url + "\\}")
        .assertLogMatch("proxySelectStart: " + url)
        .assertLogMatch("proxySelectEnd: \\[DIRECT\\]")
        .assertLogMatch("dnsStart: " + url.host())
        .assertLogMatch("callFailed: java.net.UnknownHostException: reason")
        .assertNoMoreLogs();
  }

  @Test
  public void connectFail() {
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocols(asList(HTTP_2, HTTP_1_1));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    url = server.url("/");

    try {
      client.newCall(request().build()).execute();
      fail();
    } catch (IOException expected) {
    }

    logRecorder
        .assertLogMatch("callStart: Request\\{method=GET, url=" + url + "\\}")
        .assertLogMatch("proxySelectStart: " + url)
        .assertLogMatch("proxySelectEnd: \\[DIRECT\\]")
        .assertLogMatch("dnsStart: " + url.host())
        .assertLogMatch("dnsEnd: \\[.+\\]")
        .assertLogMatch("connectStart: " + url.host() + "/.+ DIRECT")
        .assertLogMatch("secureConnectStart")
        .assertLogMatch(
            "connectFailed: null javax\\.net\\.ssl\\.(?:SSLProtocolException|SSLHandshakeException): (?:Unexpected handshake message: client_hello|Handshake message sequence violation, 1|Read error).*")
        .assertLogMatch(
            "callFailed: javax\\.net\\.ssl\\.(?:SSLProtocolException|SSLHandshakeException): (?:Unexpected handshake message: client_hello|Handshake message sequence violation, 1|Read error).*")
        .assertNoMoreLogs();
  }

  private Request.Builder request() {
    return new Request.Builder().url(url);
  }

  private static class LogRecorder extends HttpLoggingInterceptorTest.LogRecorder {
    @Override LogRecorder assertLogMatch(String pattern) {
      return (LogRecorder) super.assertLogMatch("\\[\\d+ ms] " + pattern);
    }
  }
}
