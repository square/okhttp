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
import okio.*;
import org.junit.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class URLConnectionStreamTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private OkHttpClient client = clientTestRule.newClient();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @After public void tearDown() throws Exception {
    Authenticator.setDefault(null);
  }


  @Test public void flushAfterStreamTransmittedWithChunkedEncoding() throws IOException {
    testFlushAfterStreamTransmitted(TransferKind.CHUNKED);
  }

  @Test public void flushAfterStreamTransmittedWithFixedLength() throws IOException {
    testFlushAfterStreamTransmitted(TransferKind.FIXED_LENGTH);
  }

  @Test public void flushAfterStreamTransmittedWithNoLengthHeaders() throws IOException {
    testFlushAfterStreamTransmitted(TransferKind.END_OF_STREAM);
  }

  /**
   * We explicitly permit apps to close the upload stream even after it has been transmitted.  We
   * also permit flush so that buffered streams can do a no-op flush when they are closed.
   * http://b/3038470
   */
  private void testFlushAfterStreamTransmitted(TransferKind transferKind) throws IOException {
    server.enqueue(new MockResponse()
            .setBody("abc"));

    AtomicReference<BufferedSink> sinkReference = new AtomicReference<>();
    Response response = getResponse(new Request.Builder()
            .url(server.url("/"))
            .post(new ForwardingRequestBody(transferKind.newRequestBody("def")) {
              @Override public void writeTo(BufferedSink sink) throws IOException {
                sinkReference.set(sink);
                super.writeTo(sink);
              }
            })
            .build());

    assertThat(readAscii(response.body().byteStream(), Integer.MAX_VALUE)).isEqualTo(
            "abc");

    try {
      sinkReference.get().flush();
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      sinkReference.get().write("ghi".getBytes(UTF_8));
      sinkReference.get().emit();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  // http://code.google.com/p/android/issues/detail?id=20442
  @Test public void inputStreamAvailableWithChunkedEncoding() throws Exception {
    testInputStreamAvailable(TransferKind.CHUNKED);
  }

  @Test public void inputStreamAvailableWithContentLengthHeader() throws Exception {
    testInputStreamAvailable(TransferKind.FIXED_LENGTH);
  }

  @Test public void inputStreamAvailableWithNoLengthHeaders() throws Exception {
    testInputStreamAvailable(TransferKind.END_OF_STREAM);
  }

  private void testInputStreamAvailable(TransferKind transferKind) throws IOException {
    String body = "ABCDEFGH";
    MockResponse mockResponse = new MockResponse();
    transferKind.setBody(mockResponse, body, 4);
    server.enqueue(mockResponse);
    Response response = getResponse(newRequest("/"));
    InputStream in = response.body().byteStream();
    for (int i = 0; i < body.length(); i++) {
      assertThat(in.available()).isGreaterThanOrEqualTo(0);
      assertThat(in.read()).isEqualTo(body.charAt(i));
    }
    assertThat(in.available()).isEqualTo(0);
    assertThat(in.read()).isEqualTo(-1);
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

  private Request newRequest(String s) {
    return newRequest(server.url(s));
  }

  private Request newRequest(HttpUrl url) {
    return new Request.Builder()
            .url(url)
            .build();
  }
}
