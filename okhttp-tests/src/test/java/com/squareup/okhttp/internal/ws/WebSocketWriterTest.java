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
import java.util.Arrays;
import java.util.Random;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.After;
import org.junit.Test;

import static com.squareup.okhttp.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.WebSocket.PayloadType.TEXT;
import static com.squareup.okhttp.internal.ws.Protocol.toggleMask;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class WebSocketWriterTest {
  private final Buffer data = new Buffer();
  private final Random random = new Random(0);

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  private final WebSocketWriter serverWriter = new WebSocketWriter(false, data, random);
  private final WebSocketWriter clientWriter = new WebSocketWriter(true, data, random);

  @After public void tearDown() throws IOException {
    assertEquals("Data not empty", "", data.readByteString().hex());
  }

  @Test public void serverSendSimpleHello() throws IOException {
    Buffer payload = new Buffer().writeUtf8("Hello");
    serverWriter.sendMessage(TEXT, payload);
    assertData("810548656c6c6f");
  }

  @Test public void clientSendSimpleHello() throws IOException {
    Buffer payload = new Buffer().writeUtf8("Hello");
    clientWriter.sendMessage(TEXT, payload);
    assertData("818560b420bb28d14cd70f");
  }

  @Test public void serverStreamSimpleHello() throws IOException {
    BufferedSink sink = serverWriter.newMessageSink(TEXT);

    sink.writeUtf8("Hel").flush();
    assertData("010348656c");

    sink.writeUtf8("lo").flush();
    assertData("00026c6f");

    sink.close();
    assertData("8000");
  }

  @Test public void serverStreamCloseFlushes() throws IOException {
    BufferedSink sink = serverWriter.newMessageSink(TEXT);

    sink.writeUtf8("Hel").flush();
    assertData("010348656c");

    sink.writeUtf8("lo").close();
    assertData("00026c6f");
    assertData("8000");
  }

  @Test public void clientStreamSimpleHello() throws IOException {
    BufferedSink sink = clientWriter.newMessageSink(TEXT);

    sink.writeUtf8("Hel").flush();
    assertData("018360b420bb28d14c");

    sink.writeUtf8("lo").flush();
    assertData("00823851d9d4543e");

    sink.close();
    assertData("80807acb933d");
  }

  @Test public void serverSendBinary() throws IOException {
    byte[] payload = binaryData(100);
    serverWriter.sendMessage(BINARY, new Buffer().write(payload));
    assertData("8264");
    assertData(payload);
  }

  @Test public void serverSendBinaryShort() throws IOException {
    byte[] payload = binaryData(1000);
    serverWriter.sendMessage(BINARY, new Buffer().write(payload));
    assertData("827e03e8");
    assertData(payload);
  }

  @Test public void serverSendBinaryLong() throws IOException {
    byte[] payload = binaryData(65537);
    serverWriter.sendMessage(BINARY, new Buffer().write(payload));
    assertData("827f0000000000010001");
    assertData(payload);
  }

  @Test public void clientSendBinary() throws IOException {
    byte[] payload = binaryData(100);
    clientWriter.sendMessage(BINARY, new Buffer().write(payload));
    assertData("82e4");

    byte[] maskKey = new byte[4];
    random.setSeed(0); // Reset the seed so we can mask the payload.
    random.nextBytes(maskKey);
    toggleMask(payload, payload.length, maskKey, 0);

    assertData(maskKey);
    assertData(payload);
  }

  @Test public void serverStreamBinary() throws IOException {
    byte[] payload = binaryData(100);
    BufferedSink sink = serverWriter.newMessageSink(BINARY);

    sink.write(payload, 0, 50).flush();
    assertData("0232");
    assertData(Arrays.copyOfRange(payload, 0, 50));

    sink.write(payload, 50, 50).flush();
    assertData("0032");
    assertData(Arrays.copyOfRange(payload, 50, 100));

    sink.close();
    assertData("8000");
  }

  @Test public void clientStreamBinary() throws IOException {
    byte[] maskKey1 = new byte[4];
    random.nextBytes(maskKey1);
    byte[] maskKey2 = new byte[4];
    random.nextBytes(maskKey2);
    byte[] maskKey3 = new byte[4];
    random.nextBytes(maskKey3);

    random.setSeed(0); // Reset the seed so real data matches.

    byte[] payload = binaryData(100);
    BufferedSink sink = clientWriter.newMessageSink(BINARY);

    sink.write(payload, 0, 50).flush();
    byte[] part1 = Arrays.copyOfRange(payload, 0, 50);
    toggleMask(part1, 50, maskKey1, 0);
    assertData("02b2");
    assertData(maskKey1);
    assertData(part1);

    sink.write(payload, 50, 50).flush();
    byte[] part2 = Arrays.copyOfRange(payload, 50, 100);
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
    try {
      clientWriter.writeClose(0, "Hello");
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Code required to include reason.", e.getMessage());
    }
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

  @Test public void controlFrameTooLongThrows() throws IOException {
    try {
      serverWriter.writePing(new Buffer().write(binaryData(1000)));
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Control frame payload must be less than 125B.", e.getMessage());
    }
  }

  @Test public void twoWritersThrows() {
    clientWriter.newMessageSink(TEXT);
    try {
      clientWriter.newMessageSink(TEXT);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Another message writer is active. Did you call close()?", e.getMessage());
    }
  }

  @Test public void writeWhileWriterThrows() throws IOException {
    clientWriter.newMessageSink(TEXT);
    try {
      clientWriter.sendMessage(TEXT, new Buffer());
      fail();
    } catch (IllegalStateException e) {
      assertEquals("A message writer is active. Did you call close()?", e.getMessage());
    }
  }

  private void assertData(String hex) throws EOFException {
    ByteString expected = ByteString.decodeHex(hex);
    ByteString actual = this.data.readByteString(expected.size());
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
