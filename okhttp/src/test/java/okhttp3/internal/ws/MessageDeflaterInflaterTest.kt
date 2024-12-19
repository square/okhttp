/*
 * Copyright (C) 2020 Square, Inc.
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
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import java.io.EOFException
import kotlin.test.assertFailsWith
import okhttp3.TestUtil.fragmentBuffer
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Test

internal class MessageDeflaterInflaterTest {
  @Test
  fun `inflate golden value`() {
    val inflater = MessageInflater(false)
    val message = "f248cdc9c957c8cc4bcb492cc9cccf530400".decodeHex()
    assertThat(inflater.inflate(message)).isEqualTo("Hello inflation!".encodeUtf8())
  }

  /**
   * We had a bug where self-finishing inflater streams would infinite loop!
   * https://github.com/square/okhttp/issues/8078
   */
  @Test
  fun `inflate returns finished before bytesRead reaches input length`() {
    val inflater = MessageInflater(false)
    val message = "53621260020000".decodeHex()
    assertThat(inflater.inflate(message)).isEqualTo("22021002".decodeHex())
  }

  @Test
  fun `deflate golden value`() {
    val deflater = MessageDeflater(false)
    val deflated = deflater.deflate("Hello deflate!".encodeUtf8())
    assertThat(deflated.hex()).isEqualTo("f248cdc9c95748494dcb492c49550400")
  }

  @Test
  fun `inflate deflate`() {
    val deflater = MessageDeflater(false)
    val inflater = MessageInflater(false)

    val goldenValue = "Hello deflate!".repeat(100).encodeUtf8()

    val deflated = deflater.deflate(goldenValue)
    assertThat(deflated.size).isLessThan(goldenValue.size)
    val inflated = inflater.inflate(deflated)

    assertThat(inflated).isEqualTo(goldenValue)
  }

  @Test
  fun `inflate deflate empty message`() {
    val deflater = MessageDeflater(false)
    val inflater = MessageInflater(false)

    val goldenValue = "".encodeUtf8()

    val deflated = deflater.deflate(goldenValue)
    assertThat(deflated).isEqualTo("00".decodeHex())
    val inflated = inflater.inflate(deflated)

    assertThat(inflated).isEqualTo(goldenValue)
  }

  @Test
  fun `inflate deflate with context takeover`() {
    val deflater = MessageDeflater(false)
    val inflater = MessageInflater(false)

    val goldenValue1 = "Hello deflate!".repeat(100).encodeUtf8()
    val deflatedValue1 = deflater.deflate(goldenValue1)
    assertThat(inflater.inflate(deflatedValue1)).isEqualTo(goldenValue1)

    val goldenValue2 = "Hello deflate?".repeat(100).encodeUtf8()
    val deflatedValue2 = deflater.deflate(goldenValue2)
    assertThat(inflater.inflate(deflatedValue2)).isEqualTo(goldenValue2)

    assertThat(deflatedValue2.size).isLessThan(deflatedValue1.size)
  }

  @Test
  fun `inflate deflate with no context takeover`() {
    val deflater = MessageDeflater(true)
    val inflater = MessageInflater(true)

    val goldenValue1 = "Hello deflate!".repeat(100).encodeUtf8()
    val deflatedValue1 = deflater.deflate(goldenValue1)
    assertThat(inflater.inflate(deflatedValue1)).isEqualTo(goldenValue1)

    val goldenValue2 = "Hello deflate!".repeat(100).encodeUtf8()
    val deflatedValue2 = deflater.deflate(goldenValue2)
    assertThat(inflater.inflate(deflatedValue2)).isEqualTo(goldenValue2)

    assertThat(deflatedValue2).isEqualTo(deflatedValue1)
  }

  @Test
  fun `deflate after close`() {
    val deflater = MessageDeflater(true)
    deflater.close()

    assertFailsWith<Exception> {
      deflater.deflate("Hello deflate!".encodeUtf8())
    }
  }

  @Test
  fun `inflate after close`() {
    val inflater = MessageInflater(false)

    inflater.close()

    assertFailsWith<Exception> {
      inflater.inflate("f240e30300".decodeHex())
    }
  }

  @Test
  fun `per message compression left with deflated bytes`() {
    // per message compression
    val inflater = MessageInflater(noContextTakeover = true)
    val message =
      FileSystem.RESOURCES.read("/okhttp3/internal/ws/inflater_notworking.txt".toPath()) {
        readUtf8().trim().decodeHex()
      }
    val buffer = fragmentBuffer(Buffer().write(message))
    val thrown = assertFailsWith<IOException> {
      inflater.inflate(buffer)
    }
    assertThat(thrown).hasMessage("additional bytes (5) after inflation")
  }

  @Test
  fun `per message compression left with deflated bytes working message`() {
    // per message compression
    val inflater = MessageInflater(noContextTakeover = true)
    val message =
      FileSystem.RESOURCES.read("/okhttp3/internal/ws/inflater_working.txt".toPath()) {
        readUtf8().trim().decodeHex()
      }
    val buffer = fragmentBuffer(Buffer().write(message))
    inflater.inflate(buffer)
    assertThat(buffer.size).isGreaterThan(0)

    val bufferAgain = fragmentBuffer(Buffer().write(message))
    inflater.inflate(bufferAgain)
    assertThat(bufferAgain.size).isGreaterThan(0)
  }

  /**
   * Test for an [EOFException] caused by mishandling of fragmented buffers in web socket
   * compression. https://github.com/square/okhttp/issues/5965
   */
  @Test
  fun `inflate golden value in buffer that has been fragmented`() {
    val inflater = MessageInflater(false)
    val buffer = fragmentBuffer(Buffer().write("f248cdc9c957c8cc4bcb492cc9cccf530400".decodeHex()))
    inflater.inflate(buffer)
    assertThat(buffer.readUtf8()).isEqualTo("Hello inflation!")
  }

  private fun MessageDeflater.deflate(byteString: ByteString): ByteString {
    val buffer = Buffer()
    buffer.write(byteString)
    deflate(buffer)
    return buffer.readByteString()
  }

  private fun MessageInflater.inflate(byteString: ByteString): ByteString {
    val buffer = Buffer()
    buffer.write(byteString)
    inflate(buffer)
    return buffer.readByteString()
  }
}
