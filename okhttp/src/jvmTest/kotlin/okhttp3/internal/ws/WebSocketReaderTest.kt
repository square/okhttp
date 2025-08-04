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
package okhttp3.internal.ws

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.matches
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.util.Random
import kotlin.test.assertFailsWith
import okhttp3.internal.format
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.EMPTY
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class WebSocketReaderTest {
  private val data = Buffer()
  private val callback = WebSocketRecorder("client")
  private val random = Random(0)

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  private val serverReader =
    WebSocketReader(
      isClient = false,
      source = data,
      frameCallback = callback.asFrameCallback(),
      perMessageDeflate = false,
      noContextTakeover = false,
    )
  private val serverReaderWithCompression =
    WebSocketReader(
      isClient = false,
      source = data,
      frameCallback = callback.asFrameCallback(),
      perMessageDeflate = true,
      noContextTakeover = false,
    )
  private val clientReader =
    WebSocketReader(
      isClient = true,
      source = data,
      frameCallback = callback.asFrameCallback(),
      perMessageDeflate = false,
      noContextTakeover = false,
    )
  private val clientReaderWithCompression =
    WebSocketReader(
      isClient = true,
      source = data,
      frameCallback = callback.asFrameCallback(),
      perMessageDeflate = true,
      noContextTakeover = false,
    )

  @AfterEach fun tearDown() {
    callback.assertExhausted()
  }

  @Test fun controlFramesMustBeFinal() {
    data.write("0a00".decodeHex()) // Empty pong.
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Control frames must be final.")
    }
  }

  @Test fun reservedFlag1IsUnsupportedWithNoCompression() {
    data.write("ca00".decodeHex()) // Empty pong, flag 1 set.
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Unexpected rsv1 flag")
    }
  }

  @Test fun reservedFlag1IsUnsupportedForControlFrames() {
    data.write("ca00".decodeHex()) // Empty pong, flag 1 set.
    assertFailsWith<ProtocolException> {
      clientReaderWithCompression.processNextFrame()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Unexpected rsv1 flag")
    }
  }

  @Test fun reservedFlag1IsUnsupportedForContinuationFrames() {
    data.write("c000".decodeHex()) // Empty continuation, flag 1 set.
    assertFailsWith<ProtocolException> {
      clientReaderWithCompression.processNextFrame()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Unexpected rsv1 flag")
    }
  }

  @Test fun reservedFlags2and3AreUnsupported() {
    data.write("aa00".decodeHex()) // Empty pong, flag 2 set.
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Unexpected rsv2 flag")
    }
    data.clear()
    data.write("9a00".decodeHex()) // Empty pong, flag 3 set.
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Unexpected rsv3 flag")
    }
  }

  @Test fun clientSentFramesMustBeMasked() {
    data.write("8100".decodeHex())
    assertFailsWith<ProtocolException> {
      serverReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Client-sent frames must be masked.")
    }
  }

  @Test fun serverSentFramesMustNotBeMasked() {
    data.write("8180".decodeHex())
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Server-sent frames must not be masked.")
    }
  }

  @Test fun controlFramePayloadMax() {
    data.write("8a7e007e".decodeHex())
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Control frame must be less than 125B.")
    }
  }

  @Test fun clientSimpleHello() {
    data.write("810548656c6c6f".decodeHex()) // Hello
    clientReader.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientWithCompressionSimpleUncompressedHello() {
    data.write("810548656c6c6f".decodeHex()) // Hello
    clientReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientWithCompressionSimpleCompressedHello() {
    data.write("c107f248cdc9c90700".decodeHex()) // Hello
    clientReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun serverSimpleHello() {
    data.write("818537fa213d7f9f4d5158".decodeHex()) // Hello
    serverReader.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun serverWithCompressionSimpleUncompressedHello() {
    data.write("818537fa213d7f9f4d5158".decodeHex()) // Hello
    serverReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun serverWithCompressionSimpleCompressedHello() {
    data.write("c18760b420bb92fced72a9b320".decodeHex()) // Hello
    serverReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientFramePayloadShort() {
    data.write("817E000548656c6c6f".decodeHex()) // Hello
    clientReader.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientFramePayloadLong() {
    data.write("817f000000000000000548656c6c6f".decodeHex()) // Hello
    clientReader.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientFramePayloadTooLongThrows() {
    data.write("817f8000000000000000".decodeHex())
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "Frame length 0x8000000000000000 > 0x7FFFFFFFFFFFFFFF",
      )
    }
  }

  @Test fun serverHelloTwoChunks() {
    data.write("818537fa213d7f9f4d".decodeHex()) // Hel
    data.write("5158".decodeHex()) // lo
    serverReader.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun serverWithCompressionHelloTwoChunks() {
    data.write("818537fa213d7f9f4d".decodeHex()) // Hel
    data.write("5158".decodeHex()) // lo
    serverReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun serverWithCompressionCompressedHelloTwoChunks() {
    data.write("418460b420bb92fced72".decodeHex()) // first 4 bytes of compressed 'Hello'
    data.write("80833851d9d4f156d9".decodeHex()) // last 3 bytes of compressed 'Hello'
    serverReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientTwoFrameHello() {
    data.write("010348656c".decodeHex()) // Hel
    data.write("80026c6f".decodeHex()) // lo
    clientReader.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientWithCompressionTwoFrameHello() {
    data.write("010348656c".decodeHex()) // Hel
    data.write("80026c6f".decodeHex()) // lo
    clientReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientWithCompressionTwoFrameCompressedHello() {
    data.write("4104f248cdc9".decodeHex()) // first 4 bytes of compressed 'Hello'
    data.write("8003c90700".decodeHex()) // last 3 bytes of compressed 'Hello'
    clientReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
  }

  @Test fun clientTwoFrameHelloWithPongs() {
    data.write("010348656c".decodeHex()) // Hel
    data.write("8a00".decodeHex()) // Pong
    data.write("8a00".decodeHex()) // Pong
    data.write("8a00".decodeHex()) // Pong
    data.write("8a00".decodeHex()) // Pong
    data.write("80026c6f".decodeHex()) // lo
    clientReader.processNextFrame()
    callback.assertPong(EMPTY)
    callback.assertPong(EMPTY)
    callback.assertPong(EMPTY)
    callback.assertPong(EMPTY)
    callback.assertTextMessage("Hello")
  }

  @Test fun clientTwoFrameCompressedHelloWithPongs() {
    data.write("4104f248cdc9".decodeHex()) // first 4 bytes of compressed 'Hello'
    data.write("8a00".decodeHex()) // Pong
    data.write("8a00".decodeHex()) // Pong
    data.write("8a00".decodeHex()) // Pong
    data.write("8a00".decodeHex()) // Pong
    data.write("8003c90700".decodeHex()) // last 3 bytes of compressed 'Hello'
    clientReaderWithCompression.processNextFrame()
    callback.assertPong(EMPTY)
    callback.assertPong(EMPTY)
    callback.assertPong(EMPTY)
    callback.assertPong(EMPTY)
    callback.assertTextMessage("Hello")
  }

  @Test fun clientIncompleteMessageBodyThrows() {
    data.write("810548656c".decodeHex()) // Length = 5, "Hel"
    assertFailsWith<EOFException> {
      clientReader.processNextFrame()
    }
  }

  @Test fun clientUncompressedMessageWithCompressedFlagThrows() {
    data.write("c10548656c6c6f".decodeHex()) // Uncompressed 'Hello', flag 1 set
    assertFailsWith<IOException> {
      clientReaderWithCompression.processNextFrame()
    }
  }

  @Test fun clientIncompleteControlFrameBodyThrows() {
    data.write("8a0548656c".decodeHex()) // Length = 5, "Hel"
    assertFailsWith<EOFException> {
      clientReader.processNextFrame()
    }
  }

  @Test fun serverIncompleteMessageBodyThrows() {
    data.write("818537fa213d7f9f4d".decodeHex()) // Length = 5, "Hel"
    assertFailsWith<EOFException> {
      serverReader.processNextFrame()
    }
  }

  @Test fun serverIncompleteControlFrameBodyThrows() {
    data.write("8a8537fa213d7f9f4d".decodeHex()) // Length = 5, "Hel"
    assertFailsWith<EOFException> {
      serverReader.processNextFrame()
    }
  }

  @Test fun clientSimpleBinary() {
    val bytes = binaryData(256)
    data.write("827E0100".decodeHex()).write(bytes)
    clientReader.processNextFrame()
    callback.assertBinaryMessage(bytes)
  }

  @Test fun clientTwoFrameBinary() {
    val bytes = binaryData(200)
    data.write("0264".decodeHex()).write(bytes, 0, 100)
    data.write("8064".decodeHex()).write(bytes, 100, 100)
    clientReader.processNextFrame()
    callback.assertBinaryMessage(bytes)
  }

  @Test fun twoFrameNotContinuation() {
    val bytes = binaryData(200)
    data.write("0264".decodeHex()).write(bytes, 0, 100)
    data.write("8264".decodeHex()).write(bytes, 100, 100)
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Expected continuation opcode. Got: 2")
    }
  }

  @Test fun emptyPingCallsCallback() {
    data.write("8900".decodeHex()) // Empty ping
    clientReader.processNextFrame()
    callback.assertPing(EMPTY)
  }

  @Test fun pingCallsCallback() {
    data.write("890548656c6c6f".decodeHex()) // Ping with "Hello"
    clientReader.processNextFrame()
    callback.assertPing("Hello".encodeUtf8())
  }

  @Test fun emptyCloseCallsCallback() {
    data.write("8800".decodeHex()) // Empty close
    clientReader.processNextFrame()
    callback.assertClosing(1005, "")
  }

  @Test fun closeLengthOfOneThrows() {
    data.write("880100".decodeHex()) // Close with invalid 1-byte payload
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Malformed close payload length of 1.")
    }
  }

  @Test fun closeCallsCallback() {
    data.write("880703e848656c6c6f".decodeHex()) // Close with code and reason
    clientReader.processNextFrame()
    callback.assertClosing(1000, "Hello")
  }

  @Test fun closeIncompleteCallsCallback() {
    data.write("880703e948656c6c6f".decodeHex()) // Close with code and reason
    data.close()
    clientReader.processNextFrame()
    callback.assertClosing(1001, "Hello")
  }

  @Test fun closeOutOfRangeThrows() {
    data.write("88020001".decodeHex()) // Close with code 1
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Code must be in range [1000,5000): 1")
    }
    data.write("88021388".decodeHex()) // Close with code 5000
    assertFailsWith<ProtocolException> {
      clientReader.processNextFrame()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Code must be in range [1000,5000): 5000")
    }
  }

  @Test fun closeReservedSetThrows() {
    data.write("880203ec".decodeHex()) // Close with code 1004
    data.write("880203ed".decodeHex()) // Close with code 1005
    data.write("880203ee".decodeHex()) // Close with code 1006
    for (i in 1015..2999) {
      data.write(("8802" + format("%04X", i)).decodeHex()) // Close with code 'i'
    }
    var count = 0
    while (!data.exhausted()) {
      assertFailsWith<ProtocolException> {
        clientReader.processNextFrame()
      }.also { expected ->
        assertThat(expected.message!!)
          .matches(Regex("Code \\d+ is reserved and may not be used."))
      }
      count++
    }
    assertThat(count).isEqualTo(1988)
  }

  @Test fun clientWithCompressionCannotBeUsedAfterClose() {
    data.write("c107f248cdc9c90700".decodeHex()) // Hello
    clientReaderWithCompression.processNextFrame()
    callback.assertTextMessage("Hello")
    data.write("c107f248cdc9c90700".decodeHex()) // Hello
    clientReaderWithCompression.close()
    assertFailsWith<Exception> {
      clientReaderWithCompression.processNextFrame()
    }.also { expected ->
      assertThat(expected.message!!).contains("closed")
    }
  }

  private fun binaryData(length: Int): ByteString {
    val junk = ByteArray(length)
    random.nextBytes(junk)
    return junk.toByteString()
  }
}
