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

import okhttp3.internal.Internal;
import okhttp3.internal.RecordingAuthenticator;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import javax.annotation.Nullable;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.util.Arrays.asList;
import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static okhttp3.internal.Internal.addHeaderLenient;
import static okhttp3.internal.Util.immutableListOf;
import static okhttp3.internal.Util.userAgent;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static okhttp3.mockwebserver.SocketPolicy.*;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class URLZeroLengthConnectionTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = clientTestRule.newClient();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  /** For example, empty Protobuf RPC messages end up as a zero-length POST. */
  @Test public void zeroLengthPost() throws Exception {
    zeroLengthPayload("POST");
  }

  @Test public void zeroLengthPost_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    zeroLengthPost();
  }

  /** For example, creating an Amazon S3 bucket ends up as a zero-length POST. */
  @Test public void zeroLengthPut() throws Exception {
    zeroLengthPayload("PUT");
  }

  @Test public void zeroLengthPut_HTTP_2() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    zeroLengthPut();
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    client = client.newBuilder()
            .sslSocketFactory(
                    handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
            .hostnameVerifier(new RecordingHostnameVerifier())
            .protocols(asList(protocol, Protocol.HTTP_1_1))
            .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
    server.setProtocolNegotiationEnabled(true);
    server.setProtocols(client.protocols());
  }

  private void zeroLengthPayload(String method) throws Exception {
    server.enqueue(new MockResponse());

    Response response = getResponse(new Request.Builder()
            .url(server.url("/"))
            .method(method, RequestBody.create("", null))
            .build());
    assertContent("", response);
    RecordedRequest zeroLengthPayload = server.takeRequest();
    assertThat(zeroLengthPayload.getMethod()).isEqualTo(method);
    assertThat(zeroLengthPayload.getHeader("content-length")).isEqualTo("0");
    assertThat(zeroLengthPayload.getBodySize()).isEqualTo(0L);
  }

  private Response getResponse(Request request) throws IOException {
    return client.newCall(request).execute();
  }

  private void assertContent(String expected, Response response, int limit)
          throws IOException {
    assertThat(readAscii(response.body().byteStream(), limit)).isEqualTo(
            expected);
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

  private void assertContent(String expected, Response response) throws IOException {
    assertContent(expected, response, Integer.MAX_VALUE);
  }
}
