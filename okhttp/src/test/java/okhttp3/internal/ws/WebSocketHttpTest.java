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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketPolicy;
import mockwebserver3.SocketPolicy.KeepOpen;
import mockwebserver3.SocketPolicy.NoResponse;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientTestRule;
import okhttp3.Protocol;
import okhttp3.RecordingEventListener;
import okhttp3.RecordingHostnameVerifier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TestLogHandler;
import okhttp3.TestUtil;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.UnreadableResponseBody;
import okhttp3.internal.concurrent.TaskRunner;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static java.util.Arrays.asList;
import static okhttp3.TestUtil.repeat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.fail;

@Flaky
@Tag("Slow")
public final class WebSocketHttpTest {
  // Flaky https://github.com/square/okhttp/issues/4515
  // Flaky https://github.com/square/okhttp/issues/4953

  @RegisterExtension OkHttpClientTestRule clientTestRule = configureClientTestRule();
  @RegisterExtension PlatformRule platform = new PlatformRule();
  @RegisterExtension TestLogHandler testLogHandler = new TestLogHandler(OkHttpClient.class);

  private MockWebServer webServer;
  private final HandshakeCertificates handshakeCertificates
    = platform.localhostHandshakeCertificates();
  private final WebSocketRecorder clientListener = new WebSocketRecorder("client");
  private final WebSocketRecorder serverListener = new WebSocketRecorder("server");
  private final Random random = new Random(0);
  private OkHttpClient client = clientTestRule.newClientBuilder()
      .writeTimeout(Duration.ofMillis(500))
      .readTimeout(Duration.ofMillis(500))
      .addInterceptor(chain -> {
        Response response = chain.proceed(chain.request());
        // Ensure application interceptors never see a null body.
        assertThat(response.body()).isNotNull();
        return response;
      })
      .build();

  private OkHttpClientTestRule configureClientTestRule() {
    OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();
    clientTestRule.setRecordTaskRunner(true);
    return clientTestRule;
  }

  @BeforeEach public void setUp(MockWebServer webServer) {
    this.webServer = webServer;

    platform.assumeNotOpenJSSE();
  }

  @AfterEach public void tearDown() throws InterruptedException {
    clientListener.assertExhausted();
  }

  @Test public void textMessage() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send("Hello, WebSockets!");
    serverListener.assertTextMessage("Hello, WebSockets!");

    closeWebSockets(webSocket, server);
  }

  @Test public void binaryMessage() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    webSocket.send(ByteString.encodeUtf8("Hello!"));
    serverListener.assertBinaryMessage(ByteString.of(new byte[] {'H', 'e', 'l', 'l', 'o', '!'}));

    closeWebSockets(webSocket, server);
  }

  @Test public void nullStringThrows() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();
    try {
      webSocket.send((String) null);
      fail();
    } catch (NullPointerException expected) {
    }

    closeWebSockets(webSocket, server);
  }

  @Test public void nullByteStringThrows() {
    TestUtil.assumeNotWindows();

    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();
    try {
      webSocket.send((ByteString) null);
      fail();
    } catch (NullPointerException expected) {
    }

    closeWebSockets(webSocket, server);
  }

  @Test public void serverMessage() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
    WebSocket webSocket = newWebSocket();

    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    server.send("Hello, WebSockets!");
    clientListener.assertTextMessage("Hello, WebSockets!");

    closeWebSockets(webSocket, server);
  }

  @Test public void throwingOnOpenFailsImmediately() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

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

  @Disabled("AsyncCall currently lets runtime exceptions propagate.")
  @Test public void throwingOnFailLogs() throws Exception {
    webServer.enqueue(new MockResponse.Builder()
        .code(200)
        .body("Body")
        .build());

    final RuntimeException e = new RuntimeException("boom");
    clientListener.setNextEventDelegate(new WebSocketListener() {
      @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        throw e;
      }
    });

    newWebSocket();

    assertThat(testLogHandler.take()).isEqualTo("INFO: [WS client] onFailure");
  }

  @Test public void throwingOnMessageClosesImmediatelyAndFails() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
    newWebSocket();

    WebSocket webSocket = clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    closeWebSockets(webSocket, server);
  }

  @Test public void non101RetainsBody() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .code(200)
        .body("Body")
        .build());
    newWebSocket();

    clientListener.assertFailure(200, "Body", ProtocolException.class,
        "Expected HTTP 101 response but was '200 OK'");
  }

  @Test public void notFound() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .status("HTTP/1.1 404 Not Found")
        .build());
    newWebSocket();

    clientListener.assertFailure(404, null, ProtocolException.class,
        "Expected HTTP 101 response but was '404 Not Found'");
  }

  @Test public void clientTimeoutClosesBody() {
    webServer.enqueue(new MockResponse.Builder()
        .code(408)
        .build());
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    webServer.enqueue(new MockResponse.Builder()
        .code(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build());
    webServer.enqueue(new MockResponse.Builder()
            .socketPolicy(SocketPolicy.DisconnectAtStart.INSTANCE)
            .build());

    RealWebSocket webSocket = newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'null'");

    webSocket.cancel();
  }

  @Test public void wrongConnectionHeader() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .code(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build());
    webServer.enqueue(new MockResponse.Builder()
            .socketPolicy(SocketPolicy.DisconnectAtStart.INSTANCE)
            .build());

    RealWebSocket webSocket = newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'Downgrade'");

    webSocket.cancel();
  }

  @Test public void missingUpgradeHeader() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build());
    webServer.enqueue(new MockResponse.Builder()
            .socketPolicy(SocketPolicy.DisconnectAtStart.INSTANCE)
            .build());

    RealWebSocket webSocket = newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'null'");

    webSocket.cancel();
  }

  @Test public void wrongUpgradeHeader() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build());
    webServer.enqueue(new MockResponse.Builder()
            .socketPolicy(SocketPolicy.DisconnectAtStart.INSTANCE)
            .build());

    RealWebSocket webSocket = newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'Pepsi'");

    webSocket.cancel();
  }

  @Test public void missingMagicHeader() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .build());
    webServer.enqueue(new MockResponse.Builder()
            .socketPolicy(SocketPolicy.DisconnectAtStart.INSTANCE)
            .build());

    RealWebSocket webSocket = newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'");

    webSocket.cancel();
  }

  @Test public void wrongMagicHeader() throws IOException {
    webServer.enqueue(new MockResponse.Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic")
        .build());
    webServer.enqueue(new MockResponse.Builder()
            .socketPolicy(SocketPolicy.DisconnectAtStart.INSTANCE)
            .build());

    RealWebSocket webSocket = newWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'");

    webSocket.cancel();
  }

  @Test public void clientIncludesForbiddenHeader() throws IOException {
    newWebSocket(new Request.Builder()
        .url(webServer.url("/"))
        .header("Sec-WebSocket-Extensions", "permessage-deflate")
        .build());

    clientListener.assertFailure(ProtocolException.class,
        "Request header not permitted: 'Sec-WebSocket-Extensions'");
  }

  @SuppressWarnings("KotlinInternalInJava")
  @Test public void webSocketAndApplicationInterceptors() {
    final AtomicInteger interceptedCount = new AtomicInteger();

    client = client.newBuilder()
        .addInterceptor(chain -> {
          assertThat(chain.request().body()).isNull();
          Response response = chain.proceed(chain.request());
          assertThat(response.header("Connection")).isEqualTo("Upgrade");
          assertThat(response.body()).isInstanceOf(UnreadableResponseBody.class);
          interceptedCount.incrementAndGet();
          return response;
        })
        .build();

    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

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

    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    closeWebSockets(webSocket, server);
  }

  @Test public void overflowOutgoingQueue() {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

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
    TestUtil.assumeNotWindows();

    websocketScheme("ws");
  }

  @Test public void wsUppercaseScheme() {
    websocketScheme("WS");
  }

  @Test public void wssScheme() {
    webServer.useHttps(handshakeCertificates.sslSocketFactory());
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("wss");
  }

  @Test public void httpsScheme() {
    webServer.useHttps(handshakeCertificates.sslSocketFactory());
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager())
        .hostnameVerifier(new RecordingHostnameVerifier())
        .build();

    websocketScheme("https");
  }

  @Test public void readTimeoutAppliesToHttpRequest() {
    webServer.enqueue(new MockResponse.Builder()
        .socketPolicy(NoResponse.INSTANCE)
        .build());

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
            .body(new Buffer().write(ByteString.decodeHex("81"))) // Truncated frame.
            .removeHeader("Content-Length")
            .socketPolicy(KeepOpen.INSTANCE)
            .build();
      }
    });

    WebSocket webSocket = newWebSocket();
    clientListener.assertOpen();

    clientListener.assertFailure(SocketTimeoutException.class, "timeout", "Read timed out");
    assertThat(webSocket.close(1000, null)).isFalse();
  }

  @Test public void readTimeoutDoesNotApplyAcrossFrames() throws Exception {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
        .pingInterval(Duration.ofMillis(500))
        .build();

    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    TestUtil.assumeNotWindows();

    client = client.newBuilder()
        .pingInterval(Duration.ofMillis(500))
        .build();

    // Stall in onOpen to prevent pongs from being sent.
    final CountDownLatch latch = new CountDownLatch(1);
    webServer.enqueue(new MockResponse.Builder()
      .webSocketUpgrade(new WebSocketListener() {
        @Override public void onOpen(WebSocket webSocket, Response response) {
          try {
            latch.await(); // The server can't respond to pings!
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        }
      })
      .build());

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
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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

    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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
    webServer.enqueue(new MockResponse.Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build());

    client = client.newBuilder()
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .callTimeout(Duration.ofMillis(100))
        .build();

    newWebSocket();
    clientListener.assertFailure(InterruptedIOException.class, "timeout");
  }

  @Test public void callTimeoutDoesNotApplyOnceConnected() throws Exception {
    client = client.newBuilder()
        .callTimeout(Duration.ofMillis(100))
        .build();

    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());
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

    webServer.enqueue(new MockResponse.Builder()
        .code(HttpURLConnection.HTTP_NOT_FOUND)
        .body("not found!")
        .build());
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

  /** https://github.com/square/okhttp/issues/5705 */
  @Test public void closeWithoutSuccessfulConnect() {
    Request request = new Request.Builder()
        .url(webServer.url("/"))
        .build();
    WebSocket webSocket = client.newWebSocket(request, clientListener);
    webSocket.send("hello");
    webSocket.close(1000, null);
  }

  /** https://github.com/square/okhttp/issues/7768 */
  @Test public void reconnectingToNonWebSocket() throws InterruptedException {
    for (int i = 0; i < 30; i++) {
      webServer.enqueue(new MockResponse.Builder()
        .bodyDelay(100, TimeUnit.MILLISECONDS)
        .body("Wrong endpoint")
        .code(401)
        .build());
    }

    Request request = new Request.Builder()
      .url(webServer.url("/"))
      .build();

    CountDownLatch attempts = new CountDownLatch(20);

    List<WebSocket> webSockets = Collections.synchronizedList(new ArrayList<>());

    WebSocketListener reconnectOnFailure = new WebSocketListener() {
      @Override
      public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        if (attempts.getCount() > 0) {
          clientListener.setNextEventDelegate(this);
          webSockets.add(client.newWebSocket(request, clientListener));
          attempts.countDown();
        }
      }
    };

    clientListener.setNextEventDelegate(reconnectOnFailure);

    webSockets.add(client.newWebSocket(request, clientListener));

    attempts.await();

    synchronized (webSockets) {
      for (WebSocket webSocket : webSockets) {
        webSocket.cancel();
      }
    }
  }

  @Test public void compressedMessages() throws Exception {
    successfulExtensions("permessage-deflate");
  }

  @Test public void compressedMessagesNoClientContextTakeover() throws Exception {
    successfulExtensions("permessage-deflate; client_no_context_takeover");
  }

  @Test public void compressedMessagesNoServerContextTakeover() throws Exception {
    successfulExtensions("permessage-deflate; server_no_context_takeover");
  }

  @Test public void unexpectedExtensionParameter() throws Exception {
    extensionNegotiationFailure("permessage-deflate; unknown_parameter=15");
  }

  @Test public void clientMaxWindowBitsIncluded() throws Exception {
    extensionNegotiationFailure("permessage-deflate; client_max_window_bits=15");
  }

  @Test public void serverMaxWindowBitsTooLow() throws Exception {
    extensionNegotiationFailure("permessage-deflate; server_max_window_bits=7");
  }

  @Test public void serverMaxWindowBitsTooHigh() throws Exception {
    extensionNegotiationFailure("permessage-deflate; server_max_window_bits=16");
  }

  @Test public void serverMaxWindowBitsJustRight() throws Exception {
    successfulExtensions("permessage-deflate; server_max_window_bits=15");
  }

  private void successfulExtensions(String extensionsHeader) throws Exception {
    webServer.enqueue(new MockResponse.Builder()
        .addHeader("Sec-WebSocket-Extensions", extensionsHeader)
        .webSocketUpgrade(serverListener)
        .build());

    WebSocket client = newWebSocket();
    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    // Server to client message big enough to be compressed.
    String message1 = TestUtil.repeat('a', (int) RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE);
    server.send(message1);
    clientListener.assertTextMessage(message1);

    // Client to server message big enough to be compressed.
    String message2 = TestUtil.repeat('b', (int) RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE);
    client.send(message2);
    serverListener.assertTextMessage(message2);

    // Empty server to client message.
    String message3 = "";
    server.send(message3);
    clientListener.assertTextMessage(message3);

    // Empty client to server message.
    String message4 = "";
    client.send(message4);
    serverListener.assertTextMessage(message4);

    // Server to client message that shares context with message1.
    String message5 = message1 + message1;
    server.send(message5);
    clientListener.assertTextMessage(message5);

    // Client to server message that shares context with message2.
    String message6 = message2 + message2;
    client.send(message6);
    serverListener.assertTextMessage(message6);

    closeWebSockets(client, server);

    RecordedRequest upgradeRequest = webServer.takeRequest();
    assertThat(upgradeRequest.getHeaders().get("Sec-WebSocket-Extensions"))
        .isEqualTo("permessage-deflate");
  }

  private void extensionNegotiationFailure(String extensionsHeader) throws Exception {
    webServer.enqueue(new MockResponse.Builder()
        .addHeader("Sec-WebSocket-Extensions", extensionsHeader)
        .webSocketUpgrade(serverListener)
        .build());

    newWebSocket();
    clientListener.assertOpen();
    WebSocket server = serverListener.assertOpen();

    String clientReason = "unexpected Sec-WebSocket-Extensions in response header";
    serverListener.assertClosing(1010, clientReason);
    server.close(1010, "");
    clientListener.assertClosing(1010, "");
    clientListener.assertClosed(1010, "");
    serverListener.assertClosed(1010, clientReason);
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }

  private MockResponse.Builder upgradeResponse(RecordedRequest request) {
    String key = request.getHeaders().get("Sec-WebSocket-Key");
    return new MockResponse.Builder()
        .status("HTTP/1.1 101 Switching Protocols")
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", WebSocketProtocol.INSTANCE.acceptHeader(key));
  }

  private void websocketScheme(String scheme) {
    webServer.enqueue(new MockResponse.Builder()
        .webSocketUpgrade(serverListener)
        .build());

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
    RealWebSocket webSocket = new RealWebSocket(TaskRunner.INSTANCE, request, clientListener,
        random, client.pingIntervalMillis(), null, 0L);
    webSocket.connect(client);
    return webSocket;
  }

  private void closeWebSockets(WebSocket client, WebSocket server) {
    server.close(1001, "");
    clientListener.assertClosing(1001, "");
    client.close(1000, "");
    serverListener.assertClosing(1000, "");
    clientListener.assertClosed(1001, "");
    serverListener.assertClosed(1000, "");
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }
}
