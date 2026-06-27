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
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
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
 * argument (see the `android-containers` workflow); the test skips when it is absent, so it is inert
 * in the regular `connectedCheck` matrix.
 *
 * Android's system Conscrypt has no PQC, so this installs the bundled `conscrypt-android` (2.6+),
 * which does. A successful HTTPS response against a server restricted to [NamedGroup.X25519MLKEM768]
 * proves the group was negotiated.
 *
 * Note: applying [ConnectionSpec.Builder.namedGroups] on Android needs the Android implementation of
 * `applyNamedGroups` (tracked separately). Until that lands the spec setting is a no-op on device, so
 * this test relies on the provider's default group preference; once it lands the configuration is
 * honored explicitly.
 */
class PostQuantumAndroidTest {
  private val serverUrl: String? =
    InstrumentationRegistry.getArguments().getString("pqcServerUrl")

  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setup() {
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext<Context>()
    Security.insertProviderAt(Conscrypt.newProviderBuilder().build(), 1)
  }

  @AfterEach
  fun tearDown() {
    Security.removeProvider("Conscrypt")
    if (::client.isInitialized) {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  @Test
  fun negotiatesPostQuantumGroup() {
    assumeTrue(serverUrl != null, "pqcServerUrl not set; skipping (needs the PQC server container)")
    assumeTrue(conscryptSupportsPostQuantum(), "bundled Conscrypt < 2.6 has no post-quantum key exchange")

    // Conscrypt is installed above; cert validation is bypassed because this is about key exchange,
    // not authentication (the container uses a throwaway self-signed certificate).
    val sslContext =
      SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager), SecureRandom())
      }

    client =
      OkHttpClient
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

    val response = client.newCall(Request(serverUrl!!.toHttpUrl())).execute()
    response.use {
      assertThat(response.code).isEqualTo(200)
      // `openssl s_server -www` echoes the negotiated parameters in its HTML status page.
      assertThat(response.body.string()).contains("Protocol")
    }
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
