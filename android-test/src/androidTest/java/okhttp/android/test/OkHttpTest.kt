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
package okhttp.android.test

import android.os.Build
import android.support.test.runner.AndroidJUnit4
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@RunWith(AndroidJUnit4::class)
class OkHttpTest {
  private lateinit var client: OkHttpClient

  @JvmField
  @Rule
  val server = MockWebServer()
  private val handshakeCertificates = localhost()

  @Before
  fun createClient() {
    client = OkHttpClient.Builder()
        .build()
  }

  @After
  fun cleanup() {
    client.dispatcher.executorService.shutdownNow()
  }

  @Test
  fun testRequest() {
    assumeNetwork()

    val request = Request.Builder().url("https://api.twitter.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testRequestUsesAndroidConscrypt() {
    assumeNetwork()

    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    var socketClass: String? = null

    val client2 = client.newBuilder()
        .eventListener(object : EventListener() {
          override fun connectionAcquired(call: Call, connection: Connection) {
            socketClass = connection.socket().javaClass.name
          }
        })
        .build()

    val response = client2.newCall(request).execute()

    response.use {
      assertEquals(Protocol.HTTP_2, response.protocol)
      if (Build.VERSION.SDK_INT >= 29) {
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      } else {
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }
      assertEquals(200, response.code)
      assertTrue(socketClass?.startsWith("com.android.org.conscrypt.") == true)
    }
  }

  @Test
  fun testHttpRequestNotBlockedOnLegacyAndroid() {
    assumeTrue(Build.VERSION.SDK_INT < 23)

    val request = Request.Builder().url("http://squareup.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testHttpRequestBlocked() {
    assumeTrue(Build.VERSION.SDK_INT >= 23)

    val request = Request.Builder().url("http://squareup.com/robots.txt").build()

    try {
      client.newCall(request).execute()
      fail("expected cleartext blocking")
    } catch (_: java.net.UnknownServiceException) {
    }
  }

  @Test
  fun testMockWebserverRequest() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testCertificatePinningFailure() {
    enableTls()

    val certificatePinner = CertificatePinner.Builder()
        .add(server.hostName, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()
    client = client.newBuilder().certificatePinner(certificatePinner).build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    try {
      client.newCall(request).execute()
      fail()
    } catch (_: SSLPeerUnverifiedException) {
    }
  }

  @Test
  fun testCertificatePinningSuccess() {
    enableTls()

    val certificatePinner = CertificatePinner.Builder()
        .add(server.hostName,
            CertificatePinner.pin(handshakeCertificates.trustManager.acceptedIssuers[0]))
        .build()
    client = client.newBuilder().certificatePinner(certificatePinner).build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  private fun enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      assumeNoException(uhe)
    }
  }
}
