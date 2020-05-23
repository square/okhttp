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
import static java.nio.charset.StandardCharsets.UTF_8;
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

public final class URLConnectionUploadTest {

  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
  @Rule public final MockWebServer server = new MockWebServer();
  private OkHttpClient client = clientTestRule.newClient();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @Test public void chunkedUpload_byteByByte() throws Exception {
    doUpload(TransferKind.CHUNKED, URLConnectionTest.WriteKind.BYTE_BY_BYTE);
  }

  @Test public void chunkedUpload_smallBuffers() throws Exception {
    doUpload(TransferKind.CHUNKED, URLConnectionTest.WriteKind.SMALL_BUFFERS);
  }

  @Test public void chunkedUpload_largeBuffers() throws Exception {
    doUpload(TransferKind.CHUNKED, URLConnectionTest.WriteKind.LARGE_BUFFERS);
  }

  @Test public void fixedLengthUpload_byteByByte() throws Exception {
    doUpload(TransferKind.FIXED_LENGTH, URLConnectionTest.WriteKind.BYTE_BY_BYTE);
  }

  @Test public void fixedLengthUpload_smallBuffers() throws Exception {
    doUpload(TransferKind.FIXED_LENGTH, URLConnectionTest.WriteKind.SMALL_BUFFERS);
  }

  @Test public void fixedLengthUpload_largeBuffers() throws Exception {
    doUpload(TransferKind.FIXED_LENGTH, URLConnectionTest.WriteKind.LARGE_BUFFERS);
  }

  private void doUpload(TransferKind uploadKind, URLConnectionTest.WriteKind writeKind) throws Exception {
    int n = 512 * 1024;
    server.setBodyLimit(0);
    server.enqueue(new MockResponse());

    RequestBody requestBody = new RequestBody() {
      @Override public @Nullable MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return uploadKind == TransferKind.CHUNKED ? -1L : n;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        if (writeKind == URLConnectionTest.WriteKind.BYTE_BY_BYTE) {
          for (int i = 0; i < n; ++i) {
            sink.writeByte('x');
          }
        } else {
          byte[] buf = new byte[writeKind == URLConnectionTest.WriteKind.SMALL_BUFFERS ? 256 : 64 * 1024];
          Arrays.fill(buf, (byte) 'x');
          for (int i = 0; i < n; i += buf.length) {
            sink.write(buf, 0, Math.min(buf.length, n - i));
          }
        }
      }
    };

    Response response = getResponse(new Request.Builder()
            .url(server.url("/"))
            .post(requestBody)
            .build());
    assertThat(response.code()).isEqualTo(200);
    RecordedRequest request = server.takeRequest();
    assertThat(request.getBodySize()).isEqualTo(n);
    if (uploadKind == TransferKind.CHUNKED) {
      assertThat(request.getChunkSizes()).isNotEmpty();
    } else {
      assertThat(request.getChunkSizes()).isEmpty();
    }
  }

  private Response getResponse(Request request) throws IOException {
    return client.newCall(request).execute();
  }
}
