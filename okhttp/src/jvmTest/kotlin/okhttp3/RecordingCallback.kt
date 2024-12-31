/*
 * Copyright (C) 2013 Square, Inc.
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

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Records received HTTP responses so they can be later retrieved by tests.
 */
class RecordingCallback : Callback {
  private val responses = mutableListOf<RecordedResponse>()

  @Synchronized
  override fun onFailure(
    call: Call,
    e: IOException,
  ) {
    responses.add(RecordedResponse(call.request(), null, null, null, e))
    (this as Object).notifyAll()
  }

  @Synchronized
  override fun onResponse(
    call: Call,
    response: Response,
  ) {
    val body = response.body.string()
    responses.add(RecordedResponse(call.request(), response, null, body, null))
    (this as Object).notifyAll()
  }

  /**
   * Returns the recorded response triggered by `request`. Throws if the response isn't
   * enqueued before the timeout.
   */
  @Synchronized
  fun await(url: HttpUrl): RecordedResponse {
    val timeoutMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + TIMEOUT_MILLIS
    while (true) {
      val i = responses.iterator()
      while (i.hasNext()) {
        val recordedResponse = i.next()
        if (recordedResponse.request.url.equals(url)) {
          i.remove()
          return recordedResponse
        }
      }
      val nowMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
      if (nowMillis >= timeoutMillis) break
      (this as Object).wait(timeoutMillis - nowMillis)
    }

    throw AssertionError("Timed out waiting for response to $url")
  }

  companion object {
    val TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10)
  }
}
