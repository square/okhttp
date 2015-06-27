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
package com.squareup.okhttp.ws;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import com.squareup.okhttp.testing.RecordingHostnameVerifier;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import okio.Buffer;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import static com.squareup.okhttp.ws.WebSocket.PayloadType.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class WebSocketCallTest {
  private static final SSLContext sslContext = SslContextBuilder.localhost();
  @Rule public final MockWebServerRule server = new MockWebServerRule();

  private final WebSocketRecorder listener = new WebSocketRecorder();
  private final OkHttpClient client = new OkHttpClient();
  private final Random random = new Random(0);

  @After public void tearDown() {
    listener.assertExhausted();
  }

  @Test public void startMustBeCalledInTimeoutTime() throws InterruptedException {
    server.enqueue(new MockResponse().withWebSocketUpgrade(new EmptyWebSocketCallback()));

    client.setConnectTimeout(1, TimeUnit.SECONDS);
    Request request = new Request.Builder().get().url(server.url("/")).build();
    WebSocketCall call = new WebSocketCall(client, request, random);

    final AtomicReference<IOException> failureRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    call.enqueue(new WebSocketCallback() {
      @Override public void onConnect(WebSocket webSocket, Response response) {
      }

      @Override public void onFailure(IOException e, Response response) {
        failureRef.set(e);
        latch.countDown();
      }
    });

    assertTrue(latch.await(2, TimeUnit.SECONDS));

    IOException failure = failureRef.get();
    assertNotNull(failure);
    assertTrue(failure instanceof InterruptedIOException);
    assertEquals("Timeout waiting for call to WebSocket.start()", failure.getMessage());
  }

  @Test public void writingBeforeStartThrows() throws IOException {
    server.enqueue(new MockResponse().withWebSocketUpgrade(new EmptyWebSocketCallback()));

    WebSocket webSocket = awaitWebSocket();
    try {
      webSocket.sendPing(new Buffer().writeUtf8("Hello, WebSockets!"));
    } catch (IllegalStateException e) {
      assertEquals("start() not called", e.getMessage());
    }
    try {
      webSocket.sendMessage(TEXT, new Buffer().writeUtf8("Hello, WebSockets!"));
    } catch (IllegalStateException e) {
      assertEquals("start() not called", e.getMessage());
    }
    try {
      webSocket.newMessageSink(TEXT);
    } catch (IllegalStateException e) {
      assertEquals("start() not called", e.getMessage());
    }
    try {
      webSocket.close(1000, "Bye!");
    } catch (IllegalStateException e) {
      assertEquals("start() not called", e.getMessage());
    }
  }

  @Test public void clientPingPong() throws IOException {
    server.enqueue(new MockResponse().withWebSocketUpgrade(new EmptyWebSocketCallback()));

    WebSocket webSocket = awaitWebSocket();
    webSocket.start(listener);
    webSocket.sendPing(new Buffer().writeUtf8("Hello, WebSockets!"));
    listener.assertPong(new Buffer().writeUtf8("Hello, WebSockets!"));
  }

  @Test public void clientMessage() throws IOException {
    final WebSocketRecorder serverListener = new WebSocketRecorder();
    server.enqueue(new MockResponse().withWebSocketUpgrade(new EmptyWebSocketCallback() {
      @Override public void onConnect(WebSocket webSocket, Response response) {
        webSocket.start(serverListener);
      }
    }));

    WebSocket webSocket = awaitWebSocket();
    webSocket.start(listener);
    webSocket.sendMessage(TEXT, new Buffer().writeUtf8("Hello, WebSockets!"));
    serverListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void serverMessage() throws IOException {
    WebSocketCallback serverListener = new EmptyWebSocketCallback() {
      @Override public void onConnect(final WebSocket webSocket, Response response) {
        webSocket.start(new EmptyWebSocketListener());

        new Thread() {
          @Override public void run() {
            try {
              webSocket.sendMessage(TEXT, new Buffer().writeUtf8("Hello, WebSockets!"));
            } catch (IOException e) {
              throw new AssertionError(e);
            }
          }
        }.start();
      }
    };
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = awaitWebSocket();
    webSocket.start(listener);
    listener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void clientStreamingMessage() throws IOException {
    final WebSocketRecorder serverListener = new WebSocketRecorder();
    server.enqueue(new MockResponse().withWebSocketUpgrade(new EmptyWebSocketCallback() {
      @Override public void onConnect(WebSocket webSocket, Response response) {
        webSocket.start(serverListener);
      }
    }));

    WebSocket webSocket = awaitWebSocket();
    webSocket.start(listener);
    BufferedSink sink = webSocket.newMessageSink(TEXT);
    sink.writeUtf8("Hello, ").flush();
    sink.writeUtf8("WebSockets!").flush();
    sink.close();

    serverListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void serverStreamingMessage() throws IOException {
    WebSocketCallback serverListener = new EmptyWebSocketCallback() {
      @Override public void onConnect(final WebSocket webSocket, Response response) {
        webSocket.start(new EmptyWebSocketListener());

        new Thread() {
          @Override public void run() {
            try {
              BufferedSink sink = webSocket.newMessageSink(TEXT);
              sink.writeUtf8("Hello, ").flush();
              sink.writeUtf8("WebSockets!").flush();
              sink.close();
            } catch (IOException e) {
              throw new AssertionError(e);
            }
          }
        }.start();
      }
    };
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = awaitWebSocket();
    webSocket.start(listener);
    listener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void okButNotOk() {
    server.enqueue(new MockResponse());
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals("Expected HTTP 101 response but was '200 OK'", e.getMessage());
    }
  }

  @Test public void notFound() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 404 Not Found"));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals("Expected HTTP 101 response but was '404 Not Found'", e.getMessage());
    }
  }

  @Test public void missingConnectionHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals("Expected 'Connection' header value 'Upgrade' but was 'null'", e.getMessage());
    }
  }

  @Test public void wrongConnectionHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals("Expected 'Connection' header value 'Upgrade' but was 'Downgrade'",
          e.getMessage());
    }
  }

  @Test public void missingUpgradeHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals("Expected 'Upgrade' header value 'websocket' but was 'null'", e.getMessage());
    }
  }

  @Test public void wrongUpgradeHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals("Expected 'Upgrade' header value 'websocket' but was 'Pepsi'", e.getMessage());
    }
  }

  @Test public void missingMagicHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket"));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals(
          "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'",
          e.getMessage());
    }
  }

  @Test public void wrongMagicHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic"));
    try {
      awaitWebSocket();
    } catch (IOException e) {
      assertTrue(e instanceof ProtocolException);
      assertEquals(
          "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'",
          e.getMessage());
    }
  }

  @Test public void wsScheme() throws IOException {
    websocketScheme("ws");
  }

  @Test public void wsUppercaseScheme() throws IOException {
    websocketScheme("WS");
  }

  @Test public void wssScheme() throws IOException {
    server.get().useHttps(sslContext.getSocketFactory(), false);
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    websocketScheme("wss");
  }

  @Test public void httpsScheme() throws IOException {
    server.get().useHttps(sslContext.getSocketFactory(), false);
    client.setSslSocketFactory(sslContext.getSocketFactory());
    client.setHostnameVerifier(new RecordingHostnameVerifier());

    websocketScheme("https");
  }

  private void websocketScheme(String scheme) throws IOException {
    final WebSocketRecorder serverListener = new WebSocketRecorder();
    server.enqueue(new MockResponse().withWebSocketUpgrade(new EmptyWebSocketCallback() {
      @Override public void onConnect(WebSocket webSocket, Response response) {
        webSocket.start(serverListener);
      }
    }));

    Request request1 = new Request.Builder()
        .url(scheme + "://" + server.getHostName() + ":" + server.getPort() + "/")
        .build();

    WebSocket webSocket = awaitWebSocket(request1);
    webSocket.start(listener);
    webSocket.sendMessage(TEXT, new Buffer().writeUtf8("abc"));
    serverListener.assertTextMessage("abc");
  }

  private WebSocket awaitWebSocket() throws IOException {
    return awaitWebSocket(new Request.Builder().get().url(server.url("/")).build());
  }

  private WebSocket awaitWebSocket(Request request) throws IOException {
    WebSocketCall call = new WebSocketCall(client, request, random);

    final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
    final AtomicReference<IOException> failureRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    call.enqueue(new WebSocketCallback() {
      @Override public void onConnect(WebSocket webSocket, Response response) {
        webSocketRef.set(webSocket);
        latch.countDown();
      }

      @Override public void onFailure(IOException e, Response response) {
        failureRef.set(e);
        latch.countDown();
      }
    });

    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out.");
      }
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }

    IOException failure = failureRef.get();
    if (failure != null) {
      throw failure;
    }

    return webSocketRef.get();
  }
}
