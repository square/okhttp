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

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.internal.duplex.DuplexRequestBody;
import okhttp3.internal.duplex.MwsDuplexAccess;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody;
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

  @Test public void trueDuplexClientWritesFirst() throws IOException {
    MockResponse mockResponse = new MockResponse();
    mockResponse.clearHeaders();
    MwsDuplexAccess.instance.setBody(mockResponse, new DuplexResponseBody() {
      @Override public void onRequest(RecordedRequest request,
          BufferedSource requestBody, BufferedSink responseBody) throws IOException {

        assertEquals("request A", requestBody.readUtf8Line());
        responseBody.writeUtf8("response B\n");
        responseBody.flush();

        assertEquals("request C", requestBody.readUtf8Line());
        responseBody.writeUtf8("response D\n");
        responseBody.flush();

        assertEquals("request E", requestBody.readUtf8Line());
        responseBody.writeUtf8("response F\n");
        responseBody.flush();

        assertNull(requestBody.readUtf8Line());
        requestBody.close();
        responseBody.close();
      }
    });
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    DuplexRequestBody duplexRequestBody = new DuplexRequestBody(null, 128);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(duplexRequestBody)
        .build());

    BufferedSink requestBody = duplexRequestBody.createSink();
    requestBody.writeUtf8("request A\n");
    requestBody.flush();

    try (Response response = call.execute()) {
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
  }

  @Test public void trueDuplexServerWritesFirst() throws IOException {
    MockResponse mockResponse = new MockResponse();
    mockResponse.clearHeaders();
    MwsDuplexAccess.instance.setBody(mockResponse, new DuplexResponseBody() {
      @Override public void onRequest(RecordedRequest request,
          BufferedSource requestBody, BufferedSink responseBody) throws IOException {
        responseBody.writeUtf8("response A\n");
        responseBody.flush();
        assertEquals("request B", requestBody.readUtf8Line());

        responseBody.writeUtf8("response C\n");
        responseBody.flush();
        assertEquals("request D", requestBody.readUtf8Line());

        responseBody.writeUtf8("response E\n");
        responseBody.flush();
        assertEquals("request F", requestBody.readUtf8Line());

        responseBody.close();
      }
    });
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    DuplexRequestBody duplexRequestBody = new DuplexRequestBody(null, 128);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .post(duplexRequestBody)
        .build());

    BufferedSink requestBody = duplexRequestBody.createSink();

    try (Response response = call.execute()) {
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
  }

  @Test public void clientReadsHeadersDataTrailers() throws IOException {
    final LatchParty latchParty = new LatchParty();
    MockResponse mockResponse = new MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setTrailers(Headers.of("trailers", "boom"));
    MwsDuplexAccess.instance.setBody(mockResponse, new DuplexResponseBody() {
      @Override public void onRequest(RecordedRequest request,
          BufferedSource requestBody, BufferedSink responseBody) throws IOException {

        latchParty.step(1);
        responseBody.writeUtf8("ok");
        responseBody.flush();

        latchParty.step(3);
        responseBody.writeUtf8("taco");
        responseBody.flush();

        latchParty.step(5);
        responseBody.close();
      }
    });
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .build());

    try (Response response = call.execute()) {
      assertEquals(Headers.of("h1", "v1", "h2", "v2"), response.headers());

      latchParty.step(2);
      BufferedSource responseBody = response.body().source();
      assertEquals("ok", responseBody.readUtf8(2));

      latchParty.step(4);
      assertEquals("taco", responseBody.readUtf8(4));

      latchParty.step(6);
      assertTrue(responseBody.exhausted());
      assertEquals(Headers.of("trailers", "boom"), response.trailers());
    }
  }

  @Test public void serverReadsHeadersData() throws IOException {
    final AtomicReference<BufferedSource> requestBodySourceRef = new AtomicReference<>();

    MockResponse mockResponse = new MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2");
    MwsDuplexAccess.instance.setBody(mockResponse, new DuplexResponseBody() {
      @Override public void onRequest(RecordedRequest request,
          BufferedSource requestBody, BufferedSink responseBody) throws IOException {
        responseBody.close();

        requestBodySourceRef.set(requestBody);
      }
    });
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    Request request = new Request.Builder()
        .url(server.url("/"))
        .method("POST", new DuplexRequestBody(null, 1024L))
        .build();
    Call call = client.newCall(request);

    BufferedSink sink = ((DuplexRequestBody) request.body).createSink();
    sink.writeUtf8("hey\n");

    try (Response response = call.execute()) {
      sink.writeUtf8("whats going on\n");
      sink.close();

      // check what the server received
      BufferedSource requestBody = requestBodySourceRef.get();
      assertEquals("hey", requestBody.readUtf8Line());
      assertEquals("whats going on", requestBody.readUtf8Line());
      assertTrue(requestBody.exhausted());
    }
  }

  // TODO(oldergod) write tests for headers discarded with 100 Continue

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

  static final class LatchParty {
    private int currentRound = 1;

    synchronized void step(int round) {
      try {
        // Wait until I can be released.
        while (currentRound != round) {
          wait();
        }

        // Release the other thread.
        currentRound++;
        notifyAll();
      } catch (InterruptedException e) {
        throw new AssertionError();
      }
    }
  }
}
