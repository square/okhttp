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
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Protocol;
import okhttp3.RecordingEventListener;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TestLogHandler;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.concurrent.TaskRunner;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Arrays.asList;
import static okhttp3.TestUtil.repeat;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.Assert.fail;

@Flaky
public final class WebSocketHttpTest {
  // Flaky https://github.com/square/okhttp/issues/4515
  // Flaky https://github.com/square/okhttp/issues/4953

  @Rule public final MockWebServer webServer = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
  @Rule public final PlatformRule platform = new PlatformRule();

  private final HandshakeCertificates handshakeCertificates = localhost();
  private final WebSocketRecorder clientListener = new WebSocketRecorder("client");
  private final WebSocketRecorder serverListener = new WebSocketRecorder("server");
  private final Random random = new Random(0);
  private OkHttpClient client = clientTestRule.newClientBuilder()
      .writeTimeout(500, TimeUnit.MILLISECONDS)
      .readTimeout(500, TimeUnit.MILLISECONDS)
      .addInterceptor(chain -> {
        Response response = chain.proceed(chain.request());
        // Ensure application interceptors never see a null body.
        assertThat(response.body()).isNotNull();
        return response;
      })
      .build();

  @Before public void setUp() {
    platform.assumeNotOpenJSSE();
  }

  @After public void tearDown() {
    clientListener.assertExhausted();
  }

  @Test public void textMessage() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send("Hello, WebSockets!");
    serverListener.assertTextMessage("Hello, WebSockets!");

    closeWebSockets(webSocket, server);
  }

  @Test public void binaryMessage() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send(ByteString.encodeUtf8("Hello!"));
    serverListener.assertBinaryMessage(ByteString.of(new byte[] {'H', 'e', 'l', 'l', 'o', '!'}));

    closeWebSockets(webSocket, server);
  }

  @Test public void nullStringThrows() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();
    try {
      webSocket.send((String) null);
      fail();
    } catch (IllegalArgumentException expected) {
    }

    closeWebSockets(webSocket, server);
  }

  @Test public void nullByteStringThrows() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();
    try {
      webSocket.send((ByteString) null);
      fail();
    } catch (IllegalArgumentException expected) {
    }

    closeWebSockets(webSocket, server);
  }

  @Test public void serverMessage() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    server.send("Hello, WebSockets!");
    clientListener.assertTextMessage("Hello, WebSockets!");

    closeWebSockets(webSocket, server);
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
    serverListener.assertFailure(EOFException.class);
    serverListener.assertExhausted();
    clientListener.assertFailure(e);
  }

  @Ignore("AsyncCall currently lets runtime exceptions propagate.")
  @Test public void throwingOnFailLogs() throws Exception {
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

    assertThat(logs.take()).isEqualTo("");
    logger.removeHandler(logs);
  }

  @Test public void throwingOnMessageClosesImmediatelyAndFails() {
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

  @Test public void throwingOnClosingClosesImmediatelyAndFails() {
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
    serverListener.assertClosing(1000, "");
    serverListener.assertClosed(1000, "");
    serverListener.assertExhausted();
  }

  @Test public void unplannedCloseHandledWithoutFailure() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    newWebSocket();

    WebSocket webSocket = clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    closeWebSockets(webSocket, server);
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

    closeWebSockets(webSocket, server);
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

  @Test public void webSocketAndApplicationInterceptors() {
    final AtomicInteger interceptedCount = new AtomicInteger();

    client = client.newBuilder()
        .addInterceptor(chain -> {
          assertThat(chain.request().body()).isNull();
          Response response = chain.proceed(chain.request());
          assertThat(response.header("Connection")).isEqualTo("Upgrade");
          assertThat(response.body().source().exhausted()).isTrue();
          interceptedCount.incrementAndGet();
          return response;
        })
        .build();

    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();
    assertThat(interceptedCount.get()).isEqualTo(1);

    closeWebSockets(webSocket, serverListener.assertOpen());
  }

  @Test public void webSocketAndNetworkInterceptors() {
    client = client.newBuilder()
        .addNetworkInterceptor(chain -> {
          throw new AssertionError(); // Network interceptors don't execute.
        })
        .build();

    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    closeWebSockets(webSocket, server);
  }

  @Test public void overflowOutgoingQueue() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();

    // Send messages until the client's outgoing buffer overflows!
    ByteString message = ByteString.of(new byte[1024 * 1024]);
    long messageCount = 0;
    while (true) {
      boolean success = webSocket.send(message);
      if (!success) break;

      messageCount++;
      long queueSize = webSocket.queueSize();
      assertThat(queueSize).isBetween(0L, messageCount * message.size());
      // Expect to fail before enqueueing 32 MiB.
      assertThat(messageCount).isLessThan(32L);
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

  @Test public void closeReasonMaximumLength() {
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

  @Test public void closeReasonTooLong() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    WebSocket webSocket = newWebSocket();
    WebSocket server = serverListener.assertOpen();

    clientListener.assertOpen();
    String reason = repeat('X', 124);
    try {
      webSocket.close(1000, reason);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo(("reason.size() > 123: " + reason));
    }

    webSocket.close(1000, null);
    serverListener.assertClosing(1000, "");

    server.close(1000, null);
    clientListener.assertClosing(1000, "");
    clientListener.assertClosed(1000, "");

    serverListener.assertClosed(1000, "");
  }

  @Test public void wsScheme() {
    websocketScheme("ws");
  }

  @Test public void wsUppercaseScheme() {
    websocketScheme("WS");
  }

  @Test public void wssScheme() {
    webServer.useHttps(handshakeCertificates.sslSocketFactory(), false);
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("wss");
  }

  @Test public void httpsScheme() {
    webServer.useHttps(handshakeCertificates.sslSocketFactory(), false);
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("https");
  }

  @Test public void readTimeoutAppliesToHttpRequest() {
    webServer.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.NO_RESPONSE));

    WebSocket webSocket = newWebSocket();

    clientListener.assertFailure(SocketTimeoutException.class, "timeout", "Read timed out");
    assertThat(webSocket.close(1000, null)).isFalse();
  }

  /**
   * There's no read timeout when reading the first byte of a new frame. But as soon as we start
   * reading a frame we enable the read timeout. In this test we have the server returning the first
   * byte of a frame but no more frames.
   */
  @Test public void readTimeoutAppliesWithinFrames() {
    webServer.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        return upgradeResponse(request)
            .setBody(new Buffer().write(ByteString.decodeHex("81"))) // Truncated frame.
            .removeHeader("Content-Length")
            .setSocketPolicy(SocketPolicy.KEEP_OPEN);
      }
    });

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();

    clientListener.assertFailure(SocketTimeoutException.class, "timeout", "Read timed out");
    assertThat(webSocket.close(1000, null)).isFalse();
  }

  @Test public void readTimeoutDoesNotApplyAcrossFrames() throws Exception {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    // Sleep longer than the HTTP client's read timeout.
    Thread.sleep(client.readTimeoutMillis() + 500);

    server.send("abc");
    clientListener.assertTextMessage("abc");

    closeWebSockets(webSocket, server);
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
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilPong3))
        .isCloseTo(1500L, offset(250L));

    // The client pinged the server 3 times, and it has ponged back 3 times.
    assertThat(webSocket.sentPingCount()).isEqualTo(3);
    assertThat(server.receivedPingCount()).isEqualTo(3);
    assertThat(webSocket.receivedPongCount()).isEqualTo(3);

    // The server has never pinged the client.
    assertThat(server.receivedPongCount()).isEqualTo(0);
    assertThat(webSocket.receivedPingCount()).isEqualTo(0);

    closeWebSockets(webSocket, server);
  }

  @Test public void clientDoesNotPingServerByDefault() throws Exception {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealWebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    RealWebSocket server = (RealWebSocket) serverListener.assertOpen();

    Thread.sleep(1000);

    // No pings and no pongs.
    assertThat(webSocket.sentPingCount()).isEqualTo(0);
    assertThat(webSocket.receivedPingCount()).isEqualTo(0);
    assertThat(webSocket.receivedPongCount()).isEqualTo(0);
    assertThat(server.sentPingCount()).isEqualTo(0);
    assertThat(server.receivedPingCount()).isEqualTo(0);
    assertThat(server.receivedPongCount()).isEqualTo(0);

    closeWebSockets(webSocket, server);
  }

  /**
   * Configure the websocket to send pings every 500 ms. Artificially prevent the server from
   * responding to pings. The client should give up when attempting to send its 2nd ping, at about
   * 1000 ms.
   */
  @Test public void unacknowledgedPingFailsConnection() {
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
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure))
        .isCloseTo(1000L, offset(250L));
  }

  /** https://github.com/square/okhttp/issues/2788 */
  @Test public void clientCancelsIfCloseIsNotAcknowledged() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealWebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    // Initiate a close on the client, which will schedule a hard cancel in 500 ms.
    long closeAtNanos = System.nanoTime();
    webSocket.close(1000, "goodbye", 500L);
    serverListener.assertClosing(1000, "goodbye");

    // Confirm that the hard cancel occurred after 500 ms.
    clientListener.assertFailure();
    long elapsedUntilFailure = System.nanoTime() - closeAtNanos;
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure))
        .isCloseTo(500L, offset(250L));

    // Close the server and confirm it saw what we expected.
    server.close(1000, null);
    serverListener.assertClosed(1000, "goodbye");
  }

  @Test public void webSocketsDontTriggerEventListener() {
    RecordingEventListener listener = new RecordingEventListener();

    client = client.newBuilder()
        .eventListenerFactory(clientTestRule.wrap(listener))
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

    assertThat(listener.recordedEventTypes()).isEmpty();
  }

  @Test public void callTimeoutAppliesToSetup() throws Exception {
    webServer.enqueue(new MockResponse()
        .setHeadersDelay(500, TimeUnit.MILLISECONDS));

    client = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    newWebSocket();
    clientListener.assertFailure(InterruptedIOException.class, "timeout");
  }

  @Test public void callTimeoutDoesNotApplyOnceConnected() throws Exception {
    client = client.newBuilder()
        .callTimeout(100, TimeUnit.MILLISECONDS)
        .build();

    webServer.enqueue(new MockResponse()
        .withWebSocketUpgrade(serverListener));
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    Thread.sleep(500);

    server.send("Hello, WebSockets!");
    clientListener.assertTextMessage("Hello, WebSockets!");

    closeWebSockets(webSocket, server);
  }

  /**
   * We had a bug where web socket connections were leaked if the HTTP connection upgrade was not
   * successful. This test confirms that connections are released back to the connection pool!
   * https://github.com/square/okhttp/issues/4258
   */
  @Test public void webSocketConnectionIsReleased() throws Exception {
    // This test assumes HTTP/1.1 pooling semantics.
    client = client.newBuilder()
        .protocols(asList(Protocol.HTTP_1_1))
        .build();

    webServer.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
        .setBody("not found!"));
    webServer.enqueue(new MockResponse());

    newWebSocket();
    clientListener.assertFailure();

    Request regularRequest = new Request.Builder()
        .url(webServer.url("/"))
        .build();
    Response response = client.newCall(regularRequest).execute();
    response.close();

    assertThat(webServer.takeRequest().getSequenceNumber()).isEqualTo(0);
    assertThat(webServer.takeRequest().getSequenceNumber()).isEqualTo(1);
  }

  private MockResponse upgradeResponse(RecordedRequest request) {
    String key = request.getHeader("Sec-WebSocket-Key");
    return new MockResponse()
        .setStatus("HTTP/1.1 101 Switching Protocols")
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", WebSocketProtocol.INSTANCE.acceptHeader(key));
  }

  private void websocketScheme(String scheme) {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    Request request = new Request.Builder()
        .url(scheme + "://" + webServer.getHostName() + ":" + webServer.getPort() + "/")
        .build();

    RealWebSocket webSocket = newWebSocket(request);
    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send("abc");
    serverListener.assertTextMessage("abc");

    closeWebSockets(webSocket, server);
  }

  private RealWebSocket newWebSocket() {
    return newWebSocket(new Request.Builder().get().url(webServer.url("/")).build());
  }

  private RealWebSocket newWebSocket(Request request) {
    RealWebSocket webSocket = new RealWebSocket(
        TaskRunner.INSTANCE, request, clientListener, random, client.pingIntervalMillis());
    webSocket.connect(client);
    return webSocket;
  }

  private void closeWebSockets(WebSocket webSocket, WebSocket server) {
    server.close(1001, "");
    clientListener.assertClosing(1001, "");
    webSocket.close(1000, "");
    serverListener.assertClosing(1000, "");
    clientListener.assertClosed(1001, "");
    serverListener.assertClosed(1000, "");
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }
}
