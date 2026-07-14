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
package okhttp3.dnsoverhttps

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.InetAddress
import kotlin.test.Test
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

class DnsMessageReaderTest {
  @Test
  fun `resource record with compressed suffix`() {
    val reader =
      DnsMessageReader(
        "00008180000100040000000006676f6f676c6503636f6d00001c0001c00c001c00010000003c00102607f8b040" +
          "2318070000000000000071c00c001c00010000003c00102607f8b0402318070000000000000065c00c001c00" +
          "010000003c00102607f8b040231807000000000000008ac00c001c00010000003c00102607f8b04023180700" +
          "0000000000008b",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessageReader.DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            DnsMessageReader.Question(
              name = "google.com",
              type = 28,
              `class` = 1,
            ),
          ),
        answers =
          listOf(
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:71").address.toByteString(),
            ),
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:65").address.toByteString(),
            ),
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8a").address.toByteString(),
            ),
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8b").address.toByteString(),
            ),
          ),
      ),
    )
  }

  @Test
  fun `resource record with not compressed suffix`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000106676f6f676c6503636f6d0000010001c00c000100010000028e0004acd917ee00" +
          "002904d000000000000d000c0009585858585858585858",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessageReader.DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            DnsMessageReader.Question(
              name = "google.com",
              type = 1,
              `class` = 1,
            ),
          ),
        answers =
          listOf(
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 654,
              address = InetAddress.getByName("172.217.23.238").address.toByteString(),
            ),
          ),
      ),
    )
  }

  @Test
  fun `ignore cname`() {
    val reader =
      DnsMessageReader(
        "0000818000010002000000000567726170680866616365626f6f6b03636f6d0000010001c00c0005000100000a" +
          "0f000c04737461720463313072c012c030000100010000002e00041f0d5008",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessageReader.DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            DnsMessageReader.Question(
              name = "graph.facebook.com",
              type = 1,
              `class` = 1,
            ),
          ),
        answers =
          listOf(
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "star.c10r.facebook.com",
              timeToLive = 46,
              address = InetAddress.getByName("31.13.80.8").address.toByteString(),
            ),
          ),
      ),
    )
  }

  @Test
  fun `ignore soa`() {
    val reader =
      DnsMessageReader(
        "0000818300010000000100000e7364666c6b686673646c6b6a646602656500001c0001c01b00060001000004ff" +
          "0038026e7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e6574c01b6a554d2d0000" +
          "0e10000003840012750000000e10",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessageReader.DnsMessage(
        id = 0,
        flags = -32381,
        questions =
          listOf(
            DnsMessageReader.Question(
              name = "sdflkhfsdlkjdf.ee",
              type = 28,
              `class` = 1,
            ),
          ),
        answers =
          listOf(),
      ),
    )
  }

  /** https://www.rfc-editor.org/info/rfc2671/ */
  @Test
  fun `ignore opt`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000106676f6f676c6503636f6d0000010001c00c000100010000028e0004acd917ee00" +
          "002904d000000000000d000c0009585858585858585858",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessageReader.DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            DnsMessageReader.Question(
              name = "google.com",
              type = 1,
              `class` = 1,
            ),
          ),
        answers =
          listOf(
            DnsMessageReader.ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 654,
              address = InetAddress.getByName("172.217.23.238").address.toByteString(),
            ),
          ),
      ),
    )
  }

  private fun DnsMessageReader(hex: String) = DnsMessageReader(Buffer().write(hex.decodeHex()))
}
