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
import java.util.Random;
import okhttp3.RequestBody;
import okhttp3.internal.Util;
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

import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.toggleMask;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT, -1));

    sink.writeUtf8("Hel").flush();
    assertData("010348656c");

    sink.writeUtf8("lo").flush();
    assertData("00026c6f");

    sink.close();
    assertData("8000");
  }

  @Test public void serverSmallBufferedPayloadWrittenAsOneFrame() throws IOException {
    int length = 5;
    byte[] bytes = binaryData(length);

    RequestBody body = RequestBody.create(null, bytes);
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT, length));
    body.writeTo(sink);
    sink.close();

    assertData("8105");
    assertData(bytes);
    assertTrue(data.exhausted());
  }

  @Test public void serverLargeBufferedPayloadWrittenAsOneFrame() throws IOException {
    int length = 12345;
    byte[] bytes = binaryData(length);

    RequestBody body = RequestBody.create(null, bytes);
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT, length));
    body.writeTo(sink);
    sink.close();

    assertData("817e");
    assertData(Util.format("%04x", length));
    assertData(bytes);
    assertTrue(data.exhausted());
  }

  @Test public void serverLargeNonBufferedPayloadWrittenAsMultipleFrames() throws IOException {
    int length = 100_000;
    Buffer bytes = new Buffer().write(binaryData(length));

    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT, length));
    Buffer body = bytes.clone();
    sink.write(body.readByteString(20_000));
    sink.write(body.readByteString(20_000));
    sink.write(body.readByteString(20_000));
    sink.write(body.readByteString(20_000));
    sink.write(body.readByteString(20_000));
    sink.close();

    assertData("017e4800");
    assertData(bytes.readByteArray(18_432));
    assertData("007e5000");
    assertData(bytes.readByteArray(20_480));
    assertData("007e5000");
    assertData(bytes.readByteArray(20_480));
    assertData("007e5000");
    assertData(bytes.readByteArray(20_480));
    assertData("007e4800");
    assertData(bytes.readByteArray(18_432));
    assertData("807e06a0");
    assertData(bytes.readByteArray(1_696));
    assertTrue(data.exhausted());
  }

  @Test public void closeFlushes() throws IOException {
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_TEXT, -1));

    sink.writeUtf8("Hel").flush();
    assertData("010348656c");

    sink.writeUtf8("lo").close();
    assertData("80026c6f");
  }

  @Test public void noWritesAfterClose() throws IOException {
    Sink sink = serverWriter.newMessageSink(OPCODE_TEXT, -1);

    sink.close();
    assertData("8100");

    Buffer payload = new Buffer().writeUtf8("Hello");
    try {
      // Write to the unbuffered sink as BufferedSink keeps its own closed state.
      sink.write(payload, payload.size());
      fail();
    } catch (IOException e) {
      assertEquals("closed", e.getMessage());
    }
  }

  @Test public void clientTextMessage() throws IOException {
    BufferedSink sink = Okio.buffer(clientWriter.newMessageSink(OPCODE_TEXT, -1));

    sink.writeUtf8("Hel").flush();
    assertData("018360b420bb28d14c");

    sink.writeUtf8("lo").flush();
    assertData("00823851d9d4543e");

    sink.close();
    assertData("80807acb933d");
  }

  @Test public void serverBinaryMessage() throws IOException {
    BufferedSink sink = Okio.buffer(serverWriter.newMessageSink(OPCODE_BINARY, -1));

    sink.write(binaryData(50)).flush();
    assertData("0232");
    assertData(binaryData(50));

    sink.write(binaryData(50)).flush();
    assertData("0032");
    assertData(binaryData(50));

    sink.close();
    assertData("8000");
  }

  @Test public void serverMessageLengthShort() throws IOException {
    Sink sink = serverWriter.newMessageSink(OPCODE_BINARY, -1);

    // Create a payload which will overflow the normal payload byte size.
    Buffer payload = new Buffer();
    while (payload.completeSegmentByteCount() <= PAYLOAD_BYTE_MAX) {
      payload.writeByte('0');
    }
    long byteCount = payload.completeSegmentByteCount();

    // Write directly to the unbuffered sink. This ensures it will become single frame.
    sink.write(payload.clone(), byteCount);
    assertData("027e"); // 'e' == 4-byte follow-up length.
    assertData(Util.format("%04X", payload.completeSegmentByteCount()));
    assertData(payload.readByteArray());

    sink.close();
    assertData("8000");
  }

  @Test public void serverMessageLengthLong() throws IOException {
    Sink sink = serverWriter.newMessageSink(OPCODE_BINARY, -1);

    // Create a payload which will overflow the normal and short payload byte size.
    Buffer payload = new Buffer();
    while (payload.completeSegmentByteCount() <= PAYLOAD_SHORT_MAX) {
      payload.writeByte('0');
    }
    long byteCount = payload.completeSegmentByteCount();

    // Write directly to the unbuffered sink. This ensures it will become single frame.
    sink.write(payload.clone(), byteCount);
    assertData("027f"); // 'f' == 16-byte follow-up length.
    assertData(Util.format("%016X", byteCount));
    assertData(payload.readByteArray(byteCount));

    sink.close();
    assertData("8000");
  }

  @Test public void clientBinary() throws IOException {
    byte[] maskKey1 = new byte[4];
    random.nextBytes(maskKey1);
    byte[] maskKey2 = new byte[4];
    random.nextBytes(maskKey2);

    random.setSeed(0); // Reset the seed so real data matches.

    BufferedSink sink = Okio.buffer(clientWriter.newMessageSink(OPCODE_BINARY, -1));

    byte[] part1 = binaryData(50);
    sink.write(part1).flush();
    toggleMask(part1, 50, maskKey1, 0);
    assertData("02b2");
    assertData(maskKey1);
    assertData(part1);

    byte[] part2 = binaryData(50);
    sink.write(part2).close();
    toggleMask(part2, 50, maskKey2, 0);
    assertData("80b2");
    assertData(maskKey2);
    assertData(part2);
  }

  @Test public void serverEmptyClose() throws IOException {
    serverWriter.writeClose(0, null);
    assertData("8800");
  }

  @Test public void serverCloseWithCode() throws IOException {
    serverWriter.writeClose(1001, null);
    assertData("880203e9");
  }

  @Test public void serverCloseWithCodeAndReason() throws IOException {
    serverWriter.writeClose(1001, "Hello");
    assertData("880703e948656c6c6f");
  }

  @Test public void clientEmptyClose() throws IOException {
    clientWriter.writeClose(0, null);
    assertData("888060b420bb");
  }

  @Test public void clientCloseWithCode() throws IOException {
    clientWriter.writeClose(1001, null);
    assertData("888260b420bb635d");
  }

  @Test public void clientCloseWithCodeAndReason() throws IOException {
    clientWriter.writeClose(1001, "Hello");
    assertData("888760b420bb635d68de0cd84f");
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
      assertEquals("Code must be in range [1000,5000): 98724976", e.getMessage());
    }
  }

  @Test public void closeReservedThrows() throws IOException {
    try {
      clientWriter.writeClose(1005, "Hello");
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Code 1005 is reserved and may not be used.", e.getMessage());
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
      serverWriter.writePing(new Buffer().write(binaryData(1000)));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Payload size must be less than or equal to 125", e.getMessage());
    }
  }

  @Test public void pongTooLongThrows() throws IOException {
    try {
      serverWriter.writePong(new Buffer().write(binaryData(1000)));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Payload size must be less than or equal to 125", e.getMessage());
    }
  }

  @Test public void closeTooLongThrows() throws IOException {
    try {
      String longString = ByteString.of(binaryData(75)).hex();
      serverWriter.writeClose(1000, longString);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Payload size must be less than or equal to 125", e.getMessage());
    }
  }

  @Test public void twoMessageSinksThrows() {
    clientWriter.newMessageSink(OPCODE_TEXT, -1);
    try {
      clientWriter.newMessageSink(OPCODE_TEXT, -1);
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

  private static byte[] binaryData(int length) {
    byte[] junk = new byte[length];
    new Random(0).nextBytes(junk);
    return junk;
  }
}
