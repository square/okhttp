@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp3

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.WireSharkListenerFactory.WireSharkKeyLoggerListener.Launch.CommandLine
import okhttp3.WireSharkListenerFactory.WireSharkKeyLoggerListener.Launch.Gui
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener
import okio.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TwoRequestTest {
  val file = "https://storage.googleapis.com/downloads.webmproject.org/av1/exoplayer/bbb-av1-480p.mp4".toHttpUrl()

  private val wiresharkListenerFactory = WireSharkListenerFactory(
    logFile = File("/tmp/key.log"), tlsVersions = listOf(TlsVersion.TLS_1_2), launch = Gui
  )

  init {
    WireSharkListenerFactory.register()
  }

  private val connectionSpec =
    ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .build()

  val client = OkHttpClient.Builder()
//    .eventListenerFactory(LoggingEventListener.Factory())
    .eventListenerFactory(wiresharkListenerFactory)
    .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
    .connectionSpecs(listOf(connectionSpec))
    .connectionPool(ConnectionPool(connectionListener = LoggingConnectionListener()))
    .build()

  @BeforeEach
  fun enableLogging() {

    val process = wiresharkListenerFactory.launchWireShark()

//    OkHttpDebugLogging.enableHttp2()
//    OkHttpDebugLogging.enableTaskRunner()
  }

  @Test
  fun testTwoQueries() = runTest {
    if (true) {
//      val callFull = client.newCall(
//        Request(file, Headers.headersOf("Icy-MetaData", "1", "Accept-Encoding", "identity"))
//      )
//      val responseFull = callFull.executeAsync()
//
////      println(responseFull.headers)
//      callFull.cancel()
//
//      val callEnd = client.newCall(
//        Request(file, Headers.headersOf("Range", "bytes=37070547-", "Icy-MetaData", "1", "Accept-Encoding", "identity"))
//      )
//      val responseEnd = callEnd.executeAsync()
////      println(responseEnd.headers)
//      callEnd.cancel()

      val callStart = client.newCall(
        Request(file, Headers.headersOf("Range", "bytes=44-", "Icy-MetaData", "1", "Accept-Encoding", "identity"))
      )
      val responseStart = callStart.executeAsync()
      val bodyStart = responseStart.body
      val start100k = bodyStart.byteStream().readNBytes(100_000)
//      println(start100k.size)
    }

    val callDownload = client.newCall(Request(file))
    val responseDownload = callDownload.executeAsync()
    val bodyDownload = responseDownload.body

    while (true) {
      val bytes = bodyDownload.byteStream().readNBytes(100_000)
//      println(bytes.size)

      if (bytes.isEmpty()) {
        break
      }
    }
  }
}

class LoggingConnectionListener(
  private val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
) : ConnectionListener() {
  override fun connectStart(route: Route, call: Call) {
    logger.log("connectStart $route")
  }

  override fun connectFailed(route: Route, call: Call, failure: IOException) {
    logger.log("connectFailed $route $failure")
  }

  override fun connectEnd(connection: Connection, route: Route, call: Call) {
    logger.log("connectEnd $connection $route")
  }

  override fun connectionClosed(connection: Connection) {
    logger.log("connectionClosed $connection")
  }

  override fun connectionAcquired(connection: Connection, call: Call) {
    logger.log("connectionAcquired $connection")
  }

  override fun connectionReleased(connection: Connection, call: Call) {
    logger.log("connectionReleased $connection")
  }

  override fun noNewExchanges(connection: Connection) {
    logger.log("noNewExchanges $connection")
  }
}
