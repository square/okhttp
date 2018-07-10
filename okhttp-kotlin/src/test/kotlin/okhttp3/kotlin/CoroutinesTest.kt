package okhttp3.kotlin

import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.cancelAndJoin
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

  @Test
  fun `test async`() {
    val request = request("https://httpbin.org/delay/1")
    val client = client()
    val job1 = async {
      client.execute(request)
    }
    val job2 = async {
      client.execute(request)
    }
    runBlocking {
      val response1 = job1.await()
      val response2 = job2.await()

      assertEquals(200, response1.code())
      assertEquals(200, response2.code())
    }
  }

  @Test
  fun `test cancel`() {
    val request = request("https://httpbin.org/delay/5")
    val client = client()
    val response = async {
      client.execute(request)
    }
    runBlocking {
      response.cancelAndJoin()
    }
  }
}
