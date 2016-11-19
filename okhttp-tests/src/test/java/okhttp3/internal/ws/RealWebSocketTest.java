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
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;
import okio.Pipe;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RealWebSocketTest {
  // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has
  // zero effect on the behavior of the WebSocket API which is why tests are only written once
  // from the perspective of a single peer.

  private RealNewWebSocket client;
  private boolean clientConnectionCloseThrows;
  private boolean clientConnectionClosed;
  private final Pipe client2Server = new Pipe(1024L);
  private final BufferedSink client2ServerSink = Okio.buffer(client2Server.sink());
  private final NewWebSocketRecorder clientListener = new NewWebSocketRecorder("client");

  private RealNewWebSocket server;
  private boolean serverConnectionClosed;
  private final Pipe server2client = new Pipe(1024L);
  private final BufferedSink server2clientSink = Okio.buffer(server2client.sink());
  private final NewWebSocketRecorder serverListener = new NewWebSocketRecorder("server");

  @Before public void setUp() throws IOException {
    Random random = new Random(0);
    String url = "http://example.com/websocket";
    final Response response = new Response.Builder()
        .code(101)
        .request(new Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .build();

    client = new RealNewWebSocket(response.request(), clientListener, random);
    client.initReaderAndWriter(new RealNewWebSocket.Streams(
        true, Okio.buffer(server2client.source()), client2ServerSink) {
      @Override public void close() throws IOException {
        source.close();
        sink.close();
        if (clientConnectionClosed) {
          throw new AssertionError("Already closed");
        }
        clientConnectionClosed = true;

        if (clientConnectionCloseThrows) {
          throw new RuntimeException("Oops!");
        }
      }
    });

    server = new RealNewWebSocket(response.request(), serverListener, random);
    server.initReaderAndWriter(new RealNewWebSocket.Streams(
        false, Okio.buffer(client2Server.source()), server2clientSink) {
      @Override public void close() throws IOException {
        source.close();
        sink.close();
        if (serverConnectionClosed) {
          throw new AssertionError("Already closed");
        }
        serverConnectionClosed = true;
      }
    });
  }

  @After public void tearDown() {
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }

  @Test public void close() throws IOException {
    client.close(1000, "Hello!");
    assertFalse(server.processNextFrame()); // This will trigger a close response.
    serverListener.assertClosing(1000, "Hello!");
    server.close(1000, "Goodbye!");
    assertFalse(client.processNextFrame());
    clientListener.assertClosing(1000, "Goodbye!");
    serverListener.assertClosed(1000, "Hello!");
    clientListener.assertClosed(1000, "Goodbye!");
  }

  @Test public void clientCloseThenMethodsReturnFalse() throws IOException {
    client.close(1000, "Hello!");

    assertFalse(client.close(1000, "Hello!"));
    assertFalse(client.send("Hello!"));
  }

  @Test public void afterSocketClosedPingFailsWebSocket() throws IOException {
    client2Server.source().close();
    client.pong(ByteString.encodeUtf8("Ping!"));
    clientListener.assertFailure(IOException.class, "source is closed");

    assertFalse(client.send("Hello!"));
  }

  @Test public void socketClosedDuringMessageKillsWebSocket() throws IOException {
    client2Server.source().close();

    assertTrue(client.send("Hello!"));
    clientListener.assertFailure(IOException.class, "source is closed");

    // A failed write prevents further use of the WebSocket instance.
    assertFalse(client.send("Hello!"));
    assertFalse(client.pong(ByteString.encodeUtf8("Ping!")));
  }

  @Test public void serverCloseThenWritingPingSucceeds() throws IOException {
    server.close(1000, "Hello!");
    client.processNextFrame();
    clientListener.assertClosing(1000, "Hello!");

    assertTrue(client.pong(ByteString.encodeUtf8("Pong?")));
  }

  @Test public void clientCanWriteMessagesAfterServerClose() throws IOException {
    server.close(1000, "Hello!");
    client.processNextFrame();
    clientListener.assertClosing(1000, "Hello!");

    assertTrue(client.send("Hi!"));
    server.processNextFrame();
    serverListener.assertTextMessage("Hi!");
  }

  @Test public void serverCloseThenWritingCloseThrows() throws IOException {
    server.close(1000, "Hello!");
    client.processNextFrame();
    clientListener.assertClosing(1000, "Hello!");
    assertTrue(client.close(1000, "Bye!"));
  }

  @Test public void clientCloseClosesConnection() throws IOException {
    client.close(1000, "Hello!");
    assertFalse(clientConnectionClosed);
    server.processNextFrame(); // Read client closing, send server close.
    serverListener.assertClosing(1000, "Hello!");

    server.close(1000, "Goodbye!");
    client.processNextFrame(); // Read server closing, close connection.
    assertTrue(clientConnectionClosed);
    clientListener.assertClosing(1000, "Goodbye!");

    // Server and client both finished closing, connection is closed.
    serverListener.assertClosed(1000, "Hello!");
    clientListener.assertClosed(1000, "Goodbye!");
  }

  @Test public void serverCloseClosesConnection() throws IOException {
    server.close(1000, "Hello!");

    client.processNextFrame(); // Read server close, send client close, close connection.
    assertFalse(clientConnectionClosed);
    clientListener.assertClosing(1000, "Hello!");

    client.close(1000, "Hello!");
    server.processNextFrame();
    serverListener.assertClosing(1000, "Hello!");

    clientListener.assertClosed(1000, "Hello!");
    serverListener.assertClosed(1000, "Hello!");
  }

  @Test public void clientAndServerCloseClosesConnection() throws IOException {
    // Send close from both sides at the same time.
    server.close(1000, "Hello!");
    client.processNextFrame(); // Read close, close connection close.

    assertFalse(clientConnectionClosed);
    client.close(1000, "Hi!");
    server.processNextFrame();

    clientListener.assertClosing(1000, "Hello!");
    serverListener.assertClosing(1000, "Hi!");
    clientListener.assertClosed(1000, "Hello!");
    serverListener.assertClosed(1000, "Hi!");
    assertTrue(clientConnectionClosed);

    serverListener.assertExhausted(); // Client should not have sent second close.
    clientListener.assertExhausted(); // Server should not have sent second close.
  }

  @Test public void serverCloseBreaksReadMessageLoop() throws IOException {
    server.send("Hello!");
    server.close(1000, "Bye!");
    assertTrue(client.processNextFrame());
    clientListener.assertTextMessage("Hello!");
    assertFalse(client.processNextFrame());
    clientListener.assertClosing(1000, "Bye!");
  }

  @Test public void protocolErrorBeforeCloseSendsFailure() throws IOException {
    server2clientSink.write(ByteString.decodeHex("0a00")).emit(); // Invalid non-final ping frame.

    client.processNextFrame(); // Detects error, send close, close connection.
    assertTrue(clientConnectionClosed);
    clientListener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.processNextFrame();
    serverListener.assertFailure(EOFException.class, null);
  }

  @Test public void protocolErrorInCloseResponseClosesConnection() throws IOException {
    client.close(1000, "Hello");
    server.processNextFrame();
    assertFalse(clientConnectionClosed); // Not closed until close reply is received.

    // Manually write an invalid masked close frame.
    server2clientSink.write(ByteString.decodeHex("888760b420bb635c68de0cd84f")).emit();

    client.processNextFrame(); // Detects error, closes connection immediately since close already sent.
    assertTrue(clientConnectionClosed);
    clientListener.assertFailure(ProtocolException.class, "Server-sent frames must not be masked.");

    serverListener.assertClosing(1000, "Hello");
    serverListener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void protocolErrorAfterCloseDoesNotSendClose() throws IOException {
    client.close(1000, "Hello!");
    server.processNextFrame();

    assertFalse(clientConnectionClosed); // Not closed until close reply is received.
    server2clientSink.write(ByteString.decodeHex("0a00")).emit(); // Invalid non-final ping frame.

    client.processNextFrame(); // Detects error, closes connection immediately since close already sent.
    assertTrue(clientConnectionClosed);
    clientListener.assertFailure(ProtocolException.class, "Control frames must be final.");

    serverListener.assertClosing(1000, "Hello!");

    serverListener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void networkErrorReportedAsFailure() throws IOException {
    server2clientSink.close();
    client.processNextFrame();
    clientListener.assertFailure(EOFException.class, null);
  }

  @Test public void closeThrowingFailsConnection() throws IOException {
    client2Server.source().close();
    client.close(1000, null);
    clientListener.assertFailure(IOException.class, "source is closed");
  }

  @Ignore // TODO(jwilson): come up with a way to test unchecked exceptions on the writer thread.
  @Test public void closeMessageAndConnectionCloseThrowingDoesNotMaskOriginal() throws IOException {
    client2ServerSink.close();
    clientConnectionCloseThrows = true;

    client.close(1000, "Bye!");
    clientListener.assertFailure(IOException.class, "failure");
    assertTrue(clientConnectionClosed);
  }

  @Ignore // TODO(jwilson): come up with a way to test unchecked exceptions on the writer thread.
  @Test public void peerConnectionCloseThrowingPropagates() throws IOException {
    clientConnectionCloseThrows = true;

    server.close(1000, "Bye from Server!");
    client.processNextFrame();
    clientListener.assertClosing(1000, "Bye from Server!");

    client.close(1000, "Bye from Client!");
    server.processNextFrame();
    serverListener.assertClosing(1000, "Bye from Client!");
  }
}
