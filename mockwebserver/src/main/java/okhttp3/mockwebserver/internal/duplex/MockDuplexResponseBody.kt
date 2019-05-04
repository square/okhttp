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
package okhttp3.mockwebserver.internal.duplex

import okhttp3.mockwebserver.RecordedRequest
import okio.BufferedSink
import okio.BufferedSource
import okio.utf8Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private typealias Action = (RecordedRequest, BufferedSource, BufferedSink) -> Unit

/**
 * A scriptable request/response conversation. Create the script by calling methods like
 * [receiveRequest] in the sequence they are run.
 */
class MockDuplexResponseBody : DuplexResponseBody {
  private val actions = LinkedBlockingQueue<Action>()
  private val results = LinkedBlockingQueue<FutureTask<Void>>()

  fun receiveRequest(expected: String) = apply {
    actions += { _, requestBody, _ ->
      assertEquals(expected, requestBody.readUtf8(expected.utf8Size()))
    }
  }

  fun exhaustRequest() = apply {
    actions += { _, requestBody, _ -> assertTrue(requestBody.exhausted()) }
  }

  fun requestIOException() = apply {
    actions += { _, requestBody, _ ->
      try {
        requestBody.exhausted()
        fail()
      } catch (expected: IOException) {
      }
    }
  }

  @JvmOverloads fun sendResponse(
    s: String,
    responseSent: CountDownLatch = CountDownLatch(0)
  ) = apply {
    actions += { _, _, responseBody ->
      responseBody.writeUtf8(s)
      responseBody.flush()
      responseSent.countDown()
    }
  }

  fun exhaustResponse() = apply {
    actions += { _, _, responseBody -> responseBody.close() }
  }

  fun sleep(duration: Long, unit: TimeUnit) = apply {
    actions += { _, _, _ -> Thread.sleep(unit.toMillis(duration)) }
  }

  override fun onRequest(
    request: RecordedRequest,
    requestBody: BufferedSource,
    responseBody: BufferedSink
  ) {
    val futureTask = FutureTask<Void> {
      while (true) {
        val action = actions.poll() ?: break
        action(request, requestBody, responseBody)
      }
      return@FutureTask null
    }
    results.add(futureTask)
    futureTask.run()
  }

  /** Returns once the duplex conversation completes successfully. */
  fun awaitSuccess() {
    val futureTask = results.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("no onRequest call received")
    futureTask.get(5, TimeUnit.SECONDS)
  }
}
