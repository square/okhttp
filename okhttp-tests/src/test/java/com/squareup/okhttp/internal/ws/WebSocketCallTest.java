/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal.ws;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.RecordedResponse;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public final class WebSocketCallTest {
  @Rule public final MockWebServerRule server = new MockWebServerRule();

  private final WebSocketRecorder listener = new WebSocketRecorder();
  private final OkHttpClient client = new OkHttpClient();
  private final Random random = new Random(0);

  @After public void tearDown() {
    listener.assertExhausted();
  }

  @Test public void okButNotOk() {
    server.enqueue(new MockResponse());
    awaitCall();
    listener.assertFailure(ProtocolException.class, "Expected HTTP 101 response but was '200 OK'");
  }

  @Test public void notFound() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 404 Not Found"));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected HTTP 101 response but was '404 Not Found'");
  }

  @Test public void missingConnectionHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'null'");
  }

  @Test public void wrongConnectionHeader() {
    server.enqueue(new MockResponse().setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'Downgrade'");
  }

  @Test public void missingUpgradeHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'null'");
  }

  @Test public void wrongUpgradeHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'Pepsi'");
  }

  @Test public void missingMagicHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket"));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'");
  }

  @Test public void wrongMagicHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic"));
    awaitCall();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'");
  }

  private RecordedResponse awaitCall() {
    Request request = new Request.Builder().get().url(server.getUrl("/")).build();
    WebSocketCall call = new WebSocketCall(client, request, random);

    final AtomicReference<Response> responseRef = new AtomicReference<>();
    final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
    final AtomicReference<IOException> failureRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    call.enqueue(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Request request, Response response)
          throws IOException {
        webSocketRef.set(webSocket);
        responseRef.set(response);
        latch.countDown();
      }

      @Override public void onMessage(BufferedSource payload, WebSocket.PayloadType type)
          throws IOException {
        listener.onMessage(payload, type);
      }

      @Override public void onPong(Buffer payload) {
        listener.onPong(payload);
      }

      @Override public void onClose(int code, String reason) {
        listener.onClose(code, reason);
      }

      @Override public void onFailure(IOException e) {
        listener.onFailure(e);
        failureRef.set(e);
        latch.countDown();
      }
    });

    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    if (latch.getCount() > 0) {
      throw new AssertionError("Timed out.");
    }

    return new RecordedResponse(request, responseRef.get(), webSocketRef.get(), null,
        failureRef.get());
  }
}
