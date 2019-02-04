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
package okhttp3;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okhttp3.internal.duplex.AsyncRequestBody;
import okhttp3.internal.duplex.MwsDuplexAccess;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.internal.duplex.MockDuplexResponseBody;
import okhttp3.tls.HandshakeCertificates;
import okio.BufferedSink;
import okio.BufferedSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import static junit.framework.TestCase.assertTrue;
import static okhttp3.TestUtil.defaultClient;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class DuplexTest {
  @Rule public final TestRule timeout = new Timeout(30_000, TimeUnit.MILLISECONDS);
  @Rule public final MockWebServer server = new MockWebServer();

  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = defaultClient();

  @Test public void trueDuplexClientWritesFirst() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .receiveRequest("request A\n")
            .sendResponse("response B\n")
            .receiveRequest("request C\n")
            .sendResponse("response D\n")
            .receiveRequest("request E\n")
            .sendResponse("response F\n")
            .exhaustRequest());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      requestBody.writeUtf8("request A\n");
      requestBody.flush();

      BufferedSource responseBody = response.body().source();
      assertEquals("response B", responseBody.readUtf8Line());

      requestBody.writeUtf8("request C\n");
      requestBody.flush();
      assertEquals("response D", responseBody.readUtf8Line());

      requestBody.writeUtf8("request E\n");
      requestBody.flush();
      assertEquals("response F", responseBody.readUtf8Line());

      requestBody.close();
      assertNull(responseBody.readUtf8Line());
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void trueDuplexServerWritesFirst() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders(),
        new MockDuplexResponseBody()
            .sendResponse("response A\n")
            .receiveRequest("request B\n")
            .sendResponse("response C\n")
            .receiveRequest("request D\n")
            .sendResponse("response E\n")
            .receiveRequest("request F\n"));

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(new AsyncRequestBody())
        .build());

    try (Response response = call.execute()) {
      BufferedSink requestBody = ((AsyncRequestBody) call.request().body()).takeSink();
      BufferedSource responseBody = response.body().source();

      assertEquals("response A", responseBody.readUtf8Line());
      requestBody.writeUtf8("request B\n");
      requestBody.flush();

      assertEquals("response C", responseBody.readUtf8Line());
      requestBody.writeUtf8("request D\n");
      requestBody.flush();

      assertEquals("response E", responseBody.readUtf8Line());
      requestBody.writeUtf8("request F\n");
      requestBody.flush();

      assertNull(responseBody.readUtf8Line());
      requestBody.close();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void clientReadsHeadersDataTrailers() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .addHeader("h1", "v1")
            .addHeader("h2", "v2")
            .setTrailers(Headers.of("trailers", "boom")),
        new MockDuplexResponseBody()
            .sendResponse("ok")
            .exhaustResponse());

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    try (Response response = call.execute()) {
      assertEquals(Headers.of("h1", "v1", "h2", "v2"), response.headers());

      BufferedSource responseBody = response.body().source();
      assertEquals("ok", responseBody.readUtf8(2));
      assertTrue(responseBody.exhausted());
      assertEquals(Headers.of("trailers", "boom"), response.trailers());
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  @Test public void serverReadsHeadersData() throws Exception {
    enableProtocol(Protocol.HTTP_2);
    MockDuplexResponseBody mockDuplexResponseBody = enqueueResponseWithBody(
        new MockResponse()
            .clearHeaders()
            .addHeader("h1", "v1")
            .addHeader("h2", "v2"),
        new MockDuplexResponseBody()
            .exhaustResponse()
            .receiveRequest("hey\n")
            .receiveRequest("whats going on\n")
            .exhaustRequest());

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", new AsyncRequestBody())
        .build();
    Call call = client.newCall(request);

    try (Response response = call.execute()) {
      BufferedSink sink = ((AsyncRequestBody) request.body()).takeSink();
      sink.writeUtf8("hey\n");
      sink.writeUtf8("whats going on\n");
      sink.close();
    }

    mockDuplexResponseBody.awaitSuccess();
  }

  // TODO(oldergod) write tests for headers discarded with 100 Continue

  private MockDuplexResponseBody enqueueResponseWithBody(
      MockResponse response, MockDuplexResponseBody body) {
    MwsDuplexAccess.instance.setBody(response, body);
    server.enqueue(response);
    return body;
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. {@code
   * -Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317}
   */
  private void enableProtocol(Protocol protocol) {
    enableTls();
    client = client.newBuilder()
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build();
    server.setProtocols(client.protocols());
  }

  private void enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();
    server.useHttps(handshakeCertificates.sslSocketFactory(), false);
  }
}
