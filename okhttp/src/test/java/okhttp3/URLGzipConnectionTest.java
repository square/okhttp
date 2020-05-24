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
import okhttp3.tls.HandshakeCertificates;
import okio.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;

public final class URLGzipConnectionTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = clientTestRule.newClient();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @Test public void gzipAndConnectionReuseWithFixedLength() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, false);
  }

  @Test public void gzipAndConnectionReuseWithChunkedEncoding() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, false);
  }

  @Test public void gzipAndConnectionReuseWithFixedLengthAndTls() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, true);
  }

  @Test public void gzipAndConnectionReuseWithChunkedEncodingAndTls() throws Exception {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, true);
  }

  /**
   * Test a bug where gzip input streams weren't exhausting the input stream, which corrupted the
   * request that followed or prevented connection reuse. http://code.google.com/p/android/issues/detail?id=7059
   * http://code.google.com/p/android/issues/detail?id=38817
   */
  private void testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind transferKind,
                                                                         boolean tls) throws Exception {
    if (tls) {
      SSLSocketFactory socketFactory = handshakeCertificates.sslSocketFactory();
      RecordingHostnameVerifier hostnameVerifier = new RecordingHostnameVerifier();
      server.useHttps(socketFactory, false);
      client = client.newBuilder()
              .sslSocketFactory(socketFactory, handshakeCertificates.trustManager())
              .hostnameVerifier(hostnameVerifier)
              .build();
    }

    MockResponse responseOne = new MockResponse()
            .addHeader("Content-Encoding: gzip");
    transferKind.setBody(responseOne, gzip("one (gzipped)"), 5);
    server.enqueue(responseOne);
    MockResponse responseTwo = new MockResponse();
    transferKind.setBody(responseTwo, "two (identity)", 5);
    server.enqueue(responseTwo);

    Response response1 = getResponse(new Request.Builder()
            .header("Accept-Encoding", "gzip")
            .url(server.url("/"))
            .build());
    InputStream gunzippedIn = new GZIPInputStream(response1.body().byteStream());
    assertThat(readAscii(gunzippedIn, Integer.MAX_VALUE)).isEqualTo(
            "one (gzipped)");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(0);

    Response response2 = getResponse(new Request.Builder()
            .url(server.url("/"))
            .build());
    assertThat(readAscii(response2.body().byteStream(), Integer.MAX_VALUE)).isEqualTo(
            "two (identity)");
    assertThat(server.takeRequest().getSequenceNumber()).isEqualTo(1);
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

  /** Returns a gzipped copy of {@code bytes}. */
  private Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink gzipSink = Okio.buffer(new GzipSink(result));
    gzipSink.writeUtf8(data);
    gzipSink.close();
    return result;
  }


}
