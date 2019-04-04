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

import okhttp3.WebPlatformUrlTest
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.buffer
import okio.source
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException

internal class MessageInflaterTest {

  private val inflater = MessageInflater()

  @Test fun `inflates simple text`() {
    val inflated = inflater
        .inflate("f248cdc9c957c8cc4bcb492cc9cccf530400".decodeHexToBuffer())
        .readUtf8()

    assertThat(inflated).isEqualTo("Hello inflation!")
  }

  @Test fun `inflates text deflated with context takeover`() {
    val inflated = inflater
        .inflate("f248cdc9c957c8cc4bcb492cc9cccf530400".decodeHexToBuffer())
        .readUtf8()

    val inflated2 = inflater
        .inflate("f240e30300".decodeHexToBuffer())
        .readUtf8()

    assertThat(inflated).isEqualTo("Hello inflation!")
    assertThat(inflated2).isEqualTo("Hello inflation!")
  }

  @Test fun `inflates large data`() {
    val deflated32Kb = Buffer()
    // Random(0).nextBytes(32_000) deflated with MessageDeflater
    loadLargeData("web-socket-deflated-random-bytes").readAll(deflated32Kb)

    assertThat(deflated32Kb.snapshot().hex())
        .startsWith("000540fabf60b420bb38")
        .endsWith("65a3a9e9a5423ca96300")

    val inflated = inflater.inflate(deflated32Kb).hex()

    assertThat(inflated)
        .startsWith("60b420bb3851d9d47acb")
        .endsWith("ef65a3a9e9a5423ca963")
        .hasSize(64_000)
  }

  @Test fun `throws on empty buffer`() {
    try {
      inflater.inflate(Buffer())
      fail()
    } catch (e: EOFException) {
      assertThat(e.message).isEqualTo("source exhausted prematurely")
    }
  }

  @Test fun `throws on attempt to inflate with closed inflater`() {
    inflater.close()

    try {
      inflater.inflate("f240e30300".decodeHexToBuffer())
      fail()
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("closed")
    }
  }

  private fun Buffer.hex() = readByteString().hex()

  private fun String.decodeHexToBuffer(): Buffer = Buffer().write(decodeHex())

  private fun loadLargeData(name: String): BufferedSource = WebPlatformUrlTest::class.java
      .getResourceAsStream("/$name")
      .source()
      .buffer()
}
