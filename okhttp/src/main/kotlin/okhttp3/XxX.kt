package okhttp3

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
  val client = OkHttpClient.Builder()
    .pingInterval(2500, TimeUnit.MILLISECONDS)
    .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
    .build()

  val latch = CountDownLatch(1)

  val request = Request.Builder().url("https://nghttp2.org/httpbin/get").build()
  client.newCall(request).enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) {
      println("onFailure")
      latch.countDown()
    }

    override fun onResponse(call: Call, response: Response) {
      println("onResponse")
//      println(response.body?.string())
      println(response.protocol)
//      response.close()
      latch.countDown()
    }
  })

  println("await")
  latch.await()
  println("awaited")

  println(client.connectionPool.connectionCount())
//  client.connectionPool.evictAll()
//  println(client.connectionPool.connectionCount())
}
