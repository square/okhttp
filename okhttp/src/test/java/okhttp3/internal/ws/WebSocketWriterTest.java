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
import okhttp3.internal.Util;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

import static okhttp3.TestUtil.repeat;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT_MAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public final class WebSocketWriterTest {
  private final Buffer data = new Buffer();
  private final Random random = new Random(0);

  /**
   * Check all data as verified inside of the test. We do this in an AfterEachCallback so that
   * exceptions thrown from the test do not cause this check to fail.
   */
  @RegisterExtension public final AfterEachCallback noDataLeftBehind = context -> {
    if (context.getExecutionException().isPresent()) return;
    assertThat(data.readByteString().hex())
        .overridingErrorMessage("Data not empty")
        .isEqualTo("");
  };

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  private final WebSocketWriter serverWriter = new WebSocketWriter(
      false, data, random, false, false, 0L);
  private final WebSocketWriter clientWriter = new WebSocketWriter(
      true, data, random, false, false, 0L);

  @Test public void serverTextMessage() throws IOException {
    serverWriter.writeMessageFrame(OPCODE_TEXT, ByteString.encodeUtf8("Hello"));
    assertData("810548656c6c6f");
  }

  @Test public void serverCompressedTextMessage() throws IOException {
    WebSocketWriter serverWriter = new WebSocketWriter(
        false, data, random, true, false, 0L);
    serverWriter.writeMessageFrame(OPCODE_TEXT, ByteString.encodeUtf8("Hello"));
    assertData("c107f248cdc9c90700");
  }

  @Test public void serverSmallBufferedPayloadWrittenAsOneFrame() throws IOException {
    int length = 5;
    ByteString payload = ByteString.of(binaryData(length));
    serverWriter.writeMessageFrame(OPCODE_TEXT, payload);

    assertData("8105");
    assertData(payload);
  }

  @Test public void serverLargeBufferedPayloadWrittenAsOneFrame() throws IOException {
    int length = 12345;
    ByteString payload = ByteString.of(binaryData(length));
    serverWriter.writeMessageFrame(OPCODE_TEXT, payload);

    assertData("817e");
    assertData(Util.format("%04x", length));
    assertData(payload);
  }

  @Test public void clientTextMessage() throws IOException {
    clientWriter.writeMessageFrame(OPCODE_TEXT, ByteString.encodeUtf8("Hello"));

    assertData("818560b420bb28d14cd70f");
  }

  @Test public void clientCompressedTextMessage() throws IOException {
    WebSocketWriter clientWriter = new WebSocketWriter(
        false, data, random, true, false, 0L);
    clientWriter.writeMessageFrame(OPCODE_TEXT, ByteString.encodeUtf8("Hello"));
    assertData("c107f248cdc9c90700");
  }

  @Test public void serverBinaryMessage() throws IOException {
    ByteString payload = ByteString.decodeHex(""
        + "60b420bb3851d9d47acb933dbe70399bf6c92da33af01d4fb7"
        + "70e98c0325f41d3ebaf8986da712c82bcd4d554bf0b54023c2");

    serverWriter.writeMessageFrame(OPCODE_BINARY, payload);

    assertData("8232");
    assertData(payload);
  }

  @Test public void serverMessageLengthShort() throws IOException {
    // Create a payload which will overflow the normal payload byte size.
    Buffer payload = new Buffer();
    while (payload.completeSegmentByteCount() <= PAYLOAD_BYTE_MAX) {
      payload.writeByte('0');
    }

    serverWriter.writeMessageFrame(OPCODE_BINARY, payload.snapshot());

    // Write directly to the unbuffered sink. This ensures it will become single frame.
    assertData("827e"); // 'e' == 4-byte follow-up length.
    assertData(Util.format("%04X", payload.completeSegmentByteCount()));
    assertData(payload.readByteString());
  }

  @Test public void serverMessageLengthLong() throws IOException {
    // Create a payload which will overflow the normal and short payload byte size.
    Buffer payload = new Buffer();
    while (payload.completeSegmentByteCount() <= PAYLOAD_SHORT_MAX) {
      payload.writeByte('0');
    }

    serverWriter.writeMessageFrame(OPCODE_BINARY, payload.snapshot());

    // Write directly to the unbuffered sink. This ensures it will become single frame.
    assertData("827f"); // 'f' == 16-byte follow-up length.
    assertData(Util.format("%016X", payload.size()));
    assertData(payload.readByteString());
  }

  @Test public void clientBinary() throws IOException {
    ByteString payload = ByteString.decodeHex(""
        + "60b420bb3851d9d47acb933dbe70399bf6c92da33af01d4fb7"
        + "70e98c0325f41d3ebaf8986da712c82bcd4d554bf0b54023c2");

    clientWriter.writeMessageFrame(OPCODE_BINARY, payload);

    assertData("82b2");
    assertData("60b420bb");
    assertData(""
        + "0000000058e5f96f1a7fb386dec41920967d0d185a443df4d7"
        + "c4c9376391d4a65e0ed8230d1332734b796dee2b4495fb4376");
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
    serverWriter.writeClose(1001, ByteString.encodeUtf8("Hello"));
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
    clientWriter.writeClose(1001, ByteString.encodeUtf8("Hello"));
    assertData("888760b420bb635d68de0cd84f");
  }

  @Test public void closeWithOnlyReasonThrows() throws IOException {
    clientWriter.writeClose(0, ByteString.encodeUtf8("Hello"));
    assertData("888760b420bb60b468de0cd84f");
  }

  @Test public void closeCodeOutOfRangeThrows() throws IOException {
    try {
      clientWriter.writeClose(98724976, ByteString.encodeUtf8("Hello"));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo("Code must be in range [1000,5000): 98724976");
    }
  }

  @Test public void closeReservedThrows() throws IOException {
    try {
      clientWriter.writeClose(1005, ByteString.encodeUtf8("Hello"));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo("Code 1005 is reserved and may not be used.");
    }
  }

  @Test public void serverEmptyPing() throws IOException {
    serverWriter.writePing(ByteString.EMPTY);
    assertData("8900");
  }

  @Test public void clientEmptyPing() throws IOException {
    clientWriter.writePing(ByteString.EMPTY);
    assertData("898060b420bb");
  }

  @Test public void serverPingWithPayload() throws IOException {
    serverWriter.writePing(ByteString.encodeUtf8("Hello"));
    assertData("890548656c6c6f");
  }

  @Test public void clientPingWithPayload() throws IOException {
    clientWriter.writePing(ByteString.encodeUtf8("Hello"));
    assertData("898560b420bb28d14cd70f");
  }

  @Test public void serverEmptyPong() throws IOException {
    serverWriter.writePong(ByteString.EMPTY);
    assertData("8a00");
  }

  @Test public void clientEmptyPong() throws IOException {
    clientWriter.writePong(ByteString.EMPTY);
    assertData("8a8060b420bb");
  }

  @Test public void serverPongWithPayload() throws IOException {
    serverWriter.writePong(ByteString.encodeUtf8("Hello"));
    assertData("8a0548656c6c6f");
  }

  @Test public void clientPongWithPayload() throws IOException {
    clientWriter.writePong(ByteString.encodeUtf8("Hello"));
    assertData("8a8560b420bb28d14cd70f");
  }

  @Test public void pingTooLongThrows() throws IOException {
    try {
      serverWriter.writePing(ByteString.of(binaryData(1000)));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Payload size must be less than or equal to 125");
    }
  }

  @Test public void pongTooLongThrows() throws IOException {
    try {
      serverWriter.writePong(ByteString.of(binaryData(1000)));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Payload size must be less than or equal to 125");
    }
  }

  @Test public void closeTooLongThrows() throws IOException {
    try {
      ByteString longReason = ByteString.encodeUtf8(repeat('X', 124));
      serverWriter.writeClose(1000, longReason);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Payload size must be less than or equal to 125");
    }
  }

  private void assertData(String hex) throws EOFException {
    assertData(ByteString.decodeHex(hex));
  }

  private void assertData(ByteString expected) throws EOFException {
    ByteString actual = data.readByteString(Math.min(expected.size(), data.size()));
    assertThat(actual).isEqualTo(expected);
  }

  private static byte[] binaryData(int length) {
    byte[] junk = new byte[length];
    new Random(0).nextBytes(junk);
    return junk;
  }
}
