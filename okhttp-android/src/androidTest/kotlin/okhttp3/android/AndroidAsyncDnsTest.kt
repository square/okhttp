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
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.fail
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.AsyncDns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.IOException
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Run with "./gradlew :android-test:connectedCheck -PandroidBuild=true" and make sure ANDROID_SDK_ROOT is set.
 */
class AndroidAsyncDnsTest {
  @JvmField @Rule
  val serverRule = MockWebServerRule()
  private lateinit var client: OkHttpClient

  private val localhost: HandshakeCertificates by lazy {
    // Generate a self-signed cert for the server to serve and the client to trust.
    val heldCertificate =
      HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .build()
    return@lazy HandshakeCertificates.Builder()
      .addPlatformTrustedCertificates()
      .heldCertificate(heldCertificate)
      .addTrustedCertificate(heldCertificate.certificate)
      .build()
  }

  @Before
  fun init() {
    assumeTrue("Supported on API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

    client =
      OkHttpClient.Builder()
        .dns(AsyncDns.toDns(AndroidAsyncDns.IPv4, AndroidAsyncDns.IPv6))
        .sslSocketFactory(localhost.sslSocketFactory(), localhost.trustManager)
        .build()

    serverRule.server.useHttps(localhost.sslSocketFactory())
  }

  @Test
  @Ignore("java.net.UnknownHostException: No results for localhost, in CI.")
  fun testRequest() {
    serverRule.server.enqueue(MockResponse())

    val call = client.newCall(Request(serverRule.server.url("/")))

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  @Test
  fun testRequestExternal() {
    assumeNetwork()

    val call = client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  @Test
  fun testRequestInvalid() {
    val call = client.newCall(Request("https://google.invalid/".toHttpUrl()))

    try {
      call.execute()
      fail("Request can't succeed")
    } catch (ioe: IOException) {
      assertThat(ioe).hasMessage("No results for google.invalid")
    }
  }

  @Test
  @Ignore("No results on CI for localhost")
  fun testDnsRequest() {
    val (allAddresses, exception) = dnsQuery("localhost")

    assertThat(exception).isNull()
    assertThat(allAddresses).isNotEmpty()
  }

  private fun dnsQuery(hostname: String): Pair<List<InetAddress>, Exception?> {
    val allAddresses = mutableListOf<InetAddress>()
    var exception: Exception? = null
    val latch = CountDownLatch(1)

    // assumes an IPv4 address
    AndroidAsyncDns.IPv4.query(
      hostname,
      object : AsyncDns.Callback {
        override fun onResponse(
          hostname: String,
          addresses: List<InetAddress>,
        ) {
          allAddresses.addAll(addresses)
          latch.countDown()
        }

        override fun onFailure(
          hostname: String,
          e: IOException,
        ) {
          exception = e
          latch.countDown()
        }
      },
    )

    latch.await()

    return Pair(allAddresses, exception)
  }

  @Test
  fun testDnsRequestExternal() {
    assumeNetwork()

    val (allAddresses, exception) = dnsQuery("google.com")

    assertThat(exception).isNull()
    assertThat(allAddresses).isNotEmpty()
  }

  @Test
  fun testDnsRequestInvalid() {
    val (allAddresses, exception) = dnsQuery("google.invalid")

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
      connectivityManager.activeNetwork ?: throw AssumptionViolatedException("No active network")

    val client =
      OkHttpClient.Builder()
        .dns(AsyncDns.toDns(AndroidAsyncDns.IPv4, AndroidAsyncDns.IPv6))
        .socketFactory(network.socketFactory)
        .build()

    val call =
      client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

    call.execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      throw AssumptionViolatedException(uhe.message, uhe)
    }
  }
}
