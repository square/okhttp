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
package okhttp3.ws;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.SslContextBuilder;
import okhttp3.internal.tls.SslClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.ws.WebSocket.TEXT;

public final class WebSocketCallTest {
  @Rule public final MockWebServer server = new MockWebServer();

  private final SslClient sslClient = SslContextBuilder.localhost();
  private final WebSocketRecorder listener = new WebSocketRecorder();
  private final Random random = new Random(0);
  private OkHttpClient client = new OkHttpClient();

  @After public void tearDown() {
    listener.assertExhausted();
  }

  @Test public void clientPingPong() throws IOException {
    WebSocketListener serverListener = new EmptyWebSocketListener();
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = awaitWebSocket();
    webSocket.sendPing(new Buffer().writeUtf8("Hello, WebSockets!"));
    listener.assertPong(new Buffer().writeUtf8("Hello, WebSockets!"));
  }

  @Test public void clientMessage() throws IOException {
    WebSocketRecorder serverListener = new WebSocketRecorder();
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = awaitWebSocket();
    webSocket.sendMessage(RequestBody.create(TEXT, "Hello, WebSockets!"));
    serverListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void serverMessage() throws IOException {
    WebSocketListener serverListener = new EmptyWebSocketListener() {
      @Override public void onOpen(final WebSocket webSocket, Response response) {
        new Thread() {
          @Override public void run() {
            try {
              webSocket.sendMessage(RequestBody.create(TEXT, "Hello, WebSockets!"));
            } catch (IOException e) {
              throw new AssertionError(e);
            }
          }
        }.start();
      }
    };
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    awaitWebSocket();
    listener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void non101RetainsBody() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("Body"));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class, "Expected HTTP 101 response but was '200 OK'");
    listener.assertResponse(200, "Body");
  }

  @Test public void notFound() throws IOException {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 404 Not Found"));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected HTTP 101 response but was '404 Not Found'");
    listener.assertResponse(404, "");
  }

  @Test public void clientTimeoutClosesBody() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(408));
    WebSocketListener serverListener = new EmptyWebSocketListener();
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = awaitWebSocket();
    webSocket.sendPing(new Buffer().writeUtf8("WebSockets are fun!"));
    listener.assertPong(new Buffer().writeUtf8("WebSockets are fun!"));
  }

  @Test public void missingConnectionHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'null'");
  }

  @Test public void wrongConnectionHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'Downgrade'");
  }

  @Test public void missingUpgradeHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'null'");
  }

  @Test public void wrongUpgradeHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'Pepsi'");
  }

  @Test public void missingMagicHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket"));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'");
  }

  @Test public void wrongMagicHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic"));
    awaitWebSocket();
    listener.assertFailure(ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'");
  }

  @Test public void wsScheme() throws IOException {
    websocketScheme("ws");
  }

  @Test public void wsUppercaseScheme() throws IOException {
    websocketScheme("WS");
  }

  @Test public void wssScheme() throws IOException {
    server.useHttps(sslClient.socketFactory, false);
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("wss");
  }

  @Test public void httpsScheme() throws IOException {
    server.useHttps(sslClient.socketFactory, false);
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("https");
  }

  private void websocketScheme(String scheme) throws IOException {
    WebSocketRecorder serverListener = new WebSocketRecorder();
    server.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    Request request1 = new Request.Builder()
        .url(scheme + "://" + server.getHostName() + ":" + server.getPort() + "/")
        .build();

    WebSocket webSocket = awaitWebSocket(request1);
    webSocket.sendMessage(RequestBody.create(TEXT, "abc"));
    serverListener.assertTextMessage("abc");
  }

  private WebSocket awaitWebSocket() {
    return awaitWebSocket(new Request.Builder().get().url(server.url("/")).build());
  }

  private WebSocket awaitWebSocket(Request request) {
    WebSocketCall call = new WebSocketCall(client, request, random);

    final AtomicReference<Response> responseRef = new AtomicReference<>();
    final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
    final AtomicReference<IOException> failureRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    call.enqueue(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
        webSocketRef.set(webSocket);
        responseRef.set(response);
        latch.countDown();
      }

      @Override public void onMessage(ResponseBody message) throws IOException {
        listener.onMessage(message);
      }

      @Override public void onPong(Buffer payload) {
        listener.onPong(payload);
      }

      @Override public void onClose(int code, String reason) {
        listener.onClose(code, reason);
      }

      @Override public void onFailure(IOException e, Response response) {
        listener.onFailure(e, response);
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

    return webSocketRef.get();
  }

  private static class EmptyWebSocketListener implements WebSocketListener {
    @Override public void onOpen(WebSocket webSocket, Response response) {
    }

    @Override public void onMessage(ResponseBody message) throws IOException {
    }

    @Override public void onPong(Buffer payload) {
    }

    @Override public void onClose(int code, String reason) {
    }

    @Override public void onFailure(IOException e, Response response) {
    }
  }
}
