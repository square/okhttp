package mockwebserver3.junit5

import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertTrue

@ExtendWith(MockWebServerExtension::class)
class ExampleMockWebServerParameterTest {
  @Test
  fun testStarted(mockWebServer: MockWebServer) {
    assertTrue(mockWebServer.started)
  }
}