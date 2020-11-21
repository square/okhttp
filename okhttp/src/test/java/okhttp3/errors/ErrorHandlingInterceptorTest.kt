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
import okhttp3.OkHttpClientTestRule
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.Response
import okhttp3.errors.ErrorType.Companion.DNS_NAME_NOT_RESOLVED
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.UnknownHostException

class ErrorHandlingInterceptorTest(
  private val server: MockWebServer
) {
  @RegisterExtension @JvmField
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension @JvmField
  val platform = PlatformRule()

  private var client =
    clientTestRule.newClientBuilder()
      .addInterceptor(ErrorHandlingInterceptor())
      .build()

  private val handshakeCertificates = TlsUtil.localhost()

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
      makeRequest(url)
    } catch (e: DnsNameNotResolvedException) {
      assertThat(e.primaryErrorType).isEqualTo(DNS_NAME_NOT_RESOLVED)
      assertThat(e.targetHostname).isEqualTo("blah.invalid")
      assertThat(e.cause is UnknownHostException).isTrue()
    }
  }

  private fun makeRequest(url: String): Response {
    return client.newCall(Request.Builder().url(url).build()).execute()
  }
}