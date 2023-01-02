/*
 * Copyright (C) 2017 Square, Inc.
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

import java.util.Deque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionEvent.NoNewExchanges
import okhttp3.internal.connection.RealConnection
import okio.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset

open class RecordingConnectionListener : ConnectionListener() {
  val eventSequence: Deque<ConnectionEvent> = ConcurrentLinkedDeque()

  private val forbiddenLocks = mutableSetOf<Any>()

  /** The timestamp of the last taken event, used to measure elapsed time between events. */
  private var lastTimestampNs: Long? = null

  /** Confirm that the thread does not hold a lock on `lock` during the callback. */
  fun forbidLock(lock: Any) {
    forbiddenLocks.add(lock)
  }

  /**
   * Removes recorded events up to (and including) an event is found whose class equals [eventClass]
   * and returns it.
   */
  fun <T> removeUpToEvent(eventClass: Class<T>): T {
    val fullEventSequence = eventSequence.toList()
    try {
      while (true) {
        val event = takeEvent()
        if (eventClass.isInstance(event)) {
          return eventClass.cast(event)
        }
      }
    } catch (e: NoSuchElementException) {
      throw AssertionError("full event sequence: $fullEventSequence", e)
    }
  }

  /**
   * Remove and return the next event from the recorded sequence.
   *
   * @param eventClass a class to assert that the returned event is an instance of, or null to
   *     take any event class.
   * @param elapsedMs the time in milliseconds elapsed since the immediately-preceding event, or
   *     -1L to take any duration.
   */
  fun takeEvent(
    eventClass: Class<out ConnectionEvent>? = null,
    elapsedMs: Long = -1L
  ): ConnectionEvent {
    val result = eventSequence.remove()
    val actualElapsedNs = result.timestampNs - (lastTimestampNs ?: result.timestampNs)
    lastTimestampNs = result.timestampNs

    if (eventClass != null) {
      assertThat(result).isInstanceOf(eventClass)
    }

    if (elapsedMs != -1L) {
      assertThat(
        TimeUnit.NANOSECONDS.toMillis(actualElapsedNs)
          .toDouble()
      )
        .isCloseTo(elapsedMs.toDouble(), Offset.offset(100.0))
    }

    return result
  }

  fun recordedEventTypes() = eventSequence.map { it.name }

  fun clearAllEvents() {
    while (eventSequence.isNotEmpty()) {
      takeEvent()
    }
  }

  private fun logEvent(e: ConnectionEvent) {
    if (e.connection != null) {
      assertThat(Thread.holdsLock(e.connection))
        .overridingErrorMessage("Called with lock $${e.connection}")
        .isFalse()
    }
    for (lock in forbiddenLocks) {
      assertThat(Thread.holdsLock(lock))
        .overridingErrorMessage("Called with lock $lock")
        .isFalse()
    }

    eventSequence.offer(e)
  }

  override fun connectStart(route: Route, call: Call) = logEvent(ConnectionEvent.ConnectStart(System.nanoTime(), route, call))

  override fun connectFailed(route: Route, call: Call, failure: IOException) = logEvent(ConnectionEvent.ConnectFailed(System.nanoTime(), route, call, failure))

  override fun connectEnd(connection: Connection) {
    logEvent(ConnectionEvent.ConnectEnd(System.nanoTime(), connection))
  }

  override fun connectionClosed(connection: Connection) = logEvent(ConnectionEvent.ConnectionClosed(System.nanoTime(), connection))

  override fun connectionAcquired(connection: Connection, call: Call) {
    logEvent(ConnectionEvent.ConnectionAcquired(System.nanoTime(), connection, call))
  }

  override fun connectionReleased(connection: Connection, call: Call) {
    if (eventSequence.find { it is ConnectionEvent.ConnectStart && it.connection == connection } != null && connection is RealConnection) {
      if (connection.noNewExchanges) {
        assertThat(eventSequence).anyMatch { it is NoNewExchanges && it.connection == connection }
      }
    }

    logEvent(ConnectionEvent.ConnectionReleased(System.nanoTime(), connection, call))
  }

  override fun noNewExchanges(connection: Connection) = logEvent(NoNewExchanges(System.nanoTime(), connection))
}
