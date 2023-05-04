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

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertTrue

/** A duplex request body that keeps the provided sinks so they can be written to later.  */
class AsyncRequestBody : RequestBody() {
  private val requestBodySinks: BlockingQueue<BufferedSink> = LinkedBlockingQueue()

  override fun contentType(): MediaType? = null

  override fun writeTo(sink: BufferedSink) {
    requestBodySinks.add(sink)
  }

  override fun isDuplex(): Boolean = true

  @Throws(InterruptedException::class)
  fun takeSink(): BufferedSink {
    return requestBodySinks.poll(5, SECONDS) ?: throw AssertionError("no sink to take")
  }

  fun assertNoMoreSinks() {
    assertTrue(requestBodySinks.isEmpty())
  }
}
