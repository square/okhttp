/*
 * Copyright (C) 2024 Square, Inc.
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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Proxy.Type.SOCKS
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.containers.Network
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class SocksProxyTest {
  val network: Network = Network.newNetwork()

  @Container
  val mockServer: MockServerContainer =
    MockServerContainer(MOCKSERVER_IMAGE)
      .withNetwork(network)
      .withNetworkAliases("mockserver")

  @Container
  val socks5Proxy =
    GenericContainer(SOCKS5_PROXY)
      .withNetwork(network)
      .withExposedPorts(1080)

  @Test
  fun testLocal() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request().withPath("/person")
            .withQueryStringParameter("name", "peter"),
        )
        .respond(response().withBody("Peter the person!"))

      val client =
        OkHttpClient.Builder()
          .proxy(Proxy(SOCKS, InetSocketAddress(socks5Proxy.host, socks5Proxy.firstMappedPort)))
          .build()

      val response =
        client.newCall(
          Request("http://mockserver:1080/person?name=peter".toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  companion object {
    val SOCKS5_PROXY: DockerImageName =
      DockerImageName
        .parse("serjs/go-socks5-proxy")
        .withTag("v0.0.3")
  }
}
