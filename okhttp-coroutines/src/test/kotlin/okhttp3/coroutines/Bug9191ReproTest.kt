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
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.AsyncTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

@Burst
class ReproduceOkHttpIssueTest {
  val executorService = Executors.newFixedThreadPool(2)
  val stallingServer = StallingServer()

  // 1. Define Hostname
  val proxyHost = "unresponsive-proxy-host"

  // 2. Set Timeouts
  val callTimeout = Duration.ofSeconds(3 * 2)
  val connectTimeout = Duration.ofSeconds(1 * 2)
  val readTimeout = Duration.ofSeconds(2 * 2)

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
    Thread.interrupted()
    try {
      executorService.shutdownNow()
      stallingServer.stop()
    } catch (e: Exception) {
      e.printStackTrace()
    }
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

  class TestTimeout(
    val name: String,
    val thread: Thread = Thread.currentThread(),
  ) : AsyncTimeout() {
    override fun timedOut() {
      thread.interrupt()
    }

    override fun toString(): String {
      return name
    }
  }

  var recordingStart = 0L
  val testStart = System.nanoTime()

  fun sleepUntil(time: Long) {
    val currentElapsed = (System.nanoTime() - testStart)
    val targetElapsed = time - recordingStart

    val nanos = targetElapsed - currentElapsed
    val ms = nanos / 1_000_000L
    val ns = nanos - (ms * 1_000_000L)
    if (ms > 0L || nanos > 0) {
      Thread.sleep(ms, ns.toInt())
    }
  }

  /** Annoyingly this does what we want. */
  @Test
  @Throws(InterruptedException::class)
  fun perfectTest() {
    val T1 = TestTimeout("T1")
    T1.timeout(3000000000, TimeUnit.NANOSECONDS)

    val T2 = TestTimeout("T2")
    T2.timeout(10000000000, TimeUnit.NANOSECONDS)

    val T3 = TestTimeout("T3")
    T3.timeout(2000000000, TimeUnit.NANOSECONDS)

    val T4 = TestTimeout("T4")
    T4.timeout(10000000000, TimeUnit.NANOSECONDS)

    val T5 = TestTimeout("T5")
    T5.timeout(2000000000, TimeUnit.NANOSECONDS)

//    sleepUntil(0)
    T1.enter()
    sleepUntil(21_021_375)
    T2.enter()
    sleepUntil(43_177_042)
    T2.exit()
    sleepUntil(46_650_750)
    T2.enter()
    sleepUntil(50_056_459)
    T2.exit()
    sleepUntil(53_838_167)
    T3.enter()
    sleepUntil(81_087_334)
    T3.exit()
    sleepUntil(2_000_000_000)
    T4.enter()
    sleepUntil(2_169_520_000)
    T4.exit()
    sleepUntil(2_175_734_375)
    T4.enter()
    sleepUntil(2_182_283_250)
    T4.exit()
    sleepUntil(2_188_262_042)
    T5.enter()
    sleepUntil(2_195_482_917)
    T5.exit()
    assertFailsWith<InterruptedException> {
      sleepUntil(3_500_000_000)
    }
    T1.exit()
  }

  /** Annoyingly this does what we want. */
  @Test
  @Throws(InterruptedException::class)
  fun failingTest() {
    val T1 = TestTimeout("T1")
    T1.timeout(3000000000, TimeUnit.NANOSECONDS)

    val T2 = TestTimeout("T2")
    T2.timeout(10000000000, TimeUnit.NANOSECONDS)

    val T3 = TestTimeout("T3")
    T3.timeout(2000000000, TimeUnit.NANOSECONDS)

    val T4 = TestTimeout("T4")
    T4.timeout(10000000000, TimeUnit.NANOSECONDS)

    val T5 = TestTimeout("T5")
    T5.timeout(2000000000, TimeUnit.NANOSECONDS)

    recordingStart = 48037384402166
    sleepUntil(48037384402166)
    T1.enter()
    sleepUntil(48037410711791)
    T2.enter()
    sleepUntil(48037429544208)
    T2.exit()
    sleepUntil(48037432485375)
    T2.enter()
    sleepUntil(48037435469416)
    T2.exit()
    sleepUntil(48037438890625)
    T3.enter()
//calling timedOut 48037482781291 on null
    sleepUntil(48037484109708)
    T3.exit()
    sleepUntil(48039554055708)
    T4.enter()
    sleepUntil(48039558740333)
    T4.exit()
    sleepUntil(48039562572583)
    T4.enter()
    sleepUntil(48039566864625)
    T4.exit()
    sleepUntil(48039570599375)
    T5.enter()
//calling timedOut 48039574546166 on null
    sleepUntil(48039575464541)
    T5.exit()
//calling timedOut 48040398250541 on null
//calling timedOut 48040401757166 on T1 // took too long?
    assertFailsWith<InterruptedException> {
      sleepUntil(1_000_000_000 + 48040415361958) // this should time out, but too late?
    }
    T1.exit()

  }

  @Test
  @Throws(InterruptedException::class)
  fun tff() {
    executorService.submit { stallingServer.start(8080) }
    Thread.sleep(2000)

    val startTime = Instant.now()

    try {
      runBlocking { client.newCall(request).executeAsync() }.use { response ->
        println("Call Succeeded unexpectedly.")
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
