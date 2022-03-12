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
package okhttp3.android

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.AsyncDns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.opentest4j.TestAbortedException

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@ExtendWith(MockWebServerExtension::class)
class AndroidDnsTest(val server: MockWebServer) {
  private val dns = AndroidDns()

  private val localhost: HandshakeCertificates by lazy {
    // Generate a self-signed cert for the server to serve and the client to trust.
    val heldCertificate = HeldCertificate.Builder()
      .commonName("localhost")
      .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
      .build()
    return@lazy HandshakeCertificates.Builder()
      .heldCertificate(heldCertificate)
      .addTrustedCertificate(heldCertificate.certificate)
      .build()
  }

  val client = OkHttpClient.Builder()
    .dns(dns.asDns())
    .sslSocketFactory(localhost.sslSocketFactory(), localhost.trustManager)
    .build()

  @BeforeEach
  fun init() {
    server.useHttps(localhost.sslSocketFactory(), false)
  }

  @Test
  fun testRequest() {
    server.enqueue(MockResponse())

    val call = client.newCall(Request.Builder().url(server.url("/")).build())

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  @Test
  fun testRequestInvalid() {
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
    val allAddresses = mutableListOf<InetAddress>()
    var exception: Exception? = null
    val latch = CountDownLatch(1)

    dns.query("localhost", object : AsyncDns.Callback {
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
    val allAddresses = mutableListOf<InetAddress>()
    var exception: Exception? = null
    val latch = CountDownLatch(1)

    dns.query("google.invalid", object : AsyncDns.Callback {
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

  @Test
  fun testRequestOnNetwork() {
    assumeNetwork()

    val context = InstrumentationRegistry.getInstrumentation().context
    val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val network =
      connectivityManager.activeNetwork ?: throw TestAbortedException("No active network")

    val client = OkHttpClient.Builder()
      .dns(AndroidDns(network = network, dnsClasses = listOf(AsyncDns.DnsClass.IPV4)).asDns())
      .socketFactory(network.socketFactory)
      .build()

    val call =
      client.newCall(Request.Builder().url("https://google.com/robots.txt").build())

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      throw TestAbortedException(uhe.message, uhe)
    }
  }
}
