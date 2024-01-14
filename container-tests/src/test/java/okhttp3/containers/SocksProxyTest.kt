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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName


@Testcontainers
class SocksProxyTest {

  @Container
  val mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  @Container
  val socks5Proxy = GenericContainer(SOCKS5_PROXY)
    .withExposedPorts(1080)

  @Test
  fun testExternal() {
    val client = OkHttpClient.Builder()
      .proxy(Proxy(SOCKS, InetSocketAddress("localhost", socks5Proxy.firstMappedPort)))
      .build()

    // TODO replace with a test without external dependencies
    val response = client.newCall(Request(("https://google.com/robots.txt").toHttpUrl())).execute()

    assertThat(response.body.string()).contains("Disallow")
  }

  @Test
  @Disabled("Not working between two docker containers")
  fun testLocal() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request().withPath("/person")
            .withQueryStringParameter("name", "peter"),
        )
        .respond(response().withBody("Peter the person!"))

      val client = OkHttpClient.Builder()
        .proxy(Proxy(SOCKS, InetSocketAddress("localhost", socks5Proxy.firstMappedPort)))
        .build()

      val response = client.newCall(Request((mockServer.endpoint + "/person?name=peter").toHttpUrl())).execute()

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
