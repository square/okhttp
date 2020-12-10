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
package okhttp3.tls

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.testing.Flaky
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.InetAddress

@ExtendWith(MockWebServerExtension::class)
class ClientAuthTest {
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule()

  // TODO remove Flaky
  @Test @Flaky
  fun testRoundtrip(mockWebServer: MockWebServer) {
    mockWebServer.enqueue(MockResponse().setResponseCode(200))

    val serverHeldCertificate = HeldCertificate.Builder()
      .commonName("localhost")
      .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
      .build()

    val clientHeldCertificate = HeldCertificate.Builder()
      .commonName("localclient")
      .addSubjectAlternativeName("localclient")
      .build()

    val serverCerts = HandshakeCertificates.Builder()
      .heldCertificate(serverHeldCertificate)
      .addTrustedCertificate(clientHeldCertificate.certificate)
      .addTrustedCertificate(serverHeldCertificate.certificate)
      .build()

    val clientCerts = HandshakeCertificates.Builder()
      .addTrustedCertificate(serverHeldCertificate.certificate)
      .addClientAuthCertificate(mockWebServer.hostName, clientHeldCertificate)
      .build()

    mockWebServer.useHttps(serverCerts.sslSocketFactory())
    mockWebServer.requireClientAuth()

    val client = clientTestRule.newClientBuilder()
      .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
      .build()

    val result = client.newCall(Request.Builder().url(mockWebServer.url("/")).build()).execute()
    result.use {
      assertThat(it.code).isEqualTo(200)
      println(it.code)
    }
  }
}