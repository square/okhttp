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
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import javax.net.SocketFactory

@Timeout(4)
class SocketChannelTest(
  val server: MockWebServer
) {
  @JvmField @RegisterExtension val platform = PlatformRule()
  @JvmField @RegisterExtension val clientTestRule = OkHttpClientTestRule().apply {
    recordFrames = true
    recordSslDebug = true
  }

  private val handshakeCertificates = TlsUtil.localhost()

  @ParameterizedTest
  @EnumSource(SocketMode::class)
  fun plaintext(channel: SocketMode) {
    val client = clientTestRule.newClientBuilder()
      .apply {
        if (channel == SocketMode.CHANNEL) {
          socketFactory(ChannelSocketFactory())
        }
      }
      .build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder()
      .url(server.url("/"))
      .build()

    val call = client.newCall(request)
    val response1 = call.execute()

    assertThat("abc").isEqualTo(response1.body!!.string())
  }

  @ParameterizedTest
  @MethodSource("connectionTypes")
  fun testConnection(protocolParam : Protocol, tlsParam: TlsVersion, channelParam: SocketMode, tlsExtensions: TlsExtensionMode) {
    if (channelParam == SocketMode.CHANNEL && protocolParam == Protocol.HTTP_2 && tlsExtensions == TlsExtensionMode.STANDARD) {
      assumeFalse(true, "failing for channel and h2")
    }

    val client = clientTestRule.newClientBuilder()
      .callTimeout(2, TimeUnit.SECONDS)
      .apply {
        if (channelParam == SocketMode.CHANNEL) {
          socketFactory(ChannelSocketFactory())
        }

        connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
          .tlsVersions(tlsParam)
          .supportsTlsExtensions(tlsExtensions == TlsExtensionMode.STANDARD)
          .build()))

        sslSocketFactory(
          handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)

        when (protocolParam) {
          Protocol.HTTP_2 -> protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
          Protocol.HTTP_1_1 -> protocols(listOf(Protocol.HTTP_1_1))
          else -> TODO()
        }

        server.useHttps(handshakeCertificates.sslSocketFactory(), false)
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
    assertThat(response.handshake!!.tlsVersion).isEqualTo(tlsParam)

    if (tlsExtensions == TlsExtensionMode.STANDARD) {
      assertThat(response.protocol).isEqualTo(protocolParam)
    } else {
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_1_1)
    }
  }

  companion object {
    @Suppress("unused") @JvmStatic
    fun connectionTypes(): Stream<Arguments> =
      listOf(Protocol.HTTP_1_1, Protocol.HTTP_2).flatMap { protocol ->
        listOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3).flatMap { tlsVersion ->
          listOf(SocketMode.CHANNEL, SocketMode.STANDARD).flatMap { socketMode ->
            listOf(TlsExtensionMode.DISABLED, TlsExtensionMode.STANDARD).map { tlsExtensionMode ->
              arguments(protocol, tlsVersion, socketMode, tlsExtensionMode)
            }
          }
        }
      }.stream()
  }
}

class ChannelSocketFactory : SocketFactory() {
  override fun createSocket(): Socket {
    return SocketChannel.open().socket()
  }

  override fun createSocket(host: String, port: Int): Socket = TODO("Not yet implemented")

  override fun createSocket(
    host: String,
    port: Int,
    localHost: InetAddress,
    localPort: Int
  ): Socket = TODO("Not yet implemented")

  override fun createSocket(host: InetAddress, port: Int): Socket =
    TODO("Not yet implemented")

  override fun createSocket(
    address: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int
  ): Socket = TODO("Not yet implemented")
}

enum class SocketMode {
  CHANNEL, STANDARD
}

enum class TlsExtensionMode {
  DISABLED, STANDARD
}