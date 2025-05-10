/*
 * Copyright (C) 2018 Square, Inc.
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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertFailsWith
import okhttp3.AsyncDns.Companion.TYPE_A
import okhttp3.AsyncDns.Companion.TYPE_AAAA
import okhttp3.dnsoverhttps.DnsRecordCodec.decodeAnswers
import okio.ByteString.Companion.decodeHex
import org.junit.jupiter.api.Test

class DnsRecordCodecTest {
  @Test
  fun testGoogleDotComEncoding() {
    val encoded = encodeQuery("google.com", TYPE_A)
    assertThat(encoded).isEqualTo("AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ")
  }

  private fun encodeQuery(
    host: String,
    type: Int,
  ): String = DnsRecordCodec.encodeQuery(host, type).base64Url().replace("=", "")

  @Test
  fun testGoogleDotComEncodingWithIPv6() {
    val encoded = encodeQuery("google.com", TYPE_AAAA)
    assertThat(encoded).isEqualTo("AAABAAABAAAAAAAABmdvb2dsZQNjb20AABwAAQ")
  }

  @Test
  fun testGoogleDotComDecodingFromCloudflare() {
    val encoded =
      decodeAnswers(
        hostname = "test.com",
        byteString =
          (
            "00008180000100010000000006676f6f676c6503636f6d0000010001c00c0001000100000043" +
              "0004d83ad54e"
          ).decodeHex(),
      )
    assertThat(encoded).containsExactly(InetAddress.getByName("216.58.213.78"))
  }

  @Test
  fun testGoogleDotComDecodingFromGoogle() {
    val decoded =
      decodeAnswers(
        hostname = "test.com",
        byteString =
          (
            "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c" +
              "0005000100000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c0420001" +
              "00010000003b00049df00112"
          ).decodeHex(),
      )
    assertThat(decoded).containsExactly(InetAddress.getByName("157.240.1.18"))
  }

  @Test
  fun testGoogleDotComDecodingFromGoogleIPv6() {
    val decoded =
      decodeAnswers(
        hostname = "test.com",
        byteString =
          (
            "0000818000010003000000000567726170680866616365626f6f6b03636f6d00001c0001c00c" +
              "0005000100000a1b000603617069c012c0300005000100000b1f000c04737461720463313072c012c042001c" +
              "00010000003b00102a032880f0290011faceb00c00000002"
          ).decodeHex(),
      )
    assertThat(decoded)
      .containsExactly(InetAddress.getByName("2a03:2880:f029:11:face:b00c:0:2"))
  }

  @Test
  fun testGoogleDotComDecodingNxdomainFailure() {
    assertFailsWith<UnknownHostException> {
      decodeAnswers(
        hostname = "sdflkhfsdlkjdf.ee",
        byteString =
          (
            "0000818300010000000100000e7364666c6b686673646c6b6a64660265650000010001c01b" +
              "00060001000007070038026e7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e65" +
              "74c01b5adb12c100000e10000003840012750000000e10"
          ).decodeHex(),
      )
    }.also { expected ->
      assertThat(expected.message).isEqualTo("sdflkhfsdlkjdf.ee: NXDOMAIN")
    }
  }
}
