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
package okhttp.android.test

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.NamedGroup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.platform.PlatformRegistry
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises post-quantum named-group key exchange on a real Android device/emulator.
 *
 * Unlike the JVM container tests, the server runs as a Docker container on the *host*: the emulator
 * reaches it at `10.0.2.2`. The PQC-only server URL is passed in as the `pqcServerUrl` instrumentation
 * argument (see the `android-containers` workflow); the tests skip when it is absent, so they are inert
 * in the regular `connectedCheck` matrix.
 *
 * The PQC-only server only accepts [NamedGroup.X25519MLKEM768], so a successful HTTPS response proves
 * the group was negotiated. Two providers are probed:
 *
 *  * [bundledConscryptNegotiatesPostQuantumGroup] installs the app-bundled `conscrypt-android` (2.6+),
 *    which supports PQC, and is expected to pass wherever that Conscrypt is present.
 *  * [systemProviderDoesNotYetNegotiatePostQuantumGroup] uses the device's *system* TLS stack with no
 *    bundled provider. It is a canary: we expect the platform NOT to negotiate PQC yet, so the
 *    handshake against the PQC-only server should fail. It runs only on API 37+ and **fails loudly if
 *    the handshake unexpectedly succeeds** — that means the system stack gained PQC support and
 *    OkHttp's Android handling (and this expectation) should be updated.
 *
 * Note: applying [ConnectionSpec.Builder.namedGroups] on Android needs the Android implementation of
 * `applyNamedGroups` (tracked separately). Until that lands the spec setting is a no-op on device, so
 * these tests measure the provider's default group preference rather than an enforced restriction.
 */
class PostQuantumAndroidTest {
  private val serverUrl: String? =
    InstrumentationRegistry.getArguments().getString("pqcServerUrl")

  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setup() {
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext<Context>()
  }

  @AfterEach
  fun tearDown() {
    // Harmless if it was never installed.
    Security.removeProvider("Conscrypt")
    if (::client.isInitialized) {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  @Test
  fun bundledConscryptNegotiatesPostQuantumGroup() {
    assumeTrue(serverUrl != null, "pqcServerUrl not set; skipping (needs the PQC server container)")
    assumeTrue(conscryptSupportsPostQuantum(), "bundled Conscrypt < 2.6 has no post-quantum key exchange")

    Security.insertProviderAt(Conscrypt.newProviderBuilder().build(), 1)

    client = newPostQuantumClient()
    client.newCall(Request(serverUrl!!.toHttpUrl())).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      // `openssl s_server -www` echoes the negotiated parameters in its HTML status page.
      assertThat(response.body.string()).contains("Protocol")
    }
  }

  @Test
  fun systemProviderDoesNotYetNegotiatePostQuantumGroup() {
    assumeTrue(serverUrl != null, "pqcServerUrl not set; skipping (needs the PQC server container)")
    // Probe the *system* TLS stack only where PQC might plausibly exist (API 37+). No bundled provider
    // is installed.
    assumeTrue(Build.VERSION.SDK_INT >= 37, "only probing the system provider on API 37+")

    client = newPostQuantumClient()

    // We expect the system stack NOT to negotiate X25519MLKEM768 yet, so the handshake against the
    // PQC-only server should fail. Any failure (handshake/IO) is the expected "no PQC" outcome.
    val negotiated =
      runCatching {
        client.newCall(Request(serverUrl!!.toHttpUrl())).execute().use { it.isSuccessful }
      }.getOrDefault(false)

    assertThat(
      negotiated,
      "the API 37 system TLS stack negotiated ${NamedGroup.X25519MLKEM768} — platform PQC has " +
        "landed; update OkHttp's Android named-group handling and remove this expectation",
    ).isFalse()
  }

  /**
   * Builds a client that requires the post-quantum group, using whatever provider is currently highest
   * priority. Cert validation is bypassed because this is about key exchange, not authentication (the
   * container uses a throwaway self-signed certificate).
   */
  private fun newPostQuantumClient(): OkHttpClient {
    val sslContext =
      SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager), SecureRandom())
      }

    return OkHttpClient
      .Builder()
      .sslSocketFactory(sslContext.socketFactory, trustAllManager)
      .hostnameVerifier { _, _ -> true }
      .connectionSpecs(
        listOf(
          ConnectionSpec
            .Builder(ConnectionSpec.RESTRICTED_TLS)
            .namedGroups(NamedGroup.X25519MLKEM768)
            .build(),
        ),
      ).build()
  }

  companion object {
    /** Conscrypt added X25519MLKEM768 and SSLParameters.setNamedGroups in 2.6. */
    private fun conscryptSupportsPostQuantum(): Boolean {
      val version = Conscrypt.version()
      return version.major() > 2 || (version.major() == 2 && version.minor() >= 6)
    }

    private val trustAllManager =
      object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }
  }
}
