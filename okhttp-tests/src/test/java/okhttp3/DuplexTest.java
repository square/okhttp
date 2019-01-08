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

public final class DuplexTest {
  @Rule public final TestRule timeout = new Timeout(30_000, TimeUnit.MILLISECONDS);
  @Rule public final MockWebServer server = new MockWebServer();

  private HandshakeCertificates handshakeCertificates = localhost();
  private OkHttpClient client = defaultClient();

  @Test public void clientReadsHeadersDataTrailers() throws IOException {
    final LatchParty latchParty = new LatchParty();
    MockResponse mockResponse = new MockResponse()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .setTrailers(Headers.of("trailers", "boom"));
    MwsDuplexAccess.instance.setBody(mockResponse, new DuplexResponseBody() {
      @Override public void onRequest(RecordedRequest request,
          BufferedSource requestBodySource, BufferedSink responseBodySink) throws IOException {

        latchParty.step(1);
        responseBodySink.writeUtf8("ok");
        responseBodySink.flush();

        latchParty.step(3);
        responseBodySink.writeUtf8("taco");
        responseBodySink.flush();

        latchParty.step(5);
        responseBodySink.close();
      }
    });
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .duplex("POST")
        .build());

    try (Response response = call.execute()) {
      assertEquals(Headers.of("h1", "v1", "h2", "v2"), response.headers());

      latchParty.step(2);
      BufferedSource source = response.body().source();
      assertEquals("ok", source.readUtf8(2));

      latchParty.step(4);
      assertEquals("taco", source.readUtf8(4));

      latchParty.step(6);
      assertTrue(source.exhausted());
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
          BufferedSource requestBodySource, BufferedSink responseBodySink) throws IOException {
        responseBodySink.close();

        requestBodySourceRef.set(requestBodySource);
      }
    });
    server.enqueue(mockResponse);
    enableProtocol(Protocol.HTTP_2);

    Call call = client.newCall(new Request.Builder()
        .url(server.url("/"))
        .duplex("POST")
        .build());

    try (Response response = call.execute()) {
      BufferedSink sink = response.sink();
      sink.writeUtf8("hey\n");
      sink.writeUtf8("whats going on\n");
      sink.close();

      // check what the server received
      BufferedSource requestBodySource = requestBodySourceRef.get();
      assertEquals("hey", requestBodySource.readUtf8Line());
      assertEquals("whats going on", requestBodySource.readUtf8Line());
      assertTrue(requestBodySource.exhausted());
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