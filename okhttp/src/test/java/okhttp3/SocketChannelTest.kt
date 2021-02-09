/*
 * Copyright (C) 2021 Square, Inc.
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

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Protocol.HTTP_1_1
import okhttp3.Protocol.HTTP_2
import okhttp3.Provider.CONSCRYPT
import okhttp3.Provider.JSSE
import okhttp3.TlsExtensionMode.DISABLED
import okhttp3.TlsExtensionMode.STANDARD
import okhttp3.TlsVersion.TLS_1_2
import okhttp3.TlsVersion.TLS_1_3
import okhttp3.internal.platform.ConscryptPlatform
import okhttp3.internal.platform.Platform
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import org.assertj.core.api.Assertions.assertThat
import org.conscrypt.Conscrypt
import org.junit.After
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Timeout(4)
@Flaky
class SocketChannelTest(
  val server: MockWebServer
) {
  @JvmField @RegisterExtension val platform = PlatformRule()
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = true
    // recordSslDebug = true
  }

  @After
  fun cleanPlatform() {
    Security.removeProvider("Conscrypt")
    platform.resetPlatform()
  }

  private val handshakeCertificates = TlsUtil.localhost()

  @ParameterizedTest
  @MethodSource("connectionTypes")
  fun testConnection(socketMode: SocketMode) {
    // https://github.com/square/okhttp/pull/6554
    assumeFalse(
      socketMode is TlsInstance &&
        socketMode.socketMode == Channel &&
        socketMode.protocol == HTTP_2 &&
        socketMode.tlsExtensionMode == STANDARD,
      "failing for channel and h2"
    )

    if (socketMode is TlsInstance && socketMode.provider == CONSCRYPT) {
      Security.insertProviderAt(Conscrypt.newProvider(), 1)
      Platform.resetForTests(ConscryptPlatform.buildIfSupported()!!)
    }

    val client = clientTestRule.newClientBuilder()
      .callTimeout(2, TimeUnit.SECONDS)
      .apply {
        if (socketMode is TlsInstance) {
          if (socketMode.socketMode == Channel) {
            socketFactory(ChannelSocketFactory())
          }

          connectionSpecs(
            listOf(
              ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(socketMode.tlsVersion)
                .supportsTlsExtensions(socketMode.tlsExtensionMode == TlsExtensionMode.STANDARD)
                .build()
            )
          )

          sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
          )

          when (socketMode.protocol) {
            HTTP_2 -> protocols(listOf(HTTP_2, HTTP_1_1))
            HTTP_1_1 -> protocols(listOf(HTTP_1_1))
            else -> TODO()
          }

          server.useHttps(handshakeCertificates.sslSocketFactory(), false)
        } else if (socketMode == Channel) {
          socketFactory(ChannelSocketFactory())
        }
      }
      .build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
      .url(server.url("/"))
      .build()

    val promise = CompletableFuture<Response>()

    val call = client.newCall(request)
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        promise.completeExceptionally(e)
      }

      override fun onResponse(call: Call, response: Response) {
        promise.complete(response)
      }
    })

    val response = promise.get(3, TimeUnit.SECONDS)

    assertThat(response.body!!.string()).isEqualTo("abc")

    if (socketMode is TlsInstance) {
      assertThat(response.handshake!!.tlsVersion).isEqualTo(socketMode.tlsVersion)

      if (socketMode.tlsExtensionMode == TlsExtensionMode.STANDARD) {
        assertThat(response.protocol).isEqualTo(socketMode.protocol)
      } else {
        assertThat(response.protocol).isEqualTo(HTTP_1_1)
      }
    }
  }

  companion object {
    @Suppress("unused") @JvmStatic
    fun connectionTypes(): List<SocketMode> =
      listOf(CONSCRYPT, JSSE).flatMap { provider ->
        listOf(HTTP_1_1, HTTP_2).flatMap { protocol ->
          listOf(TLS_1_2, TLS_1_3).flatMap { tlsVersion ->
            listOf(Channel, Standard).flatMap { socketMode ->
              listOf(DISABLED, TlsExtensionMode.STANDARD).map { tlsExtensionMode ->
                TlsInstance(provider, protocol, tlsVersion, socketMode, tlsExtensionMode)
              }
            }
          }
        }
      } + Channel + Standard
  }
}

sealed class SocketMode

object Channel : SocketMode() {
  override fun toString(): String = "Channel"
}

object Standard : SocketMode() {
  override fun toString(): String = "Standard"
}

data class TlsInstance(
  val provider: Provider,
  val protocol: Protocol,
  val tlsVersion: TlsVersion,
  val socketMode: SocketMode,
  val tlsExtensionMode: TlsExtensionMode
) : SocketMode() {
  override fun toString(): String = "$provider/$protocol/$tlsVersion/$socketMode/$tlsExtensionMode"
}

enum class Provider {
  JSSE,
  CONSCRYPT
}

enum class TlsExtensionMode {
  DISABLED,
  STANDARD
}