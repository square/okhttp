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
import okio.*;
import org.junit.*;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

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
