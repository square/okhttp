package okhttp3.containers

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.utility.DockerImageName

class BasicMockServerTest {
  @get:Rule
  var mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  @Test
  fun testRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request().withPath("/person")
            .withQueryStringParameter("name", "peter")
        )
        .respond(response().withBody("Peter the person!"))

      val client = OkHttpClient()

      val response = client.newCall(Request((mockServer.endpoint + "/person?name=peter").toHttpUrl())).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  companion object {
    val MOCKSERVER_IMAGE: DockerImageName = DockerImageName
      .parse("mockserver/mockserver")
      .withTag("mockserver-" + MockServerClient::class.java.getPackage().implementationVersion)

    init {
      println("Docker image " + MOCKSERVER_IMAGE.asCanonicalNameString())
    }
  }
}
