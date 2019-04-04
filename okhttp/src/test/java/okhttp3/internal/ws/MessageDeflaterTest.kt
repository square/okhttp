/*
 * Copyright (C) 2019 Square, Inc.
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

import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

internal class MessageDeflaterTest {

  private val random = Random(0)

  @Test fun `deflate simple text`() {
    val deflater = MessageDeflater(false)

    val deflated = deflater.deflate("Hello deflate!".encodeUtf8())

    assertThat(deflated.hex()).isEqualTo("f248cdc9c95748494dcb492c49550400")
  }

  @Test fun `deflate large data`() {
    val deflater = MessageDeflater(false)

    val length = 100_000
    val data = binaryData(length).toByteString()
    val deflated = deflater.deflate(data).hex()

    assertThat(deflated)
        .startsWith("000540fabf60b420bb38")
        .endsWith("1cd272e9b4abf069d000")
        .hasSize(200072)
  }

  @Test fun `deflate simple text without context takeover is same`() {
    val deflater = MessageDeflater(false)

    val source = "Hello deflate!".encodeUtf8()

    val deflated = deflater.deflate(source).hex()
    val deflated2 = deflater.deflate(source).hex()

    assertThat(deflated).isEqualTo(deflated2)
  }

  @Test fun `deflate simple text with context takeover is shorter`() {
    val deflater = MessageDeflater(true)

    val source = "Hello deflate!".encodeUtf8()

    val deflated = deflater.deflate(source).hex()
    val deflated2 = deflater.deflate(source).hex()

    assertThat(deflated.length).isGreaterThan(deflated2.length)
  }

  @Test fun `any deflated data ends with 00 without context takeover`() {
    val deflater = MessageDeflater(false)

    verifyDeflatedDataEndsWith00(deflater)
  }

  @Test fun `any deflated data ends with 00 with context takeover`() {
    val deflater = MessageDeflater(true)

    verifyDeflatedDataEndsWith00(deflater)
  }

  @Test fun `throws on attempt to deflate with closed defalter`() {
    val deflater = MessageDeflater(true)

    deflater.close()

    try {
      deflater.deflate("Hello deflate!".encodeUtf8())
      fail()
    } catch (e: NullPointerException) {
      // NPE is a strange decision, but that's what java.util.zip.Deflater throws
      assertThat(e.message).isEqualTo("Deflater has been closed")
    }
  }

  private fun verifyDeflatedDataEndsWith00(deflater: MessageDeflater) {
    val length = 10
    val samples = 10000

    repeat(samples) {
      val data = binaryData(length).toByteString()
      val deflated = deflater.deflate(data).hex()

      assertThat(deflated)
          .describedAs("Deflated binary data '${data.hex()}'")
          .endsWith("00")
    }
  }

  private fun binaryData(length: Int): ByteArray = ByteArray(length).apply(random::nextBytes)

  private fun Buffer.hex() = readByteString().hex()
}
