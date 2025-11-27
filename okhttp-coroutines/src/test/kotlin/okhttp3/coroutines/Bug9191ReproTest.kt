package okhttp3.coroutines

import app.cash.burst.Burst
import assertk.assertThat
import assertk.assertions.isLessThan
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

@Burst
class ReproduceOkHttpIssueTest {
  val executorService = Executors.newFixedThreadPool(2)
  val stallingServer = StallingServer()

  // 1. Define Hostname
  val proxyHost = "unresponsive-proxy-host"

  // 2. Set Timeouts
  val callTimeout = Duration.ofSeconds(3)
  val connectTimeout = Duration.ofSeconds(1)
  val readTimeout = Duration.ofSeconds(2)

  // 3. Build the Client with the Custom Dns
  var client = OkHttpClient.Builder()
    .callTimeout(callTimeout)
    .connectTimeout(connectTimeout)
    .readTimeout(readTimeout)
    .dns(Dns { hostname: String? ->
      if (hostname == proxyHost)
        listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.1"))
      else
        Dns.Companion.SYSTEM.lookup(hostname!!)
    })
    .eventListener(object : EventListener() {
      override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        println(
          "connect start - %s - inetSocketAddress: %s, proxy: %s".format(
            call.request().url,
            inetSocketAddress,
            proxy
          )
        )
      }
    })
    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyHost, 8080)))
    .build()

  // 4. Test the Call
  val request = Request.Builder().url("https://github.com/").build() // Any valid target URL which is https

  @AfterEach
  fun close() {
    executorService.shutdownNow()
    stallingServer.stop()
  }

  @Test
  @Throws(InterruptedException::class)
  fun test(execute: Boolean = true, increasedReadTimeout: Boolean = false) {
    if (increasedReadTimeout) {
      client = client.newBuilder()
        .readTimeout(readTimeout.multipliedBy(2))
        .build()
    }

    executorService.submit(Runnable { stallingServer.start(8080) })
    Thread.sleep(2000)

    val startTime = Instant.now()

    try {
      if (execute) {
        client.newCall(request).execute().use { response ->
          println("Call Succeeded unexpectedly.")
        }
      } else {
        runBlocking { client.newCall(request).executeAsync() }.use { response ->
          println("Call Succeeded unexpectedly.")
        }
      }
    } catch (e: Exception) {
      val totalTime = Duration.between(startTime, Instant.now())
      println("--- TEST RESULT ---")
      println("Exception: " + e.javaClass.getName() + ": " + e.message)
      println("Total Time: $totalTime")
      println("Expected Time (Call Timeout): $callTimeout")
      println("Observed Time (2 x Read Timeout): " + readTimeout.multipliedBy(2))
      assertThat(totalTime).isLessThan(readTimeout.multipliedBy(2))
    }
  }

  class StallingServer {
    private lateinit var serverSocket: ServerSocket

    fun start(port: Int) {
      try {
        serverSocket = ServerSocket(port)
        serverSocket.use { serverSocket ->
          println("Java server listening on port $port")
          while (true) {
            val clientSocket = serverSocket.accept()
            println("Connection accepted from " + clientSocket.getInetAddress())

            Thread(Runnable {
              try {
                clientSocket.use { sock ->
                  // 1. Read the client's initial request (e.g., "CONNECT google.com:443 HTTP/1.1")
                  val `in` = sock.getInputStream()
                  val buffer = ByteArray(1024)
                  val bytesRead = `in`.read(buffer)
                  println("Received $bytesRead bytes. Now stalling...")

                  // 2. Respond with "200 OK" to open the tunnel (Critical step for TLS simulation)
                  val successResponse = "HTTP/1.1 200 Connection established\r\n\r\n"
                  sock.getOutputStream().write(successResponse.toByteArray())
                  sock.getOutputStream().flush()
                  println("Tunnel established on port $port. Now stalling (TLS Handshake)...")

                  // 3. Block this thread indefinitely.
                  // The OkHttp client will now send the TLS ClientHello and hit the Read Timeout (10s) waiting for the ServerHello.
                  Thread.sleep(Long.Companion.MAX_VALUE)
                }
              } catch (e: Exception) {
                System.err.println("Connection handling error: " + e.message)
              }
            }).start()
          }
        }
      } catch (e: IOException) {
        throw RuntimeException(e)
      }
    }

    fun stop() {
      serverSocket.close()
    }
  }
}
