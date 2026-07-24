/*
 * Copyright (c) 2026 OkHttp Authors
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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import java.net.InetAddress
import java.net.ProtocolException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okhttp3.internal.OkHttpInternalApi
import okio.Buffer
import okio.ByteString.Companion.decodeHex

class DnsMessageReaderWriterTest {
  @Test
  fun `ipv4 round trip`() {
    assertRoundTrip(
      DnsMessage(
        id = 65432.toShort(),
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "lysine.dev",
              type = TYPE_A,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 0,
              address = InetAddress.getByName("172.217.23.238"),
            ),
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 86_400,
              address = InetAddress.getByName("31.13.80.8"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `ipv6 round trip`() {
    assertRoundTrip(
      DnsMessage(
        id = 65432.toShort(),
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "lysine.dev",
              type = TYPE_AAAA,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:71"),
            ),
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 300,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8b"),
            ),
          ),
      ),
    )
  }

  /**
   * This also confirms that name compression accepts elements from [ResourceRecord.Https] records
   * and uses those records.
   */
  @Test
  fun `https round trip`() {
    assertRoundTrip(
      DnsMessage(
        id = 65432.toShort(),
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "lysine.dev",
              type = TYPE_HTTPS,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.Https(
              name = "lysine.dev",
              timeToLive = 60,
              priority = 2,
              targetName = "ca-west.cdn.lysine.dev",
              alpnIds = listOf("h2", "http/1.1"),
              port = 8443,
              ipAddressHints =
                listOf(
                  InetAddress.getByName("172.217.23.238"),
                  InetAddress.getByName("31.13.80.8"),
                  InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:71"),
                  InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8b"),
                ),
              echConfigList =
                """
                003dfe0d0039aa00200020a4a7bb34b77c43336c3a2931dd28c87d008218a99b44f1f0aa8a82537d487d
                43000400010001000a676f6f676c652e636f6d0000
                """.decodeHex(ignoreWhitespace = true),
            ),
            ResourceRecord.Https(
              name = "lysine.dev",
              timeToLive = 300,
              priority = 1,
              targetName = "ca-east.cdn.lysine.dev",
              alpnIds = listOf("h2"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `unbounded name compression`() {
    val buffer = Buffer()
    buffer.write(
      """
      000081800001000100000000066c7973696e65c00c000100010363646ec00c000100010000000000040a141e28
      """.decodeHex(ignoreWhitespace = true),
    )

    val reader = DnsMessageReader(buffer)
    val e =
      assertFailsWith<ProtocolException> {
        reader.read()
      }
    assertThat(e).hasMessage("malformed DNS message")
  }

  private fun assertRoundTrip(message: DnsMessage) {
    val buffer = Buffer()
    DnsMessageWriter(buffer).write(message)
    assertThat(DnsMessageReader(buffer).read()).isEqualTo(message)
  }
}
