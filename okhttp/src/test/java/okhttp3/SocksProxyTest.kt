/*
 * Copyright (C) 2014 Square, Inc.
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
import assertk.assertions.isEqualTo
import java.io.IOException
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SocksProxyTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private lateinit var server: MockWebServer
  private val socksProxy = SocksProxy()

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
    socksProxy.play()
  }

  @AfterEach
  fun tearDown() {
    socksProxy.shutdown()
  }

  @Test
  fun proxy() {
    server.enqueue(MockResponse.Builder().body("abc").build())
    server.enqueue(MockResponse.Builder().body("def").build())
    val client =
      clientTestRule.newClientBuilder()
        .proxy(socksProxy.proxy())
        .build()
    val request1 = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("abc")
    val request2 = Request.Builder().url(server.url("/")).build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("def")

    // The HTTP calls should share a single connection.
    assertThat(socksProxy.connectionCount()).isEqualTo(1)
  }

  @Test
  fun proxySelector() {
    server.enqueue(MockResponse.Builder().body("abc").build())
    val proxySelector: ProxySelector =
      object : ProxySelector() {
        override fun select(uri: URI) = listOf(socksProxy.proxy())

        override fun connectFailed(
          uri: URI,
          socketAddress: SocketAddress,
          e: IOException,
        ) = error("unexpected call")
      }
    val client =
      clientTestRule.newClientBuilder()
        .proxySelector(proxySelector)
        .build()
    val request = Request.Builder().url(server.url("/")).build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("abc")
    assertThat(socksProxy.connectionCount()).isEqualTo(1)
  }

  @Test
  fun checkRemoteDNSResolve() {
    // This testcase will fail if the target is resolved locally instead of through the proxy.
    server.enqueue(MockResponse.Builder().body("abc").build())
    val client =
      clientTestRule.newClientBuilder()
        .proxy(socksProxy.proxy())
        .build()
    val url =
      server.url("/")
        .newBuilder()
        .host(SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS)
        .build()
    val request = Request.Builder().url(url).build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body.string()).isEqualTo("abc")
    assertThat(socksProxy.connectionCount()).isEqualTo(1)
  }
}
