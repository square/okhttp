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
package okhttp3.internal.platform

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertFailsWith
import okhttp3.ech.EchConfig
import okio.ByteString
import org.junit.Test

class AndroidDnsResolverDnsTest {
  private val address = InetAddress.getByName("192.0.2.1")

  @Test
  fun lookupReturnsAddressesAndCachesEchConfig() {
    val echConfig = FakeEchConfig
    val dns =
      AndroidDnsResolverDns(
        FakeDnsLookup(
          "example.com" to AndroidDnsResult(listOf(address), echConfig),
        ),
      )

    assertThat(dns.lookup("example.com")).isEqualTo(listOf(address))
    assertThat(dns.getEchConfig("example.com")).isEqualTo(echConfig)
    assertThat(dns.getEchConfig("other.example")).isNull()
  }

  @Test
  fun lookupWithoutEchConfigClearsStaleEchConfig() {
    val echConfig = FakeEchConfig
    val lookup =
      FakeDnsLookup(
        "example.com" to AndroidDnsResult(listOf(address), echConfig),
      )
    val dns = AndroidDnsResolverDns(lookup)

    dns.lookup("example.com")
    assertThat(dns.getEchConfig("example.com")).isEqualTo(echConfig)

    lookup["example.com"] = AndroidDnsResult(listOf(address), null)
    dns.lookup("example.com")
    assertThat(dns.getEchConfig("example.com")).isNull()
  }

  @Test
  fun lookupPropagatesUnknownHostException() {
    val dns = AndroidDnsResolverDns(FakeDnsLookup())

    assertFailsWith<UnknownHostException> {
      dns.lookup("missing.example")
    }
  }

  private class FakeDnsLookup(
    vararg responses: Pair<String, AndroidDnsResult>,
  ) : AndroidDnsLookup {
    private val responses = responses.toMap().toMutableMap()

    operator fun set(
      hostname: String,
      result: AndroidDnsResult,
    ) {
      responses[hostname] = result
    }

    override fun lookup(hostname: String): AndroidDnsResult = responses[hostname] ?: throw UnknownHostException(hostname)
  }

  private object FakeEchConfig : EchConfig {
    override val config: ByteString = ByteString.EMPTY
  }
}
