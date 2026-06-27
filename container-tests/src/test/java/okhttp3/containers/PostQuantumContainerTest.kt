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
import assertk.assertions.contains
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.NamedGroup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Connects OkHttp to a TLS 1.3 server that only accepts the post-quantum named group
 * [NamedGroup.X25519MLKEM768], so a successful HTTPS response proves the group was negotiated.
 *
 * The server is `openssl s_server -www` restricted with `-groups X25519MLKEM768`; OpenSSL gained
 * native ML-KEM in 3.5. Certificate validation is bypassed because this test is about key exchange,
 * not authentication — the server uses a throwaway self-signed certificate generated on start-up.
 *
 * Like [PostQuantumMockWebServerTest], the client side needs a PQC-capable provider (JDK 27+ or
 * Conscrypt 2.6+) and OkHttp on the classpath as the multi-release jar; the test skips otherwise.
 * The container is started manually (rather than via `@Testcontainers`) so the skip happens before
 * any Docker work.
 */
class PostQuantumContainerTest {
  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  @Test
  fun negotiatesPostQuantumGroup() {
    assumeTrue(postQuantumSupported(), "client TLS provider lacks ${NamedGroup.X25519MLKEM768}")

    pqcOnlyServer().use { server ->
      server.start()

      val client =
        OkHttpClient
          .Builder()
          .sslSocketFactory(trustAllSocketFactory(), trustAllManager)
          .hostnameVerifier { _, _ -> true }
          .connectionSpecs(
            listOf(
              ConnectionSpec
                .Builder(ConnectionSpec.RESTRICTED_TLS)
                .namedGroups(NamedGroup.X25519MLKEM768)
                .build(),
            ),
          ).build()

      val url =
        HttpUrl
          .Builder()
          .scheme("https")
          .host(server.host)
          .port(server.getMappedPort(SERVER_PORT))
          .build()

      val response = client.newCall(Request(url)).execute()
      response.use {
        // `openssl s_server -www` echoes the negotiated parameters in its HTML status page.
        assertThat(response.body.string()).contains("Protocol")
      }
    }
  }

  private fun pqcOnlyServer(): GenericContainer<*> =
    GenericContainer<Nothing>(OPENSSL_IMAGE).apply {
      withExposedPorts(SERVER_PORT)
      withCommand(
        "sh",
        "-c",
        // Generate a short-lived self-signed cert, then serve a minimal HTTP page over a TLS 1.3
        // endpoint that only offers the post-quantum hybrid group.
        "openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost " +
          "-keyout /key.pem -out /cert.pem && " +
          "openssl s_server -accept $SERVER_PORT -cert /cert.pem -key /key.pem " +
          "-groups ${NamedGroup.X25519MLKEM768.javaName} -tls1_3 -www",
      )
      waitingFor(Wait.forListeningPort())
    }

  /** Native JDK support landed in JDK 27 (JEP 527); Conscrypt 2.6+ supports it on older JDKs. */
  private fun postQuantumSupported(): Boolean = PlatformVersion.majorVersion >= 27 || platform.isConscrypt()

  companion object {
    private const val SERVER_PORT = 4433

    /** Must provide OpenSSL >= 3.5, which ships native ML-KEM hybrid key exchange. */
    private val OPENSSL_IMAGE: DockerImageName = DockerImageName.parse("alpine:3.22")

    private val trustAllManager =
      object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }

    private fun trustAllSocketFactory() =
      SSLContext
        .getInstance("TLS")
        .apply { init(null, arrayOf(trustAllManager), SecureRandom()) }
        .socketFactory
  }
}
