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
package okhttp.android.test

import java.io.InterruptedIOException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.http2.Http2Connection
import org.junit.jupiter.api.Test

class MediaTest {
  var active: Int = 1
  var connection: Http2Connection? = null
  val client = OkHttpClient.Builder()
    .eventListener(object : EventListener() {
      override fun connectionAcquired(call: Call, connection: Connection) {
        this@MediaTest.connection = (connection as RealConnection).http2Connection
      }
    })
    .build()

  // https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_high_1080p_20000.mp4
  //  Range:bytes=89779418-116842110

  val request =
    Request.Builder()
      .url("https://storage.googleapis.com/wvmedia/clear/h264/tears/tears_h264_high_1080p_20000.mp4")
      .header("Range", "bytes=40000000-120000000")
      .build()

  val contentLength = 1_680_579_891

  @Test
  fun get() {
    val t1 = Thread { runDownloadThread(1) }.apply {
      start()
    }

    val t2 = Thread { runDownloadThread(2) }.apply {
      start()
    }

    while (true) {
      Thread.sleep(2000)
      println("Open Streams " + connection?.openStreamCount())
      if (active == 1) {
        active = 2
        t1.interrupt()
      } else {
        active = 1
        t2.interrupt()
      }
    }
  }

  fun runDownloadThread(id: Int) {
    while (true) {
      if (active == id) {
        println("$id:Requesting")
        try {
          val start = System.currentTimeMillis()
          val response = client.newCall(request).execute()
          response.use {
            val body = it.body!!.source()
            println("$id:Response in " + (System.currentTimeMillis() - start))
            body.readByte()
            println("$id:First byte in " + (System.currentTimeMillis() - start))
            val bytes = body.readByteArray()
            println("$id:Read ${bytes.size}")
          }
        } catch (ioe: InterruptedIOException) {
          println("$id:Interrupted ${ioe.bytesTransferred}")
        }
        println("$id:Finished")
      }

      try {
        Thread.sleep(100)
      } catch (ie: InterruptedException) {
        println("$id:Interrupted ignored")
        Thread.sleep(100)
      }
    }
  }
}
