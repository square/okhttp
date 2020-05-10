package okhttp3.issues

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS

class Issue5819: IssueBase() {
  @Test
  fun rogueConnectionSeeder() {
    clientTestRule.recordEvents = true

    // Seed the connection pool with a rogue connection.
    server.enqueue(MockResponse().setSocketPolicy(DISCONNECT_AT_END))

    val request = Request.Builder().url(server.url("/")).build()
    val seedRequest = Request.Builder().url(server.url("/seed")).build()

    client.newCall(seedRequest).execute().use {
      assertEquals(200, it.code)
    }

    // A client that repeatedly seeds the connection pool with a rogue connection.
    val seedClient = clientTestRule.newClientBuilder()
        .eventListener(object : EventListener() {
          override fun connectionAcquired(
            call: Call,
            connection: Connection
          ) {
            server.enqueue(MockResponse().setSocketPolicy(DISCONNECT_AT_END));
            try {
              client.newCall(seedRequest).execute().use {
                assertEquals(200, it.code)
              }
              // Reduce test noise
              Thread.sleep(100)
            } catch (e: IOException) {
              fail()
            }
          }
        })
        .build()

    val call = seedClient.newCall(request)

    try {
      call.execute()
      fail()
    } catch (expected: IOException) {
    }
  }
}