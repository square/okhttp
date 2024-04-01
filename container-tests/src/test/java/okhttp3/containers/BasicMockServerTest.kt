package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.configuration.Configuration
import org.mockserver.logging.MockServerLogger
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.socket.tls.KeyStoreFactory
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class BasicMockServerTest {
  @Container
  val mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  val client =
    OkHttpClient.Builder()
      .trustMockServer()
      .build()

  @Test
  fun testRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request().withPath("/person")
            .withQueryStringParameter("name", "peter"),
        )
        .respond(response().withBody("Peter the person!"))

      val response = client.newCall(Request((mockServer.endpoint + "/person?name=peter").toHttpUrl())).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  @Test
  fun testHttpsRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request().withPath("/person")
            .withQueryStringParameter("name", "peter"),
        )
        .respond(response().withBody("Peter the person!"))

      val response = client.newCall(Request((mockServer.secureEndpoint + "/person?name=peter").toHttpUrl())).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  companion object {
    val MOCKSERVER_IMAGE: DockerImageName =
      DockerImageName
        .parse("mockserver/mockserver")
        .withTag("mockserver-5.15.0")

    fun OkHttpClient.Builder.trustMockServer(): OkHttpClient.Builder =
      apply {
        val keyStoreFactory = KeyStoreFactory(Configuration.configuration(), MockServerLogger())

        val socketFactory = keyStoreFactory.sslContext().socketFactory

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStoreFactory.loadOrCreateKeyStore())
        val trustManager = trustManagerFactory.trustManagers.first() as X509TrustManager

        sslSocketFactory(socketFactory, trustManager)
      }
  }
}
