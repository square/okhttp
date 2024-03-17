package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import okhttp3.containers.BasicMockServerTest.Companion.trustMockServer
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Isolated
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@Isolated
class BasicLoomTest {
  @JvmField
  @RegisterExtension
  val platform = PlatformRule()

  @Container
  val mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  val capturedOut = ByteArrayOutputStream()

  private lateinit var executor: ExecutorService

  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    platform.assumeLoom()
    assertThat(System.getProperty("jdk.tracePinnedThreads")).isNotEmpty()

    client =
      OkHttpClient.Builder()
        .trustMockServer()
        .dispatcher(Dispatcher(newVirtualThreadPerTaskExecutor()))
        .build()

    executor = newVirtualThreadPerTaskExecutor()

    System.setOut(PrintStream(capturedOut))
  }

  @AfterEach
  fun checkForPinning() {
    assertThat(capturedOut.toString()).isEmpty()
  }

  private fun newVirtualThreadPerTaskExecutor(): ExecutorService {
    return Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor").invoke(null) as ExecutorService
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

      val results =
        (1..20).map {
          executor.submit {
            val response =
              client.newCall(Request((mockServer.secureEndpoint + "/person?name=peter").toHttpUrl())).execute()

            val body = response.body.string()
            assertThat(body).contains("Peter the person")
          }
        }

      results.forEach {
        it.get()
      }
    }
  }
}
