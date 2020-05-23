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

public final class URLResponseConnectionTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private OkHttpClient client = clientTestRule.newClient();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @Test public void response307WithGet() throws Exception {
    testRedirect(true, "GET");
  }

  @Test public void response307WithHead() throws Exception {
    testRedirect(true, "HEAD");
  }

  @Test public void response307WithOptions() throws Exception {
    testRedirect(true, "OPTIONS");
  }

  @Test public void response307WithPost() throws Exception {
    testRedirect(true, "POST");
  }

  @Test public void response308WithGet() throws Exception {
    testRedirect(false, "GET");
  }

  @Test public void response308WithHead() throws Exception {
    testRedirect(false, "HEAD");
  }

  @Test public void response308WithOptions() throws Exception {
    testRedirect(false, "OPTIONS");
  }

  @Test public void response308WithPost() throws Exception {
    testRedirect(false, "POST");
  }

  private void testRedirect(boolean temporary, String method) throws Exception {
    MockResponse response1 = new MockResponse()
            .setResponseCode(temporary ? HTTP_TEMP_REDIRECT : HTTP_PERM_REDIRECT)
            .addHeader("Location: /page2");
    if (!method.equals("HEAD")) {
      response1.setBody("This page has moved!");
    }
    server.enqueue(response1);
    server.enqueue(new MockResponse()
            .setBody("Page 2"));

    Request.Builder requestBuilder = new Request.Builder()
            .url(server.url("/page1"));
    if (method.equals("POST")) {
      requestBuilder.post(RequestBody.create("ABCD", null));
    } else {
      requestBuilder.method(method, null);
    }

    Response response = getResponse(requestBuilder.build());
    String responseString = readAscii(response.body().byteStream(), Integer.MAX_VALUE);

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo((method + " /page1 HTTP/1.1"));

    if (method.equals("GET")) {
      assertThat(responseString).isEqualTo("Page 2");
    } else if (method.equals("HEAD")) {
      assertThat(responseString).isEqualTo("");
    }

    assertThat(server.getRequestCount()).isEqualTo(2);
    RecordedRequest page2 = server.takeRequest();
    assertThat(page2.getRequestLine()).isEqualTo((method + " /page2 HTTP/1.1"));
  }

  private Response getResponse(Request request) throws IOException {
    return client.newCall(request).execute();
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

  private Request newRequest(HttpUrl url) {
    return new Request.Builder()
            .url(url)
            .build();
  }

  @Test public void response307WithPostReverted() throws Exception {
    client = client.newBuilder()
            .addNetworkInterceptor(new URLConnectionTest.LegacyRedirectInterceptor())
            .build();

    MockResponse response1 = new MockResponse()
            .setResponseCode(HTTP_TEMP_REDIRECT)
            .setBody("This page has moved!")
            .addHeader("Location: /page2");
    server.enqueue(response1);

    Request.Builder requestBuilder = new Request.Builder()
            .url(server.url("/page1"));
    requestBuilder.post(RequestBody.create("ABCD", null));

    Response response = getResponse(requestBuilder.build());
    String responseString = readAscii(response.body().byteStream(), Integer.MAX_VALUE);

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo(("POST /page1 HTTP/1.1"));

    assertThat(page1.getBody().readUtf8()).isEqualTo("ABCD");
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(responseString).isEqualTo("This page has moved!");
  }

  @Test public void response308WithPostReverted() throws Exception {
    client = client.newBuilder()
            .addNetworkInterceptor(new URLConnectionTest.LegacyRedirectInterceptor())
            .build();

    MockResponse response1 = new MockResponse()
            .setResponseCode(HTTP_PERM_REDIRECT)
            .setBody("This page has moved!")
            .addHeader("Location: /page2");
    server.enqueue(response1);

    Request.Builder requestBuilder = new Request.Builder()
            .url(server.url("/page1"));
    requestBuilder.post(RequestBody.create("ABCD", null));

    Response response = getResponse(requestBuilder.build());
    String responseString = readAscii(response.body().byteStream(), Integer.MAX_VALUE);

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo(("POST /page1 HTTP/1.1"));

    assertThat(page1.getBody().readUtf8()).isEqualTo("ABCD");
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(responseString).isEqualTo("This page has moved!");
  }

  @Test public void response300MultipleChoiceWithPost() throws Exception {
    // Chrome doesn't follow the redirect, but Firefox and the RI both do
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MULT_CHOICE, TransferKind.END_OF_STREAM);
  }

  @Test public void response301MovedPermanentlyWithPost() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_PERM, TransferKind.END_OF_STREAM);
  }

  @Test public void response302MovedTemporarilyWithPost() throws Exception {
    testResponseRedirectedWithPost(HTTP_MOVED_TEMP, TransferKind.END_OF_STREAM);
  }

  @Test public void response303SeeOtherWithPost() throws Exception {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_SEE_OTHER, TransferKind.END_OF_STREAM);
  }

  @Test public void postRedirectToGetWithChunkedRequest() throws Exception {
    testResponseRedirectedWithPost(HTTP_MOVED_TEMP, TransferKind.CHUNKED);
  }

  @Test public void postRedirectToGetWithStreamedRequest() throws Exception {
    testResponseRedirectedWithPost(HTTP_MOVED_TEMP, TransferKind.FIXED_LENGTH);
  }

  private void testResponseRedirectedWithPost(int redirectCode, TransferKind transferKind)
          throws Exception {
    server.enqueue(new MockResponse()
            .setResponseCode(redirectCode)
            .addHeader("Location: /page2")
            .setBody("This page has moved!"));
    server.enqueue(new MockResponse()
            .setBody("Page 2"));

    Response response = getResponse(new Request.Builder()
            .url(server.url("/page1"))
            .post(transferKind.newRequestBody("ABCD"))
            .build());
    assertThat(readAscii(response.body().byteStream(), Integer.MAX_VALUE)).isEqualTo(
            "Page 2");

    RecordedRequest page1 = server.takeRequest();
    assertThat(page1.getRequestLine()).isEqualTo("POST /page1 HTTP/1.1");
    assertThat(page1.getBody().readUtf8()).isEqualTo("ABCD");

    RecordedRequest page2 = server.takeRequest();
    assertThat(page2.getRequestLine()).isEqualTo("GET /page2 HTTP/1.1");
  }
}
