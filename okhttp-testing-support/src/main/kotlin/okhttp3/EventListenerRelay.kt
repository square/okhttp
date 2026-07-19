/*
 * Copyright (C) 2025 Square, Inc.
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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A special [EventListener] for testing the mechanics of event listeners.
 *
 * Each instance processes a single event on [call], and then adds a successor [EventListenerRelay]
 * on the same [call] to process the next event.
 *
 * By forcing the list of listeners to change after every event, we can detect if buggy code caches
 * a stale [EventListener] in a field or local variable.
 *
 * If a second event arrives while this instance is still handing off to its successor (for example
 * [EventListener.canceled] racing a connection event), it is queued and flushed to the successor
 * so it is not dropped.
 */
class EventListenerRelay private constructor(
  val call: Call,
  val eventRecorder: EventRecorder,
  private val state: State,
) {
  constructor(
    call: Call,
    eventRecorder: EventRecorder,
  ) : this(call, eventRecorder, State())

  private val eventListenerAdapter =
    EventListenerAdapter()
      .apply {
        listeners += ::onEvent
      }

  val eventListener: EventListener
    get() = eventListenerAdapter

  private val accepted = AtomicBoolean()

  init {
    state.tail.compareAndSet(null, this)
  }

  private fun onEvent(callEvent: CallEvent) {
    // Older relays remain in the aggregate as no-ops so cached listeners stop receiving events.
    if (this !== state.tail.get()) return

    if (!accepted.compareAndSet(false, true)) {
      state.pending.offer(callEvent)
      return
    }

    val next = EventListenerRelay(call, eventRecorder, state)
    call.addEventListener(next.eventListener)
    eventRecorder.logEvent(callEvent)
    state.tail.set(next)

    while (true) {
      val pending = state.pending.poll() ?: break
      next.onEvent(pending)
    }
  }

  private class State {
    val tail = AtomicReference<EventListenerRelay?>(null)
    val pending = ConcurrentLinkedQueue<CallEvent>()
  }
}
