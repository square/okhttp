/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.internal.USER_AGENT
import okio.ByteString

/**
 * Exercises the web socket implementation against the
 * [Autobahn Testsuite](http://autobahn.ws/testsuite/).
 */
class AutobahnTester {
  val client = OkHttpClient()

  private fun newWebSocket(
    path: String,
    listener: WebSocketListener,
  ): WebSocket {
    val request =
      Request.Builder()
        .url(HOST + path)
        .build()
    return client.newWebSocket(request, listener)
  }

  fun run() {
    try {
      val count = getTestCount()
      println("Test count: $count")
      for (number in 1..count) {
        runTest(number, count)
      }
      updateReports()
    } finally {
      client.dispatcher.executorService.shutdown()
    }
  }

  private fun runTest(
    number: Long,
    count: Long,
  ) {
    val latch = CountDownLatch(1)
    val startNanos = AtomicLong()
    newWebSocket(
      "/runCase?case=$number&agent=okhttp",
      object : WebSocketListener() {
        override fun onOpen(
          webSocket: WebSocket,
          response: Response,
        ) {
          println("Executing test case $number/$count")
          startNanos.set(System.nanoTime())
        }

        override fun onMessage(
          webSocket: WebSocket,
          bytes: ByteString,
        ) {
          webSocket.send(bytes)
        }

        override fun onMessage(
          webSocket: WebSocket,
          text: String,
        ) {
          webSocket.send(text)
        }

        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          webSocket.close(1000, null)
          latch.countDown()
        }

        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          t.printStackTrace(System.out)
          latch.countDown()
        }
      },
    )

    check(latch.await(30, TimeUnit.SECONDS)) { "Timed out waiting for test $number to finish." }
    val endNanos = System.nanoTime()
    val tookMs = TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos.get())
    println("Took ${tookMs}ms")
  }

  private fun getTestCount(): Long {
    val latch = CountDownLatch(1)
    val countRef = AtomicLong()
    val failureRef = AtomicReference<Throwable>()

    newWebSocket(
      "/getCaseCount",
      object : WebSocketListener() {
        override fun onMessage(
          webSocket: WebSocket,
          text: String,
        ) {
          countRef.set(text.toLong())
        }

        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          webSocket.close(1000, null)
          latch.countDown()
        }

        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          failureRef.set(t)
          latch.countDown()
        }
      },
    )

    check(latch.await(10, TimeUnit.SECONDS)) { "Timed out waiting for count." }

    val failure = failureRef.get()
    if (failure != null) {
      throw RuntimeException(failure)
    }

    return countRef.get()
  }

  private fun updateReports() {
    val latch = CountDownLatch(1)
    newWebSocket(
      "/updateReports?agent=$USER_AGENT",
      object : WebSocketListener() {
        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          webSocket.close(1000, null)
          latch.countDown()
        }

        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          latch.countDown()
        }
      },
    )

    check(latch.await(10, TimeUnit.SECONDS)) { "Timed out waiting for count." }
  }

  companion object {
    private const val HOST = "ws://localhost:9099"

    @JvmStatic fun main(args: Array<String>) {
      AutobahnTester().run()
    }
  }
}
