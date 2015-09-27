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
import java.util.Random;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;
import okio.Sink;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static com.squareup.okhttp.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static com.squareup.okhttp.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static com.squareup.okhttp.internal.ws.WebSocketProtocol.toggleMask;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class WebSocketWriterTest {
  private final Buffer data = new Buffer();
  private final Random random = new Random(0);

  /**
   * Check all data as verified inside of the test. We do this in a rule instead of @After so that
   * exceptions thrown from the test do not cause this check to fail.
   */
  @Rule public final TestRule noDataLeftBehind = new TestRule() {
    @Override public Statement apply(final Statement base, Description description) {
      return new Statement() {
        @Override public void evaluate() throws Throwable {
          base.evaluate();
          assertEquals("Data not empty", "", data.readByteString().hex());
        }
      };
    }
  };

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  private final WebSocketWriter serverWriter = new WebSocketWriter(false, data, random);
  private final WebSocketWriter clientWriter = new WebSocketWriter(true, data, random);

  @Test public void serverTextMessage() throws IOException {
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT));

    sink.writeUtf8("Hel").flush();
    assertData("010348656c");

    sink.writeUtf8("lo").flush();
    assertData("00026c6f");

    sink.close();
    assertData("8000");
  }

  @Test public void serverCloseFlushes() throws IOException {
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT));

    sink.writeUtf8("Hel").flush();
    assertData("010348656c");

    sink.writeUtf8("lo").close();
    assertData("00026c6f");
    assertData("8000");
  }

  @Test public void clientTextMessage() throws IOException {
    BufferedSink sink = Okio.buffer(clientWriter.newMessageSink(OPCODE_TEXT));

    sink.writeUtf8("Hel").flush();
    assertData("018360b420bb28d14c");

    sink.writeUtf8("lo").flush();
    assertData("00823851d9d4543e");

    sink.close();
    assertData("80807acb933d");
  }

  @Test public void serverBinaryMessage() throws IOException {
    Sink sink = serverWriter.newMessageSink(OPCODE_BINARY);

    sink.write(binaryData(50), 50);
    assertData("0232");
    assertData(binaryData(50).readByteArray());

    sink.write(binaryData(50), 50);
    assertData("0032");
    assertData(binaryData(50).readByteArray());

    sink.close();
    assertData("8000");
  }

  @Test public void serverBinaryMessageLengthShort() throws IOException {
    Sink sink = serverWriter.newMessageSink(OPCODE_BINARY);

    int length = 0xffff;
    sink.write(binaryData(length), length);
    assertData("027effff");
    assertData(binaryData(length).readByteArray());

    sink.close();
    assertData("8000");
  }

  @Test public void serverBinaryMessageLengthLong() throws IOException {
    Sink sink = serverWriter.newMessageSink(OPCODE_BINARY);

    int length = 65537;
    sink.write(binaryData(length), length);
    assertData("027f0000000000010001");
    assertData(binaryData(length).readByteArray());

    sink.close();
    assertData("8000");
  }

  @Test public void clientBinary() throws IOException {
    byte[] maskKey1 = new byte[4];
    random.nextBytes(maskKey1);
    byte[] maskKey2 = new byte[4];
    random.nextBytes(maskKey2);
    byte[] maskKey3 = new byte[4];
    random.nextBytes(maskKey3);

    random.setSeed(0); // Reset the seed so real data matches.

    Sink sink = clientWriter.newMessageSink(OPCODE_BINARY);

    sink.write(binaryData(50), 50);
    byte[] part1 = binaryData(50).readByteArray();
    toggleMask(part1, 50, maskKey1, 0);
    assertData("02b2");
    assertData(maskKey1);
    assertData(part1);

    sink.write(binaryData(50), 50);
    byte[] part2 = binaryData(50).readByteArray();
    toggleMask(part2, 50, maskKey2, 0);
    assertData("00b2");
    assertData(maskKey2);
    assertData(part2);

    sink.close();
    assertData("8080");
    assertData(maskKey3);
  }

  @Test public void serverEmptyClose() throws IOException {
    serverWriter.writeClose(0, null);
    assertData("8800");
  }

  @Test public void serverCloseWithCode() throws IOException {
    serverWriter.writeClose(1005, null);
    assertData("880203ed");
  }

  @Test public void serverCloseWithCodeAndReason() throws IOException {
    serverWriter.writeClose(1005, "Hello");
    assertData("880703ed48656c6c6f");
  }

  @Test public void clientEmptyClose() throws IOException {
    clientWriter.writeClose(0, null);
    assertData("888060b420bb");
  }

  @Test public void clientCloseWithCode() throws IOException {
    clientWriter.writeClose(1005, null);
    assertData("888260b420bb6359");
  }

  @Test public void clientCloseWithCodeAndReason() throws IOException {
    clientWriter.writeClose(1005, "Hello");
    assertData("888760b420bb635968de0cd84f");
  }

  @Test public void closeWithOnlyReasonThrows() throws IOException {
    clientWriter.writeClose(0, "Hello");
    assertData("888760b420bb60b468de0cd84f");
  }

  @Test public void closeCodeOutOfRangeThrows() throws IOException {
    try {
      clientWriter.writeClose(98724976, "Hello");
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Code must be in range [1000,5000).", e.getMessage());
    }
  }

  @Test public void serverEmptyPing() throws IOException {
    serverWriter.writePing(null);
    assertData("8900");
  }

  @Test public void clientEmptyPing() throws IOException {
    clientWriter.writePing(null);
    assertData("898060b420bb");
  }

  @Test public void serverPingWithPayload() throws IOException {
    serverWriter.writePing(new Buffer().writeUtf8("Hello"));
    assertData("890548656c6c6f");
  }

  @Test public void clientPingWithPayload() throws IOException {
    clientWriter.writePing(new Buffer().writeUtf8("Hello"));
    assertData("898560b420bb28d14cd70f");
  }

  @Test public void serverEmptyPong() throws IOException {
    serverWriter.writePong(null);
    assertData("8a00");
  }

  @Test public void clientEmptyPong() throws IOException {
    clientWriter.writePong(null);
    assertData("8a8060b420bb");
  }

  @Test public void serverPongWithPayload() throws IOException {
    serverWriter.writePong(new Buffer().writeUtf8("Hello"));
    assertData("8a0548656c6c6f");
  }

  @Test public void clientPongWithPayload() throws IOException {
    clientWriter.writePong(new Buffer().writeUtf8("Hello"));
    assertData("8a8560b420bb28d14cd70f");
  }

  @Test public void pingTooLongThrows() throws IOException {
    try {
      serverWriter.writePing(binaryData(1000));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Payload size must be less than or equal to 125", e.getMessage());
    }
  }

  @Test public void pongTooLongThrows() throws IOException {
    try {
      serverWriter.writePong(binaryData(1000));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Payload size must be less than or equal to 125", e.getMessage());
    }
  }

  @Test public void closeTooLongThrows() throws IOException {
    try {
      String longString = binaryData(75).readByteString().hex();
      serverWriter.writeClose(1000, longString);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Payload size must be less than or equal to 125", e.getMessage());
    }
  }

  @Test public void twoMessageSinksThrows() {
    clientWriter.newMessageSink(OPCODE_TEXT);
    try {
      clientWriter.newMessageSink(OPCODE_TEXT);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Another message writer is active. Did you call close()?", e.getMessage());
    }
  }

  private void assertData(String hex) throws EOFException {
    ByteString expected = ByteString.decodeHex(hex);
    ByteString actual = data.readByteString(expected.size());
    assertEquals(expected, actual);
  }

  private void assertData(byte[] data) throws IOException {
    int byteCount = 16;
    for (int i = 0; i < data.length; i += byteCount) {
      int count = Math.min(byteCount, data.length - i);
      Buffer expectedChunk = new Buffer();
      expectedChunk.write(data, i, count);
      assertEquals("At " + i, expectedChunk.readByteString(), this.data.readByteString(count));
    }
  }

  private static Buffer binaryData(int length) {
    byte[] junk = new byte[length];
    new Random(0).nextBytes(junk);
    return new Buffer().write(junk);
  }
}
