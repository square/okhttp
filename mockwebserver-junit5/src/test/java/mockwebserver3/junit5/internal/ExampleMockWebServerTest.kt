package mockwebserver3.junit5.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertTrue

class ExampleMockWebServerTest {
  @RegisterExtension
  @JvmField
  val mockWebServer: MockWebServerExtension = MockWebServerExtension()

  @Test
  fun testStarted() {
    assertTrue(mockWebServer.server.started)
  }
}