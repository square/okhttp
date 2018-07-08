package okhttp3.kotlin

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.junit.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.test.assertEquals

class BuildersTest {
    @Test
    fun `test client builder apis`() {
        val client1 = client()
        assertEquals(OkHttpClient().pingIntervalMillis(), client1.pingIntervalMillis())

        val client2 = client {
            pingInterval(2, MILLISECONDS)
        }
        assertEquals(2, client2.pingIntervalMillis())

        val client3 = client {
            pingInterval(3, MILLISECONDS)
        }
        assertEquals(3, client3.pingIntervalMillis())

        val client4 = client1.rebuild {
            pingInterval(4, MILLISECONDS)
        }
        assertEquals(4, client4.pingIntervalMillis())
    }

    @Test
    fun `test request builder apis`() {
        val request1 = request("https://google.com/")
        assertEquals("https://google.com/", request1.url().toString())

        val request2 = request {
            url("https://google.com/?q=2")
        }
        assertEquals("https://google.com/?q=2", request2.url().toString())

        val request3 = request {
            url("https://google.com/?q=3")
        }
        assertEquals("https://google.com/?q=3", request3.url().toString())

        val request4 = request1.rebuild {
            url("https://google.com/?q=4")
        }
        assertEquals("https://google.com/?q=4", request4.url().toString())

        val postRequest = request("https://google.com/") {
            method("POST", RequestBody.create(MediaType.parse("text.plain"), ""))
        }
        assertEquals("https://google.com/", postRequest.url().toString())
    }

    @Test
    fun `test url builder apis`() {
        val url1 = url("https://google.com/")
        assertEquals("https://google.com/", url1.toString())

        val url2 = url {
            scheme("https")
            host("google.com")
        }
        assertEquals("https://google.com/", url2.toString())

        val url3 = url {
            scheme("https")
            host("google.com")
            query("q=3")
        }
        assertEquals("https://google.com/?q=3", url3.toString())

        val url4 = url1.rebuild {
            query("q=4")
        }
        assertEquals("https://google.com/?q=4", url4.toString())
    }
}