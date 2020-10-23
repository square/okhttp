package mockwebserver3.junit5.internal

import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertTrue

@ExtendWith(MockWebServerExtension::class)
class ExampleMockWebServerParameterTest {
  @Test
  fun testStarted(mockWebServer: MockWebServer) {
    assertTrue(mockWebServer.started)
  }
}