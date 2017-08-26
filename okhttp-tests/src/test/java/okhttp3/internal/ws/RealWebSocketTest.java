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
import java.util.Random;
import java.util.concurrent.TimeUnit;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;
import okio.Okio;
import okio.Pipe;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RealWebSocketTest {
  // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has
  // zero effect on the behavior of the WebSocket API which is why tests are only written once
  // from the perspective of a single peer.

  private final Random random = new Random(0);
  private final Pipe client2Server = new Pipe(1024L);
  private final Pipe server2client = new Pipe(1024L);

  private TestStreams client = new TestStreams(true, server2client, client2Server);
  private TestStreams server = new TestStreams(false, client2Server, server2client);

  @Before public void setUp() throws IOException {
    client.initWebSocket(random, 0);
    server.initWebSocket(random, 0);
  }

  @After public void tearDown() throws Exception {
    client.listener.assertExhausted();
    server.listener.assertExhausted();
    server.source.close();
    client.source.close();
    server.webSocket.tearDown();
    client.webSocket.tearDown();
  }

  @Test public void close() throws IOException {
    client.webSocket.close(1000, "Hello!");
    assertFalse(server.processNextFrame()); // This will trigger a close response.
    server.listener.assertClosing(1000, "Hello!");
    server.webSocket.close(1000, "Goodbye!");
    assertFalse(client.processNextFrame());
    client.listener.assertClosing(1000, "Goodbye!");
    server.listener.assertClosed(1000, "Hello!");
    client.listener.assertClosed(1000, "Goodbye!");
  }

  @Test public void clientCloseThenMethodsReturnFalse() throws IOException {
    client.webSocket.close(1000, "Hello!");

    assertFalse(client.webSocket.close(1000, "Hello!"));
    assertFalse(client.webSocket.send("Hello!"));
  }

  @Test public void clientCloseWith0Fails() throws IOException {
    try {
      client.webSocket.close(0, null);
    } catch (IllegalArgumentException expected) {
      assertEquals(expected.getMessage(), "Code must be in range [1000,5000): 0");
    }
  }

  @Test public void afterSocketClosedPingFailsWebSocket() throws IOException {
    client2Server.source().close();
    client.webSocket.pong(ByteString.encodeUtf8("Ping!"));
    client.listener.assertFailure(IOException.class, "source is closed");

    assertFalse(client.webSocket.send("Hello!"));
  }

  @Test public void socketClosedDuringMessageKillsWebSocket() throws IOException {
    client2Server.source().close();

    assertTrue(client.webSocket.send("Hello!"));
    client.listener.assertFailure(IOException.class, "source is closed");

    // A failed write prevents further use of the WebSocket instance.
    assertFalse(client.webSocket.send("Hello!"));
    assertFalse(client.webSocket.pong(ByteString.encodeUtf8("Ping!")));
  }

  @Test public void serverCloseThenWritingPingSucceeds() throws IOException {
    server.webSocket.close(1000, "Hello!");
    client.processNextFrame();
    client.listener.assertClosing(1000, "Hello!");

    assertTrue(client.webSocket.pong(ByteString.encodeUtf8("Pong?")));
  }

  @Test public void clientCanWriteMessagesAfterServerClose() throws IOException {
    server.webSocket.close(1000, "Hello!");
    client.processNextFrame();
    client.listener.assertClosing(1000, "Hello!");

    assertTrue(client.webSocket.send("Hi!"));
    server.processNextFrame();
    server.listener.assertTextMessage("Hi!");
  }

  @Test public void serverCloseThenClientClose() throws IOException {
    server.webSocket.close(1000, "Hello!");
    client.processNextFrame();
    client.listener.assertClosing(1000, "Hello!");
    assertTrue(client.webSocket.close(1000, "Bye!"));
  }

  @Test public void emptyCloseInitiatesShutdown() throws IOException {
    server.sink.write(ByteString.decodeHex("8800")).emit(); // Close without code.
    client.processNextFrame();
    client.listener.assertClosing(1005, "");

    assertTrue(client.webSocket.close(1000, "Bye!"));
    server.processNextFrame();
    server.listener.assertClosing(1000, "Bye!");

    client.listener.assertClosed(1005, "");
  }

  @Test public void clientCloseClosesConnection() throws IOException {
    client.webSocket.close(1000, "Hello!");
    assertFalse(client.closed);
    server.processNextFrame(); // Read client closing, send server close.
    server.listener.assertClosing(1000, "Hello!");

    server.webSocket.close(1000, "Goodbye!");
    client.processNextFrame(); // Read server closing, close connection.
    assertTrue(client.closed);
    client.listener.assertClosing(1000, "Goodbye!");

    // Server and client both finished closing, connection is closed.
    server.listener.assertClosed(1000, "Hello!");
    client.listener.assertClosed(1000, "Goodbye!");
  }

  @Test public void serverCloseClosesConnection() throws IOException {
    server.webSocket.close(1000, "Hello!");

    client.processNextFrame(); // Read server close, send client close, close connection.
    assertFalse(client.closed);
    client.listener.assertClosing(1000, "Hello!");

    client.webSocket.close(1000, "Hello!");
    server.processNextFrame();
    server.listener.assertClosing(1000, "Hello!");

    client.listener.assertClosed(1000, "Hello!");
    server.listener.assertClosed(1000, "Hello!");
  }

  @Test public void clientAndServerCloseClosesConnection() throws Exception {
    // Send close from both sides at the same time.
    server.webSocket.close(1000, "Hello!");
    client.processNextFrame(); // Read close, close connection close.

    assertFalse(client.closed);
    client.webSocket.close(1000, "Hi!");
    server.processNextFrame();

    client.listener.assertClosing(1000, "Hello!");
    server.listener.assertClosing(1000, "Hi!");
    client.listener.assertClosed(1000, "Hello!");
    server.listener.assertClosed(1000, "Hi!");
    client.webSocket.awaitTermination(5, TimeUnit.SECONDS);
    assertTrue(client.closed);

    server.listener.assertExhausted(); // Client should not have sent second close.
    client.listener.assertExhausted(); // Server should not have sent second close.
  }

  @Test public void serverCloseBreaksReadMessageLoop() throws IOException {
    server.webSocket.send("Hello!");
    server.webSocket.close(1000, "Bye!");
    assertTrue(client.processNextFrame());
    client.listener.assertTextMessage("Hello!");
    assertFalse(client.processNextFrame());
    client.listener.assertClosing(1000, "Bye!");
  }

  @Test public void protocolErrorBeforeCloseSendsFailure() throws IOException {
    server.sink.write(ByteString.decodeHex("0a00")).emit(); // Invalid non-final ping frame.

    client.processNextFrame(); // Detects error, send close, close connection.
    assertTrue(client.closed);
    client.listener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.processNextFrame();
    server.listener.assertFailure(EOFException.class);
  }

  @Test public void protocolErrorInCloseResponseClosesConnection() throws IOException {
    client.webSocket.close(1000, "Hello");
    server.processNextFrame();
    assertFalse(client.closed); // Not closed until close reply is received.

    // Manually write an invalid masked close frame.
    server.sink.write(ByteString.decodeHex("888760b420bb635c68de0cd84f")).emit();

    client.processNextFrame();// Detects error, disconnects immediately since close already sent.
    assertTrue(client.closed);
    client.listener.assertFailure(
        ProtocolException.class, "Server-sent frames must not be masked.");

    server.listener.assertClosing(1000, "Hello");
    server.listener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void protocolErrorAfterCloseDoesNotSendClose() throws IOException {
    client.webSocket.close(1000, "Hello!");
    server.processNextFrame();

    assertFalse(client.closed); // Not closed until close reply is received.
    server.sink.write(ByteString.decodeHex("0a00")).emit(); // Invalid non-final ping frame.

    client.processNextFrame(); // Detects error, disconnects immediately since close already sent.
    assertTrue(client.closed);
    client.listener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.listener.assertClosing(1000, "Hello!");

    server.listener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void networkErrorReportedAsFailure() throws IOException {
    server.sink.close();
    client.processNextFrame();
    client.listener.assertFailure(EOFException.class);
  }

  @Test public void closeThrowingFailsConnection() throws IOException {
    client2Server.source().close();
    client.webSocket.close(1000, null);
    client.listener.assertFailure(IOException.class, "source is closed");
  }

  @Ignore // TODO(jwilson): come up with a way to test unchecked exceptions on the writer thread.
  @Test public void closeMessageAndConnectionCloseThrowingDoesNotMaskOriginal() throws IOException {
    client.sink.close();
    client.closeThrows = true;

    client.webSocket.close(1000, "Bye!");
    client.listener.assertFailure(IOException.class, "failure");
    assertTrue(client.closed);
  }

  @Ignore // TODO(jwilson): come up with a way to test unchecked exceptions on the writer thread.
  @Test public void peerConnectionCloseThrowingPropagates() throws IOException {
    client.closeThrows = true;

    server.webSocket.close(1000, "Bye from Server!");
    client.processNextFrame();
    client.listener.assertClosing(1000, "Bye from Server!");

    client.webSocket.close(1000, "Bye from Client!");
    server.processNextFrame();
    server.listener.assertClosing(1000, "Bye from Client!");
  }

  @Test public void pingOnInterval() throws IOException {
    long startNanos = System.nanoTime();
    client.initWebSocket(random, 500);

    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.
    long elapsedUntilPing1 = System.nanoTime() - startNanos;
    assertEquals(500, TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing1), 250d);

    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.
    long elapsedUntilPing2 = System.nanoTime() - startNanos;
    assertEquals(1000, TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing2), 250d);

    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.
    long elapsedUntilPing3 = System.nanoTime() - startNanos;
    assertEquals(1500, TimeUnit.NANOSECONDS.toMillis(elapsedUntilPing3), 250d);
  }

  /** One peer's streams, listener, and web socket in the test. */
  private static class TestStreams extends RealWebSocket.Streams {
    private final String name;
    private final WebSocketRecorder listener;
    private RealWebSocket webSocket;
    boolean closeThrows;
    boolean closed;

    public TestStreams(boolean client, Pipe source, Pipe sink) {
      super(client, Okio.buffer(source.source()), Okio.buffer(sink.sink()));
      this.name = client ? "client" : "server";
      this.listener = new WebSocketRecorder(name);
    }

    public void initWebSocket(Random random, int pingIntervalMillis) throws IOException {
      String url = "http://example.com/websocket";
      Response response = new Response.Builder()
          .code(101)
          .message("OK")
          .request(new Request.Builder().url(url).build())
          .protocol(Protocol.HTTP_1_1)
          .build();
      webSocket = new RealWebSocket(response.request(), listener, random);
      webSocket.initReaderAndWriter(name, pingIntervalMillis, this);
    }

    public boolean processNextFrame() throws IOException {
      return webSocket.processNextFrame();
    }

    @Override public void close() throws IOException {
      source.close();
      sink.close();
      if (closed) {
        throw new AssertionError("Already closed");
      }
      closed = true;

      if (closeThrows) {
        throw new RuntimeException("Oops!");
      }
    }
  }
}
