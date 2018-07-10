package okhttp3.kotlin

import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.test.assertEquals

class CoroutinesTest {
  @Test
  fun `test suspend execute`() {
    val request = request("https://google.com/robots.txt")
    val client = client {
      readTimeout(5000, MILLISECONDS)
    }
    val response = runBlocking {
      client.execute(request)
    }
    assertEquals(200, response.code())
    assertEquals("User-agent: *", response.body()!!.string().substring(0, 13))
  }
}
