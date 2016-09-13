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

import java.io.Closeable;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.Executor;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.WebSocketRecorder;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static okhttp3.WebSocket.BINARY;
import static okhttp3.WebSocket.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RealWebSocketTest {
  // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has
  // zero effect on the behavior of the WebSocket API which is why tests are only written once
  // from the perspective of a single peer.

  private final Executor clientExecutor = new SynchronousExecutor();
  private RealWebSocket client;
  private boolean clientConnectionCloseThrows;
  private boolean clientConnectionClosed;
  private final MemorySocket client2Server = new MemorySocket();
  private final WebSocketRecorder clientListener = new WebSocketRecorder();

  private final Executor serverExecutor = new SynchronousExecutor();
  private RealWebSocket server;
  private boolean serverConnectionClosed;
  private final MemorySocket server2client = new MemorySocket();
  private final WebSocketRecorder serverListener = new WebSocketRecorder();

  @Before public void setUp() {
    Random random = new Random(0);
    String url = "http://example.com/websocket";

    client = new RealWebSocket(true, server2client.source(), client2Server.sink(), random,
        clientExecutor, clientListener, url) {
      @Override protected void close() throws IOException {
        if (clientConnectionClosed) {
          throw new AssertionError("Already closed");
        }
        clientConnectionClosed = true;

        if (clientConnectionCloseThrows) {
          throw new IOException("Oops!");
        }
      }
    };
    server = new RealWebSocket(false, client2Server.source(), server2client.sink(), random,
        serverExecutor, serverListener, url) {
      @Override protected void close() throws IOException {
        if (serverConnectionClosed) {
          throw new AssertionError("Already closed");
        }
        serverConnectionClosed = true;
      }
    };
  }

  @After public void tearDown() {
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }

  @Test public void nullMessageThrows() throws IOException {
    try {
      client.sendMessage(null);
      fail();
    } catch (NullPointerException e) {
      assertEquals("message == null", e.getMessage());
    }
  }

  @Test public void textMessage() throws IOException {
    client.sendMessage(RequestBody.create(TEXT, "Hello!"));
    server.readMessage();
    serverListener.assertTextMessage("Hello!");
  }

  @Test public void binaryMessage() throws IOException {
    client.sendMessage(RequestBody.create(BINARY, "Hello!"));
    server.readMessage();
    serverListener.assertBinaryMessage(new byte[] {'H', 'e', 'l', 'l', 'o', '!'});
  }

  @Test public void missingContentTypeThrows() throws IOException {
    try {
      client.sendMessage(RequestBody.create(null, "Hey!"));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Message content type was null. Must use WebSocket.TEXT or WebSocket.BINARY.",
          e.getMessage());
    }
  }

  @Test public void unknownContentTypeThrows() throws IOException {
    try {
      client.sendMessage(RequestBody.create(MediaType.parse("text/plain"), "Hey!"));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(
          "Unknown message content type: text/plain. Must use WebSocket.TEXT or WebSocket.BINARY.",
          e.getMessage());
    }
  }

  @Test public void streamingMessage() throws IOException {
    RequestBody message = new RequestBody() {
      @Override public MediaType contentType() {
        return TEXT;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("Hel").flush();
        sink.writeUtf8("lo!").flush();
        sink.close();
      }
    };
    client.sendMessage(message);
    server.readMessage();
    serverListener.assertTextMessage("Hello!");
  }

  @Test public void streamingMessageCanInterleavePing() throws IOException, InterruptedException {
    RequestBody message = new RequestBody() {
      @Override public MediaType contentType() {
        return TEXT;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("Hel").flush();
        client.sendPing(new Buffer().writeUtf8("Pong?"));
        sink.writeUtf8("lo!").flush();
        sink.close();
      }
    };

    client.sendMessage(message);
    server.readMessage();
    serverListener.assertTextMessage("Hello!");
    client.readMessage();
    clientListener.assertPong(new Buffer().writeUtf8("Pong?"));
  }

  @Test public void pingWritesPong() throws IOException, InterruptedException {
    client.sendPing(new Buffer().writeUtf8("Hello!"));
    server.readMessage(); // Read the ping, write the pong.
    client.readMessage(); // Read the pong.
    clientListener.assertPong(new Buffer().writeUtf8("Hello!"));
  }

  @Test public void unsolicitedPong() throws IOException {
    client.sendPong(new Buffer().writeUtf8("Hello!"));
    server.readMessage();
    serverListener.assertPong(new Buffer().writeUtf8("Hello!"));
  }

  @Test public void close() throws IOException {
    client.close(1000, "Hello!");
    assertFalse(server.readMessage()); // This will trigger a close response.
    serverListener.assertClose(1000, "Hello!");
    assertFalse(client.readMessage());
    clientListener.assertClose(1000, "Hello!");
  }

  @Test public void clientCloseThenMethodsThrow() throws IOException {
    client.close(1000, "Hello!");

    try {
      client.sendPing(new Buffer().writeUtf8("Pong?"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("closed", e.getMessage());
    }
    try {
      client.close(1000, "Hello!");
      fail();
    } catch (IllegalStateException e) {
      assertEquals("closed", e.getMessage());
    }
    try {
      client.sendMessage(RequestBody.create(TEXT, "Hello!"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void socketClosedDuringPingKillsWebSocket() throws IOException {
    client2Server.close();

    try {
      client.sendPing(new Buffer().writeUtf8("Ping!"));
      fail();
    } catch (IOException ignored) {
    }

    // A failed write prevents further use of the WebSocket instance.
    try {
      client.sendMessage(RequestBody.create(TEXT, "Hello!"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("must call close()", e.getMessage());
    }
    try {
      client.sendPing(new Buffer().writeUtf8("Ping!"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("must call close()", e.getMessage());
    }
  }

  @Test public void socketClosedDuringMessageKillsWebSocket() throws IOException {
    client2Server.close();

    try {
      client.sendMessage(RequestBody.create(TEXT, "Hello!"));
      fail();
    } catch (IOException ignored) {
    }

    // A failed write prevents further use of the WebSocket instance.
    try {
      client.sendMessage(RequestBody.create(TEXT, "Hello!"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("must call close()", e.getMessage());
    }
    try {
      client.sendPing(new Buffer().writeUtf8("Ping!"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("must call close()", e.getMessage());
    }
  }

  @Test public void serverCloseThenWritingPingThrows() throws IOException {
    server.close(1000, "Hello!");
    client.readMessage();
    clientListener.assertClose(1000, "Hello!");

    try {
      client.sendPing(new Buffer().writeUtf8("Pong?"));
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void serverCloseThenWritingMessageThrows() throws IOException {
    server.close(1000, "Hello!");
    client.readMessage();
    clientListener.assertClose(1000, "Hello!");

    try {
      client.sendMessage(RequestBody.create(TEXT, "Hi!"));
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void serverCloseThenWritingCloseThrows() throws IOException {
    server.close(1000, "Hello!");
    client.readMessage();
    clientListener.assertClose(1000, "Hello!");

    try {
      client.close(1000, "Bye!");
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void serverCloseWhileWritingThrows() throws IOException {
    RequestBody message = new RequestBody() {
      @Override public MediaType contentType() {
        return TEXT;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        // Start writing data.
        sink.writeUtf8("Hel").flush();

        server.close(1000, "Hello!");
        client.readMessage();
        clientListener.assertClose(1000, "Hello!");

        try {
          sink.flush(); // No flushing.
          fail();
        } catch (IOException e) {
          assertEquals("closed", e.getMessage());
        }
        try {
          sink.close(); // No closing because this requires writing a frame.
          fail();
        } catch (IOException e) {
          assertEquals("closed", e.getMessage());
        }
      }
    };
    client.sendMessage(message);
  }

  @Test public void clientCloseClosesConnection() throws IOException {
    client.close(1000, "Hello!");
    assertFalse(clientConnectionClosed);
    server.readMessage(); // Read client close, send server close.
    serverListener.assertClose(1000, "Hello!");

    client.readMessage(); // Read server close, close connection.
    assertTrue(clientConnectionClosed);
    clientListener.assertClose(1000, "Hello!");
  }

  @Test public void serverCloseClosesConnection() throws IOException {
    server.close(1000, "Hello!");

    client.readMessage(); // Read server close, send client close, close connection.
    assertTrue(clientConnectionClosed);
    clientListener.assertClose(1000, "Hello!");

    server.readMessage();
    serverListener.assertClose(1000, "Hello!");
  }

  @Test public void clientAndServerCloseClosesConnection() throws IOException {
    // Send close from both sides at the same time.
    server.close(1000, "Hello!");
    client.close(1000, "Hi!");
    assertFalse(clientConnectionClosed);

    client.readMessage(); // Read close, close connection close.
    assertTrue(clientConnectionClosed);
    clientListener.assertClose(1000, "Hello!");

    server.readMessage();
    serverListener.assertClose(1000, "Hi!");

    serverListener.assertExhausted(); // Client should not have sent second close.
    clientListener.assertExhausted(); // Server should not have sent second close.
  }

  @Test public void serverCloseBreaksReadMessageLoop() throws IOException {
    server.sendMessage(RequestBody.create(TEXT, "Hello!"));
    server.close(1000, "Bye!");
    assertTrue(client.readMessage());
    clientListener.assertTextMessage("Hello!");
    assertFalse(client.readMessage());
    clientListener.assertClose(1000, "Bye!");
  }

  @Test public void protocolErrorBeforeCloseSendsClose() throws IOException {
    server2client.raw().write(ByteString.decodeHex("0a00")); // Invalid non-final ping frame.

    client.readMessage(); // Detects error, send close, close connection.
    assertTrue(clientConnectionClosed);
    clientListener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.readMessage();
    serverListener.assertClose(1002, "");
  }

  @Test public void protocolErrorAfterCloseDoesNotSendClose() throws IOException {
    client.close(1000, "Hello!");
    assertFalse(clientConnectionClosed); // Not closed until close reply is received.
    server2client.raw().write(ByteString.decodeHex("0a00")); // Invalid non-final ping frame.

    client.readMessage(); // Detects error, closes connection immediately since close already sent.
    assertTrue(clientConnectionClosed);
    clientListener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.readMessage();
    serverListener.assertClose(1000, "Hello!");

    serverListener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void closeThrowingClosesConnection() {
    client2Server.close();

    try {
      client.close(1000, null);
      fail();
    } catch (IOException ignored) {
    }
    assertTrue(clientConnectionClosed);
  }

  @Test public void closeMessageAndConnectionCloseThrowingDoesNotMaskOriginal() throws IOException {
    client2Server.close();
    clientConnectionCloseThrows = true;

    try {
      client.close(1000, "Bye!");
      fail();
    } catch (IOException e) {
      assertNotEquals("Oops!", e.getMessage());
    }
    assertTrue(clientConnectionClosed);
  }

  @Test public void peerConnectionCloseThrowingDoesNotPropagate() throws IOException {
    clientConnectionCloseThrows = true;

    server.close(1000, "Bye!");
    client.readMessage();
    assertTrue(clientConnectionClosed);
    clientListener.assertClose(1000, "Bye!");

    server.readMessage();
    serverListener.assertClose(1000, "Bye!");
  }

  static final class MemorySocket implements Closeable {
    private final Buffer buffer = new Buffer();
    private boolean closed;

    @Override public void close() {
      closed = true;
    }

    Buffer raw() {
      return buffer;
    }

    BufferedSource source() {
      return Okio.buffer(new Source() {
        @Override public long read(Buffer sink, long byteCount) throws IOException {
          if (closed) throw new IOException("closed");
          return buffer.read(sink, byteCount);
        }

        @Override public Timeout timeout() {
          return Timeout.NONE;
        }

        @Override public void close() throws IOException {
          closed = true;
        }
      });
    }

    BufferedSink sink() {
      return Okio.buffer(new Sink() {
        @Override public void write(Buffer source, long byteCount) throws IOException {
          if (closed) throw new IOException("closed");
          buffer.write(source, byteCount);
        }

        @Override public void flush() throws IOException {
        }

        @Override public Timeout timeout() {
          return Timeout.NONE;
        }

        @Override public void close() throws IOException {
          closed = true;
        }
      });
    }
  }

  static final class SynchronousExecutor implements Executor {
    @Override public void execute(Runnable command) {
      command.run();
    }
  }
}
