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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import okhttp3.ResponseBody;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class WebSocketReaderTest {
  private final Buffer data = new Buffer();
  private final WebSocketRecorder callback = new WebSocketRecorder("client");
  private final Random random = new Random(0);

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  final WebSocketReader serverReader = new WebSocketReader(false, data, callback.asFrameCallback());
  final WebSocketReader clientReader = new WebSocketReader(true, data, callback.asFrameCallback());

  @After public void tearDown() {
    callback.assertExhausted();
  }

  @Test public void controlFramesMustBeFinal() throws IOException {
    data.write(ByteString.decodeHex("0a00")); // Empty ping.
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Control frames must be final.", e.getMessage());
    }
  }

  @Test public void reservedFlagsAreUnsupported() throws IOException {
    data.write(ByteString.decodeHex("9a00")); // Empty ping, flag 1 set.
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
    data.clear();
    data.write(ByteString.decodeHex("aa00")); // Empty ping, flag 2 set.
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
    data.clear();
    data.write(ByteString.decodeHex("ca00")); // Empty ping, flag 3 set.
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
  }

  @Test public void clientSentFramesMustBeMasked() throws IOException {
    data.write(ByteString.decodeHex("8100"));
    try {
      serverReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Client-sent frames must be masked.", e.getMessage());
    }
  }

  @Test public void serverSentFramesMustNotBeMasked() throws IOException {
    data.write(ByteString.decodeHex("8180"));
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Server-sent frames must not be masked.", e.getMessage());
    }
  }

  @Test public void controlFramePayloadMax() throws IOException {
    data.write(ByteString.decodeHex("8a7e007e"));
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Control frame must be less than 125B.", e.getMessage());
    }
  }

  @Test public void clientSimpleHello() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    clientReader.processNextFrame();
    callback.assertTextMessage("Hello");
  }

  @Test public void serverSimpleHello() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d5158")); // Hello
    serverReader.processNextFrame();
    callback.assertTextMessage("Hello");
  }

  @Test public void clientFramePayloadShort() throws IOException {
    data.write(ByteString.decodeHex("817E000548656c6c6f")); // Hello
    clientReader.processNextFrame();
    callback.assertTextMessage("Hello");
  }

  @Test public void clientFramePayloadLong() throws IOException {
    data.write(ByteString.decodeHex("817f000000000000000548656c6c6f")); // Hello
    clientReader.processNextFrame();
    callback.assertTextMessage("Hello");
  }

  @Test public void clientFramePayloadTooLongThrows() throws IOException {
    data.write(ByteString.decodeHex("817f8000000000000000"));
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Frame length 0x8000000000000000 > 0x7FFFFFFFFFFFFFFF", e.getMessage());
    }
  }

  @Test public void serverHelloTwoChunks() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d")); // Hel

    final Buffer sink = new Buffer();
    callback.setNextEventDelegate(new EmptyWebSocketListener() {
      @Override public void onMessage(ResponseBody message) throws IOException {
        BufferedSource source = message.source();
        source.readFully(sink, 3); // Read "Hel"
        data.write(ByteString.decodeHex("5158")); // lo
        source.readFully(sink, 2); // Read "lo"
        source.close();
      }
    });
    serverReader.processNextFrame();

    assertEquals("Hello", sink.readUtf8());
  }

  @Test public void clientTwoFrameHello() throws IOException {
    data.write(ByteString.decodeHex("010348656c")); // Hel
    data.write(ByteString.decodeHex("80026c6f")); // lo
    clientReader.processNextFrame();
    callback.assertTextMessage("Hello");
  }

  @Test public void clientTwoFrameHelloWithPongs() throws IOException {
    data.write(ByteString.decodeHex("010348656c")); // Hel
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("80026c6f")); // lo
    clientReader.processNextFrame();
    callback.assertPong(null);
    callback.assertPong(null);
    callback.assertPong(null);
    callback.assertPong(null);
    callback.assertTextMessage("Hello");
  }

  @Test public void clientIncompleteMessageBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("810548656c")); // Length = 5, "Hel"
    try {
      clientReader.processNextFrame();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void clientIncompleteControlFrameBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("8a0548656c")); // Length = 5, "Hel"
    try {
      clientReader.processNextFrame();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void serverIncompleteMessageBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d")); // Length = 5, "Hel"
    try {
      serverReader.processNextFrame();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void serverIncompleteControlFrameBodyThrows() throws IOException {
    data.write(ByteString.decodeHex("8a8537fa213d7f9f4d")); // Length = 5, "Hel"
    try {
      serverReader.processNextFrame();
      fail();
    } catch (EOFException ignored) {
    }
  }

  @Test public void clientSimpleBinary() throws IOException {
    byte[] bytes = binaryData(256);
    data.write(ByteString.decodeHex("827E0100")).write(bytes);
    clientReader.processNextFrame();
    callback.assertBinaryMessage(bytes);
  }

  @Test public void clientTwoFrameBinary() throws IOException {
    byte[] bytes = binaryData(200);
    data.write(ByteString.decodeHex("0264")).write(bytes, 0, 100);
    data.write(ByteString.decodeHex("8064")).write(bytes, 100, 100);
    clientReader.processNextFrame();
    callback.assertBinaryMessage(bytes);
  }

  @Test public void twoFrameNotContinuation() throws IOException {
    byte[] bytes = binaryData(200);
    data.write(ByteString.decodeHex("0264")).write(bytes, 0, 100);
    data.write(ByteString.decodeHex("8264")).write(bytes, 100, 100);
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Expected continuation opcode. Got: 2", e.getMessage());
    }
  }

  @Test public void noCloseErrors() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    callback.setNextEventDelegate(new EmptyWebSocketListener() {
      @Override public void onMessage(ResponseBody body) throws IOException {
        body.source().readAll(new Buffer());
      }
    });
    try {
      clientReader.processNextFrame();
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Listener failed to call close on message payload.", e.getMessage());
    }
  }

  @Test public void closeExhaustsMessage() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello
    data.write(ByteString.decodeHex("810448657921")); // Hey!

    final Buffer sink = new Buffer();
    callback.setNextEventDelegate(new EmptyWebSocketListener() {
      @Override public void onMessage(ResponseBody message) throws IOException {
        message.source().read(sink, 3);
        message.close();
      }
    });

    clientReader.processNextFrame();
    assertEquals("Hel", sink.readUtf8());

    clientReader.processNextFrame();
    callback.assertTextMessage("Hey!");
  }

  @Test public void closeExhaustsMessageOverControlFrames() throws IOException {
    data.write(ByteString.decodeHex("010348656c")); // Hel
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("8a00")); // Pong
    data.write(ByteString.decodeHex("80026c6f")); // lo
    data.write(ByteString.decodeHex("810448657921")); // Hey!

    final Buffer sink = new Buffer();
    callback.setNextEventDelegate(new EmptyWebSocketListener() {
      @Override public void onMessage(ResponseBody message) throws IOException {
        message.source().read(sink, 2);
        message.close();
      }
    });

    clientReader.processNextFrame();
    assertEquals("He", sink.readUtf8());
    callback.assertPong(null);
    callback.assertPong(null);

    clientReader.processNextFrame();
    callback.assertTextMessage("Hey!");
  }

  @Test public void closedMessageSourceThrows() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f")); // Hello

    final AtomicReference<Exception> exception = new AtomicReference<>();
    callback.setNextEventDelegate(new EmptyWebSocketListener() {
      @Override public void onMessage(ResponseBody message) throws IOException {
        message.close();
        try {
          message.source().readAll(new Buffer());
          fail();
        } catch (IllegalStateException e) {
          exception.set(e);
        }
      }
    });
    clientReader.processNextFrame();

    assertNotNull(exception.get());
  }

  @Test public void emptyPingCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("8900")); // Empty ping
    clientReader.processNextFrame();
    callback.assertPing(null);
  }

  @Test public void pingCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("890548656c6c6f")); // Ping with "Hello"
    clientReader.processNextFrame();
    callback.assertPing(new Buffer().writeUtf8("Hello"));
  }

  @Test public void emptyCloseCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("8800")); // Empty close
    clientReader.processNextFrame();
    callback.assertClose(1000, "");
  }

  @Test public void closeLengthOfOneThrows() throws IOException {
    data.write(ByteString.decodeHex("880100")); // Close with invalid 1-byte payload
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Malformed close payload length of 1.", e.getMessage());
    }
  }

  @Test public void closeCallsCallback() throws IOException {
    data.write(ByteString.decodeHex("880703e848656c6c6f")); // Close with code and reason
    clientReader.processNextFrame();
    callback.assertClose(1000, "Hello");
  }

  @Test public void closeOutOfRangeThrows() throws IOException {
    data.write(ByteString.decodeHex("88020001")); // Close with code 1
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Code must be in range [1000,5000): 1", e.getMessage());
    }
    data.write(ByteString.decodeHex("88021388")); // Close with code 5000
    try {
      clientReader.processNextFrame();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Code must be in range [1000,5000): 5000", e.getMessage());
    }
  }

  @Test public void closeReservedSetThrows() throws IOException {
    data.write(ByteString.decodeHex("880203ec")); // Close with code 1004
    data.write(ByteString.decodeHex("880203ed")); // Close with code 1005
    data.write(ByteString.decodeHex("880203ee")); // Close with code 1006
    for (int i = 1012; i <= 2999; i++) {
      data.write(ByteString.decodeHex("8802" + Util.format("%04X", i))); // Close with code 'i'
    }

    int count = 0;
    for (; !data.exhausted(); count++) {
      try {
        clientReader.processNextFrame();
        fail();
      } catch (ProtocolException e) {
        String message = e.getMessage();
        assertTrue(message, Pattern.matches("Code \\d+ is reserved and may not be used.", message));
      }
    }
    assertEquals(1991, count);
  }

  private byte[] binaryData(int length) {
    byte[] junk = new byte[length];
    random.nextBytes(junk);
    return junk;
  }
}
