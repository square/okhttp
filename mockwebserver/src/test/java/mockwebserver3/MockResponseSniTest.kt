/*
 * Copyright (C) 2022 Block, Inc.
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
package mockwebserver3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import okhttp3.Dns
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MockResponseSniTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  val platform = PlatformRule()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
  }

  @Test
  fun clientSendsServerNameAndServerReceivesIt() {
    // java.net.ConnectException: Connection refused
    platform.assumeNotConscrypt()

    val handshakeCertificates = localhost()
    server.useHttps(handshakeCertificates.sslSocketFactory())

    val dns =
      Dns {
        Dns.SYSTEM.lookup(server.hostName)
      }

    val client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .dns(dns)
        .build()

    server.enqueue(MockResponse())

    val url = server.url("/").newBuilder().host("localhost.localdomain").build()
    val call = client.newCall(Request(url = url))
    val response = call.execute()
    assertThat(response.isSuccessful).isTrue()

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.handshakeServerNames).containsExactly(url.host)
  }

  /**
   * Use different hostnames for the TLS handshake (including SNI) and the HTTP request (in the
   * Host header).
   */
  @Test
  fun domainFronting() {
    val heldCertificate =
      HeldCertificate.Builder()
        .commonName("server name")
        .addSubjectAlternativeName("url-host.com")
        .build()
    val handshakeCertificates =
      HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())

    val dns =
      Dns {
        Dns.SYSTEM.lookup(server.hostName)
      }

    val client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .dns(dns)
        .build()

    server.enqueue(MockResponse())

    val call =
      client.newCall(
        Request(
          url = "https://url-host.com:${server.port}/".toHttpUrl(),
          headers = headersOf("Host", "header-host"),
        ),
      )
    val response = call.execute()
    assertThat(response.isSuccessful).isTrue()

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.requestUrl!!.host).isEqualTo("header-host")
    assertThat(recordedRequest.handshakeServerNames).containsExactly("url-host.com")
  }

  /** No SNI for literal IPv6 addresses. */
  @Test
  fun ipv6() {
    val recordedRequest = requestToHostnameViaProxy("2607:f8b0:400b:804::200e")
    assertThat(recordedRequest.requestUrl!!.host).isEqualTo("2607:f8b0:400b:804::200e")
    assertThat(recordedRequest.handshakeServerNames).isEmpty()
  }

  /** No SNI for literal IPv4 addresses. */
  @Test
  fun ipv4() {
    val recordedRequest = requestToHostnameViaProxy("76.223.91.57")
    assertThat(recordedRequest.requestUrl!!.host).isEqualTo("76.223.91.57")
    assertThat(recordedRequest.handshakeServerNames).isEmpty()
  }

  @Test
  fun regularHostname() {
    val recordedRequest = requestToHostnameViaProxy("cash.app")
    assertThat(recordedRequest.requestUrl!!.host).isEqualTo("cash.app")
    assertThat(recordedRequest.handshakeServerNames).containsExactly("cash.app")
  }

  /**
   * Connect to [hostnameOrIpAddress] and return what was received. To fake an arbitrary hostname we
   * tell MockWebServer to act as a proxy.
   */
  private fun requestToHostnameViaProxy(hostnameOrIpAddress: String): RecordedRequest {
    val heldCertificate =
      HeldCertificate.Builder()
        .commonName("server name")
        .addSubjectAlternativeName(hostnameOrIpAddress)
        .build()
    val handshakeCertificates =
      HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())

    val client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .proxy(server.toProxyAddress())
        .build()

    server.enqueue(MockResponse(inTunnel = true))
    server.enqueue(MockResponse())

    val call =
      client.newCall(
        Request(
          url =
            server.url("/").newBuilder()
              .host(hostnameOrIpAddress)
              .build(),
        ),
      )
    val response = call.execute()
    assertThat(response.isSuccessful).isTrue()

    server.takeRequest() // Discard the CONNECT tunnel.
    return server.takeRequest()
  }
}
