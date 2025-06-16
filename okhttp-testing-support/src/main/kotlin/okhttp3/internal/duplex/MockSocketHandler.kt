/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.duplex

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.SocketHandler
import okio.BufferedSink
import okio.BufferedSource
import okio.Socket
import okio.buffer
import okio.utf8Size

private typealias Action = (BufferedSocket) -> Unit

private class BufferedSocket(
  val socket: Socket,
) {
  val source: BufferedSource = socket.source.buffer()
  val sink: BufferedSink = socket.sink.buffer()

  fun cancel() {
    socket.cancel()
  }
}

/**
 * A scriptable request/response conversation. Create the script by calling methods like
 * [receiveRequest] in the sequence they are run.
 */
class MockSocketHandler : SocketHandler {
  private val actions = LinkedBlockingQueue<Action>()
  private val results = LinkedBlockingQueue<FutureTask<Void>>()

  fun receiveRequest(expected: String) =
    apply {
      actions += { stream ->
        val actual = stream.source.readUtf8(expected.utf8Size())
        if (actual != expected) throw AssertionError("$actual != $expected")
      }
    }

  fun exhaustRequest() =
    apply {
      actions += { stream ->
        if (!stream.source.exhausted()) throw AssertionError("expected exhausted")
      }
    }

  fun cancelStream() =
    apply {
      actions += { stream -> stream.cancel() }
    }

  fun requestIOException() =
    apply {
      actions += { stream ->
        try {
          stream.source.exhausted()
          throw AssertionError("expected IOException")
        } catch (expected: IOException) {
        }
      }
    }

  @JvmOverloads
  fun sendResponse(
    s: String,
    responseSent: CountDownLatch = CountDownLatch(0),
  ) = apply {
    actions += { stream ->
      stream.sink.writeUtf8(s)
      stream.sink.flush()
      responseSent.countDown()
    }
  }

  fun exhaustResponse() =
    apply {
      actions += { stream -> stream.sink.close() }
    }

  fun sleep(
    duration: Long,
    unit: TimeUnit,
  ) = apply {
    actions += { Thread.sleep(unit.toMillis(duration)) }
  }

  override fun handle(socket: Socket) {
    val task = serviceSocketTask(BufferedSocket(socket))
    results.add(task)
    task.run()
  }

  /** Returns a task that processes both request and response from [socket]. */
  private fun serviceSocketTask(socket: BufferedSocket): FutureTask<Void> {
    return FutureTask<Void> {
      socket.source.use {
        socket.sink.use {
          while (true) {
            val action = actions.poll() ?: break
            action(socket)
          }
        }
      }
      return@FutureTask null
    }
  }

  /** Returns once all stream actions complete successfully. */
  fun awaitSuccess() {
    val futureTask =
      results.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("no onRequest call received")
    futureTask.get(5, TimeUnit.SECONDS)
  }
}
