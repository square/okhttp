/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
package okhttp.android.test

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.android.AndroidDns
import okhttp3.AsyncDns
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okio.IOException
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@ExtendWith(MockWebServerExtension::class)
class AsyncDnsTest(val server: MockWebServer) {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @RegisterExtension public val clientTestRule = OkHttpClientTestRule()

  @Test
  fun testRequest() {
    server.enqueue(MockResponse())

    val dns = AndroidDns()

    val client = clientTestRule.newClientBuilder().dns(dns.asDns()).build()

    val call = client.newCall(Request.Builder().url(server.url("/")).build())

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  @Test
  fun testRequestInvalid() {
    val dns = AndroidDns()

    val client = clientTestRule.newClientBuilder().dns(dns.asDns()).build()

    val call = client.newCall(Request.Builder().url("https://google.invalid/").build())

    try {
      call.execute()
      fail("Request can't succeed")
    } catch (ioe: IOException) {
      assertThat(ioe).hasMessage("No results for google.invalid")
    }
  }

  @Test
  fun testDnsRequest() {
    val dns = AndroidDns()

    val allAddresses = mutableListOf<InetAddress>()
    var exception: Exception? = null
    val latch = CountDownLatch(1)

    dns.query("localhost", object: AsyncDns.Callback {
      override fun onAddressResults(dnsClass: AsyncDns.DnsClass, addresses: List<InetAddress>) {
        allAddresses.addAll(addresses)
      }

      override fun onComplete() {
        latch.countDown()
      }

      override fun onError(dnsClass: AsyncDns.DnsClass, e: IOException) {
        exception = e
        latch.countDown()
      }
    })

    latch.await()

    assertThat(exception).isNull()
    assertThat(allAddresses).isNotEmpty
  }

  @Test
  fun testDnsRequestInvalid() {
    val dns = AndroidDns()

    val allAddresses = mutableListOf<InetAddress>()
    var exception: Exception? = null
    val latch = CountDownLatch(1)

    dns.query("google.invalid", object: AsyncDns.Callback {
      override fun onAddressResults(dnsClass: AsyncDns.DnsClass, addresses: List<InetAddress>) {
        allAddresses.addAll(addresses)
      }

      override fun onComplete() {
        latch.countDown()
      }

      override fun onError(dnsClass: AsyncDns.DnsClass, e: IOException) {
        exception = e
        latch.countDown()
      }
    })

    latch.await()

    assertThat(exception).isNull()
    assertThat(allAddresses).isEmpty()
  }
}
