/*
 * Copyright (C) 2026 Square, Inc.
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
package okhttp3.containers

import assertk.assertThat
import assertk.assertions.isEqualTo
import javax.net.ssl.SSLHandshakeException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.NamedGroup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Verifies the [NamedGroup] / `supported_groups` plumbing end-to-end against a [MockWebServer]
 * restricted to a single post-quantum named group.
 *
 * This lives in `container-tests` rather than okhttp's own test suite on purpose: here OkHttp is
 * consumed as the published multi-release jar, so the `META-INF/versions/20` implementation of
 * `SSLParameters.setNamedGroups` is active. In okhttp's `jvmTest` the exploded classes are used and
 * the base no-op would run instead, hiding the behaviour under test.
 *
 * Actual post-quantum negotiation needs a PQC-capable TLS provider: native JSSE on JDK 27+
 * (JEP 527), or Conscrypt 2.6+. The tests skip otherwise.
 */
class PostQuantumMockWebServerTest {
  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  private val localhost: HeldCertificate =
    HeldCertificate
      .Builder()
      .addSubjectAlternativeName("localhost")
      .build()

  private val serverCertificates: HandshakeCertificates =
    HandshakeCertificates
      .Builder()
      .heldCertificate(localhost)
      .build()

  private val clientCertificates: HandshakeCertificates =
    HandshakeCertificates
      .Builder()
      .addTrustedCertificate(localhost.certificate)
      .build()

  @Test
  fun postQuantumGroupNegotiates() {
    assumeTrue(postQuantumSupported(), "client TLS provider lacks $POST_QUANTUM_GROUP")

    val server = pqcOnlyServer()
    server.use {
      val client =
        OkHttpClient
          .Builder()
          .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
          .connectionSpecs(listOf(connectionSpec(POST_QUANTUM_GROUP)))
          .build()

      val response = client.newCall(Request(server.url("/"))).execute()
      response.use {
        assertThat(response.body.string()).isEqualTo("pong")
      }
    }
  }

  @Test
  fun classicalOnlyClientCannotReachPostQuantumOnlyServer() {
    assumeTrue(postQuantumSupported(), "client TLS provider lacks $POST_QUANTUM_GROUP")

    val server = pqcOnlyServer()
    server.use {
      // This client only offers a classical group, so it shares no named group with the PQC-only
      // server: the handshake must fail. This proves the server really is restricted (and that the
      // positive test isn't passing by silently falling back to a classical group).
      val client =
        OkHttpClient
          .Builder()
          .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
          .connectionSpecs(listOf(connectionSpec(NamedGroup.X25519)))
          .build()

      assertThrows<SSLHandshakeException> {
        client.newCall(Request(server.url("/"))).execute()
      }
    }
  }

  private fun pqcOnlyServer(): MockWebServer =
    MockWebServer().apply {
      useHttps(serverCertificates.sslSocketFactory())
      namedGroups = listOf(POST_QUANTUM_GROUP)
      enqueue(MockResponse(body = "pong"))
      enqueue(MockResponse(body = "pong"))
      start()
    }

  private fun connectionSpec(vararg groups: NamedGroup): ConnectionSpec =
    ConnectionSpec
      .Builder(ConnectionSpec.RESTRICTED_TLS)
      .namedGroups(*groups)
      .build()

  /** Native JDK support landed in JDK 27 (JEP 527); Conscrypt 2.6+ supports it on older JDKs. */
  private fun postQuantumSupported(): Boolean =
    PlatformVersion.majorVersion >= 27 || (platform.isConscrypt() && conscryptSupportsPostQuantum())

  companion object {
    private val POST_QUANTUM_GROUP = NamedGroup.X25519MLKEM768

    /** Conscrypt added X25519MLKEM768 and SSLParameters.setNamedGroups in 2.6. */
    fun conscryptSupportsPostQuantum(): Boolean {
      val version = Conscrypt.version()
      return version.major() > 2 || (version.major() == 2 && version.minor() >= 6)
    }
  }
}
