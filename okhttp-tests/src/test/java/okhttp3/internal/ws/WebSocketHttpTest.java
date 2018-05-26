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
package okhttp3.internal.ws;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.RecordingEventListener;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TestLogHandler;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.mockwebserver.internal.tls.SslClient;
import okio.Buffer;
import okio.ByteString;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static okhttp3.TestUtil.repeat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class WebSocketHttpTest {
  @Rule public final MockWebServer webServer = new MockWebServer();

  private final SslClient sslClient = SslClient.localhost();
  private final WebSocketRecorder clientListener = new WebSocketRecorder("client");
  private final WebSocketRecorder serverListener = new WebSocketRecorder("server");
  private final Random random = new Random(0);
  private OkHttpClient client = defaultClient().newBuilder()
      .writeTimeout(500, TimeUnit.MILLISECONDS)
      .readTimeout(500, TimeUnit.MILLISECONDS)
      .addInterceptor(new Interceptor() {
        @Override public Response intercept(Chain chain) throws IOException {
          Response response = chain.proceed(chain.request());
          assertNotNull(response.body()); // Ensure application interceptors never see a null body.
          return response;
        }
      })
      .build();

  @After public void tearDown() {
    clientListener.assertExhausted();
  }

  @Test public void textMessage() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    serverListener.assertOpen();

    webSocket.send("Hello, WebSockets!");
    serverListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void binaryMessage() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    serverListener.assertOpen();

    webSocket.send(ByteString.encodeUtf8("Hello!"));
    serverListener.assertBinaryMessage(ByteString.of(new byte[] {'H', 'e', 'l', 'l', 'o', '!'}));
  }

  @Test public void nullStringThrows() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    try {
      webSocket.send((String) null);
      fail();
    } catch (NullPointerException e) {
      assertEquals("text == null", e.getMessage());
    }
  }

  @Test public void nullByteStringThrows() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    try {
      webSocket.send((ByteString) null);
      fail();
    } catch (NullPointerException e) {
      assertEquals("bytes == null", e.getMessage());
    }
  }

  @Test public void serverMessage() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    server.send("Hello, WebSockets!");
    clientListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void throwingOnOpenFailsImmediately() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
        throw e;
      }
    });
    newWebSocket();

    serverListener.assertOpen();
    serverListener.assertExhausted();
    clientListener.assertFailure(e);
  }

  @Ignore("AsyncCall currently lets runtime exceptions propagate.")
  @Test public void throwingOnFailLogs() throws InterruptedException {
    TestLogHandler logs = new TestLogHandler();
    Logger logger = Logger.getLogger(OkHttpClient.class.getName());
    logger.addHandler(logs);

    webServer.enqueue(new MockResponse().setResponseCode(200).setBody("Body"));

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new WebSocketListener() {
      @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        throw e;
      }
    });

    newWebSocket();

    assertEquals("", logs.take());
    logger.removeHandler(logs);
  }

  @Test public void throwingOnMessageClosesImmediatelyAndFails() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new WebSocketListener() {
      @Override public void onMessage(WebSocket webSocket, String text) {
        throw e;
      }
    });

    server.send("Hello, WebSockets!");
    clientListener.assertFailure(e);
    serverListener.assertFailure(EOFException.class);
    serverListener.assertExhausted();
  }

  @Test public void throwingOnClosingClosesImmediatelyAndFails() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new WebSocketListener() {
      @Override public void onClosing(WebSocket webSocket, int code, String reason) {
        throw e;
      }
    });

    server.close(1000, "bye");
    clientListener.assertFailure(e);
    serverListener.assertFailure();
    serverListener.assertExhausted();
  }

  @Test public void unplannedCloseHandledByCloseWithoutFailure() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();
    clientListener.setNextEventDelegate(new WebSocketListener() {
      @Override public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
      }
    });

    server.close(1001, "bye");
    clientListener.assertClosed(1001, "bye");
    clientListener.assertExhausted();
    serverListener.assertClosing(1000,  "");
    serverListener.assertClosed(1000,  "");
    serverListener.assertExhausted();
  }

  @Test public void unplannedCloseHandledWithoutFailure() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    server.close(1001, "bye");
    clientListener.assertClosing(1001, "bye");
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }

  @Test public void non101RetainsBody() throws IOException {
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody("Body"));
    newWebSocket();

    clientListener.assertFailure(200, "Body", ProtocolException.class,
        "Expected HTTP 101 response but was '200 OK'");
  }

  @Test public void notFound() throws IOException {
    webServer.enqueue(new MockResponse().setStatus("HTTP/1.1 404 Not Found"));
    newWebSocket();

    clientListener.assertFailure(404, null, ProtocolException.class,
        "Expected HTTP 101 response but was '404 Not Found'");
  }

  @Test public void clientTimeoutClosesBody() {
    webServer.enqueue(new MockResponse().setResponseCode(408));
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send("abc");
    serverListener.assertTextMessage("abc");

    server.send("def");
    clientListener.assertTextMessage("def");
  }

  @Test public void missingConnectionHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'null'");
  }

  @Test public void wrongConnectionHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'Downgrade'");
  }

  @Test public void missingUpgradeHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'null'");
  }

  @Test public void wrongUpgradeHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'Pepsi'");
  }

  @Test public void missingMagicHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket"));
    newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'");
  }

  @Test public void wrongMagicHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic"));
    newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'");
  }

  @Test public void webSocketAndApplicationInterceptors() throws IOException {
    final AtomicInteger interceptedCount = new AtomicInteger();

    client = client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            assertNull(chain.request().body());
            Response response = chain.proceed(chain.request());
            assertEquals("Upgrade", response.header("Connection"));
            assertTrue(response.body().source().exhausted());
            interceptedCount.incrementAndGet();
            return response;
          }
        }).build();

    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();
    assertEquals(1, interceptedCount.get());
    webSocket.close(1000, null);

    WebSocket server = serverListener.assertOpen();
    server.close(1000, null);
  }

  @Test public void webSocketAndNetworkInterceptors() throws IOException {
    client = client.newBuilder()
        .addNetworkInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            throw new AssertionError(); // Network interceptors don't execute.
          }
        }).build();

    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();
    webSocket.close(1000, null);

    WebSocket server = serverListener.assertOpen();
    server.close(1000, null);
  }

  @Test public void overflowOutgoingQueue() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();

    // Send messages until the client's outgoing buffer overflows!
    ByteString message = ByteString.of(new byte[1024 * 1024]);
    int messageCount = 0;
    while (true) {
      boolean success = webSocket.send(message);
      if (!success) break;

      messageCount++;
      long queueSize = webSocket.queueSize();
      assertTrue(queueSize >= 0 && queueSize <= messageCount * message.size());
      assertTrue(messageCount < 32); // Expect to fail before enqueueing 32 MiB.
    }

    // Confirm all sent messages were received, followed by a client-initiated close.
    WebSocket server = serverListener.assertOpen();
    for (int i = 0; i < messageCount; i++) {
      serverListener.assertBinaryMessage(message);
    }
    serverListener.assertClosing(1001, "");

    // When the server acknowledges the close the connection shuts down gracefully.
    server.close(1000, null);
    clientListener.assertClosing(1000, "");
    clientListener.assertClosed(1000, "");
    serverListener.assertClosed(1001, "");
  }

  @Test public void closeReasonMaximumLength() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    String clientReason = repeat('C', 123);
    String serverReason = repeat('S', 123);

    WebSocket webSocket = newWebSocket();
    WebSocket server = serverListener.assertOpen();

    clientListener.assertOpen();
    webSocket.close(1000, clientReason);
    serverListener.assertClosing(1000, clientReason);

    server.close(1000, serverReason);
    clientListener.assertClosing(1000, serverReason);
    clientListener.assertClosed(1000, serverReason);

    serverListener.assertClosed(1000, clientReason);
  }

  @Test public void closeReasonTooLong() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    WebSocket server = serverListener.assertOpen();

    clientListener.assertOpen();
    String reason = repeat('X', 124);
    try {
      webSocket.close(1000, reason);
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("reason.size() > 123: " + reason, expected.getMessage());
    }

    webSocket.close(1000, null);
    serverListener.assertClosing(1000, "");

    server.close(1000, null);
    clientListener.assertClosing(1000, "");
    clientListener.assertClosed(1000, "");

    serverListener.assertClosed(1000, "");
  }

  @Test public void wsScheme() throws IOException {
    websocketScheme("ws");
  }

  @Test public void wsUppercaseScheme() throws IOException {
    websocketScheme("WS");
  }

  @Test public void wssScheme() throws IOException {
    webServer.useHttps(sslClient.socketFactory, false);
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("wss");
  }

  @Test public void httpsScheme() throws IOException {
    webServer.useHttps(sslClient.socketFactory, false);
    client = client.newBuilder()
        .sslSocketFactory(sslClient.socketFactory, sslClient.trustManager)
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("https");
  }

  @Test public void readTimeoutAppliesToHttpRequest() throws IOException {
    webServer.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.NO_RESPONSE));

    WebSocket webSocket = newWebSocket();

    clientListener.assertFailure(SocketTimeoutException.class, "timeout", "Read timed out");
    assertFalse(webSocket.close(1000, null));
  }

  /**
   * There's no read timeout when reading the first byte of a new frame. But as soon as we start
   * reading a frame we enable the read timeout. In this test we have the server returning the first
   * byte of a frame but no more frames.
   */
  @Test public void readTimeoutAppliesWithinFrames() throws IOException {
    webServer.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        return upgradeResponse(request)
            .setBody(new Buffer().write(ByteString.decodeHex("81"))) // Truncated frame.
            .removeHeader("Content-Length")
            .setSocketPolicy(SocketPolicy.KEEP_OPEN);
      }
    });

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();

    clientListener.assertFailure(SocketTimeoutException.class, "timeout", "Read timed out");
    assertFalse(webSocket.close(1000, null));
  }

  @Test public void readTimeoutDoesNotApplyAcrossFrames() throws Exception {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    // Sleep longer than the HTTP client's read timeout.
    Thread.sleep(client.readTimeoutMillis() + 500);

    server.send("abc");
    clientListener.assertTextMessage("abc");
  }

  @Test public void clientPingsServerOnInterval() throws Exception {
    client = client.newBuilder()
        .pingInterval(500, TimeUnit.MILLISECONDS)
        .build();

    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealWebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    RealWebSocket server = (RealWebSocket) serverListener.assertOpen();

    long startNanos = System.nanoTime();
    while (webSocket.receivedPongCount() < 3) {
      Thread.sleep(50);
    }

    long elapsedUntilPong3 = System.nanoTime() - startNanos;
    assertEquals(1500, TimeUnit.NANOSECONDS.toMillis(elapsedUntilPong3), 250d);

    // The client pinged the server 3 times, and it has ponged back 3 times.
    assertEquals(3, webSocket.sentPingCount());
    assertEquals(3, server.receivedPingCount());
    assertEquals(3, webSocket.receivedPongCount());

    // The server has never pinged the client.
    assertEquals(0, server.receivedPongCount());
    assertEquals(0, webSocket.receivedPingCount());
  }

  @Test public void clientDoesNotPingServerByDefault() throws Exception {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealWebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    RealWebSocket server = (RealWebSocket) serverListener.assertOpen();

    Thread.sleep(1000);

    // No pings and no pongs.
    assertEquals(0, webSocket.sentPingCount());
    assertEquals(0, webSocket.receivedPingCount());
    assertEquals(0, webSocket.receivedPongCount());
    assertEquals(0, server.sentPingCount());
    assertEquals(0, server.receivedPingCount());
    assertEquals(0, server.receivedPongCount());
  }

  /**
   * Configure the websocket to send pings every 500 ms. Artificially prevent the server from
   * responding to pings. The client should give up when attempting to send its 2nd ping, at about
   * 1000 ms.
   */
  @Test public void unacknowledgedPingFailsConnection() throws Exception {
    client = client.newBuilder()
        .pingInterval(500, TimeUnit.MILLISECONDS)
        .build();

    // Stall in onOpen to prevent pongs from being sent.
    final CountDownLatch latch = new CountDownLatch(1);
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
      @Override public void onOpen(WebSocket webSocket, Response response) {
        try {
          latch.await(); // The server can't respond to pings!
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }
    }));

    long openAtNanos = System.nanoTime();
    newWebSocket();
    clientListener.assertOpen();
    clientListener.assertFailure(SocketTimeoutException.class,
        "sent ping but didn't receive pong within 500ms (after 0 successful ping/pongs)");
    latch.countDown();

    long elapsedUntilFailure = System.nanoTime() - openAtNanos;
    assertEquals(1000, TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure), 250d);
  }

  /** https://github.com/square/okhttp/issues/2788 */
  @Test public void clientCancelsIfCloseIsNotAcknowledged() throws Exception {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealWebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    // Initiate a close on the client, which will schedule a hard cancel in 500 ms.
    long closeAtNanos = System.nanoTime();
    webSocket.close(1000, "goodbye", 500);
    serverListener.assertClosing(1000, "goodbye");

    // Confirm that the hard cancel occurred after 500 ms.
    clientListener.assertFailure();
    long elapsedUntilFailure = System.nanoTime() - closeAtNanos;
    assertEquals(500, TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure), 250d);

    // Close the server and confirm it saw what we expected.
    server.close(1000, null);
    serverListener.assertClosed(1000, "goodbye");
  }

  @Test public void webSocketsDontTriggerEventListener() throws IOException {
    RecordingEventListener listener = new RecordingEventListener();

    client = client.newBuilder()
        .eventListener(listener)
        .build();

    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send("Web Sockets and Events?!");
    serverListener.assertTextMessage("Web Sockets and Events?!");

    webSocket.close(1000, "");
    serverListener.assertClosing(1000, "");

    server.close(1000, "");
    clientListener.assertClosing(1000, "");
    clientListener.assertClosed(1000, "");
    serverListener.assertClosed(1000, "");

    assertEquals(Collections.emptyList(), listener.recordedEventTypes());
  }

  private MockResponse upgradeResponse(RecordedRequest request) {
    String key = request.getHeader("Sec-WebSocket-Key");
    return new MockResponse()
        .setStatus("HTTP/1.1 101 Switching Protocols")
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", WebSocketProtocol.acceptHeader(key));
  }

  private void websocketScheme(String scheme) throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    Request request = new Request.Builder()
        .url(scheme + "://" + webServer.getHostName() + ":" + webServer.getPort() + "/")
        .build();

    RealWebSocket webSocket = newWebSocket(request);
    clientListener.assertOpen();
    serverListener.assertOpen();

    webSocket.send("abc");
    serverListener.assertTextMessage("abc");
  }

  private RealWebSocket newWebSocket() {
    return newWebSocket(new Request.Builder().get().url(webServer.url("/")).build());
  }

  private RealWebSocket newWebSocket(Request request) {
    WebsocketUpgradeHandler handler =
        new WebsocketUpgradeHandler(request, clientListener, random, client.pingIntervalMillis());
    Http11Upgrade upgrade = new Http11Upgrade();
    upgrade.connect(client, request, handler);
    return (RealWebSocket) handler.result();
  }
}
