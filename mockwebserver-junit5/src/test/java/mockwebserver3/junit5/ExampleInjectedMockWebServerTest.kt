package mockwebserver3.junit5

import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ExampleInjectedMockWebServerTest {
  @MockWebServerInstance(tls = true)
  val server: MockWebServer = MockWebServer()

  @Test
  fun testStarted() {
    assertTrue(server.started)
  }
}