package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class BasicMockServerTest {
  @Container
  val mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  @Test
  fun testRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request().withPath("/person")
            .withQueryStringParameter("name", "peter"),
        )
        .respond(response().withBody("Peter the person!"))

      val client = OkHttpClient()

      val response = client.newCall(Request((mockServer.endpoint + "/person?name=peter").toHttpUrl())).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  companion object {
    val MOCKSERVER_IMAGE: DockerImageName =
      DockerImageName
        .parse("mockserver/mockserver")
        .withTag("mockserver-5.15.0")
  }
}
