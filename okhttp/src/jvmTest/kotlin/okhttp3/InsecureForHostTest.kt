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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import javax.net.ssl.SSLException
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class InsecureForHostTest {
  @RegisterExtension @JvmField
  val platform = PlatformRule()

  @RegisterExtension @JvmField
  val clientTestRule = OkHttpClientTestRule()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setup(server: MockWebServer) {
    this.server = server

    // BCX509ExtendedTrustManager not supported in TlsUtil.newTrustManager
    platform.assumeNotBouncyCastle()
  }

  @Test fun `untrusted host in insecureHosts connects successfully`() {
    val serverCertificates = platform.localhostHandshakeCertificates()
    server.useHttps(serverCertificates.sslSocketFactory())
    server.enqueue(MockResponse())

    val clientCertificates =
      HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .addInsecureHost(server.hostName)
        .build()

    val client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.handshake!!.cipherSuite).isNotNull()
    assertThat(response.handshake!!.tlsVersion).isNotNull()
    assertThat(response.handshake!!.localCertificates).isEmpty()
    assertThat(response.handshake!!.localPrincipal).isNull()
    assertThat(response.handshake!!.peerCertificates).isEmpty()
    assertThat(response.handshake!!.peerPrincipal).isNull()
  }

  @Test fun `bad certificates host in insecureHosts fails with SSLException`() {
    val heldCertificate =
      HeldCertificate.Builder()
        .addSubjectAlternativeName("example.com")
        .build()
    val serverCertificates =
      HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .build()
    server.useHttps(serverCertificates.sslSocketFactory())
    server.enqueue(MockResponse())

    val clientCertificates =
      HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .addInsecureHost(server.hostName)
        .build()

    val client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    val call = client.newCall(Request(server.url("/")))
    assertFailsWith<SSLException> {
      call.execute()
    }
  }

  @Test fun `untrusted host not in insecureHosts fails with SSLException`() {
    val serverCertificates = platform.localhostHandshakeCertificates()
    server.useHttps(serverCertificates.sslSocketFactory())
    server.enqueue(MockResponse())

    val clientCertificates =
      HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .addInsecureHost("${server.hostName}2")
        .build()

    val client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    val call = client.newCall(Request(server.url("/")))
    assertFailsWith<SSLException> {
      call.execute()
    }
  }
}
