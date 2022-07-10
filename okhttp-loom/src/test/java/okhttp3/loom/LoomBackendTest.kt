/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.loom

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil
import okio.IOException
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@ExtendWith(MockWebServerExtension::class)
class LoomBackendTest(
  val server: MockWebServer
) {
  @RegisterExtension
  val platform = PlatformRule()

  private var assertVirtual = false

  private val handshakeCertificates = TlsUtil.localhost()

  var client = LoomClientBuilder.clientBuilder()
    .eventListener(object : EventListener() {
      override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        assertVirtual()
      }

      override fun requestHeadersStart(call: Call) {
        assertVirtual()
      }

      override fun responseHeadersStart(call: Call) {
        assertVirtual()
      }
    })
    .build()

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  fun assertVirtual() {
    if (assertVirtual) {
      assertThat(Thread.currentThread().isVirtual).isTrue()
    }
  }

  @ParameterizedTest
  @MethodSource("ssl")
  fun makeExecuteRequest(ssl: Boolean) {
    if (ssl) {
      enableTls()
    }

    server.enqueue(MockResponse().apply {
      setBody("12345")
    })

    val response =
      client.newCall(Request(server.url("/"))).execute()

    assertThat(response.protocol).isEqualTo(if (ssl) Protocol.HTTP_2 else Protocol.HTTP_1_1)
    assertThat(response.body.string()).contains("12345")
  }

  @ParameterizedTest
  @MethodSource("ssl")
  fun makeEnqueueRequest(ssl: Boolean) {
    if (ssl) {
      enableTls()
    }

    server.enqueue(MockResponse().apply {
      setBody("12345")
    })

    assertVirtual = true

    val completableFuture = CompletableFuture<Response>()

    client.newCall(Request(server.url("/"))).enqueue(
      object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          completableFuture.completeExceptionally(e)
        }

        override fun onResponse(call: Call, response: Response) {
          completableFuture.complete(response)
        }
      }
    )

    val response = completableFuture.get()
    assertThat(response.protocol).isEqualTo(if (ssl) Protocol.HTTP_2 else Protocol.HTTP_1_1)
    assertThat(response.body.string()).contains("12345")
  }

  @ParameterizedTest
  @MethodSource("ssl")
  fun makeEnqueueBatch(ssl: Boolean) {
    if (ssl) {
      enableTls()
    }

    val requests = 1000
    val countDownLatch = CountDownLatch(requests)

    repeat(requests) {
      server.enqueue(MockResponse().apply {
        setBody("12345")
      })
    }

    assertVirtual = true

    val errors = Collections.synchronizedList(mutableListOf<Throwable>())

    repeat(requests) {
      client.newCall(Request(server.url("/"))).enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            countDownLatch.countDown()
            errors.add(e)
          }

          override fun onResponse(call: Call, response: Response) {
            countDownLatch.countDown()
            assertThat(response.body.string()).isEqualTo("12345")
          }
        }
      )
    }

    countDownLatch.await(10, TimeUnit.SECONDS)

    assertThat(countDownLatch.count).isEqualTo(0)

    assertThat(errors).isEmpty()
  }

  companion object {
    @JvmStatic
    fun ssl(): Collection<Boolean> {
      return listOfNotNull(true, false)
    }
  }
}
