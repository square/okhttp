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

import com.squareup.okhttp.ws.WebSocketRecorder;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.okhttp.ws.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.ws.WebSocket.PayloadType.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RealWebSocketTest {
  // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has
  // zero effect on the behavior of the WebSocket API which is why tests are only written once
  // from the perspective of a single peer.

  private final Executor clientExecutor = Executors.newSingleThreadExecutor();
  private RealWebSocket client;
  private boolean clientConnectionCloseThrows;
  private boolean clientConnectionClosed;
  private final Buffer client2Server = new Buffer();
  private final WebSocketRecorder clientListener = new WebSocketRecorder();

  private final Executor serverExecutor = Executors.newSingleThreadExecutor();
  private RealWebSocket server;
  private final Buffer server2client = new Buffer();
  private final WebSocketRecorder serverListener = new WebSocketRecorder();

  @Before public void setUp() {
    Random random = new Random(0);
    String url = "http://example.com/websocket";

    client = new RealWebSocket(true, server2client, client2Server, random, clientExecutor,
        clientListener, url) {
      @Override protected void closeConnection() throws IOException {
        clientConnectionClosed = true;
        if (clientConnectionCloseThrows) {
          throw new IOException("Oops!");
        }
      }
    };
    server = new RealWebSocket(false, client2Server, server2client, random, serverExecutor,
        serverListener, url) {
      @Override protected void closeConnection() throws IOException {
      }
    };
  }

  @After public void tearDown() {
    clientListener.assertExhausted();
    serverListener.assertExhausted();
  }

  @Test public void textMessage() throws IOException {
    client.sendMessage(TEXT, new Buffer().writeUtf8("Hello!"));
    server.readMessage();
    serverListener.assertTextMessage("Hello!");
  }

  @Test public void binaryMessage() throws IOException {
    client.sendMessage(BINARY, new Buffer().writeUtf8("Hello!"));
    server.readMessage();
    serverListener.assertBinaryMessage(new byte[] { 'H', 'e', 'l', 'l', 'o', '!' });
  }

  @Test public void streamingMessage() throws IOException {
    BufferedSink sink = client.newMessageSink(TEXT);
    sink.writeUtf8("Hel").flush();
    sink.writeUtf8("lo!").flush();
    sink.close();
    server.readMessage();
    serverListener.assertTextMessage("Hello!");
  }

  @Test public void streamingMessageCanInterleavePing() throws IOException, InterruptedException {
    BufferedSink sink = client.newMessageSink(TEXT);
    sink.writeUtf8("Hel").flush();
    client.sendPing(new Buffer().writeUtf8("Pong?"));
    sink.writeUtf8("lo!").flush();
    sink.close();
    server.readMessage();
    serverListener.assertTextMessage("Hello!");
    waitForExecutor(serverExecutor); // Pong write happens asynchronously.
    client.readMessage();
    clientListener.assertPong(new Buffer().writeUtf8("Pong?"));
  }

  @Test public void pingWritesPong() throws IOException, InterruptedException {
    client.sendPing(new Buffer().writeUtf8("Hello!"));
    server.readMessage(); // Read the ping, write the pong.
    waitForExecutor(serverExecutor); // Pong write happens asynchronously.
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
      client.sendMessage(TEXT, new Buffer().writeUtf8("Hello!"));
      fail();
    } catch (IllegalStateException e) {
      assertEquals("closed", e.getMessage());
    }
    try {
      client.newMessageSink(TEXT);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void serverCloseThenWritingThrows() throws IOException {
    server.close(1000, "Hello!");
    client.readMessage();
    clientListener.assertClose(1000, "Hello!");

    try {
      client.sendPing(new Buffer().writeUtf8("Pong?"));
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
    try {
      client.sendMessage(TEXT, new Buffer().writeUtf8("Hi!"));
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
    try {
      client.close(1000, "Bye!");
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void serverCloseWhileWritingThrows() throws IOException {
    // Start writing data.
    BufferedSink sink = client.newMessageSink(TEXT);
    sink.writeUtf8("Hel").flush();

    server.close(1000, "Hello!");
    client.readMessage();
    clientListener.assertClose(1000, "Hello!");

    try {
      sink.writeUtf8("lo!").emit(); // No writing to the underlying sink.
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
      sink.buffer().clear();
    }
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

  @Test public void clientCloseClosesConnection() throws IOException {
    client.close(1000, "Hello!");
    assertFalse(clientConnectionClosed);
    server.readMessage(); // Read client close, send server close.
    serverListener.assertClose(1000, "Hello!");

    client.readMessage(); // Read server close.
    waitForExecutor(clientExecutor); // Close happens asynchronously.
    assertTrue(clientConnectionClosed);
    clientListener.assertClose(1000, "Hello!");
  }

  @Test public void serverCloseClosesConnection() throws IOException {
    server.close(1000, "Hello!");

    client.readMessage(); // Read server close, send client close, close connection.
    clientListener.assertClose(1000, "Hello!");
    assertTrue(clientConnectionClosed);

    server.readMessage();
    serverListener.assertClose(1000, "Hello!");
  }

  @Test public void clientAndServerCloseClosesConnection() throws IOException {
    // Send close from both sides at the same time.
    server.close(1000, "Hello!");
    client.close(1000, "Hi!");
    assertFalse(clientConnectionClosed);

    client.readMessage(); // Read close, should NOT send close.
    waitForExecutor(clientExecutor); // Close happens asynchronously.
    assertTrue(clientConnectionClosed);
    clientListener.assertClose(1000, "Hello!");

    server.readMessage();
    serverListener.assertClose(1000, "Hi!");

    serverListener.assertExhausted(); // Client should not have sent second close.
    clientListener.assertExhausted(); // Server should not have sent second close.
  }

  @Test public void serverCloseBreaksReadMessageLoop() throws IOException {
    server.sendMessage(TEXT, new Buffer().writeUtf8("Hello!"));
    server.close(1000, "Bye!");
    assertTrue(client.readMessage());
    clientListener.assertTextMessage("Hello!");
    assertFalse(client.readMessage());
    clientListener.assertClose(1000, "Bye!");
  }

  @Test public void protocolErrorBeforeCloseSendsClose() {
    server2client.write(ByteString.decodeHex("0a00")); // Invalid non-final ping frame.

    client.readMessage(); // Detects error, send close.
    clientListener.assertFailure(ProtocolException.class, "Control frames must be final.");
    assertTrue(clientConnectionClosed);

    server.readMessage();
    serverListener.assertClose(1002, "");
  }

  @Test public void protocolErrorAfterCloseDoesNotSendClose() throws IOException {
    client.close(1000, "Hello!");
    server2client.write(ByteString.decodeHex("0a00")); // Invalid non-final ping frame.

    client.readMessage();
    clientListener.assertFailure(ProtocolException.class, "Control frames must be final.");
    assertTrue(clientConnectionClosed);

    server.readMessage();
    serverListener.assertClose(1000, "Hello!");
  }

  @Test public void peerConnectionCloseThrowingDoesNotPropagate() throws IOException {
    clientConnectionCloseThrows = true;

    server.close(1000, "Bye!");
    client.readMessage();
    clientListener.assertClose(1000, "Bye!");
    assertTrue(clientConnectionClosed);

    server.readMessage();
    serverListener.assertClose(1000, "Bye!");
  }

  private static void waitForExecutor(Executor executor) {
    final CountDownLatch latch = new CountDownLatch(1);
    executor.execute(new Runnable() {
      @Override public void run() {
        latch.countDown();
      }
    });
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for executor.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
