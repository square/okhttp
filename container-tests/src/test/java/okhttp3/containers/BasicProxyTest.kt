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
import assertk.assertions.isEqualTo
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import okhttp3.containers.BasicMockServerTest.Companion.trustMockServer
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.configuration.Configuration
import org.mockserver.logging.MockServerLogger
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.proxyconfiguration.ProxyConfiguration
import org.mockserver.socket.tls.KeyStoreFactory
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class BasicProxyTest {
  @Container
  val mockServer: MockServerContainer =
    MockServerContainer(MOCKSERVER_IMAGE)
      .withNetworkAliases("mockserver")

  @Test
  fun testOkHttpDirect() {
    testRequest {
      val client = OkHttpClient()

      val response =
        client.newCall(
          Request((mockServer.endpoint + "/person?name=peter").toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_1_1)
    }
  }

  @Test
  fun testOkHttpProxied() {
    testRequest {
      it.withProxyConfiguration(ProxyConfiguration.proxyConfiguration(ProxyConfiguration.Type.HTTP, it.remoteAddress()))

      val client =
        OkHttpClient.Builder()
          .proxy(Proxy(Proxy.Type.HTTP, it.remoteAddress()))
          .build()

      val response =
        client.newCall(
          Request((mockServer.endpoint + "/person?name=peter").toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  @Test
  fun testOkHttpSecureDirect() {
    testRequest {
      val client =
        OkHttpClient.Builder()
          .trustMockServer()
          .build()

      val response =
        client.newCall(
          Request((mockServer.secureEndpoint + "/person?name=peter").toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
    }
  }

  @Test
  fun testOkHttpSecureProxiedHttp1() {
    testRequest {
      val client =
        OkHttpClient.Builder()
          .trustMockServer()
          .proxy(Proxy(Proxy.Type.HTTP, it.remoteAddress()))
          .protocols(listOf(Protocol.HTTP_1_1))
          .build()

      val response =
        client.newCall(
          Request((mockServer.secureEndpoint + "/person?name=peter").toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_1_1)
    }
  }

  @Test
  fun testUrlConnectionDirect() {
    testRequest {
      val url = URI(mockServer.endpoint + "/person?name=peter").toURL()

      val connection = url.openConnection() as HttpURLConnection

      assertThat(connection.inputStream.source().buffer().readUtf8()).contains("Peter the person")
    }
  }

  @Test
  fun testUrlConnectionPlaintextProxied() {
    testRequest {
      val proxy =
        Proxy(
          Proxy.Type.HTTP,
          it.remoteAddress(),
        )

      val url = URI(mockServer.endpoint + "/person?name=peter").toURL()

      val connection = url.openConnection(proxy) as HttpURLConnection

      assertThat(connection.inputStream.source().buffer().readUtf8()).contains("Peter the person")
    }
  }

  @Test
  fun testUrlConnectionSecureDirect() {
    val keyStoreFactory = KeyStoreFactory(Configuration.configuration(), MockServerLogger())
    HttpsURLConnection.setDefaultSSLSocketFactory(keyStoreFactory.sslContext().socketFactory)

    testRequest {
      val url = URI(mockServer.secureEndpoint + "/person?name=peter").toURL()

      val connection = url.openConnection() as HttpURLConnection

      assertThat(connection.inputStream.source().buffer().readUtf8()).contains("Peter the person")
    }
  }

  @Test
  fun testUrlConnectionSecureProxied() {
    val keyStoreFactory = KeyStoreFactory(Configuration.configuration(), MockServerLogger())
    HttpsURLConnection.setDefaultSSLSocketFactory(keyStoreFactory.sslContext().socketFactory)

    testRequest {
      val proxy =
        Proxy(
          Proxy.Type.HTTP,
          it.remoteAddress(),
        )

      val url = URI(mockServer.secureEndpoint + "/person?name=peter").toURL()

      val connection = url.openConnection(proxy) as HttpURLConnection

      assertThat(connection.inputStream.source().buffer().readUtf8()).contains("Peter the person")
    }
  }

  private fun testRequest(function: (MockServerClient) -> Unit) {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      val request =
        request().withPath("/person")
          .withQueryStringParameter("name", "peter")

      mockServerClient
        .`when`(
          request,
        )
        .respond(response().withBody("Peter the person!"))

      function(mockServerClient)
    }
  }
}
