/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.http2.Http2
import okhttp3.internal.http2.Http2Connection
import org.junit.Before
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MediaTest {
  var active: Int = 1
  var logger: Logger? = null

  val client = OkHttpClient.Builder()
    .eventListenerFactory { ClosingListener() }
    .build()

  // https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_high_1080p_20000.mp4
  //  Range:bytes=89779418-116842110

  val request =
    Request.Builder()
      .url("https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_high_1080p_20000.mp4")
      .header("Range", "bytes=0-8000000")
      .build()

  class ClosingListener: EventListener() {
    var connection: RealConnection? = null

    override fun connectionAcquired(call: Call, connection: Connection) {
      this.connection = connection as RealConnection
    }

    override fun callFailed(call: Call, ioe: IOException) {
      if (ioe is InterruptedIOException) {
        // Uncomment to fix
//        connection?.noNewExchanges = true
      }
    }
  }

  @BeforeEach
  fun setupLogging() {
    val logHandler = ConsoleHandler().apply {
      level = Level.FINE
      formatter = object : SimpleFormatter() {
        override fun format(record: LogRecord) =
          String.format("[%1\$tF %1\$tT] %2\$s %n", record.millis, record.message)
      }
    }

    logger = Logger.getLogger(Http2::class.java.name).apply {
      addHandler(logHandler)
      level = Level.FINEST
    }
  }

  @Test
  fun get() {
    val threads = (1..10).map {
      Thread { runDownloadThread(it) }.apply {
        start()
      }
    }

    threads.first().join()
  }

  fun runDownloadThread(id: Int) {
    while (true) {
      // clear flag
      Thread.interrupted()

      try {
        val response = client.newCall(request).execute()
        if (active == id) {
          response.use {
            val body = it.body!!.source()
            body.skip(8000001)
            println("$id:Success")
          }
        } else {
          Thread.sleep(200)
          Thread.currentThread().interrupt()
          try {
            response.body!!.source().skip(8000001)
          } catch (ioe: InterruptedIOException) {
            println("$id:Interrupted")
          }
        }
      } catch (ste: SocketTimeoutException) {
        println("SocketTimeoutException")
      }
    }
  }
}
