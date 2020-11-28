/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.errors

import mockwebserver3.MockWebServer
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.errors.ErrorType.Companion.DNS_NAME_NOT_RESOLVED
import okhttp3.errors.ErrorType.Companion.TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

class ErrorHandlingInterceptorTest {
  @RegisterExtension @JvmField
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension @JvmField
  val platform = PlatformRule()

  private var client =
    clientTestRule.newClient()

  private val handshakeCertificates = TlsUtil.localhost()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setup(server: MockWebServer) {
    this.server = server
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .hostnameVerifier(RecordingHostnameVerifier())
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  @Test
  fun testDnsFailureSecure() {
    val url = "https://blah.invalid"

    try {
      makeRequest(url.toHttpUrl())
    } catch (e: UnknownHostException) {
      val errorDetails = e.errorDetails

      assertThat(errorDetails?.errorType).isEqualTo(DNS_NAME_NOT_RESOLVED)
      assertThat(errorDetails?.hostname).isEqualTo("blah.invalid")
    }
  }

  @Test
  fun testCertPinningFailure() {
    enableTls()

    client = client.newBuilder()
      .certificatePinner(
        CertificatePinner.Builder()
          .add(server.hostName, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
          .build()
      )
      .build()

    try {
      makeRequest(server.url("/"))
    } catch (e: SSLPeerUnverifiedException) {
      val errorDetails = e.errorDetails!!

      assertThat(errorDetails.errorType).isEqualTo(TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN)
      assertThat(errorDetails.hostname).isEqualTo(server.hostName)
      assertThat(errorDetails.matchingPins).containsExactly(
        CertificatePinner.Pin(server.hostName, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
      )
      assertThat(errorDetails.peerCertificates?.first()?.subjectAlternativeNames?.first()).isEqualTo(listOf(2, "localhost"))
    }
  }

  private fun makeRequest(url: HttpUrl): Response {
    return client.newCall(Request.Builder().url(url).build()).execute()
  }
}