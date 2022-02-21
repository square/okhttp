/*
 * Copyright (C) 2022 Square, Inc.
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

package okhttp3.internal.connection

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.Inet4Address
import java.net.Inet6Address
import org.junit.jupiter.api.Test

class InetAddressOrderTest {
  val ipv4_10_0_0_6 = Inet4Address.getByName("10.0.0.6")
  val ipv4_10_0_0_1 = Inet4Address.getByName("10.0.0.1")
  val ipv4_10_0_0_4 = Inet4Address.getByName("10.0.0.4")
  val ipv6_ab = Inet6Address.getByName("::ac")
  val ipv6_fc = Inet6Address.getByName("::fc")

  @Test fun prioritiseIpv6Example() {
    val result = reorderForHappyEyeballs(
      listOf(
        ipv4_10_0_0_6,
        ipv4_10_0_0_1,
        ipv4_10_0_0_4,
        ipv6_ab, ipv6_fc
      )
    )

    assertThat(result).isEqualTo(
      listOf(ipv6_ab, ipv4_10_0_0_6, ipv6_fc, ipv4_10_0_0_1, ipv4_10_0_0_4)
    )
  }

  @Test fun ipv6Only() {
    val result = reorderForHappyEyeballs(listOf(ipv6_ab, ipv6_fc))

    assertThat(result).isEqualTo(
      listOf(ipv6_ab, ipv6_fc)
    )
  }

  @Test fun ipv4Only() {
    val result = reorderForHappyEyeballs(
      listOf(
        ipv4_10_0_0_6,
        ipv4_10_0_0_1,
        ipv4_10_0_0_4
      )
    )

    assertThat(result).isEqualTo(
      listOf(ipv4_10_0_0_6, ipv4_10_0_0_1, ipv4_10_0_0_4)
    )
  }

  @Test fun singleIpv6() {
    val result = reorderForHappyEyeballs(listOf(ipv6_ab))

    assertThat(result).isEqualTo(
      listOf(ipv6_ab)
    )
  }

  @Test fun singleIpv4() {
    val result = reorderForHappyEyeballs(listOf(ipv4_10_0_0_6))

    assertThat(result).isEqualTo(
      listOf(ipv4_10_0_0_6)
    )
  }

  @Test fun prioritiseIpv6() {
    val result = reorderForHappyEyeballs(listOf(ipv4_10_0_0_6, ipv6_ab))

    assertThat(result).isEqualTo(
      listOf(ipv6_ab, ipv4_10_0_0_6)
    )
  }
}
