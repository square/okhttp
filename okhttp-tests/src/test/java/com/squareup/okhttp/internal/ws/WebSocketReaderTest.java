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

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import org.junit.Test;

import static com.squareup.okhttp.internal.ws.WebSocket.PayloadType;
import static com.squareup.okhttp.internal.ws.RecordingWebSocketListener.MessageDelegate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class WebSocketReaderTest {
  private final Buffer data = new Buffer();
  private final RecordingWebSocketListener listener = new RecordingWebSocketListener();
  private final RecordingReaderFrameCallback callback = new RecordingReaderFrameCallback();
  private final Random random = new Random(0);

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  private final WebSocketReader serverReader = new WebSocketReader(false, data, listener, callback);
  private final WebSocketReader clientReader = new WebSocketReader(true, data, listener, callback);

  @Test public void controlFramesMustBeFinal() throws IOException {
    data.write(ByteString.decodeHex("0a00")); // Empty ping.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Control frames must be final.", e.getMessage());
    }
  }

  @Test public void reservedFlagsAreUnsupported() throws IOException {
    data.write(ByteString.decodeHex("9a00")); // Empty ping, flag 1 set.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
    data.clear();
    data.write(ByteString.decodeHex("aa00")); // Empty ping, flag 2 set.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
    data.clear();
    data.write(ByteString.decodeHex("ca00")); // Empty ping, flag 3 set.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
  }

  @Test public void clientSentFramesMustBeMasked() throws IOException {
    data.write(ByteString.decodeHex("8100"));
    try {
      serverReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Client-sent frames must be masked. Server sent must not.", e.getMessage());
    }
  }

  @Test public void serverSentFramesMustNotBeMasked() throws IOException {
    data.write(ByteString.decodeHex("8180"));
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Client-sent frames must be masked. Server sent must not.", e.getMessage());
    }
  }

  @Test public void controlFramePayloadMax() throws IOException {
    data.write(ByteString.decodeHex("8a7e007e"));
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Control frame must be less than 125B.", e.getMessage());
    }
  }

  @Test public void clientSimpleHello() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    clientReader.readMessage();
    listener.assertTextMessage("Hello");
  }

  @Test public void serverSimpleHello() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d5158")); // Hello
    serverReader.readMessage();
    listener.assertTextMessage("Hello");
  }

  @Test public void serverHelloTwoChunks() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d")); // Hel

    final Buffer sink = new Buffer();
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.readFully(sink, 3); // Read "Hel"
        data.write(ByteString.decodeHex("5158")); // lo
        payload.readFully(sink, 2); // Read "lo"
        payload.close();
      }
    });
    serverReader.readMessage();

    assertEquals("Hello", sink.readUtf8());
  }

  @Test public void clientTwoFrameHello() throws IOException {
    data.write(ByteString.decodeHex("010348656c")); // Hel
    data.write(ByteString.decodeHex("80026c6f")); // lo
    clientReader.readMessage();
    listener.assertTextMessage("Hello");
  }

  @Test public void clientTwoFrameHelloWithPongs() throws IOException {
    data.write(ByteString.decodeHex("010348656c")); // Hel
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("80026c6f")); // lo
    clientReader.readMessage();
    listener.assertTextMessage("Hello");
  }

  @Test public void clientIncompleteMessageBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("810548656c")); // Length = 5, "Hel"
    try {
      clientReader.readMessage();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void clientIncompleteControlFrameBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("8a0548656c")); // Length = 5, "Hel"
    try {
      clientReader.readMessage();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void serverIncompleteMessageBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d")); // Length = 5, "Hel"
    try {
      serverReader.readMessage();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void serverIncompleteControlFrameBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("8a8537fa213d7f9f4d")); // Length = 5, "Hel"
    try {
      serverReader.readMessage();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void clientSimpleBinary() throws IOException {
    byte[] bytes = binaryData(256);
    data.write(ByteString.decodeHex("827E0100")).write(bytes);
    clientReader.readMessage();
    listener.assertBinaryMessage(bytes);
  }

  @Test public void clientTwoFrameBinary() throws IOException {
    byte[] bytes = binaryData(200);
    data.write(ByteString.decodeHex("0264")).write(bytes, 0, 100);
    data.write(ByteString.decodeHex("8064")).write(bytes, 100, 100);
    clientReader.readMessage();
    listener.assertBinaryMessage(bytes);
  }

  @Test public void twoFrameNotContinuation() throws IOException {
    byte[] bytes = binaryData(200);
    data.write(ByteString.decodeHex("0264")).write(bytes, 0, 100);
    data.write(ByteString.decodeHex("8264")).write(bytes, 100, 100);
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Expected continuation opcode. Got: 2", e.getMessage());
    }
  }

  @Test public void noCloseErrors() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.readAll(new Buffer());
      }
    });
    try {
      clientReader.readMessage();
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Listener failed to call close on message payload.", e.getMessage());
    }
  }

  @Test public void closeExhaustsMessage() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    data.write(ByteString.decodeHex("810448657921")); // Hey!

    final Buffer sink = new Buffer();
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.read(sink, 3);
        payload.close();
      }
    });

    clientReader.readMessage();
    assertEquals("Hel", sink.readUtf8());

    clientReader.readMessage();
    listener.assertTextMessage("Hey!");
  }

  @Test public void closeExhaustsMessageOverControlFrames() throws IOException {
    data.write(ByteString.decodeHex("010348656c")); // Hel
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("80026c6f")); // lo
    data.write(ByteString.decodeHex("810448657921")); // Hey!

    final Buffer sink = new Buffer();
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.read(sink, 2);
        payload.close();
      }
    });

    clientReader.readMessage();
    assertEquals("He", sink.readUtf8());

    clientReader.readMessage();
    listener.assertTextMessage("Hey!");
  }

  @Test public void closedMessageSourceThrows() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello

    final AtomicReference<IOException> exception = new AtomicReference<IOException>();
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.close();
        try {
          payload.readAll(new Buffer());
          fail();
        } catch (IOException e) {
          exception.set(e);
        }
      }
    });
    clientReader.readMessage();

    assertNotNull(exception.get());
  }

  @Test public void emptyPingCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("8900")); // Empty ping
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    clientReader.readMessage();
    callback.assertPing(null);
    listener.assertTextMessage("Hello");
  }

  @Test public void pingCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("890548656c6c6f")); // Ping with "Hello"
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    clientReader.readMessage();
    callback.assertPing(new Buffer().writeUtf8("Hello"));
    listener.assertTextMessage("Hello");
  }

  @Test public void emptyCloseCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("8800")); // Empty close
    clientReader.readMessage();
    callback.assertClose(null);
    listener.onClose(0, "");
  }

  @Test public void closeCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("880703e848656c6c6f")); // Close with code and reason
    clientReader.readMessage();
    callback.assertClose(new Buffer().writeShort(1000).writeUtf8("Hello"));
    listener.onClose(1000, "Hello");
  }

  private byte[] binaryData(int length) {
    byte[] junk = new byte[length];
    random.nextBytes(junk);
    return junk;
  }
}
