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
import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.http2.Http2
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MediaTest {
  var active: Int = 1
  var logger: Logger? = null

  // Whether to interrupt the execute call or the body read.
  val interruptEarlyExecute = true

  // if true, then after an interrupt exception, the connection will be closed
  // thread 1 will recover if stuck on the next request after a SocketTimeoutException
  val repairConnection = false

  val detailedLogging = false

  val client = OkHttpClient.Builder()
    .eventListenerFactory { ClosingListener() }
    .build()

  // https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_high_1080p_20000.mp4
  //  Range:bytes=89779418-116842110

  val request =
    Request.Builder()
      .url(
        "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_high_1080p_20000.mp4"
      )
      .header("Range", "bytes=0-8000000")
      .build()

  inner class ClosingListener : EventListener() {
    var connection: RealConnection? = null

    override fun connectionAcquired(call: Call, connection: Connection) {
      this.connection = connection as RealConnection
    }

    override fun callFailed(call: Call, ioe: IOException) {
      if (ioe is InterruptedIOException) {
        if (repairConnection) {
          connection?.noNewExchanges = true
        }
      }
    }

    override fun canceled(call: Call) {
      if (detailedLogging) {
        println("Canceled")
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
      this.filter = Filter {
        detailedLogging && (
          it.message.contains("WINDOW_UPDATE")
            || it.message.contains("GOAWAY")
            || it.message.contains("HEADERS")
            || it.message.contains("DATA")
          )
      }
    }

    logger = Logger.getLogger(Http2::class.java.name).apply {
      addHandler(logHandler)
      level = Level.FINEST
    }
  }

  @Test
  fun get() {
    // with threads = 1, the first request will succeed.  With threads = 10 it will get blocked
    val threadCount = 10
    val threads = (1..threadCount).map {
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
        val call = client.newCall(request)
        if (active == id) {
          val response = call.execute()
          response.use {
            it.body!!.source().skip(8000001)
            println("$id:Success")
          }
        } else {
          Thread.sleep(200)
          if (interruptEarlyExecute) {
            Thread.currentThread().interrupt()
          }
          try {
            call.execute().use {
              if (!interruptEarlyExecute) {
                Thread.currentThread().interrupt()
              }
              it.body!!.source().skip(8000001)
            }
          } catch (ioe: InterruptedIOException) {
            // sample logging
            if (detailedLogging || id == 2) {
              println("$id:Interrupted")
            }
          }
        }
      } catch (ste: SocketTimeoutException) {
        println("SocketTimeoutException")
      }
    }
  }
}
