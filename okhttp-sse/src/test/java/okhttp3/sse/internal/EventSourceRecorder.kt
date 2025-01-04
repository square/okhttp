/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.sse.internal

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.get
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

class EventSourceRecorder : EventSourceListener() {
  private val events = LinkedBlockingDeque<Any>()
  private var cancel = false

  fun enqueueCancel() {
    cancel = true
  }

  override fun onOpen(
    eventSource: EventSource,
    response: Response,
  ) {
    get().log("[ES] onOpen", Platform.INFO, null)
    events.add(Open(eventSource, response))
    drainCancelQueue(eventSource)
  }

  override fun onEvent(
    eventSource: EventSource,
    id: String?,
    type: String?,
    data: String,
  ) {
    get().log("[ES] onEvent", Platform.INFO, null)
    events.add(Event(id, type, data))
    drainCancelQueue(eventSource)
  }

  override fun onClosed(eventSource: EventSource) {
    get().log("[ES] onClosed", Platform.INFO, null)
    events.add(Closed)
    drainCancelQueue(eventSource)
  }

  override fun onFailure(
    eventSource: EventSource,
    t: Throwable?,
    response: Response?,
  ) {
    get().log("[ES] onFailure", Platform.INFO, t)
    events.add(Failure(t, response))
    drainCancelQueue(eventSource)
  }

  private fun drainCancelQueue(eventSource: EventSource) {
    if (cancel) {
      cancel = false
      eventSource.cancel()
    }
  }

  private fun nextEvent(): Any {
    return events.poll(10, TimeUnit.SECONDS)
      ?: throw AssertionError("Timed out waiting for event.")
  }

  fun assertExhausted() {
    assertThat(events).isEmpty()
  }

  fun assertEvent(
    id: String?,
    type: String?,
    data: String,
  ) {
    assertThat(nextEvent()).isEqualTo(Event(id, type, data))
  }

  fun assertOpen(): EventSource {
    val event = nextEvent() as Open
    return event.eventSource
  }

  fun assertClose() {
    nextEvent() as Closed
  }

  fun assertFailure(message: String?) {
    val event = nextEvent() as Failure
    if (message != null) {
      assertThat(event.t!!.message).isEqualTo(message)
    } else {
      assertThat(event.t).isNull()
    }
  }

  internal data class Open(
    val eventSource: EventSource,
    val response: Response,
  )

  internal data class Failure(
    val t: Throwable?,
    val response: Response?,
  )

  internal object Closed
}
