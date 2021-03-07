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
package mockwebserver3.internal.duplex

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.RecordedRequest
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Http2Stream
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.utf8Size

private typealias Action = (RecordedRequest, BufferedSource, BufferedSink, Http2Stream) -> Unit

/**
 * A scriptable request/response conversation. Create the script by calling methods like
 * [receiveRequest] in the sequence they are run.
 */
class MockDuplexResponseBody : DuplexResponseBody {
  private val actions = LinkedBlockingQueue<Action>()
  private val results = LinkedBlockingQueue<FutureTask<Void>>()

  fun receiveRequest(expected: String) = apply {
    actions += { _, requestBody, _, _ ->
      val actual = requestBody.readUtf8(expected.utf8Size())
      if (actual != expected) throw AssertionError("$actual != $expected")
    }
  }

  fun exhaustRequest() = apply {
    actions += { _, requestBody, _, _ ->
      if (!requestBody.exhausted()) throw AssertionError("expected exhausted")
    }
  }

  fun cancelStream(errorCode: ErrorCode) = apply {
    actions += { _, _, _, stream -> stream.closeLater(errorCode) }
  }

  fun requestIOException() = apply {
    actions += { _, requestBody, _, _ ->
      try {
        requestBody.exhausted()
        throw AssertionError("expected IOException")
      } catch (expected: IOException) {
      }
    }
  }

  @JvmOverloads fun sendResponse(
    s: String,
    responseSent: CountDownLatch = CountDownLatch(0)
  ) = apply {
    actions += { _, _, responseBody, _ ->
      responseBody.writeUtf8(s)
      responseBody.flush()
      responseSent.countDown()
    }
  }

  fun exhaustResponse() = apply {
    actions += { _, _, responseBody, _ -> responseBody.close() }
  }

  fun sleep(duration: Long, unit: TimeUnit) = apply {
    actions += { _, _, _, _ -> Thread.sleep(unit.toMillis(duration)) }
  }

  override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
    val task = serviceStreamTask(request, http2Stream)
    results.add(task)
    task.run()
  }

  /** Returns a task that processes both request and response from [http2Stream]. */
  private fun serviceStreamTask(
    request: RecordedRequest,
    http2Stream: Http2Stream
  ): FutureTask<Void> {
    return FutureTask<Void> {
      http2Stream.getSource().buffer().use { requestBody ->
        http2Stream.getSink().buffer().use { responseBody ->
          while (true) {
            val action = actions.poll() ?: break
            action(request, requestBody, responseBody, http2Stream)
          }
        }
      }
      return@FutureTask null
    }
  }

  /** Returns once the duplex conversation completes successfully. */
  fun awaitSuccess() {
    val futureTask = results.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("no onRequest call received")
    futureTask.get(5, TimeUnit.SECONDS)
  }
}
