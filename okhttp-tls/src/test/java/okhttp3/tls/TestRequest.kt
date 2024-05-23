package okhttp3.tls

import java.io.IOException
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionListener
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.OkHttpDebugLogging.enable
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.FlowControlListener
import okhttp3.internal.http2.Http2
import okhttp3.internal.http2.flowcontrol.WindowCounter

val logHandler = ConsoleHandler().apply {
  level = Level.FINE
  formatter = object : SimpleFormatter() {
    override fun format(record: LogRecord) = record.message + "\n"
  }
}

class Http2FlowControlConnectionListener : ConnectionListener(), FlowControlListener {
  val logger: Logger = Logger.getLogger(Http2FlowControlConnectionListener::class.java.name)
  val start = System.currentTimeMillis()

  override fun receivingStreamWindowChanged(
    streamId: Int,
    windowCounter: WindowCounter,
    bufferSize: Long,
  ) {
    // unacked is the consumed bytes (read by the app), but not yet acknowledged to the server
    logger.fine("$streamId,${windowCounter.unacknowledged},$bufferSize")
  }

  override fun receivingConnectionWindowChanged(windowCounter: WindowCounter) {
    logger.fine("Connection,${windowCounter.unacknowledged},")
  }

  override fun connectionAcquired(connection: Connection, call: Call) {
//    System.err.println("connectionAcquired")
  }

  override fun connectionReleased(connection: Connection, call: Call) {
//    System.err.println("connectionReleased")
  }
}

fun main() {
  enable(Http2::class.java.name, logHandler)
  enable(Http2FlowControlConnectionListener::class.java.name, logHandler)

  val mediaUrl =
    "https://25523.mc.tritondigital.com/OMNY_DSVANDAAGAPP_PODCAST_P/media-session/048a28d3-b74f-4782-ab7f-d07291f80ce1/d/clips/fdd7ab40-270d-4a1e-a257-acd200da1324/2e850afd-b63e-4791-ae3f-b0380101da98/47eba102-a0f8-4277-b44e-b0d1012b5f6f/audio/direct/t1701998186/Gaza_(2_2)_Ondanks_vredespogingen_dreven_Isra_l_en_Palestina_verder_uit_elkaar.mp3?t=1701998186&in_playlist=a4cdb64d-2881-4330-be5d-b0380101dbc7&utm_source=Podcast".toHttpUrl()

  val client = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(connectionListener = Http2FlowControlConnectionListener()))
    .build()

  client.newCall(
    Request(mediaUrl)
  ).enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) {
      println("onFailure $e")
    }

    override fun onResponse(call: Call, response: Response) {
      println("onResponse ${response.code}")
      println(response.headers)
//      System.err.response.body.byteStream().readNBytes(10_000)
    }
  })

  println("sleeping")
  Thread.sleep(100_000)
  println("finished")
}
