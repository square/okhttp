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
package okhttp3;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.logging.Logger;
import okhttp3.internal.tls.SslClient;
import okhttp3.internal.ws.NewWebSocketRecorder;
import okhttp3.internal.ws.RealNewWebSocket;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.TestUtil.defaultClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public final class WebSocketHttpTest {
  @Rule public final MockWebServer webServer = new MockWebServer();

  private final SslClient sslClient = SslClient.localhost();
  private final NewWebSocketRecorder clientListener = new NewWebSocketRecorder("client");
  private final NewWebSocketRecorder serverListener = new NewWebSocketRecorder("server");
  private final Random random = new Random(0);
  private OkHttpClient client = defaultClient().newBuilder()
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
    NewWebSocket client = enqueueClientWebSocket();

    clientListener.assertOpen();
    serverListener.assertOpen();

    client.send("Hello, WebSockets!");
    serverListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void binaryMessage() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealNewWebSocket client = enqueueClientWebSocket();

    clientListener.assertOpen();
    serverListener.assertOpen();

    client.send(ByteString.encodeUtf8("Hello!"));
    serverListener.assertBinaryMessage(new byte[] {'H', 'e', 'l', 'l', 'o', '!'});
  }

  @Test public void nullStringThrows() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealNewWebSocket client = enqueueClientWebSocket();

    clientListener.assertOpen();
    try {
      client.send((String) null);
      fail();
    } catch (NullPointerException e) {
      assertEquals("text == null", e.getMessage());
    }
  }

  @Test public void nullByteStringThrows() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealNewWebSocket client = enqueueClientWebSocket();

    clientListener.assertOpen();
    try {
      client.send((ByteString) null);
      fail();
    } catch (NullPointerException e) {
      assertEquals("bytes == null", e.getMessage());
    }
  }

  @Test public void serverMessage() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    enqueueClientWebSocket();

    clientListener.assertOpen();
    NewWebSocket server = serverListener.assertOpen();

    server.send("Hello, WebSockets!");
    clientListener.assertTextMessage("Hello, WebSockets!");
  }

  @Test public void throwingOnOpenFailsImmediately() {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new NewWebSocket.Listener() {
      @Override public void onOpen(NewWebSocket webSocket, Response response) {
        throw e;
      }
    });
    enqueueClientWebSocket();

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
    clientListener.setNextEventDelegate(new NewWebSocket.Listener() {
      @Override public void onFailure(NewWebSocket webSocket, Throwable t, Response response) {
        throw e;
      }
    });

    enqueueClientWebSocket();

    assertEquals("", logs.take());
    logger.removeHandler(logs);
  }

  @Test public void throwingOnMessageClosesImmediatelyAndFails() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    enqueueClientWebSocket();

    clientListener.assertOpen();
    NewWebSocket server = serverListener.assertOpen();

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new NewWebSocket.Listener() {
      @Override public void onMessage(NewWebSocket webSocket, String text) {
        throw e;
      }
    });

    server.send("Hello, WebSockets!");
    clientListener.assertFailure(e);
    serverListener.assertExhausted();
  }

  @Test public void throwingOnClosingClosesImmediatelyAndFails() throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    enqueueClientWebSocket();

    clientListener.assertOpen();
    NewWebSocket server = serverListener.assertOpen();

    final RuntimeException e = new RuntimeException();
    clientListener.setNextEventDelegate(new NewWebSocket.Listener() {
      @Override public void onClosing(NewWebSocket webSocket, int code, String reason) {
        throw e;
      }
    });

    server.close(1000, "bye");
    clientListener.assertFailure(e);
    serverListener.assertExhausted();
  }

  @Test public void non101RetainsBody() throws IOException {
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody("Body"));
    enqueueClientWebSocket();

    clientListener.assertFailure(200, "Body", ProtocolException.class,
        "Expected HTTP 101 response but was '200 OK'");
  }

  @Test public void notFound() throws IOException {
    webServer.enqueue(new MockResponse().setStatus("HTTP/1.1 404 Not Found"));
    enqueueClientWebSocket();

    clientListener.assertFailure(404, null, ProtocolException.class,
        "Expected HTTP 101 response but was '404 Not Found'");
  }

  @Test public void clientTimeoutClosesBody() throws IOException {
    webServer.enqueue(new MockResponse().setResponseCode(408));
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));
    RealNewWebSocket client = enqueueClientWebSocket();

    clientListener.assertOpen();
    NewWebSocket server = serverListener.assertOpen();

    client.send("abc");
    serverListener.assertTextMessage("abc");

    server.send("def");
    clientListener.assertTextMessage("def");
  }

  @Test public void missingConnectionHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    enqueueClientWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'null'");
  }

  @Test public void wrongConnectionHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    enqueueClientWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Connection' header value 'Upgrade' but was 'Downgrade'");
  }

  @Test public void missingUpgradeHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    enqueueClientWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'null'");
  }

  @Test public void wrongUpgradeHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk="));
    enqueueClientWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Upgrade' header value 'websocket' but was 'Pepsi'");
  }

  @Test public void missingMagicHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket"));
    enqueueClientWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'");
  }

  @Test public void wrongMagicHeader() throws IOException {
    webServer.enqueue(new MockResponse()
        .setResponseCode(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic"));
    enqueueClientWebSocket();

    clientListener.assertFailure(101, null, ProtocolException.class,
        "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'");
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

  private void websocketScheme(String scheme) throws IOException {
    webServer.enqueue(new MockResponse().withWebSocketUpgrade(serverListener));

    Request request = new Request.Builder()
        .url(scheme + "://" + webServer.getHostName() + ":" + webServer.getPort() + "/")
        .build();

    RealNewWebSocket webSocket = enqueueClientWebSocket(request);
    clientListener.assertOpen();
    serverListener.assertOpen();

    webSocket.send("abc");
    serverListener.assertTextMessage("abc");
  }

  private RealNewWebSocket enqueueClientWebSocket() {
    return enqueueClientWebSocket(new Request.Builder().get().url(webServer.url("/")).build());
  }

  private RealNewWebSocket enqueueClientWebSocket(Request request) {
    RealNewWebSocket webSocket = new RealNewWebSocket(request, clientListener, random);
    webSocket.connect(client);
    return webSocket;
  }
}
