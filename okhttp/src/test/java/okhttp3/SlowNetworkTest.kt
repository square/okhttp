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

import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import okhttp3.internal.connection.RealConnection
import okhttp3.testing.PlatformRule
import okio.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SlowNetworkTest {
  @JvmField
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private lateinit var client: OkHttpClient
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server

    client =
      clientTestRule.newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        )
        .socketFactory(object : DelegatingSocketFactory(getDefault()) {
          override fun createSocket(): Socket {
            return object : Socket() {
              override fun connect(endpoint: SocketAddress?) {
                Thread.sleep(100)
                super.connect(endpoint)
              }

              override fun connect(endpoint: SocketAddress?, timeout: Int) {
                Thread.sleep(100)
                super.connect(endpoint, timeout)
              }

              override fun close() {
                Thread.sleep(100)
                super.close()
              }
            }
          }
        })
        .callTimeout(15.seconds)
        .connectTimeout(15.seconds)
        .eventListener(object : EventListener() {
          override fun connectionAcquired(call: Call, connection: Connection) {
            (connection as RealConnection).noNewExchanges()
          }
        })
        .build()

    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  @Test
  fun slowRequests() {
    repeat(100) {
      server.enqueue(
        MockResponse.Builder()
          .socketPolicy(SocketPolicy.DelayAccept(10.milliseconds))
          .build(),
      )
    }

    val latch = CountDownLatch(100)

    (1..100).map {
      client.newCall(Request(server.url("/"))).enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            println(e)
            latch.countDown()
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
//            println("response")
            response.body.string()
            latch.countDown()
          }
        },
      )
    }

    latch.await()
  }

  @Test
  fun test1() {
    repeat(10) {
      slowRequests()
    }
  }

  @Test
  fun test2() {
    repeat(10) {
      slowRequests()
    }
  }

  @Test
  fun test3() {
    repeat(10) {
      slowRequests()
    }
  }
}
